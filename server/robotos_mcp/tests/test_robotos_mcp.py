import io
import json
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import server.robotos_mcp as mcp

TOKEN = "t" * 40


class FakeR1(BaseHTTPRequestHandler):
    sent = []

    def log_message(self, *args):
        pass

    def reply(self, code, payload):
        body = json.dumps(payload).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def authorized(self):
        if self.headers.get("Authorization") != "Bearer " + TOKEN:
            self.reply(401, {"error": "Unauthorized"})
            return False
        return True

    def do_GET(self):
        if not self.authorized():
            return
        if self.path == "/v1/status":
            self.reply(200, {"battery_percent": 80, "can_text": True})
        elif self.path.startswith("/v1/texts"):
            self.reply(200, {"path": self.path})
        elif self.path.startswith("/v1/sms/"):
            self.reply(404, {"error": "No text with that id."})
        else:
            self.reply(404, {"error": "Not found"})

    def do_POST(self):
        if not self.authorized():
            return
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        FakeR1.sent.append(body)
        if body["to"] == "Nobody":
            self.reply(404, {"error": "No recipient named \"Nobody\" is saved on the R1. Nothing was sent; use a phone number."})
        else:
            self.reply(200, {"id": "00000000-0000-4000-8000-000000000001", "to": "+14055550123", "status": "sent", "parts": 1})


class RobotosMcpTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), FakeR1)
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()
        cls.r1 = mcp.R1("http://127.0.0.1:%d" % cls.server.server_address[1], TOKEN)

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()

    def setUp(self):
        FakeR1.sent.clear()

    def rpc(self, *messages, r1=None):
        out = io.StringIO()
        mcp.serve(r1 or self.r1, io.StringIO("".join(json.dumps(m) + "\n" for m in messages)), out)
        return [json.loads(line) for line in out.getvalue().splitlines()]

    def test_initialize_negotiates_and_notifications_get_no_reply(self):
        replies = self.rpc(
            {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocolVersion": "2025-06-18", "capabilities": {}}},
            {"jsonrpc": "2.0", "method": "notifications/initialized"},
            {"jsonrpc": "2.0", "id": 2, "method": "initialize", "params": {"protocolVersion": "1999-01-01"}},
            {"jsonrpc": "2.0", "id": 3, "method": "ping"},
        )
        self.assertEqual([1, 2, 3], [r["id"] for r in replies])
        self.assertEqual("2025-06-18", replies[0]["result"]["protocolVersion"])
        self.assertIn(replies[1]["result"]["protocolVersion"], mcp.PROTOCOLS)
        self.assertEqual({"tools": {"listChanged": False}}, replies[0]["result"]["capabilities"])
        self.assertEqual({}, replies[2]["result"])

    def test_tools_list_is_static_and_schemas_are_closed(self):
        tools = self.rpc({"jsonrpc": "2.0", "id": 1, "method": "tools/list"})[0]["result"]["tools"]
        self.assertEqual(["send_sms", "sms_status", "recent_texts", "list_recipients", "r1_status"], [t["name"] for t in tools])
        for tool in tools:
            self.assertEqual("object", tool["inputSchema"]["type"])
            self.assertFalse(tool["inputSchema"]["additionalProperties"])

    def test_send_sms_posts_once_and_returns_structured_status(self):
        reply = self.rpc({"jsonrpc": "2.0", "id": 7, "method": "tools/call",
                          "params": {"name": "send_sms", "arguments": {"to": "405-555-0123", "body": "On my way"}}})[0]
        self.assertFalse(reply["result"]["isError"])
        self.assertEqual("sent", reply["result"]["structuredContent"]["status"])
        self.assertEqual([{"to": "405-555-0123", "body": "On my way"}], FakeR1.sent)

    def test_errors_are_tool_errors_with_the_r1_explanation(self):
        reply = self.rpc({"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                          "params": {"name": "send_sms", "arguments": {"to": "Nobody", "body": "Hi"}}})[0]["result"]
        self.assertTrue(reply["isError"])
        self.assertIn("Nothing was sent", reply["content"][0]["text"])
        missing = self.rpc({"jsonrpc": "2.0", "id": 2, "method": "tools/call",
                            "params": {"name": "send_sms", "arguments": {"to": "Sam"}}})[0]["result"]
        self.assertTrue(missing["isError"])
        self.assertEqual(1, len(FakeR1.sent))

    def test_wrong_token_and_sleeping_r1_explain_themselves(self):
        wrong = mcp.R1(self.r1.url, "x" * 40)
        text = self.rpc({"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": "r1_status"}}, r1=wrong)[0]["result"]
        self.assertTrue(text["isError"])
        self.assertIn("token", text["content"][0]["text"])
        asleep = mcp.R1("http://127.0.0.1:9", TOKEN, timeout=2)
        text = self.rpc({"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": "r1_status"}}, r1=asleep)[0]["result"]
        self.assertTrue(text["isError"])
        self.assertEqual(mcp.ASLEEP, text["content"][0]["text"])

    def test_queries_are_encoded_and_unknown_methods_error(self):
        result = self.rpc({"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                           "params": {"name": "recent_texts", "arguments": {"with": "Mom & Dad", "limit": 5}}})[0]["result"]
        self.assertEqual("/v1/texts?with=Mom+%26+Dad&limit=5", result["structuredContent"]["path"])
        error = self.rpc({"jsonrpc": "2.0", "id": 9, "method": "resources/list"})[0]
        self.assertEqual(-32601, error["error"]["code"])
        parse = mcp.serve  # malformed input yields a parse error, never a crash
        out = io.StringIO()
        parse(self.r1, io.StringIO("not json\n"), out)
        self.assertEqual(-32700, json.loads(out.getvalue())["error"]["code"])


if __name__ == "__main__":
    unittest.main()
