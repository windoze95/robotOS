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
    @contextlib.contextmanager
    def native_fixture(self, platform='darwin'):
        with tempfile.TemporaryDirectory() as temporary, contextlib.ExitStack() as stack:
            home = Path(temporary)
            hermes_home, state = home / '.hermes', home / 'hub'
            hermes_home.mkdir()
            (hermes_home / 'config.yaml').write_text('model: existing-model\nprovider: existing-provider\nmcp_servers:\n  unrelated: {}\n')
            stack.enter_context(patch.object(Path, 'home', return_value=home))
            stack.enter_context(patch.object(sys, 'path', list(sys.path)))
            stack.enter_context(patch.object(setup_hub.sys, 'platform', platform))
            stack.enter_context(patch.object(setup_hub.shutil, 'which', side_effect=lambda name: '/bin/' + name))
            stack.enter_context(patch.dict(os.environ, {'HERMES_HOME': str(hermes_home), 'XDG_CONFIG_HOME': str(home / '.config')}))
            external = stack.enter_context(patch.object(setup_hub.subprocess, 'run', return_value=Mock(returncode=0)))
            hub_ready = stack.enter_context(patch.object(setup_hub, 'wait_for_hub'))
            hermes_ready = stack.enter_context(patch.object(setup_hub, 'wait_for_hermes'))
            output = stack.enter_context(contextlib.redirect_stdout(io.StringIO()))
            args = ['--source', str(Path(__file__).resolve().parents[2]), '--state', str(state)]
            yield home, hermes_home, state, args, external, hub_ready, hermes_ready, output

    def test_complete_native_fresh_install_and_reinstall_preserve_identity(self):
        with self.native_fixture() as (_, hermes_home, state, args, external, hub_ready, hermes_ready, output):
            env = hermes_home / '.env'
            env.write_text('OTHER_PROVIDER_KEY=leave-alone\nexport API_SERVER_KEY="existing-api-key"\nAPI_SERVER_PORT=9123\nAPI_SERVER_HOST=0.0.0.0\n')
            existing_yaml = (hermes_home / 'config.yaml').read_text()
            self.assertEqual(0, setup_hub.main(args + ['--public-origin', 'https://hub.example.com']))
            first = json.loads((state / 'hub.json').read_text())
            self.assertEqual('http://127.0.0.1:9123', first['hermes_url'])
            self.assertEqual('existing-api-key', first['hermes_token'])
            self.assertIn('OTHER_PROVIDER_KEY=leave-alone', env.read_text())
            self.assertIn('API_SERVER_HOST="127.0.0.1"', env.read_text())
            self.assertEqual(existing_yaml, (hermes_home / 'config.yaml').read_text())
            commands = [call.args[0] for call in external.call_args_list]
            self.assertIn(['/bin/hermes', 'gateway', 'install', '--if-missing', '--start-now', '--start-on-login'], commands)
            self.assertIn(['/bin/hermes', 'gateway', 'restart'], commands)
            config_commands = [command for command in commands if command[1:3] == ['config', 'set']]
            self.assertTrue(config_commands)
            self.assertTrue(all(command[3].startswith('mcp_servers.robotos.') for command in config_commands))
            self.assertNotIn('existing-api-key', repr(commands) + output.getvalue())
            hub_ready.assert_called_once_with(first)
            hermes_ready.assert_called_once_with(first)
            self.assertEqual(0, setup_hub.main(args + ['--register-hermes']))
            self.assertEqual(first, json.loads((state / 'hub.json').read_text()))
            self.assertIn('enrollment are not performed or verified', output.getvalue())

    def test_complete_no_start_prepares_configuration_without_lifecycle_or_probes(self):
        with self.native_fixture('linux') as (_, hermes_home, state, args, external, hub_ready, hermes_ready, output):
            self.assertEqual(0, setup_hub.main(args + ['--public-origin', 'https://hub.example.com', '--no-start']))
            config = json.loads((state / 'hub.json').read_text())
            self.assertGreaterEqual(len(config['hermes_token']), 32)
            self.assertEqual(config['hermes_token'], setup_hub.hermes_environment(hermes_home / '.env')['API_SERVER_KEY'])
            self.assertTrue(all(call.args[0][1:3] == ['config', 'set'] for call in external.call_args_list))
            hub_ready.assert_not_called()
            hermes_ready.assert_not_called()
            self.assertIn('readiness was not checked', output.getvalue())

    def test_default_requires_https_and_configured_hermes_before_mutation(self):
        with self.native_fixture() as (_, hermes_home, state, args, external, _, _, _):
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                setup_hub.main(args)
            self.assertFalse(state.exists())
            self.assertFalse((hermes_home / '.env').exists())
            (hermes_home / 'config.yaml').unlink()
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                setup_hub.main(args + ['--public-origin', 'https://hub.example.com'])
            self.assertFalse(state.exists())
            external.assert_not_called()

    def test_split_existing_endpoint_requires_explicit_hub_only(self):
        with self.native_fixture() as (_, hermes_home, state, args, external, _, _, _):
            setup_hub.main(args + ['--hub-only', '--no-start', '--hermes-url', 'https://hermes.example.com',
                                   '--public-origin', 'https://hub.example.com'])
            before = (state / 'hub.json').read_bytes()
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                setup_hub.main(args + ['--no-start'])
            self.assertEqual(before, (state / 'hub.json').read_bytes())
            self.assertFalse((hermes_home / '.env').exists())
            external.assert_not_called()

    def test_failed_authenticated_upstream_does_not_report_success(self):
        with self.native_fixture() as (_, _, _, args, _, _, hermes_ready, output):
            hermes_ready.side_effect = RuntimeError('Hermes rejected the configured API credential')
            with self.assertRaisesRegex(RuntimeError, 'rejected'):
                setup_hub.main(args + ['--public-origin', 'https://hub.example.com'])
            self.assertNotIn('are ready', output.getvalue())

    def test_local_api_preserves_port_and_key_repairs_stale_hub_token(self):
        existing = {'hermes_url': 'http://localhost:9001/v1', 'hermes_token': 'stale-key'}
        config = dict(existing)
        settings = setup_hub.local_hermes(config, existing, {'API_SERVER_KEY': 'current-key', 'API_SERVER_PORT': '8001'})
        self.assertEqual('http://127.0.0.1:9001', config['hermes_url'])
        self.assertEqual('current-key', config['hermes_token'])
        self.assertEqual('9001', settings['API_SERVER_PORT'])
        with self.assertRaisesRegex(ValueError, 'never rotated'):
            setup_hub.local_hermes(dict(existing), existing, {'API_SERVER_KEY': 'current-key'}, explicit_token='different-key')

    def test_dotenv_tokens_retain_hashes_quotes_and_backslashes(self):
        examples = {
            'abc#def': 'abc#def',
            'abc#def # note': 'abc#def',
            r'"abc\\def\"ghi#j" # note': 'abc\\def"ghi#j',
            r"'abc\\def\'ghi#j'": "abc\\def'ghi#j",
            '#literal': '#literal',
        }
        for raw, expected in examples.items():
            self.assertEqual(expected, setup_hub.environment_value(raw), raw)
            written = setup_hub.managed_environment('OTHER=keep\n', {'API_SERVER_KEY': expected})
            self.assertEqual(expected, setup_hub.environment_value(written.splitlines()[-1].partition('=')[2]))
        with self.assertRaisesRegex(ValueError, 'literal'):
            setup_hub.environment_value('${SECRET_REFERENCE}')
        with self.assertRaisesRegex(ValueError, 'multiline'):
            setup_hub.environment_value('"unclosed')
        with self.assertRaisesRegex(ValueError, 'ambiguous'):
            setup_hub.environment_value(' #literal')
        with self.native_fixture() as (_, hermes_home, state, args, _, _, _, _):
            (hermes_home / '.env').write_text('API_SERVER_KEY=abc#def\n')
            setup_hub.main(args + ['--public-origin', 'https://hub.example.com', '--no-start'])
            self.assertEqual('abc#def', json.loads((state / 'hub.json').read_text())['hermes_token'])

    def test_service_session_and_port_errors_leave_installation_unmodified(self):
        with self.native_fixture() as (_, hermes_home, state, args, external, _, _, _):
            external.return_value.returncode = 1
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                setup_hub.main(args + ['--public-origin', 'https://hub.example.com'])
            self.assertFalse(state.exists())
            self.assertFalse((hermes_home / '.env').exists())
            external.reset_mock()
            (hermes_home / '.env').write_text('API_SERVER_PORT=8787\nAPI_SERVER_KEY=keep\n')
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                setup_hub.main(args + ['--public-origin', 'https://hub.example.com', '--no-start'])
            self.assertFalse(state.exists())
            self.assertEqual('API_SERVER_PORT=8787\nAPI_SERVER_KEY=keep\n', (hermes_home / '.env').read_text())
            external.assert_not_called()

    def test_secret_file_interpolation_is_rejected_before_dotenv_or_service_changes(self):
        with self.native_fixture() as (home, hermes_home, state, args, external, _, _, _):
            token = home / 'token'
            token.write_text('prefix-' * 5 + '${EXISTING_VARIABLE}')
            for flags in [
                ['--mcp-only', '--hub-url', 'https://hub.example.com', '--mcp-token-file', str(token)],
                ['--public-origin', 'https://hub.example.com', '--hermes-token-file', str(token)],
            ]:
                with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                    setup_hub.main(args + flags + ['--no-start'])
                self.assertFalse(state.exists())
                self.assertFalse((hermes_home / '.env').exists())
                external.assert_not_called()

    def test_authenticated_probe_rejects_auth_failure_and_missing_runs_without_retry(self):
        source = str(Path(__file__).resolve().parents[2])
        with patch.object(sys, 'path', [source] + sys.path):
            from robotos_hub.upstream import Client, UpstreamError
            with patch.object(Client, 'capabilities', side_effect=UpstreamError(401)), patch.object(setup_hub.time, 'sleep') as sleep:
                with self.assertRaisesRegex(RuntimeError, 'credential'):
                    setup_hub.wait_for_hermes({'hermes_url': 'http://127.0.0.1:8642', 'hermes_token': 'test'})
                sleep.assert_not_called()
            with patch.object(Client, 'capabilities', return_value={'runs': False}):
                with self.assertRaisesRegex(RuntimeError, 'Runs API'):
                    setup_hub.wait_for_hermes({'hermes_url': 'http://127.0.0.1:8642', 'hermes_token': 'test'})

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
                self.assertEqual(0, setup_hub.main(['--source', str(source), '--state', str(state), '--hub-only', '--no-start']))
                external.assert_not_called()
                config_path = state / 'hub.json'
                first = json.loads(config_path.read_text())
                self.assertEqual(0o600, config_path.stat().st_mode & 0o777)
                self.assertEqual(0o700, Path(first['data_dir']).stat().st_mode & 0o777)
                sentinel = Path(first['data_dir']) / 'preserved'
                sentinel.write_text('existing workspace')
                self.assertEqual(0, setup_hub.main(['--source', str(source), '--state', str(state), '--hub-only', '--no-start']))
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
                args = ['--source', str(source), '--state', str(state), '--hub-only', '--no-start']
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
                self.assertEqual(token, setup_hub.hermes_environment(saved_env)['ROBOTOS_HUB_MCP_TOKEN'])
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
            (home / '.hermes').mkdir()
            (home / '.hermes/config.yaml').write_text('model: existing-model\n')
            with patch.object(Path, 'home', return_value=home), patch.object(sys, 'path', list(sys.path)), \
                 patch.dict(os.environ, {'HERMES_HOME': str(home / '.hermes')}), \
                 patch.object(setup_hub.sys, 'platform', 'linux'), \
                 patch.object(setup_hub.shutil, 'which', return_value='/bin/hermes'), \
                 patch.object(setup_hub.subprocess, 'run') as external, contextlib.redirect_stdout(io.StringIO()):
                args = ['--source', str(source), '--state', str(state), '--no-start', '--public-origin', 'https://hub.example.com']
                setup_hub.main(args + ['--hub-only'])
                before = (state / 'hub.json').read_bytes()
                with self.assertRaises(SystemExit):
                    setup_hub.main(args + ['--register-hermes', '--mcp-token-file', str(key)])
                self.assertEqual(before, (state / 'hub.json').read_bytes())
                self.assertFalse((home / '.hermes/.env').exists())
                external.assert_not_called()


if __name__ == "__main__":
    unittest.main()
