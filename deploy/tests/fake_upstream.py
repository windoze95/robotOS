"""Disposable authenticated TLS Hermes stub and TLS reverse proxy, for container smoke only."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import http.client
import json
from pathlib import Path
import ssl

TOKEN = Path('/test/hermes-token').read_text().strip()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        self.route()

    def do_POST(self):
        self.route()

    def route(self):
        body = self.rfile.read(int(self.headers.get('Content-Length', '0')))
        if self.path.startswith('/api/v1/'):
            # Acts like a normal ingress: preserves Host and does not fabricate identity.
            connection = http.client.HTTPConnection('hub', 8787, timeout=5)
            headers = {key: value for key, value in self.headers.items() if key.lower() != 'connection'}
            connection.request(self.command, self.path, body, headers)
            response = connection.getresponse()
            data = response.read()
            self.send_response(response.status)
            for key, value in response.getheaders():
                if key.lower() in {'content-type', 'set-cookie'}:
                    self.send_header(key, value)
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            connection.close()
            return
        if self.headers.get('Authorization') != 'Bearer ' + TOKEN:
            status, result = 401, {'error': 'unauthorized'}
        elif self.path == '/v1/capabilities':
            status, result = 200, {'object': 'hermes.api_server.capabilities', 'features': {
                'run_submission': True, 'run_status': True,
                'runs_idempotency': {'supported': True, 'durable': True, 'retention_seconds': 86400}}}
        elif self.path == '/v1/runs' and self.command == 'POST':
            assert self.headers.get('Idempotency-Key')
            status, result = 200, {'run_id': 'smoke-run'}
        elif self.path == '/v1/runs/smoke-run':
            status, result = 200, {'run_id': 'smoke-run', 'status': 'completed', 'output': 'container TLS complete'}
        else:
            status, result = 404, {'error': 'not_found'}
        data = json.dumps(result).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)


server = ThreadingHTTPServer(('0.0.0.0', 8642), Handler)
context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
context.load_cert_chain('/test/cert.pem', '/test/key.pem')
server.socket = context.wrap_socket(server.socket, server_side=True)
server.serve_forever()
