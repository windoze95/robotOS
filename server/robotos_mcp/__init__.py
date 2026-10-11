"""robotos: Hermes's MCP tools for the user's Rabbit R1 running robotOS.

Hermes spawns this as a stdio MCP server. Each tool call becomes one request to the R1's device API
over the tailnet (through the local userspace tailscaled's HTTP proxy when one is configured). The
tool list never depends on the R1 being awake; a call to a sleeping R1 reports that plainly.
Standard library only.
"""
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

VERSION = "1.0.0"
PROTOCOLS = ("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")
INSTRUCTIONS = (
    "These tools act on the user's Rabbit R1 (robotOS): its SIM sends and receives the user's texts. "
    "When the user asks you to text someone, call send_sms; the request itself is the authorization, so "
    "do not ask for confirmation unless the recipient or message is unclear. Report the result briefly."
)

TOOLS = [
    {
        "name": "send_sms",
        "title": "Send a text from the R1",
        "description": (
            "Send one SMS right now from the user's Rabbit R1, from the user's own phone number. "
            "`to` is a phone number (+14055550123, 405-555-0123) or the exact name of a recipient saved on the R1 "
            "(see list_recipients). Write `body` as the user would; keep it short unless asked otherwise. "
            "Returns the status: 'sent' means the carrier accepted every part; 'delivered' needs a carrier report."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "to": {"type": "string", "description": "Phone number or saved recipient name."},
                "body": {"type": "string", "description": "The message text (up to 1600 characters)."},
            },
            "required": ["to", "body"],
            "additionalProperties": False,
        },
    },
    {
        "name": "sms_status",
        "title": "Check a sent text",
        "description": "The current status of a text sent with send_sms (sending, sent, delivered, failed, unknown).",
        "inputSchema": {
            "type": "object",
            "properties": {"id": {"type": "string", "description": "The id returned by send_sms."}},
            "required": ["id"],
            "additionalProperties": False,
        },
    },
    {
        "name": "recent_texts",
        "title": "Read texts on the R1",
        "description": (
            "Texts stored on the R1. Without `with`: the latest conversations, newest first. With `with` "
            "(a phone number or saved name): that conversation's recent messages, oldest first."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "with": {"type": "string", "description": "Phone number or saved recipient name."},
                "limit": {"type": "integer", "minimum": 1, "maximum": 100, "description": "How many (default 20)."},
            },
            "additionalProperties": False,
        },
    },
    {
        "name": "list_recipients",
        "title": "Saved recipients",
        "description": "Names and numbers saved on the R1, which send_sms accepts by name.",
        "inputSchema": {"type": "object", "properties": {}, "additionalProperties": False},
    },
    {
        "name": "r1_status",
        "title": "R1 status",
        "description": "Battery, charging, connectivity, whether it can text, and today's text count.",
        "inputSchema": {"type": "object", "properties": {}, "additionalProperties": False},
    },
]

ASLEEP = (
    "The R1 can't be reached: it is probably asleep on battery (radios off), out of coverage, or off Tailscale. "
    "Nothing was done. Ask the user to press its button or plug it in, then try again."
)


class R1:
    """The R1's device API: {ROBOTOS_R1_URL}/v1/... with a bearer token."""

    def __init__(self, url, token, proxy=None, timeout=20.0):
        self.url = url.rstrip("/")
        self.token = token
        handlers = [urllib.request.ProxyHandler({"http": proxy, "https": proxy} if proxy else {})]
        self.opener = urllib.request.build_opener(*handlers)
        self.timeout = timeout

    def call(self, method, path, body=None, timeout=None):
        data = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request(self.url + path, data=data, method=method, headers={
            "Authorization": "Bearer " + self.token,
            "Content-Type": "application/json",
            "Accept": "application/json",
        })
        try:
            with self.opener.open(request, timeout=timeout or self.timeout) as response:
                return response.status, json.loads(response.read(1_048_576) or b"{}")
        except urllib.error.HTTPError as error:
            try:
                payload = json.loads(error.read(65_536) or b"{}")
            except ValueError:
                payload = {"error": "HTTP %d" % error.code}
            return error.code, payload
        except (urllib.error.URLError, OSError, ValueError):
            return None, {"error": ASLEEP}


def run_tool(r1, name, arguments):
    """One tool call -> (text, is_error, structured)."""
    args = arguments or {}
    if name == "send_sms":
        to, body = str(args.get("to", "")).strip(), str(args.get("body", "")).strip()
        if not to or not body:
            return "send_sms needs both `to` and `body`. Nothing was sent.", True, None
        status, payload = r1.call("POST", "/v1/sms", {"to": to, "body": body}, timeout=60)
    elif name == "sms_status":
        status, payload = r1.call("GET", "/v1/sms/" + urllib.parse.quote(str(args.get("id", "")), safe=""))
    elif name == "recent_texts":
        query = {k: str(v) for k, v in (("with", args.get("with")), ("limit", args.get("limit"))) if v not in (None, "")}
        status, payload = r1.call("GET", "/v1/texts" + ("?" + urllib.parse.urlencode(query) if query else ""))
    elif name == "list_recipients":
        status, payload = r1.call("GET", "/v1/recipients")
    elif name == "r1_status":
        status, payload = r1.call("GET", "/v1/status")
    else:
        return "Unknown tool %s." % name, True, None
    if status == 200:
        return json.dumps(payload, ensure_ascii=False), False, payload
    if status == 401:
        return "The R1 rejected this server's token (robotOS → Settings → Hermes). Nothing was done.", True, None
    return str(payload.get("error") or "The R1 returned HTTP %s." % status), True, None


def handle(r1, message):
    """One JSON-RPC message -> response dict, or None for notifications."""
    method, ident = message.get("method"), message.get("id")
    if ident is None:  # notifications (initialized, cancelled, …) need no answer
        return None
    if method == "initialize":
        asked = (message.get("params") or {}).get("protocolVersion")
        result = {
            "protocolVersion": asked if asked in PROTOCOLS else PROTOCOLS[1],
            "capabilities": {"tools": {"listChanged": False}},
            "serverInfo": {"name": "robotos", "version": VERSION},
            "instructions": INSTRUCTIONS,
        }
    elif method == "ping":
        result = {}
    elif method == "tools/list":
        result = {"tools": TOOLS}
    elif method == "tools/call":
        params = message.get("params") or {}
        text, is_error, structured = run_tool(r1, params.get("name"), params.get("arguments"))
        result = {"content": [{"type": "text", "text": text}], "isError": is_error}
        if structured is not None:
            result["structuredContent"] = structured
    else:
        return {"jsonrpc": "2.0", "id": ident, "error": {"code": -32601, "message": "Method not found: %s" % method}}
    return {"jsonrpc": "2.0", "id": ident, "result": result}


def serve(r1, stdin=sys.stdin, stdout=sys.stdout):
    for line in stdin:
        line = line.strip()
        if not line:
            continue
        try:
            message = json.loads(line)
        except ValueError:
            response = {"jsonrpc": "2.0", "id": None, "error": {"code": -32700, "message": "Parse error"}}
        else:
            batch = message if isinstance(message, list) else [message]
            replies = [r for r in (handle(r1, m) for m in batch if isinstance(m, dict)) if r is not None]
            response = replies if isinstance(message, list) else (replies[0] if replies else None)
        if response:
            stdout.write(json.dumps(response, ensure_ascii=False) + "\n")
            stdout.flush()


def main():
    url, token = os.environ.get("ROBOTOS_R1_URL", ""), os.environ.get("ROBOTOS_R1_TOKEN", "")
    if not url or len(token) < 32:
        sys.stderr.write("robotos: set ROBOTOS_R1_URL and ROBOTOS_R1_TOKEN (32+ characters)\n")
        return 2
    serve(R1(url, token, os.environ.get("ROBOTOS_PROXY") or None))
    return 0
