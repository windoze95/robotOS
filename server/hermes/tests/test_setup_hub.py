import json
import contextlib
import io
import subprocess
import sys
import os
from pathlib import Path
import plistlib
import tempfile
import unittest
from unittest.mock import patch, Mock

from server.hermes import setup_hub


class HubInstallerTest(unittest.TestCase):
    def test_reinstall_preserves_credentials_and_workspace(self):
        state = Path("/private/robotOS hub")
        first = setup_hub.configuration({}, state, "https://host.tailnet.ts.net:8443", {"API_SERVER_KEY": "existing-host-key"})
        second = setup_hub.configuration(first, state, None, {"API_SERVER_KEY": "different-value"})
        self.assertEqual(first, second)
        self.assertEqual(str(state / "data"), second["data_dir"])
        self.assertEqual("existing-host-key", second["hermes_token"])
        self.assertGreaterEqual(len(second["owner_password"]), 30)
        self.assertNotEqual(second["mcp_token"], second["owner_token"])

    def test_service_files_reference_protected_config_without_secrets(self):
        state = Path("/tmp/robotOS private")
        agent = setup_hub.launch_agent("/python", state / "service", state / "hub.json", state)
        encoded = plistlib.dumps(agent)
        self.assertEqual(agent, plistlib.loads(encoded))
        self.assertIn(str(state / "hub.json"), agent["ProgramArguments"])
        self.assertEqual(0o077, agent["Umask"])
        unit = setup_hub.systemd_unit("/python", state / "service", state / "hub.json")
        self.assertIn('"/tmp/robotOS private/hub.json"', unit)
        self.assertIn("UMask=0077", unit)
        self.assertIn("NoNewPrivileges=true", unit)

    def test_mcp_has_only_scoped_token_reference(self):
        entry = setup_hub.mcp_entry("/python", Path("/private/service"), 8787)
        self.assertEqual("${ROBOTOS_HUB_MCP_TOKEN}", entry["env"]["ROBOTOS_HUB_MCP_TOKEN"])
        self.assertNotIn("owner", json.dumps(entry))
        self.assertNotIn("ROBOTOS_R1_TOKEN", entry["env"])

    def test_private_write_repairs_loose_permissions_and_replaces_atomically(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "hub.json"
            path.write_text("old")
            os.chmod(path, 0o644)
            setup_hub.write_private(path, "new")
            self.assertEqual("new", path.read_text())
            self.assertEqual(0o600, path.stat().st_mode & 0o777)
            self.assertEqual([path], list(Path(directory).iterdir()))

    def test_no_start_installs_importable_packages_in_isolated_home_and_preserves_state(self):
        with tempfile.TemporaryDirectory() as temporary:
            home = Path(temporary)
            state = home / 'private-state'
            source = Path(__file__).resolve().parents[2]
            with patch.object(Path, 'home', return_value=home), patch.object(sys, 'path', list(sys.path)), \
                 patch.object(setup_hub.sys, 'platform', 'darwin'), patch.object(setup_hub.subprocess, 'run') as external, \
                 patch.dict(os.environ, {'HERMES_HOME': str(home / '.hermes')}), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(0, setup_hub.main(['--source', str(source), '--state', str(state), '--no-start']))
                external.assert_not_called()
                config_path = state / 'hub.json'
                first = json.loads(config_path.read_text())
                self.assertEqual(0o600, config_path.stat().st_mode & 0o777)
                self.assertEqual(0o700, Path(first['data_dir']).stat().st_mode & 0o777)
                sentinel = Path(first['data_dir']) / 'preserved'
                sentinel.write_text('existing workspace')
                self.assertEqual(0, setup_hub.main(['--source', str(source), '--state', str(state), '--no-start']))
                self.assertEqual(first, json.loads(config_path.read_text()))
                self.assertEqual('existing workspace', sentinel.read_text())
                external.assert_not_called()
                self.assertTrue((home / 'Library/LaunchAgents/ai.robotos.hub.plist').is_file())
            env = dict(os.environ, PYTHONPATH=str(state / 'service'))
            checked = subprocess.run([sys.executable, '-m', 'robotos_hub', '--config', str(config_path), '--check-config'],
                                     cwd=state / 'service', env=env, capture_output=True, text=True, timeout=15)
            self.assertEqual(0, checked.returncode, checked.stderr)
            self.assertIn('configuration is valid', checked.stdout)
            imported = subprocess.run([sys.executable, '-c', 'import robotos_hub.http, robotos_hub.upstream, robotos_mcp.hub'],
                                      cwd=state / 'service', env=env, capture_output=True, text=True, timeout=15)
            self.assertEqual(0, imported.returncode, imported.stderr)

    def test_invalid_origin_fails_before_service_changes(self):
        for origin in ("http://example.com", "https://user:password@example.com", "https://example.com/path"):
            with self.assertRaises(SystemExit):
                setup_hub.main(["--public-origin", origin])

    def test_split_host_configuration_preserves_device_identity_and_state(self):
        with tempfile.TemporaryDirectory() as temporary:
            home = Path(temporary)
            state, key = home / 'state', home / 'hermes-key'
            key.write_text('remote-api-key\n')
            source = Path(__file__).resolve().parents[2]
            with patch.object(Path, 'home', return_value=home), patch.object(sys, 'path', list(sys.path)), \
                 patch.object(setup_hub.sys, 'platform', 'linux'), patch.dict(os.environ, {'HERMES_HOME': str(home / '.hermes')}), \
                 patch.object(setup_hub.subprocess, 'run') as external, contextlib.redirect_stdout(io.StringIO()) as output:
                args = ['--source', str(source), '--state', str(state), '--no-start']
                setup_hub.main(args)
                before = json.loads((state / 'hub.json').read_text())
                setup_hub.main(args + ['--hermes-url', 'https://hermes.example.com', '--hermes-token-file', str(key)])
                after = json.loads((state / 'hub.json').read_text())
                self.assertEqual('https://hermes.example.com', after['hermes_url'])
                self.assertEqual('remote-api-key', after['hermes_token'])
                for field in ('owner_token', 'owner_password', 'mcp_token', 'data_dir'):
                    self.assertEqual(before[field], after[field])
                self.assertNotIn('remote-api-key', output.getvalue())
                external.assert_not_called()

    def test_mcp_only_installs_adapter_and_skill_without_hub_or_service(self):
        with tempfile.TemporaryDirectory() as temporary:
            home = Path(temporary)
            state, key = home / 'adapter', home / 'mcp-key'
            source = Path(__file__).resolve().parents[2]
            token = 'scoped-secret-' * 4
            key.write_text(token + '\n')
            with patch.object(Path, 'home', return_value=home), patch.object(sys, 'path', list(sys.path)), \
                 patch.dict(os.environ, {'HERMES_HOME': str(home / '.hermes')}), \
                 patch.object(setup_hub.shutil, 'which', return_value='/bin/hermes'), \
                 patch.object(setup_hub.subprocess, 'run') as external, contextlib.redirect_stdout(io.StringIO()) as output:
                args = ['--source', str(source), '--state', str(state), '--mcp-only', '--no-start',
                        '--hub-url', 'https://hub.example.com', '--mcp-token-file', str(key)]
                self.assertEqual(0, setup_hub.main(args))
                self.assertTrue((state / 'service/robotos_mcp/__main__.py').is_file())
                self.assertFalse((state / 'service/robotos_hub').exists())
                self.assertFalse((state / 'hub.json').exists())
                self.assertFalse((state / 'owner-password.txt').exists())
                self.assertFalse((home / 'Library/LaunchAgents').exists())
                self.assertFalse((home / '.config/systemd').exists())
                self.assertTrue((home / '.hermes/skills/robotos/SKILL.md').is_file())
                saved_env = home / '.hermes/.env'
                self.assertEqual(0o600, saved_env.stat().st_mode & 0o777)
                self.assertEqual(token, setup_hub.read_env(saved_env)['ROBOTOS_HUB_MCP_TOKEN'])
                calls = [call.args[0] for call in external.call_args_list]
                self.assertTrue(calls)
                self.assertTrue(all(command[1:3] == ['config', 'set'] for command in calls))
                self.assertNotIn(token, repr(calls) + output.getvalue())
                # Reinstall may reuse the existing scoped credential, without ever opening hub config.
                (state / 'hub.json').write_text('not a hub config and must not be read')
                self.assertEqual(0, setup_hub.main(args[:-2]))

    def test_remote_plaintext_and_conflicting_mode_fail_before_install(self):
        invalid = [
            ['--hermes-url', 'http://192.168.1.2:8642'],
            ['--mcp-only', '--hub-url', 'http://hub:8787'],
            ['--mcp-only'],
            ['--mcp-only', '--hub-url', 'https://hub.example.com', '--hermes-url', 'https://hermes.example.com'],
            ['--hub-url', 'https://hub.example.com'],
        ]
        with patch.object(setup_hub, 'install_code') as install:
            for args in invalid:
                with self.assertRaises(SystemExit):
                    setup_hub.main(args)
            install.assert_not_called()

    def test_launchd_retries_only_transient_bootstrap_registration_race(self):
        with patch.object(setup_hub.subprocess, 'run', side_effect=[Mock(returncode=0), Mock(returncode=5), Mock(returncode=0)]) as run, \
             patch.object(setup_hub.time, 'sleep') as sleep:
            setup_hub.start_launch_agent('/private/agent.plist')
            self.assertEqual(run.call_args_list[1].args, run.call_args_list[2].args)
            sleep.assert_called_once_with(0.5)
        with patch.object(setup_hub.subprocess, 'run', side_effect=[Mock(returncode=0), Mock(returncode=78)]) as run, \
             patch.object(setup_hub.time, 'sleep') as sleep:
            with self.assertRaises(RuntimeError):
                setup_hub.start_launch_agent('/private/agent.plist')
            self.assertEqual(2, run.call_count)
            sleep.assert_not_called()

    def test_registering_a_foreign_token_cannot_break_local_hub_identity(self):
        with tempfile.TemporaryDirectory() as temporary:
            home = Path(temporary)
            state, key = home / 'hub', home / 'wrong-token'
            source = Path(__file__).resolve().parents[2]
            key.write_text('another-hub-token-' * 3)
            with patch.object(Path, 'home', return_value=home), patch.object(sys, 'path', list(sys.path)), \
                 patch.dict(os.environ, {'HERMES_HOME': str(home / '.hermes')}), \
                 patch.object(setup_hub.sys, 'platform', 'linux'), \
                 patch.object(setup_hub.shutil, 'which', return_value='/bin/hermes'), \
                 patch.object(setup_hub.subprocess, 'run') as external, contextlib.redirect_stdout(io.StringIO()):
                args = ['--source', str(source), '--state', str(state), '--no-start']
                setup_hub.main(args)
                before = (state / 'hub.json').read_bytes()
                with self.assertRaises(SystemExit):
                    setup_hub.main(args + ['--register-hermes', '--mcp-token-file', str(key)])
                self.assertEqual(before, (state / 'hub.json').read_bytes())
                self.assertFalse((home / '.hermes/.env').exists())
                external.assert_not_called()


if __name__ == "__main__":
    unittest.main()
