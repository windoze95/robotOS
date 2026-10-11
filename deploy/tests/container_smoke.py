#!/usr/bin/env python3
"""Real, disposable Docker acceptance: persistence, process fence, TLS and adapter.

Only containers/networks/volumes created with a random robotos-smoke prefix are removed.
No real credentials, model requests, devices, or existing Docker context are changed.
"""
import argparse
import http.client
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]


def command(*args, check=True, input=None):
    result = subprocess.run(args, input=input, text=True, capture_output=True)
    if check and result.returncode:
        raise RuntimeError('Command failed: ' + args[0] + ' ' + args[1] + '\n' + result.stderr[-2000:])
    return result


def docker(*args, **kwargs):
    return command('docker', *args, **kwargs)


def await_healthy(name, seconds=60):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        data = json.loads(docker('inspect', name).stdout)[0]['State']
        if data.get('Health', {}).get('Status') == 'healthy':
            return
        if not data['Running']:
            raise RuntimeError('Container stopped: ' + name + '\n' + docker('logs', name).stdout[-2000:])
        time.sleep(1)
    raise RuntimeError('Container did not become healthy: ' + name + '\n' + docker('logs', name).stdout[-2000:])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', required=True)
    parser.add_argument('--hermes-image')
    args = parser.parse_args()
    prefix = 'robotos-smoke-' + uuid.uuid4().hex[:10]
    network, volume, hub, upstream, fence, hermes = [prefix + '-' + suffix for suffix in ('net', 'data', 'hub', 'tls', 'fence', 'hermes')]
    hermes_volume = prefix + '-hermes-data'
    containers = []
    volumes = []
    with tempfile.TemporaryDirectory(prefix=prefix) as temporary:
        folder = Path(temporary)
        # A private parent protects these readable files; Docker mounts hide the parent.
        for name in ('owner-password', 'owner-token', 'mcp-token', 'hermes-token'):
            (folder / name).write_text(secrets.token_urlsafe(40))
            (folder / name).chmod(0o444)
        (folder / 'speech-token').write_text('')
        (folder / 'speech-token').chmod(0o444)
        command('openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1',
                '-subj', '/CN=hermes.test', '-addext', 'subjectAltName=DNS:hermes.test,DNS:workspace.test',
                '-keyout', str(folder / 'key.pem'), '-out', str(folder / 'cert.pem'))
        (folder / 'key.pem').chmod(0o444)
        try:
            docker('network', 'create', network)
            for target in (volume, hermes_volume):
                docker('volume', 'create', target)
                volumes.append(target)
            docker('run', '-d', '--name', upstream, '--network', network, '--network-alias', 'hermes.test',
                   '--network-alias', 'workspace.test', '-v', str(folder) + ':/test:ro',
                   '-v', str(ROOT / 'deploy/tests/fake_upstream.py') + ':/test-server.py:ro',
                   '--entrypoint', 'python', args.image, '/test-server.py')
            containers.append(upstream)
            common = ['--network', network, '--read-only', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges',
                      '--tmpfs', '/tmp:rw,noexec,nosuid,size=16m', '-v', volume + ':/var/lib/robotos',
                      '-v', str(folder) + ':/run/secrets:ro', '-e', 'ROBOTOS_HUB_BIND=0.0.0.0',
                      '-e', 'ROBOTOS_HUB_ALLOW_REMOTE_BIND=true', '-e', 'ROBOTOS_HUB_PUBLIC_ORIGIN=https://workspace.test:8642',
                      '-e', 'ROBOTOS_HUB_HERMES_URL=https://hermes.test:8642', '-e', 'ROBOTOS_HUB_WORKER_INTERVAL=0.1',
                      '-e', 'SSL_CERT_FILE=/run/secrets/cert.pem']
            for name in ('owner-password', 'owner-token', 'mcp-token', 'hermes-token', 'speech-token'):
                common += ['-e', 'ROBOTOS_HUB_' + name.replace('-', '_').upper() + '_FILE=/run/secrets/' + name]
            docker('run', '-d', '--name', hub, '--network-alias', 'hub', '-p', '127.0.0.1::8787',
                   '--health-interval', '1s', *common, args.image)
            containers.append(hub)
            await_healthy(hub)
            port = int(docker('port', hub, '8787/tcp').stdout.strip().rsplit(':', 1)[1])
            owner = (folder / 'owner-token').read_text()

            def request(method, path, body=None, token=owner, headers=None):
                connection = http.client.HTTPConnection('127.0.0.1', port, timeout=10)
                fields = {'Host': 'workspace.test:8642', 'Content-Type': 'application/json'}
                if token:
                    fields['Authorization'] = 'Bearer ' + token
                fields.update(headers or {})
                connection.request(method, '/api/v1/' + path, None if body is None else json.dumps(body), fields)
                response = connection.getresponse()
                status, payload, response_headers = response.status, json.loads(response.read()), dict(response.getheaders())
                connection.close()
                return status, payload, response_headers

            assert request('GET', 'notes', token='')[0] == 401, 'Unauthenticated access was allowed'
            assert request('GET', 'health', headers={'Host': 'wrong.test'})[0] == 421, 'Host validation was bypassed'
            assert request('POST', 'notes', {'id': 'bad-origin'}, headers={'Origin': 'https://evil.test'})[0] == 403
            password = (folder / 'owner-password').read_text()
            status, _, headers = request('POST', 'session', {'password': password}, token='', headers={'Origin': 'https://workspace.test:8642'})
            assert status == 200 and '; Secure' in headers['Set-Cookie'], 'Public-origin cookie is not secure'
            status, note, _ = request('POST', 'notes', {'id': 'persistent-note', 'kind': 'text', 'title': 'Smoke', 'text': 'survives restart'})
            assert status in (200, 201), (status, note)
            status, prompt, _ = request('POST', 'prompts', {'id': 'tls-prompt', 'title': 'Smoke', 'instruction': 'test only', 'blocks': []})
            assert status in (200, 201), (status, prompt)
            status, run, _ = request('POST', 'prompts/tls-prompt/run', {'revision': 1, 'request_id': 'tls-request'})
            assert status in (200, 202)
            deadline = time.monotonic() + 20
            while time.monotonic() < deadline:
                _, run, _ = request('GET', 'runs/' + run['id'])
                if run['status'] == 'completed':
                    break
                time.sleep(0.2)
            assert run['status'] == 'completed' and run['output'] == 'container TLS complete', 'Split-container HTTPS run failed: ' + run['status']
            # A second writer must fail before startup recovery even when its port differs.
            blocked = docker('run', '--name', fence, *common, '-e', 'ROBOTOS_HUB_PORT=8788', args.image, check=False)
            containers.append(fence)
            assert blocked.returncode == 2 and 'already owns' in blocked.stderr, 'Second writer was not fenced'
            docker('restart', hub)
            await_healthy(hub)
            port = int(docker('port', hub, '8787/tcp').stdout.strip().rsplit(':', 1)[1])
            assert request('GET', 'notes/persistent-note')[1]['text'] == 'survives restart'
            assert request('GET', 'runs/' + run['id'])[1]['status'] == 'completed'
            print('Hub smoke passed: nonroot/read-only, probes, auth/origin, HTTPS Hermes, durable run, restart persistence, single-writer fence.')
            if args.hermes_image:
                seed = "from pathlib import Path; import os; os.umask(0o077); p=Path('/opt/data/hermes'); p.mkdir(mode=0o700,exist_ok=True); (p/'config.yaml').write_text('display:\\n  compact: true\\n'); (p/'.env').write_text('API_SERVER_KEY=old-synthetic-key-must-not-override-mounted-key\\n')"
                docker('run', '--rm', '-v', hermes_volume + ':/opt/data', '--entrypoint',
                       '/opt/hermes/.venv/bin/python', args.hermes_image, '-c', seed)
                docker('run', '-d', '--name', hermes, '--network', network, '--cap-drop', 'ALL',
                       '--security-opt', 'no-new-privileges', '-v', hermes_volume + ':/opt/data',
                       '-v', str(folder) + ':/run/secrets:ro', '-e', 'ROBOTOS_HUB_URL=https://workspace.test:8642',
                       '-e', 'ROBOTOS_HUB_MCP_TOKEN_FILE=/run/secrets/mcp-token',
                       '-e', 'API_SERVER_KEY_FILE=/run/secrets/hermes-token', '-e', 'SSL_CERT_FILE=/run/secrets/cert.pem',
                       args.hermes_image)
                containers.append(hermes)
                # Read adapter config inside the real upstream image and call the scoped
                # adapter through a real TLS proxy. No provider credentials/model call.
                script = """import json, os, sys, time, urllib.request
sys.path.insert(0, '/opt/hermes')
import hermes_yaml as yaml
from pathlib import Path
for _ in range(90):
    try:
        urllib.request.urlopen('http://127.0.0.1:8642/health', timeout=2).read()
        break
    except Exception: time.sleep(1)
else: raise SystemExit('Hermes API failed to start')
key = Path('/run/secrets/hermes-token').read_text().strip()
request = urllib.request.Request('http://127.0.0.1:8642/v1/capabilities', headers={'Authorization': 'Bearer ' + key})
with urllib.request.urlopen(request, timeout=5) as response:
    assert json.load(response)['object'] == 'hermes.api_server.capabilities'
configuration = yaml.safe_load(Path('/opt/data/hermes/config.yaml').read_text())
assert configuration['display']['compact'] is True
c = configuration['mcp_servers']['robotos']
os.environ.update(c['env'])
sys.path.insert(0, '/opt/robotos')
from robotos_mcp.hub import from_env
hub = from_env([])
status, notes = hub.call('GET', '/notes')
assert status == 200 and any(x['id'] == 'persistent-note' for x in notes['items'])
assert Path('/opt/data/hermes/skills/robotos/SKILL.md').is_file()
assert os.getuid() == 10000
print('Hermes smoke passed: unprivileged gateway/API, managed skill and scoped MCP over HTTPS.')
"""
                result = docker('exec', hermes, '/opt/hermes/.venv/bin/python', '-c', script)
                print(result.stdout.strip())
        finally:
            for container in reversed(containers):
                docker('rm', '-f', container, check=False)
            for target in volumes:
                docker('volume', 'rm', target, check=False)
            docker('network', 'rm', network, check=False)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
