"""Data-preservation checks for the workspace schema cleanup."""
import json
from unittest.mock import patch

from robotos_hub.store import SCHEMA_VERSION, Store, canonical, now
from robotos_hub.upstream import UpstreamError, Workers
from robotos_hub.tests.test_hub import HubFixture


class SchemaTest(HubFixture):
    def database_state(self):
        """Read every retained row, including private receipts and credential hashes."""
        with self.store.connection() as db:
            tables = [row[0] for row in db.execute(
                "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")]
            return {
                'version': db.execute('PRAGMA user_version').fetchone()[0],
                'schema': [tuple(row) for row in db.execute(
                    "SELECT type,name,tbl_name,sql FROM sqlite_master ORDER BY type,name")],
                'rows': {table: [dict(row) for row in db.execute(
                    'SELECT * FROM ' + table + ' ORDER BY rowid')] for table in tables},
            }

    def seed_workspace(self):
        self.config.hermes_instructions = 'Retain the exact original instructions.\n'
        self.config.hermes_model = 'original-model'
        self.config.hermes_provider = 'original-provider'
        self.audio = b'original recording bytes\x00\xff'
        self.store.save_media(self.owner, 'recording', 'audio/wav', self.audio)
        self.store.create_note(self.owner, {
            'id': 'voice-note', 'title': 'Recording', 'kind': 'audio',
            'text': 'Original transcript — keep spacing.  ', 'media_id': 'recording',
        })
        self.store.create_note(self.owner, {
            'id': 'written-note', 'title': 'Written note', 'text': 'Unsummarized source',
        })
        blocks = [{'id': 'original-block', 'kind': 'audio', 'note_id': 'voice-note',
                   'media_id': 'recording', 'text': 'Original transcript — keep spacing.  '}]
        self.store.create_prompt(self.owner, {
            'id': 'completed', 'title': 'Original title', 'instruction': '  Exact instruction\n',
            'blocks': blocks,
        })
        completed = self.store.reserve_run(self.a['workspace'], 'completed', {
            'revision': 1, 'request_id': 'completed-request',
        })
        self.workers.tick()
        self.fake.state = 'completed'
        self.workers.tick()
        lease = self.store.lease(self.owner, 'completed')
        self.store.update_prompt(self.owner, 'completed', {
            'base_revision': 1, 'lease_token': lease['lease_token'], 'title': 'Edited title',
            'instruction': 'Unsubmitted next revision', 'blocks': blocks,
        })
        self.store.resume(self.owner, {
            'base_revision': 0, 'prompt_id': 'completed', 'block_id': 'original-block', 'scroll_y': 37,
        })

        self.store.create_prompt(self.owner, {'id': 'unknown', 'instruction': 'Uncertain request'})
        unknown = self.store.reserve_run(self.owner, 'unknown', {'revision': 1, 'request_id': 'unknown-request'})
        self.fake.fail_submission = True
        self.workers.tick()
        self.fake.fail_submission = False
        self.fake.state = 'running'

        self.store.create_prompt(self.owner, {'id': 'cancelled', 'instruction': 'Never dispatched'})
        cancelled = self.store.reserve_run(self.owner, 'cancelled', {'revision': 1, 'request_id': 'cancelled-request'})
        self.workers.control(self.owner, cancelled['id'], 'stop', {'request_id': 'cancel-control'})

        self.store.create_prompt(self.owner, {'id': 'approval', 'instruction': 'Approval context'})
        approval = self.store.reserve_run(self.owner, 'approval', {'revision': 1, 'request_id': 'approval-request'})
        self.workers.tick()
        with self.store.transaction() as db:
            db.execute("UPDATE runs SET status='waiting_for_approval',approval=? WHERE id=?", (
                canonical({'request_id': 'permission-id', 'description': 'An exact approval description',
                           'tool': 'write_file', 'arguments': {'path': 'original.txt'}}), approval['id']))
        with patch.object(self.fake, 'hermes', side_effect=UpstreamError()):
            self.workers.control(self.owner, approval['id'], 'approval', {
                'request_id': 'unknown-control', 'approval_id': 'permission-id', 'choice': 'once',
            })

        self.store.create_prompt(self.owner, {'id': 'queued', 'instruction': 'Queued exact text'})
        queued = self.store.reserve_run(self.owner, 'queued', {'revision': 1, 'request_id': 'queued-request'})

        self.checkin()
        self.checkin(self.b)
        for command_id, device, result in [('applied-command', self.a, 'applied'),
                                           ('unknown-command', self.b, 'unknown')]:
            self.store.create_command(self.owner, device['device_id'], {
                'id': command_id, 'type': 'ring', 'payload': {'duration_seconds': 12},
                'expires_at': now() + 600000,
            })
            self.store.ack(device['recovery'], command_id, {'status': 'received'})
            self.store.ack(device['recovery'], command_id, {'status': result, 'result': {'message': result}})
        policy = self.store.patch_device(self.owner, self.a['device_id'], {
            'base_revision': 1, 'lost': True, 'lost_message': 'Preserve this recovery policy',
        })
        self.checkin(applied_policy_revision=policy['policy_revision'], security={
            'app_restriction_available': True, 'workspace_restricted': True,
            'secure_lock_configured': False, 'device_admin_active': False,
        })
        self.enrollment = self.store.enrollment(self.owner, {'name': 'Pending second enrollment'})
        self.session_token, self.csrf = self.store.login(self.config.owner_password)
        self.run_ids = {name: receipt['id'] for name, receipt in (
            ('completed', completed), ('unknown', unknown), ('cancelled', cancelled),
            ('approval', approval), ('queued', queued))}
        # The original transport serialization must survive, not merely an equivalent reconstructed dict.
        with self.store.transaction() as db:
            for row in db.execute('SELECT id,upstream_payload FROM runs WHERE upstream_payload IS NOT NULL').fetchall():
                payload = json.loads(row['upstream_payload'])
                db.execute('UPDATE runs SET upstream_payload=? WHERE id=?',
                           (json.dumps(payload, ensure_ascii=False, indent=2) + '\n', row['id']))
            db.execute('UPDATE runs SET hermes_session_id=? WHERE id=?',
                       ('confirmed:original-session', completed['id']))

    def make_v3(self):
        """Restore the actual removed v3 columns and pre-cleanup JSON shapes."""
        with self.store.transaction() as db:
            db.execute('ALTER TABLE devices ADD COLUMN lost_applied INTEGER NOT NULL DEFAULT 0')
            db.execute('UPDATE devices SET lost_applied=lost')
            db.execute('ALTER TABLE controls ADD COLUMN created_at INTEGER NOT NULL DEFAULT 0')
            db.execute('UPDATE controls SET created_at=123456789')
            db.execute('CREATE TABLE prompt_versions(prompt_id TEXT NOT NULL,revision INTEGER NOT NULL,'
                       'snapshot TEXT NOT NULL,PRIMARY KEY(prompt_id,revision))')
            for row in db.execute('SELECT * FROM prompts').fetchall():
                snapshot = dict(row)
                snapshot['blocks'] = json.loads(snapshot['blocks'])
                db.execute('INSERT INTO prompt_versions VALUES(?,?,?)',
                           (row['id'], row['revision'], canonical(snapshot)))
            for row in db.execute('SELECT * FROM runs').fetchall():
                snapshot = json.loads(row['snapshot'])
                snapshot.update(id=row['prompt_id'], revision=row['prompt_revision'], created_at=11, updated_at=22,
                                last_run_revision=None, last_run_id=None, changed_since_run=False, state='draft')
                requester = json.loads(row['requester'])
                requester['actor'] = 'device:' + requester['id'] if requester['role'] == 'device' else 'owner-token'
                capabilities = dict(json.loads(row['capabilities']), runs=True)
                db.execute('UPDATE runs SET snapshot=?,requester=?,capabilities=? WHERE id=?',
                           (canonical(snapshot), canonical(requester), canonical(capabilities), row['id']))
            db.execute('PRAGMA user_version=3')

    def assert_minimal_schema(self):
        with self.store.connection() as db:
            self.assertEqual(SCHEMA_VERSION, db.execute('PRAGMA user_version').fetchone()[0])
            names = {row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
            self.assertNotIn('prompt_versions', names)
            self.assertFalse(any(name.endswith('_clean') for name in names))
            devices = {row['name'] for row in db.execute('PRAGMA table_info(devices)')}
            self.assertNotIn('lost_applied', devices)
            self.assertTrue({'policy_revision', 'applied_policy_revision', 'policy_applied_at', 'security'} <= devices)
            controls = {row['name'] for row in db.execute('PRAGMA table_info(controls)')}
            self.assertEqual({'request_id', 'run_id', 'kind', 'payload', 'status', 'result'}, controls)
            self.assertIn('hermes_session_id', {row['name'] for row in db.execute('PRAGMA table_info(runs)')})
            self.assertIn('issuer_role', {row['name'] for row in db.execute('PRAGMA table_info(commands)')})
            self.assertEqual('ok', db.execute('PRAGMA integrity_check').fetchone()[0])

    def test_fresh_schema_and_new_submission_contain_only_needed_fields(self):
        self.assert_minimal_schema()
        self.prompt(self.owner)
        run = self.store.reserve_run(self.owner, 'prompt', {'revision': 1, 'request_id': 'fresh'})
        self.assertEqual({'title', 'instruction', 'blocks', 'preview'}, set(run['snapshot']))
        self.assertEqual('Do the work', run['snapshot']['preview'])
        self.workers.tick()
        with self.store.connection() as db:
            row = self.store._row(db, 'runs', run['id'])
            self.assertEqual({'role': 'owner', 'id': 'owner-token'}, json.loads(row['requester']))
            self.assertEqual({'durable': True, 'retention_seconds': 3600, 'stop': True, 'approval': True},
                             json.loads(row['capabilities']))

    def test_v3_upgrade_preserves_all_retained_rows_and_reopens_idempotently(self):
        self.seed_workspace()
        retained = self.database_state()['rows']
        self.make_v3()
        self.store = Store(self.config)
        self.assert_minimal_schema()
        self.assertEqual(retained, self.database_state()['rows'])
        self.assertEqual(self.audio, self.store.read_media(self.owner, 'recording')[1])
        self.assertEqual(self.csrf, self.store.session(self.session_token)['csrf_token'])
        self.assertEqual('device', self.store.authenticate_token(self.b['device_token'])['role'])
        self.assertEqual('recovery', self.store.authenticate_token(self.a['recovery_token'])['role'])
        self.assertError('device_quarantined', self.store.authenticate_token, self.a['device_token'])
        policy = self.store.devices(self.owner, self.a['device_id'])
        self.assertEqual(policy['policy_revision'], policy['applied_policy_revision'])
        self.assertTrue(policy['security']['workspace_restricted'])
        self.assertEqual('Preserve this recovery policy', policy['lost_message'])
        saved = self.store.prompts(self.owner, 'completed')
        self.assertEqual(self.run_ids['completed'], saved['last_run_id'])
        self.assertTrue({'state', 'last_run_revision', 'changed_since_run'}.isdisjoint(saved))
        completed = self.store.runs(self.owner, self.run_ids['completed'])
        self.assertEqual('Original title', completed['snapshot']['title'])
        self.assertEqual('  Exact instruction\n\n\nOriginal transcript — keep spacing.  ', completed['snapshot']['preview'])
        self.assertEqual('saved result', completed['output'])
        self.assertEqual('confirmed:original-session', completed['hermes']['session_id'])
        self.assertEqual('submission_unknown', self.store.runs(self.owner, self.run_ids['unknown'])['status'])
        self.assertEqual('cancelled', self.store.runs(self.owner, self.run_ids['cancelled'])['status'])
        queued = self.store.runs(self.owner, self.run_ids['queued'])
        self.assertEqual('queued', queued['status'])
        self.assertEqual('Queued exact text', queued['snapshot']['preview'])
        self.assertError('submission_unknown', self.store.reserve_run, self.owner, 'unknown',
                         {'revision': 1, 'request_id': 'unsafe-new-attempt', 'run_again': True})
        self.assertError('control_unknown', self.store.reserve_control, self.owner, self.run_ids['approval'], 'approval',
                         {'request_id': 'unsafe-new-control', 'approval_id': 'permission-id', 'choice': 'once'})
        self.assertEqual(self.run_ids['completed'], self.store.reserve_run(self.owner, 'completed',
                         {'revision': 1, 'request_id': 'completed-request'})['id'])
        workers = Workers(self.store, self.fake)
        stopped = workers.control(self.owner, self.run_ids['cancelled'], 'stop', {'request_id': 'cancel-control'})
        self.assertEqual('applied', stopped['control']['status'])
        after_upgrade = self.database_state()
        self.store = Store(self.config)
        self.assertEqual(after_upgrade, self.database_state())

    def test_upgraded_uncertain_submission_reuses_original_payload_and_identity(self):
        self.seed_workspace()
        with self.store.connection() as db:
            original = dict(self.store._row(db, 'runs', self.run_ids['unknown']))
        self.make_v3()
        self.store = Store(self.config)
        self.config.hermes_model = 'different-model'
        self.config.hermes_provider = 'different-provider'
        self.config.hermes_instructions = 'Different current instructions'
        before = len(self.fake.submissions)
        result = Workers(self.store, self.fake).reconcile(self.owner, self.run_ids['unknown'])
        self.assertEqual('running', result['status'])
        self.assertEqual(before + 1, len(self.fake.submissions))
        self.assertEqual((json.loads(original['upstream_payload']), original['id']), self.fake.submissions[-1])
        with self.store.connection() as db:
            row = self.store._row(db, 'runs', original['id'])
            self.assertEqual(original['submitted_at'], row['submitted_at'])
            self.assertEqual(original['upstream_identity'], row['upstream_identity'])
            self.assertEqual({'durable', 'retention_seconds', 'stop', 'approval'}, set(json.loads(row['capabilities'])))

    def test_mid_upgrade_failure_rolls_back_schema_and_every_row(self):
        self.seed_workspace()
        self.make_v3()
        with self.store.transaction() as db:
            db.execute('UPDATE runs SET snapshot=? WHERE id=?', ('{broken-json', self.run_ids['unknown']))
        before = self.database_state()
        with self.assertRaises(json.JSONDecodeError):
            Store(self.config)
        self.assertEqual(before, self.database_state())
        self.assertEqual(self.audio, (self.store.media_dir / 'recording').read_bytes())

    def test_future_schema_refused_without_schema_or_data_changes(self):
        self.seed_workspace()
        self.make_v3()
        with self.store.transaction() as db:
            db.execute('PRAGMA user_version=' + str(SCHEMA_VERSION + 1))
            db.execute('CREATE TABLE future_feature(id TEXT PRIMARY KEY, payload TEXT NOT NULL)')
            db.execute("INSERT INTO future_feature VALUES('preserve','opaque future data')")
        before = self.database_state()
        with self.assertRaisesRegex(ValueError, 'newer robotOS hub'):
            Store(self.config)
        self.assertEqual(before, self.database_state())

    def test_edited_prompt_submits_current_content_without_rewriting_old_input(self):
        self.prompt(self.owner)
        original = self.store.reserve_run(self.owner, 'prompt', {'revision': 1, 'request_id': 'original'})
        self.workers.tick()
        self.fake.state = 'completed'
        self.workers.tick()
        lease = self.store.lease(self.owner, 'prompt')
        self.store.update_prompt(self.owner, 'prompt', {
            'base_revision': 1, 'lease_token': lease['lease_token'], 'title': 'Current',
            'instruction': '  Current instruction\n',
            'blocks': [{'id': 'current-block', 'kind': 'text', 'text': '\nExact block  '}],
        })
        current = self.store.reserve_run(self.owner, 'prompt', {'revision': 2, 'request_id': 'current'})
        self.assertEqual({'title': 'Current', 'instruction': '  Current instruction\n',
                          'blocks': [{'id': 'current-block', 'kind': 'text', 'text': '\nExact block  '}],
                          'preview': '  Current instruction\n\n\n\nExact block  '}, current['snapshot'])
        self.assertEqual(original['snapshot'], self.store.runs(self.owner, original['id'])['snapshot'])
        self.assertError('revision_conflict', self.store.reserve_run, self.owner, 'prompt',
                         {'revision': 1, 'request_id': 'stale-revision'})
        self.workers.tick()
        self.assertEqual(current['snapshot']['preview'], self.fake.submissions[-1][0]['input'])
        self.assert_minimal_schema()
