#!/usr/bin/env python3
"""Validate the real manifests in a disposable Docker-hosted k3s control plane.

Never uses the operator's kubectl context. Requires Docker and kubectl. The only
privileged container is this temporary Kubernetes test node, removed in finally.
"""
import argparse
import http.client
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import ssl
import subprocess
import tempfile
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
K3S = 'rancher/k3s:v1.34.1-k3s1@sha256:5e0707cfd1239b358ef73f3254bc3eadc027dd30cd5ec6ca41e29e47652a1b8c'


def run(*args, input=None, check=True):
    result = subprocess.run(args, input=input, text=True, capture_output=True)
    if check and result.returncode:
        raise RuntimeError(args[0] + ' failed: ' + result.stderr[-3000:])
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', required=True)
    parser.add_argument('--hermes-image', help='Also validate the optional Hermes deployment and authenticated API')
    args = parser.parse_args()
    name = 'robotos-k3s-smoke-' + uuid.uuid4().hex[:10]
    with tempfile.TemporaryDirectory(prefix=name) as temporary:
        folder = Path(temporary)
        kubeconfig = folder / 'kubeconfig'
        proxy = None
        try:
            run('docker', 'run', '-d', '--privileged', '--name', name, '-p', '127.0.0.1::6443',
                '--tmpfs', '/run', '--tmpfs', '/var/run', K3S, 'server',
                '--disable', 'metrics-server', '--disable', 'servicelb', '--write-kubeconfig-mode', '600')
            port = run('docker', 'port', name, '6443/tcp').stdout.strip().rsplit(':', 1)[1]
            for _ in range(90):
                result = run('docker', 'exec', name, 'cat', '/etc/rancher/k3s/k3s.yaml', check=False)
                if result.returncode == 0:
                    kubeconfig.write_text(result.stdout.replace('127.0.0.1:6443', '127.0.0.1:' + port))
                    kubeconfig.chmod(0o600)
                    break
                time.sleep(1)
            else:
                raise RuntimeError('Disposable k3s did not initialize')

            def kubectl(*more, **kwargs):
                return run('kubectl', '--kubeconfig', str(kubeconfig), *more, **kwargs)

            for _ in range(90):
                result = kubectl('get', 'nodes', '-o', 'json', check=False)
                if result.returncode == 0 and json.loads(result.stdout).get('items'):
                    break
                time.sleep(1)
            kubectl('wait', '--for=condition=Ready', 'node', '--all', '--timeout=120s')
            for image_name in filter(None, (args.image, args.hermes_image)):
                image_tar = folder / 'image.tar'
                run('docker', 'save', '-o', str(image_tar), image_name)
                with image_tar.open('rb') as image:
                    result = subprocess.run(['docker', 'exec', '-i', name, 'ctr', 'images', 'import', '-'], stdin=image, capture_output=True)
                    if result.returncode:
                        raise RuntimeError('Could not import test image into disposable k3s')
                image_tar.unlink()
            manifests = folder / 'k3s'
            shutil.copytree(ROOT / 'deploy/k3s', manifests)
            if args.hermes_image:
                kustomization = manifests / 'kustomization.yaml'
                kustomization.write_text(kustomization.read_text().replace('  - hub.yaml', '  - hub.yaml\n  - hermes.yaml'))
            # Render real assets; substitute only the just-built test image.
            rendered = kubectl('kustomize', str(manifests)).stdout.replace('image: robotos-hub:local', 'image: ' + args.image)
            if args.hermes_image:
                rendered = rendered.replace('image: robotos-hermes:local', 'image: ' + args.hermes_image)
            kubectl('apply', '-f', str(manifests / 'namespace.yaml'))
            private = folder / 'private'
            private.mkdir(mode=0o700)
            for key in ('owner-password', 'owner-token', 'mcp-token', 'hermes-token', 'speech-token'):
                (private / key).write_text(secrets.token_urlsafe(40))
            kubectl('-n', 'robotos', 'create', 'secret', 'generic', 'robotos-credentials', '--from-file=' + str(private))
            run('openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1',
                '-subj', '/CN=workspace.example.net', '-addext', 'subjectAltName=DNS:workspace.example.net',
                '-keyout', str(folder / 'tls.key'), '-out', str(folder / 'tls.crt'))
            kubectl('-n', 'robotos', 'create', 'secret', 'tls', 'robotos-tls',
                    '--cert=' + str(folder / 'tls.crt'), '--key=' + str(folder / 'tls.key'))
            kubectl('apply', '-f', '-', input=rendered)
            kubectl('-n', 'robotos', 'rollout', 'status', 'deployment/robotos-hub', '--timeout=120s')
            if args.hermes_image:
                kubectl('-n', 'robotos', 'rollout', 'status', 'deployment/robotos-hermes', '--timeout=180s')
                script = """import json, os, stat, urllib.request
from pathlib import Path
assert os.getuid()==10000
assert os.path.islink('/run/secrets/hermes-token')
assert not Path('/run/secrets/owner-token').exists()
mode=stat.S_IMODE(Path('/opt/data/hermes').stat().st_mode)
assert mode & 0o777 == 0o700, oct(mode)
key=Path('/run/secrets/hermes-token').read_text().strip()
r=urllib.request.Request('http://127.0.0.1:8642/v1/capabilities',headers={'Authorization':'Bearer '+key})
with urllib.request.urlopen(r,timeout=5) as response: assert json.load(response)['object']=='hermes.api_server.capabilities'
print('k3s Hermes passed: restricted nonroot pod, private PVC, scoped projected secrets and authenticated API.')
"""
                result = kubectl('-n', 'robotos', 'exec', 'deployment/robotos-hermes', '--', '/opt/hermes/.venv/bin/python', '-c', script)
                print(result.stdout.strip())
            pod = json.loads(kubectl('-n', 'robotos', 'get', 'pods', '-l', 'app=robotos-hub', '-o', 'json').stdout)['items'][0]
            assert pod['spec']['securityContext']['runAsNonRoot']
            projected = kubectl('-n', 'robotos', 'exec', pod['metadata']['name'], '--', 'python', '-c',
                                'import os; assert os.path.islink("/run/secrets/owner-password"); assert os.getuid()==10001; print("projected secrets and UID verified")')
            print(projected.stdout.strip())
            with socket.socket() as candidate:
                candidate.bind(('127.0.0.1', 0))
                local_port = candidate.getsockname()[1]

            def connect():
                nonlocal proxy
                proxy = subprocess.Popen(['kubectl', '--kubeconfig', str(kubeconfig), '-n', 'robotos', 'port-forward',
                                          '--address=127.0.0.1', 'service/robotos-hub', str(local_port) + ':8787'],
                                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                for _ in range(30):
                    try:
                        with socket.create_connection(('127.0.0.1', local_port), 1):
                            return
                    except OSError:
                        time.sleep(0.2)
                raise RuntimeError('Port forward did not start')

            def request(method, path, body=None):
                connection = http.client.HTTPConnection('127.0.0.1', local_port, timeout=5)
                connection.request(method, '/api/v1/' + path, None if body is None else json.dumps(body),
                                   {'Host': 'localhost:8787', 'Content-Type': 'application/json',
                                    'Authorization': 'Bearer ' + (private / 'owner-token').read_text()})
                result = connection.getresponse()
                data = json.loads(result.read())
                assert result.status == 200, (result.status, data)
                connection.close()
                return data

            connect()
            assert request('GET', 'ready')['status'] == 'ok'
            request('POST', 'notes', {'id': 'k3s-persist', 'kind': 'text', 'title': 'k3s smoke', 'text': 'retained on rollout'})
            proxy.terminate()
            proxy.wait(timeout=10)
            proxy = None
            kubectl('-n', 'robotos', 'rollout', 'restart', 'deployment/robotos-hub')
            kubectl('-n', 'robotos', 'rollout', 'status', 'deployment/robotos-hub', '--timeout=120s')
            connect()
            assert request('GET', 'notes/k3s-persist')['text'] == 'retained on rollout'
            proxy.terminate()
            proxy.wait(timeout=10)
            proxy = None
            for _ in range(120):
                result = kubectl('-n', 'kube-system', 'get', 'deployment/traefik', check=False)
                if result.returncode == 0:
                    break
                time.sleep(1)
            kubectl('-n', 'kube-system', 'rollout', 'status', 'deployment/traefik', '--timeout=180s')
            proxy = subprocess.Popen(['kubectl', '--kubeconfig', str(kubeconfig), '-n', 'kube-system', 'port-forward',
                                      '--address=127.0.0.1', 'service/traefik', str(local_port) + ':443'],
                                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            context = ssl.create_default_context(cafile=str(folder / 'tls.crt'))
            for _ in range(30):
                try:
                    raw = socket.create_connection(('127.0.0.1', local_port), 2)
                    with context.wrap_socket(raw, server_hostname='workspace.example.net') as tls:
                        tls.sendall(b'GET /api/v1/health HTTP/1.1\r\nHost: workspace.example.net\r\nConnection: close\r\n\r\n')
                        response = http.client.HTTPResponse(tls)
                        response.begin()
                        assert response.status == 200, response.status
                        assert json.loads(response.read())['status'] == 'ok'
                    break
                except (OSError, AssertionError):
                    time.sleep(1)
            else:
                raise RuntimeError('Traefik TLS ingress/NetworkPolicy did not permit the configured route')
            pod = json.loads(kubectl('-n', 'robotos', 'get', 'pods', '-l', 'app=robotos-hub', '-o', 'json').stdout)['items'][0]
            # Verify hub egress through the service-to-Traefik DNAT path too.
            certificate = (folder / 'tls.crt').read_text()
            script = "import ssl,socket,http.client,json; c=ssl.create_default_context(cadata=" + repr(certificate) + "); s=c.wrap_socket(socket.create_connection(('traefik.kube-system.svc',443),10),server_hostname='workspace.example.net'); s.sendall(b'GET /api/v1/health HTTP/1.1\\r\\nHost: workspace.example.net\\r\\nConnection: close\\r\\n\\r\\n'); r=http.client.HTTPResponse(s); r.begin(); assert r.status==200; assert json.loads(r.read())['status']=='ok'; s.close()"
            kubectl('-n', 'robotos', 'exec', pod['metadata']['name'], '--', 'python', '-c', script)
            print('k3s smoke passed: restricted pod, projected secrets, probes, PVC/Recreate persistence, verified TLS ingress and ingress/egress NetworkPolicy.')
        finally:
            if proxy is not None:
                proxy.terminate()
                proxy.wait(timeout=10)
            run('docker', 'rm', '-f', '-v', name, check=False)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
