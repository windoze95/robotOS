"""Validate the actual community quickstart with Docker Compose's parser."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class ComposeQuickstartTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not shutil.which('docker') or subprocess.run(
                ['docker', 'compose', 'version'], capture_output=True).returncode:
            if os.environ.get('CI'):
                raise RuntimeError('Docker Compose is required to validate the primary quickstart in CI')
            raise unittest.SkipTest('Docker Compose is not installed')

    def config(self, files, extra=''):
        with tempfile.TemporaryDirectory() as temporary:
            env = Path(temporary) / 'quickstart.env'
            env.write_text('ROBOTOS_PUBLIC_ORIGIN=https://workspace.example.net\n'
                           'HERMES_API_URL=https://hermes.example.net\n'
                           'ROBOTOS_HOSTNAME=workspace.example.net\n'
                           'HERMES_HOSTNAME=hermes.example.net\n'
                           'ROBOTOS_SECRET_DIR=' + temporary + '\n' + extra)
            # Do not load a developer's .env, Compose overrides or credentials.
            clean_env = {key: value for key, value in os.environ.items()
                         if key in ('PATH', 'HOME', 'DOCKER_CONFIG', 'DOCKER_HOST', 'DOCKER_CONTEXT')}
            command = ['docker', 'compose', '--env-file', str(env)]
            for name in files:
                command += ['-f', str(ROOT / 'deploy/hub' / name)]
            result = subprocess.run(command + ['config', '--format', 'json'], env=clean_env,
                                    capture_output=True, text=True, timeout=30)
            self.assertEqual(0, result.returncode, result.stderr)
            return json.loads(result.stdout)

    def test_full_quickstart_uses_published_images_and_persistent_private_service_boundaries(self):
        config = self.config(['compose.yaml', 'compose.hermes.yaml'])
        self.assertEqual({'hub', 'hermes'}, set(config['services']))
        for name in ('hub', 'hermes'):
            service = config['services'][name]
            self.assertEqual('ghcr.io/windoze95/robotos-' + name + ':main', service['image'])
            self.assertTrue(all(port['host_ip'] == '127.0.0.1' for port in service['ports']))
            self.assertIn('ALL', service['cap_drop'])
            self.assertIn('no-new-privileges:true', service['security_opt'])
            self.assertTrue(any(mount['type'] == 'volume' for mount in service['volumes']))
        secrets = {secret['source'] for secret in config['services']['hermes']['secrets']}
        self.assertEqual({'mcp-token', 'hermes-token'}, secrets)
        self.assertNotIn('owner-password', secrets)

    def test_hub_only_and_immutable_image_overrides_still_work(self):
        tag = 'sha-' + 'a' * 40
        config = self.config(['compose.yaml'], 'ROBOTOS_IMAGE=ghcr.io/windoze95/robotos-hub:' + tag + '\n')
        self.assertEqual({'hub'}, set(config['services']))
        self.assertEqual('ghcr.io/windoze95/robotos-hub:' + tag, config['services']['hub']['image'])

    def test_optional_tls_overlay_routes_both_public_names_without_exposing_internal_http(self):
        config = self.config(['compose.yaml', 'compose.hermes.yaml', 'compose.tls.yaml', 'compose.tls-hermes.yaml'])
        self.assertEqual({'hub', 'hermes', 'tls'}, set(config['services']))
        tls = config['services']['tls']
        self.assertEqual({'80', '443'}, {port['published'] for port in tls['ports']})
        self.assertEqual('workspace.example.net', tls['environment']['ROBOTOS_HOSTNAME'])
        self.assertEqual('hermes.example.net', tls['environment']['HERMES_HOSTNAME'])
        self.assertTrue(any(mount['source'].endswith('/Caddyfile.hermes') for mount in tls['volumes']))


if __name__ == '__main__':
    unittest.main()
