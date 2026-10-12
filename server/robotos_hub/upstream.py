"""Host-only Hermes and speech transports. Ambiguous mutations are never silently repeated."""
import hashlib
import json
import math
import re
import secrets
import threading
import time
import urllib.error
import urllib.request
from .store import HubError, TERMINAL, canonical, digest, identifier, now, string


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class UpstreamError(Exception):
    def __init__(self, status=None):
        self.status = status
        super().__init__('Upstream request failed' + (' (HTTP %s)' % status if status else ''))


class Client:
    def __init__(self, config):
        self.config = config
        self.opener = urllib.request.build_opener(NoRedirect)

    @property
    def identity(self):
        return digest(self.config.hermes_url + '\0' + self.config.hermes_token)

    def request(self, url, token, method='GET', payload=None, headers=None, data=None, timeout=30):
        headers = dict(headers or {})
        headers['Accept'] = 'application/json'
        if token:
            headers['Authorization'] = 'Bearer ' + token
        if payload is not None:
            data = canonical(payload).encode()
            headers['Content-Type'] = 'application/json'
        request = urllib.request.Request(url, data=data, method=method, headers=headers)
        try:
            with self.opener.open(request, timeout=timeout) as response:
                raw = response.read(1024 * 1024 + 1)
                if len(raw) > 1024 * 1024:
                    raise UpstreamError()
                result = json.loads(raw)
                if not isinstance(result, dict):
                    raise UpstreamError()
                return result
        except urllib.error.HTTPError as exc:
            raise UpstreamError(exc.code) from None
        except (OSError, ValueError, urllib.error.URLError):
            raise UpstreamError() from None

    def hermes(self, method, path, payload=None, headers=None):
        if not self.config.hermes_url:
            raise HubError(503, 'hermes_not_configured', 'Hermes is not configured on the host')
        base = self.config.hermes_url.removesuffix('/v1')
        return self.request(base + path, self.config.hermes_token, method, payload, headers)

    def capabilities(self):
        result = self.hermes('GET', '/v1/capabilities')
        if result.get('object') != 'hermes.api_server.capabilities' or not isinstance(result.get('features'), dict):
            raise HubError(502, 'unsupported_hermes', 'Hermes did not return supported capabilities')
        features, endpoints = result['features'], result.get('endpoints', {})
        if not isinstance(endpoints, dict):
            raise HubError(502, 'unsupported_hermes', 'Hermes returned an invalid endpoint table')
        def supports(flag, name, method, path):
            if features.get(flag) is not True:
                return False
            advertised = endpoints.get(name)
            if advertised is not None and (not isinstance(advertised, dict) or advertised.get('method') != method or advertised.get('path') != path):
                raise HubError(502, 'unsupported_hermes', 'Hermes advertised an unexpected endpoint')
            return True
        durable = features.get('runs_idempotency', {})
        if not isinstance(durable, dict):
            raise HubError(502, 'unsupported_hermes', 'Hermes returned invalid idempotency capabilities')
        retention = durable.get('retention_seconds', 0)
        if isinstance(retention, bool) or not isinstance(retention, (int, float)) or not math.isfinite(retention):
            raise HubError(502, 'unsupported_hermes', 'Hermes returned invalid idempotency retention')
        approval_flag = 'run_approval_response' if 'run_approval_response' in features else 'run_approval'
        return {'runs': supports('run_submission', 'runs', 'POST', '/v1/runs') and supports('run_status', 'run_status', 'GET', '/v1/runs/{run_id}'),
                'stop': supports('run_stop', 'run_stop', 'POST', '/v1/runs/{run_id}/stop'),
                'approval': features.get('approval_events') is True and supports(approval_flag, 'run_approval', 'POST', '/v1/runs/{run_id}/approval'),
                'durable': durable.get('supported') is True and durable.get('durable') is True,
                'retention_seconds': max(0, min(int(retention), 7 * 86400))}

    def transcribe(self, media, data):
        if not self.config.speech_url:
            raise HubError(503, 'speech_not_configured', 'Speech transcription is not configured on the host')
        boundary = 'robotos-' + secrets.token_hex(24)
        extension = {'audio/wav': 'wav', 'audio/x-wav': 'wav', 'audio/mpeg': 'mp3', 'audio/mp4': 'm4a', 'audio/aac': 'aac', 'audio/ogg': 'ogg', 'audio/webm': 'webm', 'audio/flac': 'flac'}[media['content_type']]
        model = self.config.speech_model
        chunks = [('--' + boundary + '\r\nContent-Disposition: form-data; name="model"\r\n\r\n' + model + '\r\n').encode(),
                  ('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="recording.' + extension + '"\r\nContent-Type: ' + media['content_type'] + '\r\n\r\n').encode(),
                  data, ('\r\n--' + boundary + '--\r\n').encode()]
        url = self.config.speech_url
        if not url.endswith('/audio/transcriptions'):
            url += '/audio/transcriptions'
        result = self.request(url, self.config.speech_token, 'POST', data=b''.join(chunks),
                              headers={'Content-Type': 'multipart/form-data; boundary=' + boundary}, timeout=120)
        text = string(result.get('text'), 'transcription')
        if not text.strip():
            raise HubError(422, 'empty_transcript', 'Speech service returned no usable transcript; original audio is preserved')
        return text


class Workers:
    def __init__(self, store, client=None):
        self.store, self.config = store, store.config
        self.client = client or Client(self.config)
        self.stop_event = threading.Event()
        self.thread = None
        self._run_locks = {}
        self._run_locks_guard = threading.Lock()
        self._transcribe_slots = threading.BoundedSemaphore(2)

    def start(self):
        self.thread = threading.Thread(target=self._loop, daemon=True, name='robotos-hub-runs')
        self.thread.start()

    def stop(self):
        self.stop_event.set()
        if self.thread:
            self.thread.join(timeout=5)

    def _loop(self):
        while not self.stop_event.is_set():
            try:
                self.tick()
            except Exception:
                # Never print payload, token, upstream URL, or audio into service logs.
                pass
            self.stop_event.wait(self.config.worker_interval)

    def _update(self, run_id, **fields):
        allowed = {'status', 'output', 'error', 'remote_id', 'hermes_session_id', 'upstream_payload', 'upstream_identity', 'capabilities', 'approval', 'submitted_at'}
        if not fields.keys() <= allowed:
            raise ValueError('Unsupported run update')
        with self.store.transaction() as db:
            row = self.store._row(db, 'runs', run_id)
            if row['status'] in TERMINAL:
                return
            fields['updated_at'] = now()
            db.execute('UPDATE runs SET ' + ','.join(key + '=?' for key in fields) + ' WHERE id=?', (*fields.values(), run_id))
            self.store.change(db, 'run', run_id)
            self.store.change(db, 'prompt', row['prompt_id'])

    def tick(self):
        with self.store.connection() as db:
            queued = [dict(row) for row in db.execute("SELECT * FROM runs WHERE status='queued' AND remote_id IS NULL ORDER BY created_at LIMIT 8")]
            running = [dict(row) for row in db.execute("SELECT * FROM runs WHERE remote_id IS NOT NULL AND status NOT IN ('completed','failed','cancelled','interrupted') ORDER BY updated_at LIMIT 32")]
        for run in queued:
            if self.stop_event.is_set():
                break
            self.submit(run['id'])
        for run in running:
            if self.stop_event.is_set():
                break
            self.poll(run)

    def submit(self, run_id, recovery=False):
        try:
            caps = self.client.capabilities()
            if not caps['runs']:
                raise HubError(503, 'unsupported_hermes', 'Hermes must support durable run status before prompts can run')
        except (HubError, UpstreamError) as exc:
            if recovery:
                raise HubError(503, 'hermes_unavailable', str(exc)) from None
            self._update(run_id, status='failed', error=str(exc))
            return
        with self.store.transaction() as db:
            run = dict(self.store._row(db, 'runs', run_id))
            if recovery:
                original = json.loads(run['capabilities'])
                if run['status'] != 'submission_unknown' or run['remote_id']:
                    raise HubError(409, 'not_unknown', 'Run has no uncertain submission to check')
                if run['upstream_identity'] != self.client.identity:
                    raise HubError(409, 'upstream_changed', 'Hermes connection changed; inspect the original host')
                window = min(original.get('retention_seconds', 0), caps['retention_seconds']) * 1000
                if not original.get('durable') or not caps['durable'] or not run['submitted_at'] or now() - run['submitted_at'] >= window:
                    raise HubError(409, 'unsafe_retry', 'Hermes cannot prove this request is still deduplicated; inspect it on the host')
                payload = json.loads(run['upstream_payload'])
            else:
                if run.get('requester'):
                    try:
                        self.store.authorize(db, json.loads(run['requester']))
                    except HubError:
                        db.execute("UPDATE runs SET status='cancelled',error='Initiating device access was revoked before dispatch',updated_at=? WHERE id=?", (now(), run_id))
                        self.store.change(db, 'run', run_id)
                        return
                if run['status'] != 'queued' or run['remote_id']:
                    return
                snapshot = json.loads(run['snapshot'])
                payload = {'input': snapshot['preview'], 'session_id': 'robotos-' + run['prompt_id'], 'instructions': self.config.hermes_instructions}
                if self.config.hermes_model:
                    payload['model'] = self.config.hermes_model
                if self.config.hermes_provider:
                    payload['provider'] = self.config.hermes_provider
            # Save exact request, identity and capabilities before crossing the network boundary.
            db.execute("UPDATE runs SET status='submitting',upstream_payload=?,upstream_identity=?,capabilities=?,submitted_at=COALESCE(submitted_at,?),updated_at=? WHERE id=?",
                       (canonical(payload), self.client.identity, canonical(original if recovery else caps), now(), now(), run_id))
            self.store.change(db, 'run', run_id)
        try:
            admission = self.client.hermes('POST', '/v1/runs', payload, {'Idempotency-Key': run_id})
            remote_id = identifier(admission.get('run_id'))
        except Exception as exc:
            rejected = isinstance(exc, UpstreamError) and exc.status in {400, 401, 403, 404, 405, 413, 422, 429}
            self._update(run_id, status='failed' if rejected else 'submission_unknown', error='Hermes rejected the request' if rejected else 'Hermes submission could not be confirmed. Check the same submission before retrying.')
            return
        self._update(run_id, status='running', remote_id=remote_id, error='')

    def _run_lock(self, run_id):
        with self._run_locks_guard:
            return self._run_locks.setdefault(run_id, threading.RLock())

    def poll(self, run):
        # A GET started before an approval/stop must finish before that control, not overwrite
        # its result afterward. Keep unrelated runs independent and allow control's fresh poll.
        with self._run_lock(run['id']):
            with self.store.connection() as db:
                current = dict(self.store._row(db, 'runs', run['id']))
            if current['status'] in TERMINAL or not current['remote_id']:
                return
            self._poll(current)

    def _poll(self, run):
        if run['upstream_identity'] != self.client.identity:
            self._update(run['id'], error='Hermes connection changed; inspect the original host')
            return
        try:
            result = self.client.hermes('GET', '/v1/runs/' + identifier(run['remote_id']))
            status = result.get('status')
            if result.get('run_id') != run['remote_id'] or status not in TERMINAL | {'started', 'queued', 'running', 'waiting_for_approval', 'stopping'}:
                raise UpstreamError()
            if status == 'completed' and not isinstance(result.get('output'), str):
                raise UpstreamError()
            output, error = string(result.get('output', ''), limit=1000000), string(result.get('error', ''), 'error', 10000)
            approval = result.get('approval')
            if approval is not None and (not isinstance(approval, dict) or len(canonical(approval)) > 32000):
                raise UpstreamError()
            reference = {}
            session_id = result.get('session_id')
            if isinstance(session_id, str) and 0 < len(session_id) <= 256 and all(ord(c) >= 32 and ord(c) != 127 for c in session_id):
                reference['hermes_session_id'] = session_id
            self._update(run['id'], status='running' if status in {'queued', 'started'} else status, output=output, error=error,
                         approval=canonical(approval) if approval else None, **reference)
        except (UpstreamError, HubError):
            self._update(run['id'], error='Hermes status unavailable; the task may still be running')

    def reconcile(self, actor, run_id):
        self.store.runs(actor, run_id)
        self.submit(run_id, recovery=True)
        return self.store.runs(actor, run_id)

    def control(self, actor, run_id, kind, body):
        with self._run_lock(run_id):
            reserved, fresh = self.store.reserve_control(actor, run_id, kind, body)
            if not fresh:
                return dict(self.store.runs(actor, run_id), control={'request_id': reserved['request_id'], 'status': reserved['status'], 'result': json.loads(reserved['result'])})
            with self.store.connection() as db:
                run = dict(self.store._row(db, 'runs', run_id))
            try:
                if run['upstream_identity'] != self.client.identity:
                    raise HubError(409, 'upstream_changed', 'Hermes connection changed')
                result = self.client.hermes('POST', '/v1/runs/' + identifier(run['remote_id']) + '/' + kind, json.loads(reserved['payload']))
                if kind == 'approval':
                    payload = json.loads(reserved['payload'])
                    if result.get('run_id') != run['remote_id'] or result.get('request_id') != payload['request_id'] or result.get('resolved') != 1:
                        raise UpstreamError()
                elif result.get('status') not in {'stopping', 'cancelled', 'completed', 'failed', 'interrupted'}:
                    raise UpstreamError()
                status = 'applied'
                self.poll(run)
            except Exception:
                status, result = 'unknown', {'error': 'Control outcome could not be confirmed. Check task status on the host before another control.'}
                self._update(run_id, error=result['error'])
            with self.store.transaction() as db:
                db.execute('UPDATE controls SET status=?,result=? WHERE request_id=?', (status, canonical(result), reserved['request_id']))
            return dict(self.store.runs(actor, run_id), control={'request_id': reserved['request_id'], 'status': status, 'result': result})

    def transcribe(self, actor, note_id, body):
        note = self.store.notes(actor, note_id)
        if note['revision'] != body.get('base_revision'):
            raise HubError(409, 'revision_conflict', 'A newer note revision is saved', current_revision=note['revision'])
        if note['kind'] != 'audio' or not note['media_id']:
            raise HubError(400, 'audio_required', 'Only audio notes can be transcribed')
        if not self._transcribe_slots.acquire(blocking=False):
            raise HubError(429, 'speech_busy', 'Transcription workers are busy; try again shortly')
        try:
            media, data = self.store.read_media(actor, note['media_id'])
            try:
                transcript = self.client.transcribe(media, data)
            except UpstreamError as exc:
                raise HubError(502, 'speech_failed', str(exc) + '; original audio is preserved') from None
            # Recheck revocation and revision after the slow call; never overwrite edits made meanwhile.
            return self.store.update_note(actor, note_id, {'base_revision': note['revision'], 'title': note['title'], 'text': transcript})
        finally:
            self._transcribe_slots.release()
