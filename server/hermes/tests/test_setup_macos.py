import json
import unittest

from server.hermes import setup_macos as setup


class SetupMacosTest(unittest.TestCase):
    def test_env_keeps_existing_secrets_and_other_lines_and_adds_missing_ones(self):
        before = "TZ=America/Chicago\n# comment\nAPI_SERVER_KEY=keep-me\nAPI_SERVER_HOST=0.0.0.0\n"
        after = setup.merge_env(before, {"API_SERVER_ENABLED": "true", "API_SERVER_HOST": "127.0.0.1"},
                                ["API_SERVER_KEY", "ROBOTOS_R1_TOKEN"])
        lines = after.splitlines()
        self.assertEqual(["TZ=America/Chicago", "# comment", "API_SERVER_KEY=keep-me", "API_SERVER_HOST=127.0.0.1"], lines[:4])
        self.assertIn("API_SERVER_ENABLED=true", lines)
        token = [l for l in lines if l.startswith("ROBOTOS_R1_TOKEN=")]
        self.assertEqual(1, len(token))
        self.assertGreaterEqual(len(token[0].split("=", 1)[1]), 32)
        # Re-running changes nothing.
        self.assertEqual(after, setup.merge_env(after, {"API_SERVER_ENABLED": "true", "API_SERVER_HOST": "127.0.0.1"},
                                                ["API_SERVER_KEY", "ROBOTOS_R1_TOKEN"]))

    def test_tailscale_agent_adds_the_proxy_once_and_keeps_an_existing_agent(self):
        fresh = setup.tailscale_agent("/opt/homebrew/bin/tailscaled")
        self.assertIn("--tun=userspace-networking", fresh["ProgramArguments"])
        self.assertEqual(["--outbound-http-proxy-listen=127.0.0.1:1056"],
                         [a for a in fresh["ProgramArguments"] if a.startswith("--outbound")])
        existing = {"Label": setup.TAILSCALE_LABEL, "ProgramArguments": ["/x/tailscaled", "--port=0"], "KeepAlive": True}
        upgraded = setup.tailscale_agent("/opt/homebrew/bin/tailscaled", existing)
        self.assertEqual(["/x/tailscaled", "--port=0", "--outbound-http-proxy-listen=127.0.0.1:1056"], upgraded["ProgramArguments"])
        self.assertEqual(upgraded, setup.tailscale_agent("/opt/homebrew/bin/tailscaled", upgraded))
        self.assertEqual(["/x/tailscaled", "--port=0"], existing["ProgramArguments"])  # the input is not mutated

    def test_r1_settings_and_mcp_entry_keep_secrets_out_of_config_yaml(self):
        settings = setup.r1_settings("host.tailnet.ts.net", "k" * 40, "t" * 40, owner="+14055550123")
        self.assertEqual("https://host.tailnet.ts.net/v1", settings["providers"]["hermes"]["baseUrl"])
        self.assertEqual({"deviceApi": True, "token": "t" * 40, "port": 8765, "passthrough": True, "owner": "+14055550123"}, settings["hermes"])
        self.assertNotIn("passthrough", setup.r1_settings("h.ts.net", "k", "t")["hermes"])
        entry = setup.mcp_entry("/opt/homebrew/bin/python3", "100.101.102.103")
        self.assertEqual("${ROBOTOS_R1_TOKEN}", entry["env"]["ROBOTOS_R1_TOKEN"])
        self.assertEqual("http://100.101.102.103:8765", entry["env"]["ROBOTOS_R1_URL"])
        self.assertEqual(["-m", "robotos_mcp"], entry["args"])
        # `hermes config set` parses lists, maps and numbers as YAML (JSON is YAML) but stores strings as given.
        self.assertEqual("/opt/homebrew/bin/python3", setup.config_value(entry["command"]))
        self.assertEqual('["-m", "robotos_mcp"]', setup.config_value(entry["args"]))
        self.assertEqual("90", setup.config_value(entry["timeout"]))
        self.assertEqual(entry["env"], json.loads(setup.config_value(entry["env"])))

    def test_bad_arguments_stop_before_changing_anything(self):
        for argv in (["--r1", "100.1.1.1; rm -rf /"], ["--r1", "100.1.1.1", "--owner", "405-555-0123"]):
            with self.assertRaises(SystemExit):
                setup.main(argv)


if __name__ == "__main__":
    unittest.main()
