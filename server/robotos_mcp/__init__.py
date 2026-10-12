"""robotos: Hermes's MCP tools for the user's Rabbit R1 running robotOS.

Hermes spawns this as a stdio MCP server. Each tool call becomes one request to the R1's device API
over the tailnet (through the local userspace tailscaled's HTTP proxy when one is configured). The
tool list never depends on the R1 being awake; a call to a sleeping R1 reports that plainly.
Standard library only.
"""
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid

VERSION = "1.2.0"
PROTOCOLS = ("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")
INSTRUCTIONS = (
    "These tools act on the user's Rabbit R1 (robotOS): its SIM sends and receives the user's texts. "
    "When the user asks you to text someone, call send_sms; the request itself is the authorization, so "
    "do not ask for confirmation unless the recipient or message is unclear. Report the result briefly."
)
APP_TARGETS = ["home", "workspace", "notes", "prompt_builder", "chats", "messages", "hermes",
               "settings", "quick_controls", "updates", "host_devices"]

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
        "description": "Battery, charging, connectivity, whether it can text, today's text count, and the name the R1 shows for you.",
        "inputSchema": {"type": "object", "properties": {}, "additionalProperties": False},
    },
]

TOOLS.extend([
    {"name": "speak", "title": "Speak on the R1",
     "description": "Read text aloud on the R1. Requires device permission; Quiet mode and active microphone or speech block it.",
     "inputSchema": {"type": "object", "properties": {"text": {"type": "string", "minLength": 1, "maxLength": 2000}},
                     "required": ["text"], "additionalProperties": False}},
    {"name": "show", "title": "Show a notice on the R1",
     "description": "Save a dismissible notice on Home without changing the conversation or waking the screen. Requires device permission.",
     "inputSchema": {"type": "object", "properties": {"text": {"type": "string", "minLength": 1, "maxLength": 2000}},
                     "required": ["text"], "additionalProperties": False}},
    {"name": "open_app", "title": "Open a robotOS app on the R1",
     "description": "Open a built-in robotOS screen. Optional item_id opens an exact saved note (list_notes) or prompt (list_prompts); opening a prompt does not run it. Requires Open apps on screen permission and an awake, unlocked R1 with robotOS foreground. Refuses recording, unsaved note edits or approval conflicts. Returns opening when a launch is accepted, or opened for immediate workspace navigation. Cannot launch other Android apps or change settings.",
     "inputSchema": {"type": "object", "properties": {
                         "app": {"type": "string", "enum": APP_TARGETS},
                         "item_id": {"type": "string", "pattern": "^[A-Za-z0-9_-]{1,200}$",
                                     "description": "Optional exact saved item ID, only for notes or prompt_builder. Omit to open the app's list."}},
                     "required": ["app"], "additionalProperties": False}},
    {"name": "set_volume", "title": "Set R1 speech volume",
     "description": "Set speech volume from 0 (silent) to 1 (maximum). Returns the applied level. Requires device permission.",
     "inputSchema": {"type": "object", "properties": {"level": {"type": "number", "minimum": 0, "maximum": 1}},
                     "required": ["level"], "additionalProperties": False}},
    {"name": "save_recipient", "title": "Save an SMS recipient",
     "description": "Save a name and phone number on the R1. Does not send a message. Requires device permission.",
     "inputSchema": {"type": "object", "properties": {"name": {"type": "string", "minLength": 1, "maxLength": 80},
                                                     "number": {"type": "string"}},
                     "required": ["name", "number"], "additionalProperties": False}},
    {"name": "set_name", "title": "Tell the R1 your name",
     "description": "Set the name the R1 shows for you in chats, hints and notes: the name the user calls you. Works only while "
                    "the R1 has no name for you (r1_status: name_settable), normally once during robotOS setup; after that only "
                    "the user can change it on the R1. Use it only when asked to.",
     "inputSchema": {"type": "object", "properties": {"name": {"type": "string", "minLength": 1, "maxLength": 24}},
                     "required": ["name"], "additionalProperties": False}},
    {"name": "request_status", "title": "Recover a device action result",
     "description": "Read a persisted receipt using its request_id. Receipts last at least seven days. Unknown is not proof an action did not run.",
     "inputSchema": {"type": "object", "properties": {"request_id": {"type": "string"}},
                     "required": ["request_id"], "additionalProperties": False}},
])

MUTATIONS = {"send_sms": "/v1/sms", "speak": "/v1/speak", "show": "/v1/show",
             "set_volume": "/v1/volume", "save_recipient": "/v1/recipients", "set_name": "/v1/name", "open_app": "/v1/apps/open"}
for tool in TOOLS:
    if tool["name"] in MUTATIONS:
        tool["inputSchema"]["properties"]["request_id"] = {
            "type": "string", "pattern": "^[A-Za-z0-9_-]{16,128}$",
            "description": "Optional request identifier. Exact retries require r1_status to advertise request_receipt_retention_seconds, and must remain inside that window. Never change it to retry an unknown outcome.",
        }

ASLEEP = (
    "The R1 can't be reached: it is probably asleep on battery (radios off), out of coverage, or off Tailscale. "
    "Ask the user to press its button or plug it in to check again."
)


def valid_app_target(args):
    return (args.get("app") in APP_TARGETS and ("item_id" not in args or
            (args["app"] in ("notes", "prompt_builder") and isinstance(args["item_id"], str)
             and re.fullmatch(r"[A-Za-z0-9_-]{1,200}", args["item_id"]) is not None)))


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
                payload = json.loads(response.read(1_048_576) or b"{}")
                if not isinstance(payload, dict):
                    raise ValueError("Device response must be an object")
                return response.status, payload
        except urllib.error.HTTPError as error:
            try:
                payload = json.loads(error.read(65_536) or b"{}")
                if not isinstance(payload, dict):
                    raise ValueError("Device error must be an object")
            except (ValueError, OSError):
                payload = {"error": "HTTP %d" % error.code}
            if method == "POST":
                request_id = (body or {}).get("request_id")
                confirmed = request_id is not None and payload.get("request_id") == request_id
                # A proxy can lose the result after forwarding a POST. A matching receipt is
                # the only evidence tying an error to this action; never drop the lookup key.
                if error.code != 401 and (not confirmed or (error.code >= 500 and payload.get("status") != "blocked")):
                    payload = {"error": "The R1 action could not be confirmed (HTTP %d). The outcome is unknown; do not repeat it. Use request_status with this request_id." % error.code,
                               "status": "unknown"}
                payload["request_id"] = request_id
            return error.code, payload
        except (urllib.error.URLError, OSError, ValueError):
            if method == "POST":
                return None, {"error": "The R1 response was interrupted. The action outcome is unknown; do not repeat it. Use request_status with this request_id.",
                              "status": "unknown", "request_id": (body or {}).get("request_id")}
            return None, {"error": ASLEEP}


def run_tool(r1, name, arguments):
    """One tool call -> (text, is_error, structured)."""
    if hasattr(r1, "run_tool"):
        return r1.run_tool(name, arguments)
    args = arguments or {}
    if not isinstance(args, dict):
        return "Tool arguments must be an object.", True, None
    tool = next((tool for tool in TOOLS if tool["name"] == name), None)
    if tool is None:
        return "Unknown tool %s." % name, True, None
    schema = tool["inputSchema"]
    if set(args) - set(schema["properties"]) or any(k not in args for k in schema.get("required", [])):
        return "Invalid or missing arguments for %s. No action was started." % name, True, None
    if name == "open_app" and not valid_app_target(args):
        return "Choose a supported robotOS app; item_id is only for an exact saved note or prompt. No action was started.", True, None
    if name in MUTATIONS:
        payload = {k: v for k, v in args.items() if k != "request_id"}
        payload["request_id"] = args.get("request_id") or str(uuid.uuid4())
    if name == "send_sms":
        to, body = str(args.get("to", "")).strip(), str(args.get("body", "")).strip()
        if not to or not body:
            return "send_sms needs both `to` and `body`. Nothing was sent.", True, None
        payload.update(to=to, body=body)
        status, payload = r1.call("POST", "/v1/sms", payload, timeout=60)
    elif name in MUTATIONS:
        if name in ("speak", "show") and (not isinstance(args["text"], str) or not 1 <= len(args["text"].strip()) <= 2000):
            return "text must be 1–2000 characters. No action was started.", True, None
        if name == "set_volume" and (isinstance(args["level"], bool) or not isinstance(args["level"], (float, int)) or not 0 <= args["level"] <= 1):
            return "level must be between 0 and 1. No action was started.", True, None
        if name == "set_name" and (not isinstance(args["name"], str) or not 1 <= len(args["name"].strip()) <= 24):
            return "name must be 1–24 characters. Nothing was changed.", True, None
        status, payload = r1.call("POST", MUTATIONS[name], payload)
    elif name == "request_status":
        status, payload = r1.call("GET", "/v1/requests/" + urllib.parse.quote(str(args["request_id"]), safe=""))
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
        return "The R1 rejected this server's token (robotOS → Settings → Hermes). Nothing was done.", True, payload
    return str(payload.get("error") or "The R1 returned HTTP %s." % status), True, payload


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
        result = {"tools": getattr(r1, "tools", TOOLS)}
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
    from .hub import from_env
    try:
        hub = from_env(TOOLS)
    except ValueError as error:
        sys.stderr.write("robotos: %s\n" % error)
        return 2
    if hub is not None:
        serve(hub)
        return 0
    url, token = os.environ.get("ROBOTOS_R1_URL", ""), os.environ.get("ROBOTOS_R1_TOKEN", "")
    if not url or len(token) < 32:
        sys.stderr.write("robotos: set ROBOTOS_R1_URL and ROBOTOS_R1_TOKEN (32+ characters)\n")
        return 2
    serve(R1(url, token, os.environ.get("ROBOTOS_PROXY") or None))
    return 0
