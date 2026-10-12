"""Transactionally authoritative state. All externally visible mutations are fenced in SQLite."""
from contextlib import contextmanager
from pathlib import Path
import hashlib
import hmac
import json
import os
import re
import secrets
import sqlite3
import threading
import time
import uuid

TERMINAL = {'completed', 'failed', 'cancelled', 'interrupted'}
ACTIVE = {'queued', 'submitting', 'submission_unknown', 'running', 'waiting_for_approval', 'stopping'}
COMMAND_TYPES = {'ring', 'stop_ring', 'show_message', 'lock', 'locate', 'clear_workspace', 'factory_reset', 'update_app', 'reboot'}
DANGEROUS = {'lock', 'clear_workspace', 'factory_reset', 'update_app', 'reboot'}
LOCAL_ONLY_SETTINGS = {'remote_management', 'location_reporting', 'allow_clear_workspace', 'allow_factory_reset', 'allow_updates', 'allow_reboot', 'secure_lock', 'recovery_checkins'}
RECOVERY_COMMANDS = {'ring', 'stop_ring', 'show_message', 'lock', 'locate', 'clear_workspace', 'factory_reset'}
SECURITY_FIELDS = {'app_restriction_available', 'secure_lock_configured', 'device_admin_active', 'device_owner',
                   'secure_lock_available', 'secure_lock_applied', 'workspace_restricted'}
SCHEMA_VERSION = 4


def now():
    return int(time.time() * 1000)


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False)


def digest(value):
    return hashlib.sha256(value.encode()).hexdigest()


def identifier(value):
    if not isinstance(value, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,200}', value):
        raise HubError(400, 'invalid_id', 'Invalid identifier')
    return value


def string(value, name='text', limit=100000):
    if not isinstance(value, str) or len(value) > limit or '\x00' in value:
        raise HubError(400, 'invalid_field', 'Invalid ' + name)
    return value


def integer(value, name='revision', minimum=0, maximum=2**53):
    if isinstance(value, bool) or not isinstance(value, int) or not minimum <= value <= maximum:
        raise HubError(400, 'invalid_field', 'Invalid ' + name)
    return value


def boolean(value, name):
    if not isinstance(value, bool):
        raise HubError(400, 'invalid_field', 'Invalid ' + name)
    return value


class HubError(Exception):
    def __init__(self, status, code, message, **details):
        super().__init__(message)
        self.status, self.code, self.details = status, code, details

    def body(self):
        return dict(error=str(self), code=self.code, **self.details)


class Store:
    def __init__(self, config):
        self.config = config
        self.path = config.data_dir / 'workspace.sqlite3'
        self.media_dir = config.data_dir / 'media'
        self.media_dir.mkdir(mode=0o700, exist_ok=True)
        os.chmod(self.media_dir, 0o700)
        self._media_lock = threading.Lock()
        with self.transaction() as db:
            version = db.execute('PRAGMA user_version').fetchone()[0]
            if version > SCHEMA_VERSION:
                raise ValueError('Workspace database requires a newer robotOS hub; refusing to downgrade it')
            schema = '''
            CREATE TABLE IF NOT EXISTS devices(id TEXT PRIMARY KEY, name TEXT NOT NULL, workspace_hash TEXT UNIQUE NOT NULL,
              recovery_hash TEXT UNIQUE NOT NULL, revision INTEGER NOT NULL, settings_revision INTEGER NOT NULL,
              desired_settings TEXT NOT NULL, applied_settings TEXT NOT NULL, applied_settings_revision INTEGER NOT NULL DEFAULT 0,
              capabilities TEXT NOT NULL, lost INTEGER NOT NULL, revoked INTEGER NOT NULL, lost_message TEXT NOT NULL,
              app_version TEXT, battery INTEGER, charging INTEGER,
              location TEXT, last_seen INTEGER, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
              policy_revision INTEGER NOT NULL DEFAULT 1, applied_policy_revision INTEGER NOT NULL DEFAULT 0,
              policy_applied_at INTEGER, security TEXT NOT NULL DEFAULT '{}');
            CREATE TABLE IF NOT EXISTS enrollments(hash TEXT PRIMARY KEY, name TEXT NOT NULL, expires_at INTEGER NOT NULL);
            CREATE TABLE IF NOT EXISTS sessions(hash TEXT PRIMARY KEY, csrf TEXT NOT NULL, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL);
            CREATE TABLE IF NOT EXISTS media(id TEXT PRIMARY KEY, sha256 TEXT NOT NULL, size INTEGER NOT NULL, content_type TEXT NOT NULL, created_at INTEGER NOT NULL);
            CREATE TABLE IF NOT EXISTS notes(id TEXT PRIMARY KEY, title TEXT NOT NULL, kind TEXT NOT NULL, text TEXT NOT NULL,
              media_id TEXT, revision INTEGER NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, creation_hash TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS prompts(id TEXT PRIMARY KEY, title TEXT NOT NULL, instruction TEXT NOT NULL, blocks TEXT NOT NULL,
              revision INTEGER NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, creation_hash TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS leases(prompt_id TEXT PRIMARY KEY, actor TEXT NOT NULL, token TEXT NOT NULL, expires_at INTEGER NOT NULL);
            CREATE TABLE IF NOT EXISTS runs(id TEXT PRIMARY KEY, prompt_id TEXT NOT NULL, prompt_revision INTEGER NOT NULL,
              snapshot TEXT NOT NULL, status TEXT NOT NULL, output TEXT NOT NULL DEFAULT '', error TEXT NOT NULL DEFAULT '',
              remote_id TEXT, upstream_payload TEXT, upstream_identity TEXT, requester TEXT, capabilities TEXT NOT NULL DEFAULT '{}',
              approval TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, submitted_at INTEGER, hermes_session_id TEXT);
            CREATE TABLE IF NOT EXISTS run_requests(request_id TEXT PRIMARY KEY, payload_hash TEXT NOT NULL, run_id TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS controls(request_id TEXT PRIMARY KEY, run_id TEXT NOT NULL, kind TEXT NOT NULL,
              payload TEXT NOT NULL, status TEXT NOT NULL, result TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS commands(id TEXT PRIMARY KEY, device_id TEXT NOT NULL, type TEXT NOT NULL, payload TEXT NOT NULL,
              expires_at INTEGER NOT NULL, status TEXT NOT NULL, result TEXT NOT NULL DEFAULT '{}', payload_hash TEXT NOT NULL,
              created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, issuer_role TEXT NOT NULL DEFAULT '');
            CREATE TABLE IF NOT EXISTS resume(id INTEGER PRIMARY KEY CHECK(id=1), revision INTEGER NOT NULL, value TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS changes(seq INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, entity_id TEXT NOT NULL,
              action TEXT NOT NULL, created_at INTEGER NOT NULL);
            CREATE TABLE IF NOT EXISTS audit(id INTEGER PRIMARY KEY AUTOINCREMENT, actor TEXT NOT NULL, action TEXT NOT NULL,
              device_id TEXT, created_at INTEGER NOT NULL);
            '''
            # Execute individually: executescript would commit before the upgrade is complete.
            definitions = {}
            for statement in schema.split(';'):
                if statement.strip():
                    header, definition = statement.strip().split('(', 1)
                    definitions[header.split()[-1]] = definition
                    db.execute(statement)
            if version < SCHEMA_VERSION:
                # Keep old backups/installations readable without replaying migrations on new databases.
                for table, additions in {
                    'devices': [('policy_revision', 'INTEGER NOT NULL DEFAULT 1'),
                                ('applied_policy_revision', 'INTEGER NOT NULL DEFAULT 0'),
                                ('policy_applied_at', 'INTEGER'), ('security', "TEXT NOT NULL DEFAULT '{}'")],
                    'commands': [('issuer_role', "TEXT NOT NULL DEFAULT ''")],
                    'runs': [('hermes_session_id', 'TEXT')],
                }.items():
                    columns = {row['name'] for row in db.execute('PRAGMA table_info(' + table + ')')}
                    for name, definition in additions:
                        if name not in columns:
                            db.execute('ALTER TABLE ' + table + ' ADD COLUMN ' + name + ' ' + definition)
                for table, obsolete in [('devices', 'lost_applied'), ('controls', 'created_at')]:
                    columns = {row['name'] for row in db.execute('PRAGMA table_info(' + table + ')')}
                    if obsolete in columns:
                        # Rebuild from the current declaration, including constraints, for older SQLite versions.
                        db.execute('CREATE TABLE ' + table + '_clean(' + definitions[table])
                        retained = ','.join(row['name'] for row in db.execute('PRAGMA table_info(' + table + '_clean)'))
                        db.execute('INSERT INTO ' + table + '_clean(' + retained + ') SELECT ' + retained + ' FROM ' + table)
                        db.execute('DROP TABLE ' + table)
                        db.execute('ALTER TABLE ' + table + '_clean RENAME TO ' + table)
                # Submission snapshots already preserve executed revisions. Unsubmitted history is unused.
                db.execute('DROP TABLE IF EXISTS prompt_versions')
                for row in db.execute('SELECT id,snapshot,requester,capabilities FROM runs').fetchall():
                    for field, obsolete in {
                        'snapshot': ('last_run_revision', 'last_run_id', 'changed_since_run', 'state',
                                     'id', 'revision', 'created_at', 'updated_at'),
                        'requester': ('actor',), 'capabilities': ('runs',),
                    }.items():
                        if row[field] is None:
                            continue
                        value = json.loads(row[field])
                        if any(key in value for key in obsolete):
                            for key in obsolete:
                                value.pop(key, None)
                            db.execute('UPDATE runs SET ' + field + '=? WHERE id=?', (canonical(value), row['id']))
                db.execute('PRAGMA user_version=' + str(SCHEMA_VERSION))
            db.execute("INSERT OR IGNORE INTO resume VALUES(1,0,'{}')")
        os.chmod(self.path, 0o600)
        self.recover()

    @contextmanager
    def connection(self):
        db = sqlite3.connect(str(self.path), timeout=30, isolation_level=None)
        db.row_factory = sqlite3.Row
        db.execute('PRAGMA busy_timeout=30000')
        db.execute('PRAGMA journal_mode=WAL')
        db.execute('PRAGMA synchronous=FULL')
        db.execute('PRAGMA foreign_keys=ON')
        try:
            yield db
        finally:
            db.close()

    @contextmanager
    def transaction(self):
        with self.connection() as db:
            db.execute('BEGIN IMMEDIATE')
            try:
                yield db
                db.execute('COMMIT')
            except BaseException:
                db.execute('ROLLBACK')
                raise

    def recover(self):
        with self.transaction() as db:
            for row in db.execute("SELECT id FROM runs WHERE status='submitting' AND remote_id IS NULL").fetchall():
                db.execute("UPDATE runs SET status='submission_unknown', error='Host restarted while submission was in progress', updated_at=? WHERE id=?", (now(), row['id']))
                self.change(db, 'run', row['id'])
            db.execute("UPDATE controls SET status='unknown',result=? WHERE status='submitting'", (canonical({'error': 'Host restarted before control outcome was confirmed'}),))
            # Never infer application of a command that the device has not acknowledged.
        for path in self.media_dir.glob('.upload-*'):
            path.unlink(missing_ok=True)

    def change(self, db, kind, entity_id, action='updated'):
        db.execute('INSERT INTO changes(kind,entity_id,action,created_at) VALUES(?,?,?,?)', (kind, entity_id, action, now()))

    def audit(self, db, actor, action, device_id=None):
        db.execute('INSERT INTO audit(actor,action,device_id,created_at) VALUES(?,?,?,?)', (actor, action, device_id, now()))

    def authorize(self, db, actor, scope='workspace'):
        if actor['role'] == 'device':
            row = db.execute('SELECT lost,revoked FROM devices WHERE id=?', (actor['id'],)).fetchone()
            if row is None or row['lost'] or row['revoked']:
                raise HubError(403, 'device_quarantined', 'Device workspace access is revoked')
        if scope == 'workspace' and actor['role'] not in {'owner', 'mcp', 'device'}:
            raise HubError(403, 'wrong_scope', 'Workspace access required')
        if scope == 'owner' and actor['role'] != 'owner':
            raise HubError(403, 'wrong_scope', 'Owner access required')
        if scope == 'recovery' and actor['role'] != 'recovery':
            raise HubError(403, 'wrong_scope', 'Device recovery credential required')

    def authenticate_token(self, token):
        if self.config.owner_token and hmac.compare_digest(token, self.config.owner_token):
            return {'role': 'owner', 'id': 'owner-token', 'actor': 'owner-token', 'recent': False}
        if self.config.mcp_token and hmac.compare_digest(token, self.config.mcp_token):
            return {'role': 'mcp', 'id': 'mcp', 'actor': 'mcp', 'recent': False}
        with self.connection() as db:
            hashed = digest(token)
            row = db.execute('SELECT * FROM devices WHERE workspace_hash=? OR recovery_hash=?', (hashed, hashed)).fetchone()
            if row:
                role = 'device' if hmac.compare_digest(row['workspace_hash'], hashed) else 'recovery'
                actor = {'role': role, 'id': row['id'], 'actor': 'device:' + row['id'], 'recent': False}
                if role == 'device':
                    self.authorize(db, actor)
                return actor
        raise HubError(401, 'unauthorized', 'Invalid credential')

    def login(self, password):
        if not isinstance(password, str) or not hmac.compare_digest(password.encode(), self.config.owner_password.encode()):
            raise HubError(401, 'unauthorized', 'Invalid credentials')
        token, csrf, timestamp = secrets.token_urlsafe(48), secrets.token_urlsafe(32), now()
        with self.transaction() as db:
            db.execute('DELETE FROM sessions WHERE expires_at<?', (timestamp,))
            db.execute('INSERT INTO sessions VALUES(?,?,?,?)', (digest(token), csrf, timestamp, timestamp + self.config.session_seconds * 1000))
        return token, csrf

    def session(self, token):
        with self.connection() as db:
            row = db.execute('SELECT * FROM sessions WHERE hash=? AND expires_at>?', (digest(token), now())).fetchone()
            if not row:
                raise HubError(401, 'unauthorized', 'Sign in required')
            return {'role': 'owner', 'id': 'owner', 'actor': 'session:' + digest(token)[:16], 'csrf_token': row['csrf'],
                    'authenticated_at': row['created_at'], 'expires_at': row['expires_at'],
                    'recent': now() - row['created_at'] <= self.config.recent_auth_seconds * 1000}

    def logout(self, token):
        with self.transaction() as db:
            db.execute('DELETE FROM sessions WHERE hash=?', (digest(token),))

    def enrollment(self, actor, body):
        name = string(body.get('name', 'R1'), 'name', 100).strip() or 'R1'
        code, expiry = secrets.token_urlsafe(12), now() + 10 * 60 * 1000
        with self.transaction() as db:
            self.authorize(db, actor, 'owner')
            db.execute('DELETE FROM enrollments WHERE expires_at<?', (now(),))
            db.execute('INSERT INTO enrollments VALUES(?,?,?)', (digest(code), name, expiry))
            self.audit(db, actor['actor'], 'enrollment_created')
        return {'code': code, 'expires_at': expiry}

    def claim(self, body):
        code = string(body.get('code'), 'code', 100)
        with self.transaction() as db:
            enrollment = db.execute('SELECT * FROM enrollments WHERE hash=? AND expires_at>?', (digest(code), now())).fetchone()
            if not enrollment:
                raise HubError(400, 'invalid_enrollment', 'Enrollment code is invalid or expired')
            name = string(body.get('name', enrollment['name']), 'name', 100).strip() or enrollment['name']
            device_id, token, recovery, timestamp = str(uuid.uuid4()), secrets.token_urlsafe(48), secrets.token_urlsafe(48), now()
            db.execute('''INSERT INTO devices(id,name,workspace_hash,recovery_hash,revision,settings_revision,desired_settings,
              applied_settings,capabilities,lost,revoked,lost_message,created_at,updated_at) VALUES(?,?,?,?,1,0,'{}','{}','{}',0,0,'',?,?)''',
              (device_id, name, digest(token), digest(recovery), timestamp, timestamp))
            db.execute('DELETE FROM enrollments WHERE hash=?', (digest(code),))
            self.audit(db, 'enrollment', 'device_enrolled', device_id)
            self.change(db, 'device', device_id)
        return {'device_id': device_id, 'device_token': token, 'recovery_token': recovery}

    def _row(self, db, table, entity_id):
        row = db.execute('SELECT * FROM ' + table + ' WHERE id=?', (identifier(entity_id),)).fetchone()
        if not row:
            raise HubError(404, 'not_found', 'Item not found')
        return row

    def _revision(self, row, base):
        if row['revision'] != integer(base):
            raise HubError(409, 'revision_conflict', 'A newer revision is saved', current_revision=row['revision'])

    def _media_exists(self, db, media_id):
        if media_id is not None:
            self._row(db, 'media', media_id)

    def note(self, row):
        return {key: row[key] for key in ('id', 'title', 'kind', 'text', 'media_id', 'revision', 'created_at', 'updated_at')}

    def notes(self, actor, note_id=None):
        with self.connection() as db:
            self.authorize(db, actor)
            if note_id:
                return self.note(self._row(db, 'notes', note_id))
            return {'items': [self.note(row) for row in db.execute('SELECT * FROM notes ORDER BY updated_at DESC')]}

    def create_note(self, actor, body):
        note_id = identifier(body.get('id') or str(uuid.uuid4()))
        title, text = string(body.get('title', ''), 'title', 500), string(body.get('text', ''))
        kind, media_id = body.get('kind', 'text'), body.get('media_id')
        if kind not in ('text', 'audio') or (kind == 'audio' and not media_id) or (kind == 'text' and media_id):
            raise HubError(400, 'invalid_note', 'Audio notes require confirmed media')
        payload_hash = digest(canonical(dict(title=title, text=text, kind=kind, media_id=media_id)))
        with self.transaction() as db:
            self.authorize(db, actor)
            existing = db.execute('SELECT * FROM notes WHERE id=?', (note_id,)).fetchone()
            if existing:
                if existing['creation_hash'] != payload_hash:
                    raise HubError(409, 'idempotency_conflict', 'Note ID already has different content')
                return self.note(existing)
            self._media_exists(db, media_id)
            timestamp = now()
            db.execute('INSERT INTO notes VALUES(?,?,?,?,?,1,?,?,?)', (note_id, title, kind, text, media_id, timestamp, timestamp, payload_hash))
            self.change(db, 'note', note_id)
            return self.note(self._row(db, 'notes', note_id))

    def update_note(self, actor, note_id, body):
        title, text = string(body.get('title', ''), 'title', 500), string(body.get('text', ''))
        with self.transaction() as db:
            self.authorize(db, actor)
            row = self._row(db, 'notes', note_id)
            self._revision(row, body.get('base_revision'))
            db.execute('UPDATE notes SET title=?,text=?,revision=revision+1,updated_at=? WHERE id=?', (title, text, now(), note_id))
            self.change(db, 'note', note_id)
            return self.note(self._row(db, 'notes', note_id))

    def delete_note(self, actor, note_id, revision):
        with self.transaction() as db:
            self.authorize(db, actor)
            self._revision(self._row(db, 'notes', note_id), revision)
            db.execute('DELETE FROM notes WHERE id=?', (note_id,))
            self.change(db, 'note', note_id, 'deleted')
        return {'deleted': True}

    def save_media(self, actor, upload_id, content_type, data):
        upload_id = identifier(upload_id)
        if content_type not in {'audio/wav', 'audio/x-wav', 'audio/mpeg', 'audio/mp4', 'audio/aac', 'audio/ogg', 'audio/webm', 'audio/flac'}:
            raise HubError(415, 'unsupported_media', 'Unsupported audio content type')
        if not data or len(data) > self.config.max_audio_bytes:
            raise HubError(413, 'media_too_large', 'Audio is empty or exceeds the upload limit')
        sha = hashlib.sha256(data).hexdigest()
        # Serialize file creation against transaction commit; a failed upload never exposes a partial file.
        with self._media_lock, self.transaction() as db:
            self.authorize(db, actor)
            existing = db.execute('SELECT * FROM media WHERE id=?', (upload_id,)).fetchone()
            if existing:
                if (existing['sha256'], existing['size'], existing['content_type']) != (sha, len(data), content_type):
                    raise HubError(409, 'idempotency_conflict', 'Upload ID already has different bytes or media type')
                return dict(existing)
            temporary = self.media_dir / ('.upload-' + secrets.token_hex(16))
            final = self.media_dir / upload_id
            try:
                fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
                with os.fdopen(fd, 'wb') as stream:
                    stream.write(data)
                    stream.flush()
                    os.fsync(stream.fileno())
                os.replace(temporary, final)
                directory = os.open(self.media_dir, os.O_RDONLY)
                try:
                    os.fsync(directory)
                finally:
                    os.close(directory)
                db.execute('INSERT INTO media VALUES(?,?,?,?,?)', (upload_id, sha, len(data), content_type, now()))
            finally:
                temporary.unlink(missing_ok=True)
            return dict(self._row(db, 'media', upload_id))

    def read_media(self, actor, media_id):
        with self.connection() as db:
            self.authorize(db, actor)
            row = dict(self._row(db, 'media', media_id))
        data = (self.media_dir / identifier(media_id)).read_bytes()
        if len(data) != row['size'] or hashlib.sha256(data).hexdigest() != row['sha256']:
            raise HubError(500, 'media_corrupt', 'Stored audio failed integrity verification')
        return row, data

    def _blocks(self, db, value):
        if not isinstance(value, list) or len(value) > 250:
            raise HubError(400, 'invalid_blocks', 'A prompt can contain at most 250 blocks')
        blocks, ids = [], set()
        for item in value:
            if not isinstance(item, dict):
                raise HubError(400, 'invalid_blocks', 'Invalid prompt block')
            block_id, kind = identifier(item.get('id') or str(uuid.uuid4())), item.get('kind', 'text')
            if block_id in ids or kind not in ('text', 'audio'):
                raise HubError(400, 'invalid_blocks', 'Block IDs must be unique and block kind valid')
            ids.add(block_id)
            block = {'id': block_id, 'kind': kind, 'text': string(item.get('text', ''))}
            if item.get('prompt') is not None:
                source = item['prompt']
                if kind != 'text' or not isinstance(source, dict):
                    raise HubError(400, 'invalid_blocks', 'Invalid Prompt Builder attachment')
                block['prompt'] = {'id': identifier(source.get('id')), 'revision': integer(source.get('revision'), minimum=1),
                                   'title': string(source.get('title', ''), 'title', 500)}
            if item.get('note_id'):
                # Deleted notes are allowed: blocks are independent immutable content snapshots.
                block['note_id'] = identifier(item['note_id'])
            if item.get('media_id'):
                self._media_exists(db, item['media_id'])
                block['media_id'] = item['media_id']
            if kind == 'audio' and not block.get('media_id'):
                raise HubError(400, 'media_required', 'Upload audio before saving an audio block')
            blocks.append(block)
        return blocks

    def prompt(self, db, row):
        item = {key: row[key] for key in ('id', 'title', 'instruction', 'revision', 'created_at', 'updated_at')}
        item['blocks'] = json.loads(row['blocks'])
        run = db.execute('SELECT * FROM runs WHERE prompt_id=? ORDER BY created_at DESC,rowid DESC LIMIT 1', (row['id'],)).fetchone()
        item['last_run_id'] = run['id'] if run else None
        return item

    def prompts(self, actor, prompt_id=None):
        with self.connection() as db:
            self.authorize(db, actor)
            if prompt_id:
                return self.prompt(db, self._row(db, 'prompts', prompt_id))
            return {'items': [self.prompt(db, row) for row in db.execute('SELECT * FROM prompts ORDER BY updated_at DESC')]}

    def create_prompt(self, actor, body):
        prompt_id = identifier(body.get('id') or str(uuid.uuid4()))
        title, instruction = string(body.get('title', ''), 'title', 500), string(body.get('instruction', ''))
        with self.transaction() as db:
            self.authorize(db, actor)
            blocks = self._blocks(db, body.get('blocks', []))
            payload_hash = digest(canonical(dict(title=title, instruction=instruction, blocks=blocks)))
            existing = db.execute('SELECT * FROM prompts WHERE id=?', (prompt_id,)).fetchone()
            if existing:
                if existing['creation_hash'] != payload_hash:
                    raise HubError(409, 'idempotency_conflict', 'Prompt ID already has different content')
                return self.prompt(db, existing)
            timestamp = now()
            db.execute('INSERT INTO prompts VALUES(?,?,?,?,1,?,?,?)', (prompt_id, title, instruction, canonical(blocks), timestamp, timestamp, payload_hash))
            self.change(db, 'prompt', prompt_id)
            return self.prompt(db, self._row(db, 'prompts', prompt_id))

    def lease(self, actor, prompt_id, takeover=False):
        boolean(takeover, 'takeover')
        with self.transaction() as db:
            self.authorize(db, actor)
            row = self._row(db, 'prompts', prompt_id)
            lease = db.execute('SELECT * FROM leases WHERE prompt_id=?', (prompt_id,)).fetchone()
            timestamp = now()
            if lease and lease['expires_at'] > timestamp and lease['actor'] != actor['actor'] and not takeover:
                raise HubError(409, 'lease_held', 'Another device is editing this prompt', expires_at=lease['expires_at'])
            token = lease['token'] if lease and lease['actor'] == actor['actor'] and lease['expires_at'] > timestamp else secrets.token_urlsafe(32)
            expires_at = timestamp + self.config.lease_seconds * 1000
            db.execute('INSERT OR REPLACE INTO leases VALUES(?,?,?,?)', (prompt_id, actor['actor'], token, expires_at))
            return {'lease_token': token, 'expires_at': expires_at, 'revision': row['revision']}

    def _check_lease(self, db, actor, prompt_id, token):
        lease = db.execute('SELECT * FROM leases WHERE prompt_id=?', (prompt_id,)).fetchone()
        if not lease or lease['expires_at'] <= now() or lease['actor'] != actor['actor'] or not isinstance(token, str) or not hmac.compare_digest(lease['token'], token):
            raise HubError(409, 'lease_lost', 'Editing control moved or expired; your unsaved edits must be recovered')
        return lease

    def release(self, actor, prompt_id, token):
        with self.transaction() as db:
            self.authorize(db, actor)
            self._check_lease(db, actor, prompt_id, token)
            db.execute('DELETE FROM leases WHERE prompt_id=?', (prompt_id,))
        return {'released': True}

    def update_prompt(self, actor, prompt_id, body):
        with self.transaction() as db:
            self.authorize(db, actor)
            row = self._row(db, 'prompts', prompt_id)
            self._check_lease(db, actor, prompt_id, body.get('lease_token'))
            self._revision(row, body.get('base_revision'))
            title, instruction = string(body.get('title', ''), 'title', 500), string(body.get('instruction', ''))
            blocks = self._blocks(db, body.get('blocks', []))
            if (title, instruction, blocks) == (row['title'], row['instruction'], json.loads(row['blocks'])):
                # Saving an unchanged prompt must not manufacture another executable revision.
                db.execute('UPDATE leases SET expires_at=? WHERE prompt_id=?', (now() + self.config.lease_seconds * 1000, prompt_id))
                return self.prompt(db, row)
            db.execute('UPDATE prompts SET title=?,instruction=?,blocks=?,revision=revision+1,updated_at=? WHERE id=?',
                       (title, instruction, canonical(blocks), now(), prompt_id))
            db.execute('UPDATE leases SET expires_at=? WHERE prompt_id=?', (now() + self.config.lease_seconds * 1000, prompt_id))
            result = self.prompt(db, self._row(db, 'prompts', prompt_id))
            self.change(db, 'prompt', prompt_id)
            return result

    def reserve_run(self, actor, prompt_id, body):
        request_id, revision = identifier(body.get('request_id')), integer(body.get('revision'))
        again = boolean(body.get('run_again', False), 'run_again')
        payload_hash = digest(canonical({'prompt_id': prompt_id, 'revision': revision, 'run_again': again}))
        with self.transaction() as db:
            self.authorize(db, actor)
            prior = db.execute('SELECT * FROM run_requests WHERE request_id=?', (request_id,)).fetchone()
            if prior:
                if prior['payload_hash'] != payload_hash:
                    raise HubError(409, 'idempotency_conflict', 'Run request ID already has different content')
                return self.run(self._row(db, 'runs', prior['run_id']))
            prompt = self._row(db, 'prompts', prompt_id)
            self._revision(prompt, revision)
            blocks = json.loads(prompt['blocks'])
            if any(block['kind'] == 'audio' and not block['text'].strip() for block in blocks):
                raise HubError(409, 'transcript_required', 'Every audio block needs a usable transcript before running')
            preview = '\n\n'.join(part for part in [prompt['instruction']] + [b['text'] for b in blocks] if part.strip())
            if not preview:
                raise HubError(400, 'empty_prompt', 'Add an instruction or note before running')
            # Unknown execution cannot be safely replaced by even an explicit Run again.
            unknown = db.execute("SELECT id FROM runs WHERE prompt_id=? AND status IN ('submitting','submission_unknown') LIMIT 1", (prompt_id,)).fetchone()
            existing = db.execute('SELECT * FROM runs WHERE prompt_id=? AND prompt_revision=? ORDER BY created_at DESC,rowid DESC LIMIT 1', (prompt_id, revision)).fetchone()
            if unknown and (again or not existing):
                raise HubError(409, 'submission_unknown', 'Resolve the uncertain submission before running again', run_id=unknown['id'])
            if existing and not again:
                run_id = existing['id']
            else:
                active = db.execute("SELECT id FROM runs WHERE prompt_id=? AND status IN ('queued','submitting','submission_unknown','running','waiting_for_approval','stopping') LIMIT 1", (prompt_id,)).fetchone()
                if active:
                    raise HubError(409, 'run_active', 'Finish or stop the current task before starting another in this prompt', run_id=active['id'])
                run_id, timestamp = str(uuid.uuid4()), now()
                snapshot = {key: prompt[key] for key in ('title', 'instruction')}
                snapshot.update(blocks=blocks, preview=preview)
                db.execute('INSERT INTO runs(id,prompt_id,prompt_revision,snapshot,status,created_at,updated_at,requester) VALUES(?,?,?,?,?,?,?,?)',
                           (run_id, prompt_id, revision, canonical(snapshot), 'queued', timestamp, timestamp, canonical({'role': actor['role'], 'id': actor['id']})))
                self.change(db, 'run', run_id)
                self.change(db, 'prompt', prompt_id)
            db.execute('INSERT INTO run_requests VALUES(?,?,?)', (request_id, payload_hash, run_id))
            return self.run(self._row(db, 'runs', run_id))

    def run(self, row):
        item = {key: row[key] for key in ('id', 'prompt_id', 'prompt_revision', 'status', 'output', 'error', 'created_at', 'updated_at')}
        item['approval'] = json.loads(row['approval']) if row['approval'] else None
        item['snapshot'] = json.loads(row['snapshot'])
        # These are references to Hermes's canonical execution, never host credentials or URLs.
        # Older receipts may lack the session ID: do not guess it from the requested session alias.
        item['hermes'] = {'run_id': row['remote_id'], 'session_id': row['hermes_session_id']}
        return item

    def runs(self, actor, run_id=None):
        with self.connection() as db:
            self.authorize(db, actor)
            if run_id:
                return self.run(self._row(db, 'runs', run_id))
            return {'items': [self.run(row) for row in db.execute('SELECT * FROM runs ORDER BY created_at DESC')]}

    def prompt_submissions(self, actor, prompt_id):
        """Receipts and cached Hermes replies for one saved prompt, without acquiring its lease."""
        with self.connection() as db:
            self.authorize(db, actor)
            self._row(db, 'prompts', prompt_id)
            return {'items': [self.run(row) for row in db.execute(
                'SELECT * FROM runs WHERE prompt_id=? ORDER BY created_at DESC,rowid DESC', (prompt_id,))]}

    def changes(self, actor, after):
        after = integer(after, 'after')
        with self.connection() as db:
            self.authorize(db, actor)
            # Device administrative events do not leak to workspace-only tokens.
            kinds = ('note', 'prompt', 'run', 'resume', 'device', 'command') if actor['role'] == 'owner' else ('note', 'prompt', 'run', 'resume')
            rows = db.execute('SELECT * FROM changes WHERE seq>? ORDER BY seq LIMIT 1000', (after,)).fetchall()
            return {'cursor': rows[-1]['seq'] if rows else after,
                    'items': [dict(row) for row in rows if row['kind'] in kinds]}

    def resume(self, actor, body=None):
        with self.transaction() as db:
            self.authorize(db, actor)
            row = db.execute('SELECT * FROM resume WHERE id=1').fetchone()
            if body is not None:
                self._revision(row, body.get('base_revision'))
                prompt_id, block_id = body.get('prompt_id'), body.get('block_id')
                if prompt_id:
                    prompt = self._row(db, 'prompts', prompt_id)
                    if block_id and block_id not in {b['id'] for b in json.loads(prompt['blocks'])}:
                        raise HubError(400, 'invalid_block', 'Block is not in the saved prompt')
                elif block_id:
                    raise HubError(400, 'invalid_block', 'A block requires a prompt')
                value = dict(prompt_id=prompt_id, block_id=block_id, scroll_y=integer(body.get('scroll_y', 0), 'scroll_y', maximum=10000000))
                db.execute('UPDATE resume SET revision=revision+1,value=? WHERE id=1', (canonical(value),))
                self.change(db, 'resume', 'shared')
                row = db.execute('SELECT * FROM resume WHERE id=1').fetchone()
            return dict(json.loads(row['value']), revision=row['revision'])

    def device(self, row):
        item = {key: row[key] for key in ('id', 'name', 'revision', 'settings_revision', 'applied_settings_revision',
                  'policy_revision', 'applied_policy_revision', 'policy_applied_at',
                  'lost_message', 'app_version', 'battery', 'last_seen', 'created_at', 'updated_at')}
        for key in ('desired_settings', 'applied_settings', 'capabilities', 'security'):
            item[key] = json.loads(row[key])
        for key in ('lost', 'revoked', 'charging'):
            item[key] = bool(row[key]) if row[key] is not None else None
        item['location'] = json.loads(row['location']) if row['location'] else None
        return item

    def devices(self, actor, device_id=None):
        with self.connection() as db:
            self.authorize(db, actor, 'owner')
            if device_id:
                return self.device(self._row(db, 'devices', device_id))
            return {'items': [self.device(row) for row in db.execute('SELECT * FROM devices ORDER BY created_at')]}

    def mcp_devices(self, actor):
        if actor['role'] not in ('owner', 'mcp'):
            raise HubError(403, 'wrong_scope', 'MCP or owner access required')
        with self.connection() as db:
            return {'items': [{'id': row['id'], 'name': row['name'], 'capabilities': json.loads(row['capabilities'])}
                             for row in db.execute('SELECT * FROM devices WHERE lost=0 AND revoked=0 ORDER BY created_at')]}

    def patch_device(self, actor, device_id, body):
        with self.transaction() as db:
            self.authorize(db, actor, 'owner')
            row = self._row(db, 'devices', device_id)
            self._revision(row, body.get('base_revision'))
            name = string(body.get('name', row['name']), 'name', 100).strip()
            lost = boolean(body.get('lost', bool(row['lost'])), 'lost')
            revoked = boolean(body.get('revoked', bool(row['revoked'])), 'revoked')
            message = string(body.get('lost_message', row['lost_message']), 'lost_message', 1000)
            policy_revision = row['policy_revision'] + int((lost, revoked, message) !=
                                                          (bool(row['lost']), bool(row['revoked']), row['lost_message']))
            settings, settings_revision = json.loads(row['desired_settings']), row['settings_revision']
            if 'settings' in body:
                if lost or revoked:
                    raise HubError(403, 'device_quarantined', 'Restore device access before changing ordinary settings')
                if not isinstance(body['settings'], dict) or len(canonical(body['settings'])) > 16000:
                    raise HubError(400, 'invalid_settings', 'Invalid desired settings')
                if set(body['settings']) & LOCAL_ONLY_SETTINGS:
                    raise HubError(403, 'local_permission_required', 'Recovery permissions can only be enabled on the device')
                for key, value in body['settings'].items():
                    if key == 'quiet':
                        boolean(value, key)
                    elif key == 'brightness':
                        integer(value, key, 1, 255)
                    elif key == 'text_size_sp':
                        integer(value, key, 14, 26)
                    elif key == 'screen_timeout_sec':
                        integer(value, key)
                        if value not in {10, 15, 30, 60, 120}:
                            raise HubError(400, 'invalid_settings', 'Unsupported screen timeout')
                    else:
                        raise HubError(400, 'invalid_settings', 'Unsupported desired setting: ' + str(key))
                settings.update(body['settings'])
                settings_revision += 1
            db.execute('''UPDATE devices SET name=?,lost=?,revoked=?,lost_message=?,desired_settings=?,settings_revision=?,policy_revision=?,
              revision=revision+1,updated_at=? WHERE id=?''', (name, int(lost), int(revoked), message, canonical(settings), settings_revision, policy_revision, now(), device_id))
            if lost or revoked:
                self._quarantine(db, device_id)
            self.change(db, 'device', device_id)
            self.audit(db, actor['actor'], 'device_policy_updated', device_id)
            return self.device(self._row(db, 'devices', device_id))

    def _quarantine(self, db, device_id):
        # Previously received commands may already have executed. Retain that uncertainty;
        # never describe them as cancelled or offer them for another execution.
        db.execute('DELETE FROM leases WHERE actor=?', ('device:' + device_id,))
        for command in db.execute("SELECT id,status FROM commands WHERE device_id=? AND (type IN ('device_tool','update_app','reboot') OR issuer_role!='owner') AND status IN ('queued','received')", (device_id,)).fetchall():
            status = 'cancelled' if command['status'] == 'queued' else 'unknown'
            db.execute('UPDATE commands SET status=?,result=?,updated_at=? WHERE id=?',
                       (status, canonical({'message': 'Device access restricted by owner policy'}), now(), command['id']))
            self.change(db, 'command', command['id'])

    def policy(self, row):
        return {key: bool(row[key]) if key in {'lost', 'revoked'} else row[key]
                for key in ('policy_revision', 'applied_policy_revision', 'lost', 'revoked', 'lost_message')}

    def _expire_commands(self, db, device_id):
        rows = db.execute("SELECT id,status FROM commands WHERE device_id=? AND expires_at<=? AND status IN ('queued','received')", (device_id, now())).fetchall()
        for row in rows:
            # A received command may have executed; do not describe its outcome as an expiry.
            status = 'expired' if row['status'] == 'queued' else 'unknown'
            db.execute('UPDATE commands SET status=?,updated_at=? WHERE id=?', (status, now(), row['id']))
            self.change(db, 'command', row['id'])

    def check_in(self, actor, body):
        with self.transaction() as db:
            self.authorize(db, actor, 'recovery')
            row = self._row(db, 'devices', actor['id'])
            applied_policy = integer(body.get('applied_policy_revision', 0), 'applied_policy_revision')
            if applied_policy > row['policy_revision']:
                raise HubError(409, 'policy_revision_conflict', 'Device acknowledged an unknown recovery policy')
            security = body.get('security', {})
            if not isinstance(security, dict) or set(security) - SECURITY_FIELDS or any(not isinstance(value, bool) for value in security.values()):
                raise HubError(400, 'invalid_security', 'Security posture must contain supported boolean values')
            restricted = bool(row['lost'] or row['revoked'])
            if restricted and applied_policy == row['policy_revision'] and security.get('workspace_restricted') is not True:
                raise HubError(409, 'policy_not_applied', 'Local restrictions must be applied before acknowledging recovery policy', **self.policy(row))
            policy_current = applied_policy == row['policy_revision']
            policy_applied_at = now() if policy_current and applied_policy > row['applied_policy_revision'] else row['policy_applied_at']
            # A delayed request may report an older policy; it must not undo a confirmed acknowledgement.
            stored_security = canonical(security) if applied_policy >= row['applied_policy_revision'] else row['security']
            capabilities = body.get('capabilities', {})
            if not isinstance(capabilities, dict) or len(capabilities) > 100 or any(not isinstance(k, str) or not isinstance(v, bool) for k, v in capabilities.items()):
                raise HubError(400, 'invalid_capabilities', 'Capabilities must contain boolean values')
            settings = body.get('settings', json.loads(row['applied_settings']))
            if not isinstance(settings, dict) or len(canonical(settings)) > 16000:
                raise HubError(400, 'invalid_settings', 'Invalid applied settings')
            for permission in LOCAL_ONLY_SETTINGS & settings.keys():
                boolean(settings[permission], permission)
            applied_revision = integer(body.get('settings_revision', row['applied_settings_revision']), 'settings_revision')
            if applied_revision > row['settings_revision']:
                raise HubError(409, 'settings_revision_conflict', 'Device acknowledged an unknown settings revision')
            battery = body.get('battery')
            if battery is not None:
                integer(battery, 'battery', 0, 100)
            charging = body.get('charging')
            if charging is not None:
                boolean(charging, 'charging')
            location = body.get('location')
            if location is not None:
                if settings.get('location_reporting') is not True:
                    raise HubError(403, 'location_not_permitted', 'Location reporting must be enabled locally')
                if not isinstance(location, dict):
                    raise HubError(400, 'invalid_location', 'Invalid location')
                for key, low, high in [('latitude', -90, 90), ('longitude', -180, 180), ('accuracy', 0, 100000)]:
                    val = location.get(key)
                    if key == 'accuracy' and val is None:
                        continue
                    if isinstance(val, bool) or not isinstance(val, (int, float)) or not low <= val <= high:
                        raise HubError(400, 'invalid_location', 'Invalid location ' + key)
                integer(location.get('observed_at'), 'observed_at', 0, now() + 60000)
                location = {k: location.get(k) for k in ('latitude', 'longitude', 'accuracy', 'observed_at')}
            # Disabling reporting removes the last fix; a check-in without a new fix does not invent one.
            stored_location = canonical(location) if location is not None else row['location'] if settings.get('location_reporting') else None
            # Enrollment/owner policy owns the name; an old device cache cannot undo a rename.
            name = row['name']
            version = string(body.get('app_version', ''), 'app_version', 100)
            db.execute('''UPDATE devices SET name=?,app_version=?,battery=?,charging=?,capabilities=?,applied_settings=?,
              applied_settings_revision=?,location=?,applied_policy_revision=?,policy_applied_at=?,security=?,last_seen=?,updated_at=? WHERE id=?''',
              (name, version, battery, charging, canonical(capabilities), canonical(settings), applied_revision, stored_location,
               max(applied_policy, row['applied_policy_revision']), policy_applied_at, stored_security, now(), now(), row['id']))
            self._expire_commands(db, row['id'])
            if restricted:
                self._quarantine(db, row['id'])
            commands = [self.command(r) for r in db.execute("SELECT * FROM commands WHERE device_id=? AND status IN ('queued','received') AND expires_at>? ORDER BY created_at", (row['id'], now()))
                        if not restricted or (policy_current and r['type'] in RECOVERY_COMMANDS)]
            self.change(db, 'device', row['id'])
            return {'device_id': row['id'], 'name': row['name'], **self.policy(self._row(db, 'devices', row['id'])),
                    'settings_revision': row['settings_revision'], 'settings': json.loads(row['desired_settings']), 'commands': commands}

    def command(self, row):
        item = {key: row[key] for key in ('id', 'device_id', 'type', 'expires_at', 'status', 'created_at', 'updated_at')}
        item['payload'], item['result'] = json.loads(row['payload']), json.loads(row['result'])
        return item

    def _command_permission(self, row, command_type):
        capabilities, settings = json.loads(row['capabilities']), json.loads(row['applied_settings'])
        if capabilities.get(command_type) is not True:
            raise HubError(409, 'unsupported_capability', 'Device does not advertise this capability')
        if command_type != 'device_tool' and settings.get('remote_management') is not True:
            raise HubError(403, 'local_permission_required', 'Enable remote management on the device first')
        permission = {'lock': 'secure_lock', 'locate': 'location_reporting', 'clear_workspace': 'allow_clear_workspace',
                      'factory_reset': 'allow_factory_reset', 'update_app': 'allow_updates', 'reboot': 'allow_reboot'}.get(command_type)
        if permission and settings.get(permission) is not True:
            raise HubError(403, 'local_permission_required', 'Enable this capability on the device first')

    def _insert_command(self, db, actor, row, command_id, kind, payload, expires_at):
        identity = {'device_id': row['id'], 'type': kind, 'payload': payload}
        if kind != 'device_tool':
            identity['expires_at'] = expires_at
        binding = digest(canonical(identity))
        existing = db.execute('SELECT * FROM commands WHERE id=?', (command_id,)).fetchone()
        if existing:
            if existing['payload_hash'] != binding:
                raise HubError(409, 'idempotency_conflict', 'Command ID already has a different target or payload')
            return self.command(existing)
        if expires_at <= now() or expires_at > now() + 7 * 86400 * 1000:
            raise HubError(400, 'invalid_expiry', 'Command expiry must be in the next seven days')
        timestamp = now()
        db.execute('INSERT INTO commands(id,device_id,type,payload,expires_at,status,payload_hash,created_at,updated_at,issuer_role) VALUES(?,?,?,?,?,?,?,?,?,?)',
                   (command_id, row['id'], kind, canonical(payload), expires_at, 'queued', binding, timestamp, timestamp, actor['role']))
        self.change(db, 'command', command_id)
        self.audit(db, actor['actor'], 'command_' + kind, row['id'])
        return self.command(self._row(db, 'commands', command_id))

    def create_command(self, actor, device_id, body):
        command_id, kind = identifier(body.get('id')), body.get('type')
        if kind not in COMMAND_TYPES:
            raise HubError(400, 'invalid_command', 'Unknown command type')
        payload = body.get('payload', {})
        if not isinstance(payload, dict) or len(canonical(payload)) > 16000:
            raise HubError(400, 'invalid_payload', 'Invalid command payload')
        if kind == 'ring':
            integer(payload.get('duration_seconds', 30), 'duration_seconds', 1, 120)
        if kind == 'show_message':
            string(payload.get('message'), 'message', 1000)
        if kind == 'update_app':
            # The R1 selects only its official signed release feed, never a supplied binary URL.
            integer(payload.get('version_code'), 'version_code', 1, 2147483647)
            if set(payload) != {'version_code'}:
                raise HubError(400, 'invalid_update', 'Update accepts only an official release version_code')
        expires_at = integer(body.get('expires_at'), 'expires_at')
        with self.transaction() as db:
            if actor['role'] == 'mcp':
                if kind not in self.config.mcp_allowed_commands:
                    raise HubError(403, 'wrong_scope', 'This command is not allowed through MCP')
            else:
                self.authorize(db, actor, 'owner')
            row = self._row(db, 'devices', device_id)
            if (row['lost'] or row['revoked']) and (kind not in RECOVERY_COMMANDS or actor['role'] != 'owner'):
                raise HubError(403, 'device_quarantined', 'Only owner recovery actions are available while this device is restricted')
            if kind in DANGEROUS:
                if actor['role'] != 'owner' or not actor.get('recent'):
                    raise HubError(403, 'recent_auth_required', 'Sign in again before issuing this command')
                if body.get('confirmation') != device_id:
                    raise HubError(400, 'confirmation_required', 'Confirmation must match the exact device ID')
            self._command_permission(row, kind)
            result = self._insert_command(db, actor, row, command_id, kind, payload, expires_at)
            if kind == 'clear_workspace' and not row['revoked']:
                db.execute('UPDATE devices SET revoked=1,revision=revision+1,policy_revision=policy_revision+1,updated_at=? WHERE id=?', (now(), device_id))
                self._quarantine(db, device_id)
                self.change(db, 'device', device_id)
                self.audit(db, actor['actor'], 'workspace_revoked_for_clear', device_id)
            return result

    def commands(self, actor, device_id):
        with self.transaction() as db:
            self.authorize(db, actor, 'owner')
            self._row(db, 'devices', device_id)
            self._expire_commands(db, device_id)
            return {'items': [self.command(row) for row in db.execute('SELECT * FROM commands WHERE device_id=? ORDER BY created_at DESC LIMIT 1000', (device_id,))]}

    def ack(self, actor, command_id, body):
        status, result = body.get('status'), body.get('result', {})
        if status not in {'received', 'applied', 'failed', 'unknown'} or not isinstance(result, dict) or len(canonical(result)) > 128000:
            raise HubError(400, 'invalid_receipt', 'Invalid command receipt')
        with self.transaction() as db:
            self.authorize(db, actor, 'recovery')
            row = self._row(db, 'commands', command_id)
            if row['device_id'] != actor['id']:
                raise HubError(404, 'not_found', 'Command not found')
            device = self._row(db, 'devices', actor['id'])
            if status == 'received' and (device['lost'] or device['revoked']):
                if row['type'] not in RECOVERY_COMMANDS or row['issuer_role'] != 'owner':
                    raise HubError(403, 'device_quarantined', 'Device action admission denied by recovery policy', **self.policy(device))
                if device['applied_policy_revision'] != device['policy_revision']:
                    raise HubError(409, 'policy_not_applied', 'Apply recovery policy before executing commands', **self.policy(device))
            if status == 'received':
                if row['expires_at'] <= now():
                    raise HubError(409, 'command_expired', 'Command expired before device handoff', **self.policy(device))
                # Check-in may have withdrawn a capability after this command was queued.
                self._command_permission(device, row['type'])
            if row['status'] in {'applied', 'failed', 'cancelled', 'expired'}:
                if row['status'] != status or json.loads(row['result']) != result:
                    raise HubError(409, 'receipt_conflict', 'Command already has a final receipt')
                return dict(self.command(row), **self.policy(device))
            if row['status'] == 'unknown' and status == 'received':
                raise HubError(409, 'receipt_conflict', 'Uncertain actions must not be retried')
            db.execute('UPDATE commands SET status=?,result=?,updated_at=? WHERE id=?', (status, canonical(result), now(), command_id))
            self.change(db, 'command', command_id)
            self.audit(db, actor['actor'], 'command_' + status, actor['id'])
            return dict(self.command(self._row(db, 'commands', command_id)), **self.policy(device))

    def device_action(self, actor, body=None, action_id=None):
        if actor['role'] not in {'owner', 'mcp'}:
            raise HubError(403, 'wrong_scope', 'MCP or owner access required')
        with self.transaction() as db:
            if action_id:
                row = self._row(db, 'commands', action_id)
                if row['type'] != 'device_tool':
                    raise HubError(404, 'not_found', 'Action not found')
                self._expire_commands(db, row['device_id'])
                return self.command(self._row(db, 'commands', action_id))
            command_id, device_id = identifier(body.get('id')), identifier(body.get('device_id'))
            tool = body.get('tool')
            allowed = {'send_sms', 'sms_status', 'recent_texts', 'list_recipients', 'r1_status', 'speak', 'show', 'set_volume', 'save_recipient', 'set_name', 'request_status', 'open_app'}
            if tool not in allowed:
                raise HubError(403, 'tool_not_allowed', 'Tool is not allowed through the device bridge')
            arguments = body.get('arguments', {})
            if not isinstance(arguments, dict) or len(canonical(arguments)) > 64000:
                raise HubError(400, 'invalid_arguments', 'Invalid device tool arguments')
            if tool == 'open_app':
                if arguments.get('app') not in ('home', 'workspace', 'notes', 'prompt_builder', 'chats', 'messages', 'hermes', 'settings', 'quick_controls', 'updates', 'host_devices'):
                    raise HubError(400, 'invalid_arguments', 'Choose a supported robotOS app')
                if 'item_id' in arguments:
                    if arguments['app'] not in ('notes', 'prompt_builder'):
                        raise HubError(400, 'invalid_arguments', 'Only notes and prompt_builder accept item_id')
                    identifier(arguments['item_id'])
            if tool in {'send_sms', 'speak', 'show', 'set_volume', 'save_recipient', 'set_name', 'open_app'} and arguments.get('request_id') != command_id:
                raise HubError(400, 'request_identity_required', 'Mutating tools must use the durable command ID as request_id')
            row = self._row(db, 'devices', device_id)
            if row['lost'] or row['revoked']:
                raise HubError(403, 'device_quarantined', 'Device tool access is revoked')
            self._command_permission(row, 'device_tool')
            return self._insert_command(db, actor, row, command_id, 'device_tool', {'tool': tool, 'arguments': arguments}, integer(body.get('expires_at'), 'expires_at'))

    def audit_entries(self, actor):
        with self.connection() as db:
            self.authorize(db, actor, 'owner')
            return {'items': [dict(row) for row in db.execute('SELECT * FROM audit ORDER BY id DESC LIMIT 1000')]}

    def reserve_control(self, actor, run_id, kind, body):
        request_id = identifier(body.get('request_id'))
        payload = {}
        if kind == 'approval':
            payload = {'request_id': string(body.get('approval_id'), 'approval_id', 256), 'choice': body.get('choice')}
            if not payload['request_id'] or payload['choice'] not in {'once', 'deny'}:
                raise HubError(400, 'invalid_approval', 'Approval must use its current ID and once or deny')
        with self.transaction() as db:
            self.authorize(db, actor)
            prior = db.execute('SELECT * FROM controls WHERE request_id=?', (request_id,)).fetchone()
            if prior:
                if (prior['run_id'], prior['kind'], prior['payload']) != (run_id, kind, canonical(payload)):
                    raise HubError(409, 'idempotency_conflict', 'Control request ID already has different content')
                return dict(prior), False
            run = self._row(db, 'runs', run_id)
            unsettled = db.execute("SELECT request_id FROM controls WHERE run_id=? AND status IN ('submitting','unknown') LIMIT 1", (run_id,)).fetchone()
            if unsettled:
                raise HubError(409, 'control_unknown', 'A previous task control has an uncertain outcome', request_id=unsettled['request_id'])
            if kind == 'stop' and run['status'] == 'queued' and not run['remote_id']:
                # This transaction fences the worker's queued -> submitting admission. Work that
                # has never crossed the network can be cancelled locally without upstream support.
                result = canonical({'status': 'cancelled', 'run_id': run_id})
                db.execute('INSERT INTO controls VALUES(?,?,?,?,?,?)',
                           (request_id, run_id, kind, canonical(payload), 'applied', result))
                db.execute("UPDATE runs SET status='cancelled',error='',updated_at=? WHERE id=?", (now(), run_id))
                self.change(db, 'run', run_id)
                self.change(db, 'prompt', run['prompt_id'])
                return dict(db.execute('SELECT * FROM controls WHERE request_id=?', (request_id,)).fetchone()), False
            if run['status'] in TERMINAL:
                raise HubError(409, 'run_finished', 'Task already finished')
            if not run['remote_id']:
                raise HubError(409, 'submission_unknown', 'Task admission has not been confirmed')
            caps = json.loads(run['capabilities'])
            if not caps.get(kind):
                raise HubError(409, 'unsupported_capability', 'Hermes does not advertise this control')
            if kind == 'approval':
                approval = json.loads(run['approval']) if run['approval'] else {}
                if run['status'] != 'waiting_for_approval' or approval.get('request_id') != payload['request_id']:
                    raise HubError(409, 'approval_changed', 'Approval is no longer current')
            db.execute('INSERT INTO controls VALUES(?,?,?,?,?,?)', (request_id, run_id, kind, canonical(payload), 'submitting', '{}'))
            return dict(db.execute('SELECT * FROM controls WHERE request_id=?', (request_id,)).fetchone()), True
