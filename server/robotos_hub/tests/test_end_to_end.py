"""Wire-level acceptance with two enrolled clients and a real HTTP Hermes fixture."""
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import socket
import tempfile
import threading
import unittest

from robotos_hub.config import Config
from robotos_hub.http import HubServer
from robotos_hub.store import now


class HermesFixture(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def send_json(self, body):
        raw = json.dumps(body).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        if self.path == '/v1/capabilities':
            return self.send_json({'object': 'hermes.api_server.capabilities', 'features': {
                'run_submission': True, 'run_status': True, 'run_stop': True,
                'approval_events': True, 'run_approval_response': True,
                'runs_idempotency': {'supported': True, 'durable': True, 'retention_seconds': 3600}}})
        run = self.server.runs[self.path.rsplit('/', 1)[-1]]
        self.send_json(run)

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        if self.path == '/v1/runs':
            identity = self.headers['Idempotency-Key']
            self.server.attempts.append((identity, body))
            self.server.runs.setdefault(identity, {'run_id': identity, 'status': 'running', 'output': '', 'error': ''})
            if self.server.drop_admission:
                self.server.drop_admission = False
                self.connection.shutdown(socket.SHUT_RDWR)
                self.connection.close()
                return
            return self.send_json({'run_id': identity})
        if self.path.endswith('/approval'):
            identity = self.path.split('/')[-2]
            self.server.runs[identity].update(status='completed', output='The confirmed reply')
            self.send_json({'run_id': identity, 'request_id': body['request_id'], 'resolved': 1})
            return
        self.send_error(404)


class EndToEndTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.hermes = ThreadingHTTPServer(('127.0.0.1', 0), HermesFixture)
        self.hermes.runs, self.hermes.attempts, self.hermes.drop_admission = {}, [], False
        self.config = Config(Path(self.temp.name), 'temporary-acceptance-password', port=0,
                             owner_token='owner-test-' * 4, mcp_token='mcp-test-' * 5,
                             hermes_url='http://127.0.0.1:' + str(self.hermes.server_port))
        self.hub = HubServer(self.config)
        self.threads = []
        for server in (self.hermes, self.hub):
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            self.threads.append(thread)
        self.owner = {'Authorization': 'Bearer ' + self.config.owner_token}
        self.a, self.b = self.enroll('Desk R1'), self.enroll('Pocket R1')

    def tearDown(self):
        for server in (self.hub, self.hermes):
            server.shutdown()
            server.server_close()
        for thread in self.threads:
            thread.join(2)
        self.temp.cleanup()

    def call(self, method, path, body=None, credentials=None, expected=200):
        connection = http.client.HTTPConnection('127.0.0.1', self.hub.server_port, timeout=5)
        headers = dict(credentials or self.owner)
        if body is not None:
            headers['Content-Type'] = 'application/json'
            body = json.dumps(body).encode()
        connection.request(method, '/api/v1' + path, body, headers)
        response = connection.getresponse()
        result = json.loads(response.read())
        connection.close()
        self.assertEqual(expected, response.status, result)
        return result

    def enroll(self, name):
        enrollment = self.call('POST', '/enrollments', {'name': name})
        claimed = self.call('POST', '/enrollments/claim', {'code': enrollment['code']}, expected=201)
        claimed['workspace'] = {'Authorization': 'Bearer ' + claimed['device_token']}
        claimed['recovery'] = {'Authorization': 'Bearer ' + claimed['recovery_token']}
        return claimed

    def prepare_prompt(self):
        note = self.call('POST', '/notes', {'id': 'note', 'title': 'Thought', 'kind': 'text', 'text': '  Preserve these words  '}, self.a['workspace'])
        prompt = self.call('POST', '/prompts', {'id': 'shared-conversation', 'title': 'Shared work', 'instruction': '  Summarize  ',
                           'blocks': [{'id': 'block', 'kind': 'text', 'note_id': note['id'], 'text': note['text']}]}, self.a['workspace'])
        lease_a = self.call('POST', '/prompts/shared-conversation/lease', {}, self.a['workspace'])
        lease_b = self.call('POST', '/prompts/shared-conversation/lease', {'takeover': True}, self.b['workspace'])
        self.call('PUT', '/prompts/shared-conversation', dict(prompt, base_revision=1, lease_token=lease_a['lease_token']), self.a['workspace'], expected=409)
        prompt = self.call('PUT', '/prompts/shared-conversation', dict(prompt, base_revision=1, lease_token=lease_b['lease_token']), self.b['workspace'])
        self.call('PUT', '/resume', {'base_revision': 0, 'prompt_id': prompt['id'], 'scroll_y': 42}, self.b['workspace'])
        self.assertEqual(42, self.call('GET', '/resume', credentials=self.a['workspace'])['scroll_y'])
        return prompt

    def test_two_device_handoff_execution_approval_and_lost_recovery(self):
        prompt = self.prepare_prompt()
        run = self.call('POST', '/prompts/shared-conversation/run', {'revision': prompt['revision'], 'request_id': 'run-a'}, self.a['workspace'])
        duplicate = self.call('POST', '/prompts/shared-conversation/run', {'revision': prompt['revision'], 'request_id': 'run-b'}, self.b['workspace'])
        self.assertEqual(run['id'], duplicate['id'])
        self.hub.workers.tick()
        self.assertEqual(1, len(self.hermes.attempts))
        self.assertEqual('  Summarize  \n\n  Preserve these words  ', self.hermes.attempts[0][1]['input'])
        self.hermes.runs[run['id']].update(status='waiting_for_approval', approval={'request_id': 'approval-1', 'description': 'A test decision'})
        self.hub.workers.tick()
        pending = self.call('GET', '/runs/' + run['id'], credentials=self.b['workspace'])
        self.assertEqual('waiting_for_approval', pending['status'])
        result = self.call('POST', '/runs/' + run['id'] + '/approval', {'request_id': 'approval-control', 'approval_id': 'approval-1', 'choice': 'once'}, self.b['workspace'])
        self.assertEqual('completed', result['status'])
        self.assertEqual('The confirmed reply', result['output'])
        self.assertEqual('ran', self.call('GET', '/prompts/shared-conversation', credentials=self.a['workspace'])['state'])
        self.call('PATCH', '/devices/' + self.a['device_id'], {'base_revision': 1, 'lost': True, 'lost_message': 'Please return this R1'})
        self.call('GET', '/notes', credentials=self.a['workspace'], expected=403)
        packet = {'app_version': '0.4.0', 'capabilities': {'show_message': True}, 'settings': {'remote_management': True}, 'settings_revision': 0}
        recovery = self.call('POST', '/devices/self/check-in', packet, self.a['recovery'])
        self.assertTrue(recovery['lost'])
        command = self.call('POST', '/devices/' + self.a['device_id'] + '/commands',
                            {'id': 'return-message', 'type': 'show_message', 'payload': {'message': 'Please return this R1'}, 'expires_at': now() + 60000})
        self.assertEqual('queued', command['status'])
        for status in ('received', 'applied'):
            self.call('POST', '/devices/self/commands/return-message/ack', {'status': status, 'result': {'message': 'Return information saved'}}, self.a['recovery'])
        self.assertEqual('applied', self.call('GET', '/devices/' + self.a['device_id'] + '/commands')['items'][0]['status'])
        self.assertEqual('The confirmed reply', self.call('GET', '/runs/' + run['id'], credentials=self.b['workspace'])['output'])

    def test_dropped_real_upstream_admission_reconciles_same_identity_and_payload(self):
        prompt = self.prepare_prompt()
        run = self.call('POST', '/prompts/shared-conversation/run', {'revision': prompt['revision'], 'request_id': 'uncertain'}, self.a['workspace'])
        self.hermes.drop_admission = True
        self.hub.workers.tick()
        self.assertEqual('submission_unknown', self.call('GET', '/runs/' + run['id'])['status'])
        self.hub.workers.tick()
        self.assertEqual(1, len(self.hermes.attempts))
        self.call('POST', '/runs/' + run['id'] + '/reconcile', {}, self.b['workspace'])
        self.assertEqual(2, len(self.hermes.attempts))
        self.assertEqual(self.hermes.attempts[0], self.hermes.attempts[1])
        self.assertEqual(1, len(self.hermes.runs))

    def test_stop_before_upstream_dispatch_is_atomic_and_never_executes(self):
        prompt = self.prepare_prompt()
        run = self.call('POST', '/prompts/shared-conversation/run', {'revision': prompt['revision'], 'request_id': 'queued'}, self.a['workspace'])
        stopped = self.call('POST', '/runs/' + run['id'] + '/stop', {'request_id': 'stop-before-dispatch'}, self.b['workspace'])
        self.assertEqual('cancelled', stopped['status'])
        self.assertEqual('applied', stopped['control']['status'])
        repeated = self.call('POST', '/runs/' + run['id'] + '/stop', {'request_id': 'stop-before-dispatch'}, self.b['workspace'])
        self.assertEqual(stopped, repeated)
        self.hub.workers.tick()
        self.assertEqual([], self.hermes.attempts)


if __name__ == '__main__':
    unittest.main()
