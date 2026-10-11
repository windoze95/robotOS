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
from unittest.mock import patch

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


if __name__ == "__main__":
    unittest.main()
