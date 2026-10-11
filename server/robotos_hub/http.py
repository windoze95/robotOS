"""Bounded same-origin HTTP interface for the R1, owner console and scoped MCP adapter."""
from collections import defaultdict, deque
from http.cookies import SimpleCookie
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlsplit
import hmac
import json
import mimetypes
import socket
import threading
import time

from .store import HubError, Store, canonical, integer
from .upstream import Workers


class RateLimit:
    def __init__(self):
        self.lock = threading.Lock()
        self.entries = defaultdict(deque)

    def check(self, key, count=10, interval=60):
        with self.lock:
            stamp = time.monotonic()
            values = self.entries[key]
            while values and values[0] < stamp - interval:
                values.popleft()
            if len(values) >= count:
                raise HubError(429, 'rate_limited', 'Too many attempts; try again shortly')
            values.append(stamp)
            if len(self.entries) > 4096:
                self.entries = defaultdict(deque, {k: v for k, v in self.entries.items() if v and v[-1] >= stamp - interval})


class HubServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, config, store=None, workers=None):
        self.config = config
        self.store = store or Store(config)
        self.workers = workers or Workers(self.store)
        self.rate_limit = RateLimit()
        self.slots = threading.BoundedSemaphore(32)
        self.web_dir = Path(__file__).parent / 'web'
        if ':' in config.bind:
            self.address_family = socket.AF_INET6
        super().__init__((config.bind, config.port), Handler)

    def process_request(self, request, client_address):
        if not self.slots.acquire(blocking=False):
            request.close()
            return
        try:
            super().process_request(request, client_address)
        except Exception:
            self.slots.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.slots.release()


class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    server_version = 'robotOS'
    sys_version = ''

    def log_message(self, format, *args):
        # Request paths may contain identifiers; bodies and authentication never enter logs.
        pass

    def setup(self):
        super().setup()
        self.connection.settimeout(20)

    @property
    def config(self):
        return self.server.config

    @property
    def store(self):
        return self.server.store

    def do_GET(self):
        self.handle_request('GET')

    def do_POST(self):
        self.handle_request('POST')

    def do_PUT(self):
        self.handle_request('PUT')

    def do_PATCH(self):
        self.handle_request('PATCH')

    def do_DELETE(self):
        self.handle_request('DELETE')

    def do_HEAD(self):
        self.handle_request('HEAD')

    def _host_and_origin(self):
        host = self.headers.get('Host', '')
        port = self.server.server_address[1]
        local_hosts = {'localhost:' + str(port), '127.0.0.1:' + str(port), '[::1]:' + str(port)}
        if port == 80:
            local_hosts |= {'localhost', '127.0.0.1', '[::1]'}
        public = urlsplit(self.config.public_origin)
        if host not in local_hosts and (not self.config.public_origin or host != public.netloc):
            raise HubError(421, 'invalid_host', 'Host is not configured for this service')
        expected = self.config.public_origin if self.config.public_origin and host == public.netloc else 'http://' + host
        origin = self.headers.get('Origin')
        if origin and origin != expected:
            raise HubError(403, 'origin_mismatch', 'Cross-origin access is not allowed')
        site = self.headers.get('Sec-Fetch-Site')
        if site == 'cross-site':
            raise HubError(403, 'origin_mismatch', 'Cross-site access is not allowed')
        return expected

    def _session_token(self):
        try:
            cookie = SimpleCookie(self.headers.get('Cookie', ''))
            return cookie['robotos_session'].value if 'robotos_session' in cookie else ''
        except Exception:
            return ''

    def _actor(self, method):
        authorization = self.headers.get('Authorization')
        if authorization:
            if not authorization.startswith('Bearer ') or len(authorization) > 2048:
                raise HubError(401, 'unauthorized', 'Invalid authentication')
            return self.store.authenticate_token(authorization[7:])
        actor = self.store.session(self._session_token())
        if method not in {'GET', 'HEAD'}:
            csrf = self.headers.get('X-CSRF-Token', '')
            if not hmac.compare_digest(csrf, actor['csrf_token']):
                raise HubError(403, 'csrf_required', 'A valid CSRF token is required')
        return actor

    def _read(self, limit):
        if self.headers.get('Transfer-Encoding'):
            raise HubError(400, 'invalid_framing', 'Transfer encoding is not supported')
        lengths = self.headers.get_all('Content-Length', [])
        if len(lengths) != 1 or not lengths[0].isdigit():
            raise HubError(411, 'length_required', 'A single Content-Length is required')
        length = int(lengths[0])
        if length > limit:
            raise HubError(413, 'body_too_large', 'Request body exceeds the size limit')
        data = self.rfile.read(length)
        if len(data) != length:
            raise HubError(400, 'incomplete_body', 'Request upload was interrupted')
        return data

    def _json(self):
        content_type = self.headers.get('Content-Type', '').split(';')[0].strip().lower()
        if content_type != 'application/json':
            raise HubError(415, 'json_required', 'Content-Type must be application/json')
        try:
            body = json.loads(self._read(self.config.max_json_bytes), parse_constant=lambda value: (_ for _ in ()).throw(ValueError()))
        except (ValueError, UnicodeError):
            raise HubError(400, 'invalid_json', 'Invalid JSON request') from None
        if not isinstance(body, dict):
            raise HubError(400, 'invalid_json', 'JSON request must be an object')
        return body

    def _send(self, status, value, content_type='application/json; charset=utf-8', headers=None, method=None):
        data = canonical(value).encode() if not isinstance(value, bytes) else value
        self.send_response(status)
        self.send_header('Content-Type', content_type)
        self.send_header('Content-Length', str(len(data)))
        self.send_header('Cache-Control', 'no-store')
        self.send_header('X-Content-Type-Options', 'nosniff')
        self.send_header('Referrer-Policy', 'no-referrer')
        self.send_header('Cross-Origin-Resource-Policy', 'same-origin')
        self.send_header('X-Frame-Options', 'DENY')
        self.send_header('Content-Security-Policy', "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; media-src 'self' blob:; font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'")
        for key, val in (headers or {}).items():
            self.send_header(key, val)
        self.send_header('Connection', 'close')
        self.end_headers()
        self.close_connection = True
        if method != 'HEAD':
            self.wfile.write(data)

    def handle_request(self, method):
        try:
            origin = self._host_and_origin()
            parsed = urlsplit(self.path)
            if parsed.scheme or parsed.netloc or '\\' in parsed.path or '%' in parsed.path or '//' in parsed.path:
                raise HubError(400, 'invalid_path', 'Invalid request path')
            if not parsed.path.startswith('/api/v1/'):
                return self._static(method, parsed.path)
            path = parsed.path[len('/api/v1'):].strip('/')
            parts = path.split('/')
            if path == 'health' and method == 'GET':
                return self._send(200, {'status': 'ok'}, method=method)
            if path == 'session' and method == 'POST':
                self.server.rate_limit.check(('login', self.client_address[0]), 8)
                body = self._json()
                token, csrf = self.store.login(body.get('password'))
                prior = self._session_token()
                if prior:
                    self.store.logout(prior)
                actor = self.store.session(token)
                cookie = 'robotos_session=' + token + '; HttpOnly; SameSite=Strict; Path=/api/v1; Max-Age=' + str(self.config.session_seconds)
                if origin.startswith('https://'):
                    cookie += '; Secure'
                return self._send(200, self._session_body(actor), headers={'Set-Cookie': cookie})
            if path == 'enrollments/claim' and method == 'POST':
                self.server.rate_limit.check(('claim', self.client_address[0]), 20)
                return self._send(201, self.store.claim(self._json()))
            actor = self._actor(method)
            if path == 'session' and method == 'GET':
                if actor['role'] != 'owner':
                    raise HubError(403, 'wrong_scope', 'Owner access required')
                result = self._session_body(actor)
            elif path == 'logout' and method == 'POST':
                self.store.logout(self._session_token())
                return self._send(200, {'logged_out': True}, headers={'Set-Cookie': 'robotos_session=; HttpOnly; SameSite=Strict; Path=/api/v1; Max-Age=0'})
            elif path == 'enrollments' and method == 'POST':
                result = self.store.enrollment(actor, self._json())
            elif parts[0] == 'notes':
                if len(parts) == 1 and method == 'GET':
                    result = self.store.notes(actor)
                elif len(parts) == 1 and method == 'POST':
                    result = self.store.create_note(actor, self._json())
                elif len(parts) == 2 and method == 'GET':
                    result = self.store.notes(actor, parts[1])
                elif len(parts) == 2 and method == 'PUT':
                    result = self.store.update_note(actor, parts[1], self._json())
                elif len(parts) == 2 and method == 'DELETE':
                    revision = self.headers.get('If-Match', '').strip('"')
                    if not revision.isdigit():
                        raise HubError(428, 'revision_required', 'If-Match revision required')
                    result = self.store.delete_note(actor, parts[1], int(revision))
                elif len(parts) == 3 and parts[2] == 'transcribe' and method == 'POST':
                    result = self.server.workers.transcribe(actor, parts[1], self._json())
                else:
                    raise HubError(404, 'not_found', 'Route not found')
            elif path == 'media' and method == 'POST':
                result = self.store.save_media(actor, self.headers.get('X-Upload-Id'), self.headers.get('Content-Type', '').split(';')[0], self._read(self.config.max_audio_bytes))
            elif len(parts) == 2 and parts[0] == 'media' and method == 'GET':
                media, data = self.store.read_media(actor, parts[1])
                return self._send(200, data, media['content_type'], {'Content-Disposition': 'inline; filename="recording"'})
            elif parts[0] == 'prompts':
                if len(parts) == 1 and method == 'GET':
                    result = self.store.prompts(actor)
                elif len(parts) == 1 and method == 'POST':
                    result = self.store.create_prompt(actor, self._json())
                elif len(parts) == 2 and method == 'GET':
                    result = self.store.prompts(actor, parts[1])
                elif len(parts) == 2 and method == 'PUT':
                    result = self.store.update_prompt(actor, parts[1], self._json())
                elif len(parts) == 3 and method == 'POST' and parts[2] == 'lease':
                    result = self.store.lease(actor, parts[1], self._json().get('takeover', False))
                elif len(parts) == 3 and method == 'POST' and parts[2] == 'release':
                    result = self.store.release(actor, parts[1], self._json().get('lease_token'))
                elif len(parts) == 3 and method == 'POST' and parts[2] == 'run':
                    result = self.store.reserve_run(actor, parts[1], self._json())
                else:
                    raise HubError(404, 'not_found', 'Route not found')
            elif parts[0] == 'runs':
                if len(parts) == 1 and method == 'GET':
                    result = self.store.runs(actor)
                elif len(parts) == 2 and method == 'GET':
                    result = self.store.runs(actor, parts[1])
                elif len(parts) == 3 and method == 'POST' and parts[2] in {'stop', 'approval'}:
                    result = self.server.workers.control(actor, parts[1], parts[2], self._json())
                elif len(parts) == 3 and method == 'POST' and parts[2] == 'reconcile':
                    self._json()
                    result = self.server.workers.reconcile(actor, parts[1])
                else:
                    raise HubError(404, 'not_found', 'Route not found')
            elif path == 'changes' and method == 'GET':
                after = parse_qs(parsed.query).get('after', ['0'])[0]
                if not after.isdigit():
                    raise HubError(400, 'invalid_cursor', 'Invalid change cursor')
                result = self.store.changes(actor, int(after))
            elif path == 'resume' and method in {'GET', 'PUT'}:
                result = self.store.resume(actor, self._json() if method == 'PUT' else None)
            elif path == 'devices/self/check-in' and method == 'POST':
                result = self.store.check_in(actor, self._json())
            elif len(parts) == 5 and parts[:3] == ['devices', 'self', 'commands'] and parts[4] == 'ack' and method == 'POST':
                result = self.store.ack(actor, parts[3], self._json())
            elif path == 'devices' and method == 'GET':
                result = self.store.devices(actor)
            elif len(parts) == 2 and parts[0] == 'devices' and method == 'GET':
                result = self.store.devices(actor, parts[1])
            elif len(parts) == 2 and parts[0] == 'devices' and method == 'PATCH':
                result = self.store.patch_device(actor, parts[1], self._json())
            elif len(parts) == 3 and parts[0] == 'devices' and parts[2] == 'commands' and method in {'GET', 'POST'}:
                result = self.store.commands(actor, parts[1]) if method == 'GET' else self.store.create_command(actor, parts[1], self._json())
            elif path == 'mcp/devices' and method == 'GET':
                result = self.store.mcp_devices(actor)
            elif path == 'device-actions' and method == 'POST':
                result = self.store.device_action(actor, self._json())
            elif len(parts) == 2 and parts[0] == 'device-actions' and method == 'GET':
                result = self.store.device_action(actor, action_id=parts[1])
            elif path == 'audit' and method == 'GET':
                result = self.store.audit_entries(actor)
            else:
                raise HubError(404, 'not_found', 'Route not found')
            self._send(200, result, method=method)
        except HubError as exc:
            self._safe_error(exc.status, exc.body())
        except (socket.timeout, TimeoutError):
            self._safe_error(408, {'code': 'request_timeout', 'error': 'Request timed out'})
        except (BrokenPipeError, ConnectionResetError):
            self.close_connection = True
        except Exception:
            self._safe_error(500, {'code': 'internal_error', 'error': 'The operation could not be confirmed; check saved state before retrying'})

    def _safe_error(self, status, body):
        try:
            self._send(status, body)
        except (OSError, ValueError):
            self.close_connection = True

    def _session_body(self, actor):
        return {key: actor.get(key) for key in ('role', 'csrf_token', 'authenticated_at', 'expires_at')}

    def _static(self, method, path):
        if method not in {'GET', 'HEAD'}:
            raise HubError(405, 'method_not_allowed', 'Method not allowed')
        relative = path.lstrip('/') or 'index.html'
        candidate = (self.server.web_dir / relative).resolve()
        if not candidate.is_relative_to(self.server.web_dir.resolve()) or not candidate.is_file():
            raise HubError(404, 'not_found', 'File not found')
        content_type = 'text/javascript' if candidate.suffix in {'.js', '.mjs'} else mimetypes.guess_type(candidate)[0] or 'application/octet-stream'
        self._send(200, candidate.read_bytes(), content_type, method=method)
