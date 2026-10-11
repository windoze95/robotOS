import io
import json
import os
from unittest.mock import patch
import unittest

from server import robotos_mcp as mcp
from server.robotos_mcp.hub import Hub, from_env


class FakeHub(Hub):
    def __init__(self, devices=None):
        super().__init__("http://127.0.0.1:8787", "test-token-" * 5, mcp.TOOLS, receipt_wait_seconds=0)
        self.calls = []
        self.devices = devices if devices is not None else [{"id": "r1-a", "name": "Pocket"}]
        self.uncertain = False

    def call(self, method, path, body=None, timeout=None):
        self.calls.append((method, path, body))
        if path == "/mcp/devices":
            return 200, {"items": self.devices}
        if self.uncertain:
            return None, {"error": "Response interrupted", "status": "unknown"}
        if method == "POST":
            return 202, {"id": body.get("id", "run-one"), "status": "queued"}
        return 200, {"id": "run-one", "status": "completed", "output": "Saved result"}


class ReceiptHub(Hub):
    def __init__(self, receipts, wait=1):
        self.tick = 0.0
        super().__init__("http://127.0.0.1:8787", "test-token-" * 5, mcp.TOOLS,
                         receipt_wait_seconds=wait, receipt_poll_seconds=0.2,
                         clock=lambda: self.tick, sleeper=self.advance)
        self.calls, self.receipts = [], list(receipts)

    def advance(self, duration):
        self.tick += duration

    def call(self, method, path, body=None, timeout=None):
        self.calls.append((method, path, body, timeout))
        if method == "POST":
            return 202, {"id": body["id"], "status": "queued", "request_id": "not-authoritative"}
        if self.receipts:
            value = self.receipts.pop(0)
            if isinstance(value, tuple):
                return value
            return 200, dict({"id": "action-one"}, **value)
        return 200, {"id": "action-one", "status": "queued"}


class HubMcpTest(unittest.TestCase):
    def test_multiple_devices_never_select_an_arbitrary_sim(self):
        hub = FakeHub([{"id": "a"}, {"id": "b"}])
        _, failed, _ = hub.run_tool("send_sms", {"to": "Sam", "body": "Hello"})
        self.assertTrue(failed)
        self.assertEqual([("GET", "/mcp/devices", None)], hub.calls)

    def test_exact_target_and_request_identity_survive_queued_sms(self):
        hub = FakeHub()
        args = {"device_id": "r1-b", "to": "Sam", "body": "Hello", "request_id": "stable-command-1234"}
        _, failed, result = hub.run_tool("send_sms", args)
        self.assertFalse(failed)
        self.assertEqual("queued", result["status"])
        request = hub.calls[0][2]
        self.assertEqual("r1-b", request["device_id"])
        self.assertEqual(args["request_id"], request["id"])
        self.assertEqual(args["request_id"], request["arguments"]["request_id"])
        self.assertNotIn("device_id", request["arguments"])

    def test_unknown_submission_retains_recoverable_identity(self):
        hub = FakeHub()
        hub.uncertain = True
        message, failed, result = hub.run_tool("send_sms", {"device_id": "r1-a", "to": "Sam", "body": "Hello"})
        self.assertTrue(failed)
        self.assertEqual("unknown", result["status"])
        self.assertEqual(hub.calls[0][2]["id"], result["request_id"])
        self.assertIn("Preserve request_id", message)
        self.assertEqual(1, len(hub.calls))

    def test_mcp_never_advertises_owner_management_authority(self):
        hub = FakeHub()
        names = {t["name"] for t in hub.tools}
        self.assertTrue({"list_devices", "list_notes", "create_prompt", "run_prompt"} <= names)
        self.assertTrue(names.isdisjoint({"factory_reset", "lock", "revoke", "enroll", "clear_workspace"}))
        _, failed, _ = hub.run_tool("factory_reset", {"device_id": "r1-a"})
        self.assertTrue(failed)
        self.assertEqual([], hub.calls)
        self.assertTrue(all("device_id" not in t["inputSchema"]["properties"] for t in mcp.TOOLS))

    def test_rpc_advertises_hub_tools_without_network_lookup(self):
        hub = FakeHub()
        out = io.StringIO()
        mcp.serve(hub, io.StringIO(json.dumps({"jsonrpc": "2.0", "id": 1, "method": "tools/list"}) + "\n"), out)
        tools = json.loads(out.getvalue())["result"]["tools"]
        self.assertIn("list_prompts", [t["name"] for t in tools])
        self.assertEqual([], hub.calls)

    def test_run_is_bound_to_revision_and_never_implicitly_run_again(self):
        hub = FakeHub()
        hub.run_tool("run_prompt", {"id": "prompt-1", "revision": 7, "request_id": "request-1234567890"})
        self.assertEqual(("POST", "/prompts/prompt-1/run", {
            "revision": 7, "request_id": "request-1234567890", "run_again": False}), hub.calls[0])

    def test_wait_reads_receipt_until_applied_with_exactly_one_post(self):
        hub = ReceiptHub([{"status": "received"}, {"status": "applied", "result": {"http_status": 200, "body": {"spoken": True}}}])
        _, failed, result = hub.run_tool("speak", {"device_id": "r1-a", "text": "Hello", "request_id": "action-one"})
        self.assertFalse(failed)
        self.assertEqual("applied", result["status"])
        self.assertEqual("action-one", result["request_id"])
        self.assertEqual({"spoken": True}, result["result"]["body"])
        self.assertEqual(["POST", "GET", "GET"], [call[0] for call in hub.calls])
        self.assertEqual(["/device-actions/action-one"] * 2, [call[1] for call in hub.calls[1:]])
        self.assertTrue(all(0 < call[3] <= 1 for call in hub.calls[1:]))

    def test_receipt_wait_timeout_retains_last_admitted_status_and_identity(self):
        hub = ReceiptHub([], wait=0.5)
        _, failed, result = hub.run_tool("speak", {"device_id": "r1-a", "text": "Hello", "request_id": "action-one"})
        self.assertFalse(failed)
        self.assertEqual("queued", result["status"])
        self.assertEqual("action-one", result["request_id"])
        self.assertEqual(1, sum(call[0] == "POST" for call in hub.calls))
        self.assertEqual(0.5, hub.tick)

    def test_receipt_read_failure_does_not_reclassify_successful_admission(self):
        for read in [(None, {"status": "unknown"}), (503, {"error": "Unavailable"})]:
            hub = ReceiptHub([{"status": "received"}, read])
            _, failed, result = hub.run_tool("speak", {"device_id": "r1-a", "text": "Hello", "request_id": "action-one"})
            self.assertFalse(failed)
            self.assertEqual("received", result["status"])
            self.assertEqual("action-one", result["request_id"])
            self.assertIn("receipt_check_error", result)
            self.assertEqual(1, sum(call[0] == "POST" for call in hub.calls))

    def test_receipt_wait_stops_on_every_terminal_outcome_without_resubmission(self):
        for status in ["unknown", "failed", "expired", "cancelled", "applied"]:
            hub = ReceiptHub([{"status": status}, {"status": "applied"}])
            _, failed, result = hub.run_tool("speak", {"device_id": "r1-a", "text": "Hello", "request_id": "action-one"})
            self.assertEqual(status != "applied", failed)
            self.assertEqual(status, result["status"])
            self.assertEqual(["POST", "GET"], [call[0] for call in hub.calls])
            self.assertEqual(0, hub.tick)

    def test_wrong_receipt_identity_does_not_replace_original_command(self):
        hub = ReceiptHub([(200, {"id": "another-action", "status": "applied"})])
        _, failed, result = hub.run_tool("speak", {"device_id": "r1-a", "text": "Hello", "request_id": "action-one"})
        self.assertFalse(failed)
        self.assertEqual("queued", result["status"])
        self.assertEqual("action-one", result["id"])
        self.assertEqual("action-one", result["request_id"])
        self.assertIn("receipt_check_error", result)

    def test_receipt_wait_environment_is_bounded_and_defaults_to_five_seconds(self):
        env = {"ROBOTOS_HUB_URL": "http://127.0.0.1:8787", "ROBOTOS_HUB_MCP_TOKEN": "x" * 40}
        with patch.dict(os.environ, env, clear=True):
            self.assertEqual(5, from_env(mcp.TOOLS).receipt_wait_seconds)
            os.environ["ROBOTOS_HUB_RECEIPT_WAIT_SECONDS"] = "0"
            self.assertEqual(0, from_env(mcp.TOOLS).receipt_wait_seconds)
            for invalid in ["-1", "10.1", "nan", "inf", "invalid"]:
                os.environ["ROBOTOS_HUB_RECEIPT_WAIT_SECONDS"] = invalid
                with self.assertRaises(ValueError):
                    from_env(mcp.TOOLS)

    def test_unsafe_destinations_and_token_injection_rejected(self):
        for url in ("http://example.org", "https://user:pass@example.org", "https://example.org/?token=secret"):
            with self.assertRaises(ValueError):
                Hub(url, "x" * 40, mcp.TOOLS)
        with self.assertRaises(ValueError):
            Hub("https://hub.example", "x" * 40 + "\r\nInjected: yes", mcp.TOOLS)


if __name__ == "__main__":
    unittest.main()
