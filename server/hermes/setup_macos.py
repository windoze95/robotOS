"""Make Hermes the agent behind a robotOS R1, on the macOS host that runs Hermes.

Run by tools/hermes-setup.sh (or by hand from a copy of server/): installs Hermes's robotos MCP
server and skill, turns on Hermes's API server (loopback only), runs a private userspace Tailscale
node that serves that API to the tailnet over HTTPS and lets the MCP server reach the R1, installs
the Hermes gateway service, and writes the R1's settings to a private JSON file. Safe to re-run:
existing keys are kept. Standard library only.
"""
import argparse
import json
import os
import plistlib
import re
import secrets
import shutil
import socket
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

HOME = Path.home()
STATE = HOME / "Library/Application Support/robotOS"
TAILSCALE_DIR = STATE / "tailscale"
SOCKET = TAILSCALE_DIR / "tailscaled.sock"
MCP_DIR = STATE / "mcp"
R1_SETTINGS = STATE / "r1-hermes.json"
HERMES_HOME = HOME / ".hermes"
LAUNCH_AGENTS = HOME / "Library/LaunchAgents"
TAILSCALE_LABEL = "ai.robotos.tailscale"
PROXY = "127.0.0.1:1056"
API_PORT = 8642
R1_PORT = 8765


def merge_env(text, wanted, generated):
    """`wanted` keys are set; `generated` keys keep any existing value, else get a new random one."""
    lines, seen = text.splitlines(), set()
    out = []
    for line in lines:
        key = line.split("=", 1)[0].strip() if "=" in line and not line.lstrip().startswith("#") else None
        if key in wanted:
            out.append(f"{key}={wanted[key]}")
            seen.add(key)
        else:
            out.append(line)
            if key in generated:
                seen.add(key)
    out += [f"{k}={v}" for k, v in wanted.items() if k not in seen]
    out += [f"{k}={secrets.token_urlsafe(36)}" for k in generated if k not in seen]
    return "\n".join(out).rstrip("\n") + "\n"


def read_env(path):
    values = {}
    if path.exists():
        for line in path.read_text().splitlines():
            if "=" in line and not line.lstrip().startswith("#"):
                key, value = line.split("=", 1)
                values[key.strip()] = value.strip()
    return values


def tailscale_agent(tailscaled, existing=None):
    """The userspace tailscaled agent, with the loopback proxy the MCP server uses to reach the R1."""
    agent = dict(existing or {
        "Label": TAILSCALE_LABEL,
        "ProgramArguments": [tailscaled, "--tun=userspace-networking", f"--state={TAILSCALE_DIR / 'tailscaled.state'}",
                             f"--socket={SOCKET}", "--port=0"],
        "RunAtLoad": True, "KeepAlive": True,
        "StandardOutPath": str(TAILSCALE_DIR / "daemon.log"), "StandardErrorPath": str(TAILSCALE_DIR / "daemon.log"),
    })
    args = [a for a in agent["ProgramArguments"] if not a.startswith("--outbound-http-proxy-listen=")]
    agent["ProgramArguments"] = args + [f"--outbound-http-proxy-listen={PROXY}"]
    return agent


def r1_settings(dns_name, api_key, token, owner=None):
    hermes = {"deviceApi": True, "token": token, "port": R1_PORT}
    if owner:
        hermes.update(passthrough=True, owner=owner)
    return {
        "activeProvider": "hermes",
        "providers": {"hermes": {"label": "Hermes Agent", "baseUrl": f"https://{dns_name}/v1", "apiKey": api_key,
                                 "model": "hermes-agent", "session": "hermes-session", "timeoutSec": 300}},
        "hermes": hermes,
    }


def mcp_entry(python, r1_address):
    return {
        "command": python,
        "args": ["-m", "robotos_mcp"],
        "env": {"PYTHONPATH": str(MCP_DIR), "ROBOTOS_R1_URL": f"http://{r1_address}:{R1_PORT}",
                "ROBOTOS_R1_TOKEN": "${ROBOTOS_R1_TOKEN}", "ROBOTOS_PROXY": f"http://{PROXY}"},
        "timeout": 90, "connect_timeout": 30, "enabled": True,
    }


def config_value(value):
    """`hermes config set` reads lists, maps and numbers as YAML but keeps a string's quotes, so strings go raw."""
    return value if isinstance(value, str) else json.dumps(value)


def say(message):
    print(f"==> {message}", flush=True)


def run(*args, check=True, **kwargs):
    return subprocess.run([str(a) for a in args], check=check, text=True, **kwargs)


def find(name, *candidates):
    for path in [shutil.which(name), *candidates]:
        if path and Path(path).exists():
            return str(path)
    raise SystemExit(f"{name} not found. " + {"hermes": "Install Hermes Agent first.",
                                              "tailscaled": "Run: brew install tailscale"}.get(name, ""))


def write_private(path, data):
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    temporary = path.with_suffix(path.suffix + ".tmp")
    fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        f.write(data)
    os.replace(temporary, path)


def wait_for(check, seconds):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            if check():
                return True
        except Exception:
            pass
        time.sleep(1)
    return False


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--r1", required=True, help="the R1's tailnet address (its 100.x IP or MagicDNS name)")
    parser.add_argument("--owner", help="your own phone number; its texts to the R1 go to Hermes (+1XXXXXXXXXX)")
    parser.add_argument("--source", default=str(Path(__file__).resolve().parents[1]),
                        help="the server/ directory holding robotos_mcp and hermes/skills")
    parser.add_argument("--hostname", default=socket.gethostname().split(".")[0].lower() + "-hermes",
                        help="tailnet name for this host's node, used only when it first joins")
    parser.add_argument("--python", default=next((p for p in ("/opt/homebrew/bin/python3", "/usr/local/bin/python3")
                                                  if Path(p).exists()), sys.executable),
                        help="Python 3.9+ that Hermes runs the MCP server with (a stable path survives upgrades)")
    args = parser.parse_args(argv)
    if not re.fullmatch(r"[A-Za-z0-9.:-]+", args.r1):
        raise SystemExit("--r1 must be an IP address or host name")
    if args.owner and not re.fullmatch(r"\+[1-9]\d{6,14}", args.owner):
        raise SystemExit("--owner must be in international form, like +14055550123")
    os.umask(0o077)
    hermes = find("hermes", HOME / ".local/bin/hermes")
    tailscale = find("tailscale", "/opt/homebrew/bin/tailscale", "/usr/local/bin/tailscale")
    tailscaled = find("tailscaled", "/opt/homebrew/bin/tailscaled", "/usr/local/bin/tailscaled")
    source = Path(args.source)
    ts = [tailscale, f"--socket={SOCKET}"]

    say("Installing the robotos MCP server and skill")
    shutil.rmtree(MCP_DIR / "robotos_mcp", ignore_errors=True)
    shutil.copytree(source / "robotos_mcp", MCP_DIR / "robotos_mcp", ignore=shutil.ignore_patterns("tests", "__pycache__"))
    skill = HERMES_HOME / "skills/robotos"
    skill.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source / "hermes/skills/robotos/SKILL.md", skill / "SKILL.md")

    say("Turning on Hermes's API server (loopback only) and the R1 device token")
    env_path = HERMES_HOME / ".env"
    write_private(env_path, merge_env(env_path.read_text() if env_path.exists() else "",
                                      {"API_SERVER_ENABLED": "true", "API_SERVER_HOST": "127.0.0.1", "API_SERVER_PORT": str(API_PORT)},
                                      ["API_SERVER_KEY", "ROBOTOS_R1_TOKEN"]))
    keys = read_env(env_path)

    say("Registering the MCP server with Hermes")
    for key, value in mcp_entry(args.python, args.r1).items():
        run(hermes, "config", "set", f"mcp_servers.robotos.{key}", config_value(value), stdout=subprocess.DEVNULL)

    say("Starting the private Tailscale node")
    plist = LAUNCH_AGENTS / f"{TAILSCALE_LABEL}.plist"
    TAILSCALE_DIR.mkdir(parents=True, exist_ok=True, mode=0o700)
    existing = plistlib.loads(plist.read_bytes()) if plist.exists() else None
    agent = tailscale_agent(tailscaled, existing)
    if agent != existing:
        LAUNCH_AGENTS.mkdir(parents=True, exist_ok=True)
        plist.write_bytes(plistlib.dumps(agent))
        domain = f"gui/{os.getuid()}"
        run("launchctl", "bootout", f"{domain}/{TAILSCALE_LABEL}", check=False, stderr=subprocess.DEVNULL)
        time.sleep(1)
        run("launchctl", "bootstrap", domain, plist)
    if not wait_for(lambda: SOCKET.exists(), 20):
        raise SystemExit(f"tailscaled did not start; see {TAILSCALE_DIR / 'daemon.log'}")
    status = lambda: json.loads(run(*ts, "status", "--json", capture_output=True, check=False).stdout or "{}")
    if status().get("BackendState") != "Running":
        say("Approve this host in your tailnet (open the link Tailscale prints)")
        run(*ts, "up", f"--hostname={args.hostname}")
    dns_name = status()["Self"]["DNSName"].rstrip(".")

    say(f"Serving Hermes's API to the tailnet at https://{dns_name}")
    run(*ts, "serve", "--bg", "--https=443", f"http://127.0.0.1:{API_PORT}", stdout=subprocess.DEVNULL)

    say("Starting the Hermes gateway service")
    if (LAUNCH_AGENTS / "ai.hermes.gateway.plist").exists():
        run(hermes, "gateway", "restart", stdout=subprocess.DEVNULL)
    else:
        run(hermes, "gateway", "install", "--start-now", "--start-on-login", stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL)
    health = lambda: json.load(urllib.request.urlopen(f"http://127.0.0.1:{API_PORT}/health", timeout=3)).get("status") == "ok"
    if not wait_for(health, 60):
        raise SystemExit("Hermes's API server did not come up; see ~/.hermes/logs/gateway.log")

    write_private(R1_SETTINGS, json.dumps(r1_settings(dns_name, keys["API_SERVER_KEY"], keys["ROBOTOS_R1_TOKEN"], args.owner), indent=2))
    say(f"R1 settings written to {R1_SETTINGS} (private; push them with tools/hermes-setup.sh)")

    proxy = urllib.request.build_opener(urllib.request.ProxyHandler({"http": f"http://{PROXY}"}))
    probe = urllib.request.Request(f"http://{args.r1}:{R1_PORT}/v1/status", headers={"Authorization": "Bearer " + keys["ROBOTOS_R1_TOKEN"]})
    try:
        state = json.load(proxy.open(probe, timeout=10))
        say(f"Hermes can reach the R1: battery {state.get('battery_percent')}%, can text: {state.get('can_text')}")
    except Exception as error:
        say(f"Hermes can't reach the R1 yet ({error.__class__.__name__}); fine until its settings are pushed and it's awake")
    return 0


if __name__ == "__main__":
    sys.exit(main())
