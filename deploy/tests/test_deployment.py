"""Deployment boundary tests that need no Docker daemon or optional dependencies."""
import importlib.util
import json
import os
from pathlib import Path
import stat
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'server'))
from robotos_hub.config import Config, secret_file


class DeploymentConfigTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.folder = Path(self.temporary.name)
        self.env = patch.dict(os.environ, {}, clear=True)
        self.env.start()

    def tearDown(self):
        self.env.stop()
        self.temporary.cleanup()

    def config(self, **extra):
        return Config(data_dir=self.folder / 'data', owner_password='correct horse battery staple', **extra)

    def file(self, name, content, mode=0o440):
        target = self.folder / name
        if target.exists():
            target.chmod(0o600)
        target.write_text(content)
        target.chmod(mode)
        return target

    def test_default_is_loopback_and_remote_requires_explicit_opt_in(self):
        self.assertEqual('127.0.0.1', self.config().bind)
        with self.assertRaisesRegex(ValueError, 'allow_remote_bind'):
            self.config(bind='0.0.0.0', public_origin='https://workspace.example.net')
        with self.assertRaisesRegex(ValueError, 'HTTPS public origin'):
            self.config(bind='0.0.0.0', allow_remote_bind=True)
        with self.assertRaisesRegex(ValueError, 'HTTPS public origin'):
            self.config(bind='0.0.0.0', allow_remote_bind=True, public_origin='http://localhost:8787')
        config = self.config(bind='0.0.0.0', allow_remote_bind=True, public_origin='https://workspace.example.net:9443')
        self.assertEqual('https://workspace.example.net:9443', config.public_origin)

    def test_remote_hermes_and_speech_remain_authenticated_tls_endpoints(self):
        self.assertEqual('https://hermes.example.net', self.config(hermes_url='https://hermes.example.net').hermes_url)
        with self.assertRaisesRegex(ValueError, 'HTTPS'):
            self.config(hermes_url='http://hermes:8642')
        with self.assertRaisesRegex(ValueError, 'userinfo'):
            self.config(hermes_url='https://user:secret@hermes.example.net')

    def test_kubernetes_projected_secret_symlink_allowed_but_config_symlink_rejected(self):
        secret = self.file('actual-token', 'x' * 40 + '\n')
        projected = self.folder / 'projected-token'
        projected.symlink_to(secret)
        self.assertEqual('x' * 40, secret_file(projected))
        config = self.file('actual.json', json.dumps({'owner_password': 'long owner password', 'data_dir': str(self.folder / 'data')}), 0o600)
        link = self.folder / 'config.json'
        link.symlink_to(config)
        with self.assertRaisesRegex(ValueError, 'not a symlink'):
            Config.load(link)

    def test_file_secrets_override_config_but_ambiguous_environment_fails(self):
        password = self.file('password', 'container secret password\n')
        os.environ.update({'ROBOTOS_HUB_OWNER_PASSWORD_FILE': str(password), 'ROBOTOS_HUB_DATA_DIR': str(self.folder / 'data'),
                           'ROBOTOS_HUB_ALLOW_REMOTE_BIND': 'true', 'ROBOTOS_HUB_BIND': '0.0.0.0',
                           'ROBOTOS_HUB_PUBLIC_ORIGIN': 'https://workspace.example.net'})
        config = Config.load()
        self.assertEqual('container secret password', config.owner_password)
        self.assertTrue(config.allow_remote_bind)
        os.environ['ROBOTOS_HUB_OWNER_PASSWORD'] = 'ambiguous other password'
        with self.assertRaisesRegex(ValueError, 'not both'):
            Config.load()

    def test_file_does_not_accept_arbitrary_configuration_fields(self):
        os.environ['ROBOTOS_HUB_BIND_FILE'] = str(self.file('bind', '0.0.0.0'))
        with self.assertRaisesRegex(ValueError, 'secret fields'):
            Config.load()

    def test_boolean_is_not_truthiness(self):
        with self.assertRaisesRegex(ValueError, 'boolean'):
            self.config(allow_remote_bind='false')
        os.environ['ROBOTOS_HUB_ALLOW_REMOTE_BIND'] = 'yes'
        with self.assertRaisesRegex(ValueError, 'true or false'):
            Config.load()

    def test_secret_rejects_writable_or_special_or_multiline_files(self):
        for mode in (0o666, 0o660, 0o602):
            with self.assertRaisesRegex(ValueError, 'writable'):
                secret_file(self.file('writable', 'secret', mode))
        for body in ('a\nb', 'a\x00b', 'x' * 16385):
            with self.assertRaises(ValueError):
                secret_file(self.file('invalid', body))
        fifo = self.folder / 'fifo'
        os.mkfifo(fifo)
        with self.assertRaisesRegex(ValueError, 'regular'):
            secret_file(fifo)

    def test_data_remains_private_and_credentials_do_not_rotate(self):
        spec = importlib.util.spec_from_file_location('deployment_configure', ROOT / 'deploy/configure.py')
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        private = self.folder / 'private'
        module.main(['--directory', str(private)])
        old = {p.name: p.read_text() for p in private.iterdir()}
        module.main(['--directory', str(private)])
        self.assertEqual(old, {p.name: p.read_text() for p in private.iterdir()})
        self.assertEqual(0o700, stat.S_IMODE(private.stat().st_mode))
        self.assertNotEqual(old['owner-token'], old['mcp-token'])
        self.assertEqual('', secret_file(private / 'speech-token'))


if __name__ == '__main__':
    unittest.main()
