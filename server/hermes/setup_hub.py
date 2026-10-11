"""Install the persistent robotOS service without touching R1 provisioning or workspace data.

Creates private host configuration, installs a user service, and optionally connects Hermes MCP.
No credentials are printed. Works on macOS launchd and Linux systemd --user.
"""
import argparse
import json
import os
from pathlib import Path
import plistlib
import secrets
import shutil
import subprocess
import sys
import tempfile
import urllib.parse

from .setup_macos import merge_env, read_env, config_value


LABEL = "ai.robotos.hub"


def write_private(path, data):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    fd, temporary = tempfile.mkstemp(prefix="." + path.name, dir=path.parent)
    try:
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, "w") as file:
            file.write(data)
            file.flush()
            os.fsync(file.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def configuration(existing, state, origin, hermes_env):
    """Retain identity and data across reinstallation. Do not infer permission to rotate secrets."""
    config = dict(existing)
    config.setdefault("bind", "127.0.0.1")
    config.setdefault("port", 8787)
    config.setdefault("data_dir", str(state / "data"))
    config.setdefault("owner_password", secrets.token_urlsafe(30))
    config.setdefault("owner_token", secrets.token_urlsafe(36))
    config.setdefault("mcp_token", secrets.token_urlsafe(36))
    config.setdefault("public_origin", origin or "http://127.0.0.1:8787")
    if origin:
        config["public_origin"] = origin
    config.setdefault("hermes_url", "http://127.0.0.1:8642")
    config.setdefault("hermes_token", hermes_env.get("API_SERVER_KEY", ""))
    config.setdefault("speech_url", "https://api.openai.com/v1")
    config.setdefault("speech_token", hermes_env.get("OPENAI_API_KEY", ""))
    config.setdefault("speech_model", "gpt-4o-mini-transcribe")
    return config


def launch_agent(python, source, config_path, state):
    return {
        "Label": LABEL,
        "ProgramArguments": [str(python), "-m", "robotos_hub", "--config", str(config_path)],
        "WorkingDirectory": str(source),
        "EnvironmentVariables": {"PYTHONPATH": str(source), "PYTHONUNBUFFERED": "1"},
        "RunAtLoad": True, "KeepAlive": True, "ThrottleInterval": 10, "Umask": 0o077,
        "StandardOutPath": str(state / "service.log"), "StandardErrorPath": str(state / "service.log"),
    }


def systemd_unit(python, source, config_path):
    # systemd quoting has its own % expansion, independent of shell quoting.
    def quote(value):
        return '"' + str(value).replace("\\", "\\\\").replace('"', '\\"').replace("%", "%%") + '"'
    return "\n".join([
        "[Unit]", "Description=robotOS shared workspace and device management", "After=network.target", "",
        "[Service]", "Type=simple", "WorkingDirectory=" + quote(source),
        "ExecStart=" + " ".join(map(quote, [python, "-m", "robotos_hub", "--config", config_path])),
        "Environment=" + quote("PYTHONPATH=" + str(source)), "Environment=PYTHONUNBUFFERED=1",
        "Restart=on-failure", "RestartSec=10", "UMask=0077", "NoNewPrivileges=true", "PrivateTmp=true", "",
        "[Install]", "WantedBy=default.target", "",
    ])


def mcp_entry(python, source, port):
    return {"command": str(python), "args": ["-m", "robotos_mcp"], "env": {
        "PYTHONPATH": str(source), "ROBOTOS_HUB_URL": f"http://127.0.0.1:{port}",
        "ROBOTOS_HUB_MCP_TOKEN": "${ROBOTOS_HUB_MCP_TOKEN}"}, "timeout": 90, "connect_timeout": 30, "enabled": True}


def install_code(source, destination):
    destination.mkdir(parents=True, exist_ok=True, mode=0o700)
    for name in ("robotos_hub", "robotos_mcp"):
        original = source / name
        if not original.is_dir():
            raise ValueError("Missing service package: " + name)
        staged = Path(tempfile.mkdtemp(prefix="." + name, dir=destination))
        try:
            shutil.copytree(original, staged, dirs_exist_ok=True,
                            ignore=shutil.ignore_patterns("__pycache__", "*.pyc", "tests", "node_modules"))
            target, previous = destination / name, destination / (name + ".previous")
            if previous.exists():
                shutil.rmtree(previous)
            if target.exists():
                target.rename(previous)
            try:
                staged.rename(target)
            except Exception:
                if previous.exists() and not target.exists():
                    previous.rename(target)
                raise
        finally:
            if staged.exists():
                shutil.rmtree(staged)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--state", type=Path, default=Path.home() / (
        "Library/Application Support/robotOS/hub" if sys.platform == "darwin" else ".local/share/robotos-hub-service"))
    parser.add_argument("--public-origin", help="Exact HTTPS browser origin, including nondefault port")
    parser.add_argument("--register-hermes", action="store_true", help="Switch robotOS MCP to the host adapter and restart Hermes")
    parser.add_argument("--no-start", action="store_true", help="Prepare service files without installing/starting the user service")
    args = parser.parse_args(argv)
    if args.public_origin:
        parsed = urllib.parse.urlsplit(args.public_origin)
        if (parsed.scheme != "https" or not parsed.hostname or parsed.path not in ("", "/") or
                parsed.username or parsed.password or parsed.query or parsed.fragment):
            parser.error("public-origin must be a bare HTTPS origin")
        args.public_origin = args.public_origin.rstrip("/")
    state = args.state.expanduser().resolve()
    state.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(state, 0o700)
    config_path, source = state / "hub.json", state / "service"
    existing = json.loads(config_path.read_text()) if config_path.exists() else {}
    hermes_home = Path(os.environ.get("HERMES_HOME", str(Path.home() / ".hermes")))
    env_path = hermes_home / ".env"
    config = configuration(existing, state, args.public_origin, read_env(env_path))
    # Validate the configuration using the exact implementation before mutating installed code.
    sys.path.insert(0, str(args.source.resolve()))
    from robotos_hub.config import Config
    # The installed parser validates the private JSON file; preserve current config on failure.
    validation = state / ".validate.json"
    write_private(validation, json.dumps(config))
    try:
        Config.load(str(validation))
    finally:
        validation.unlink(missing_ok=True)
    install_code(args.source.resolve(), source)
    write_private(config_path, json.dumps(config, indent=2) + "\n")
    write_private(state / "owner-password.txt", config["owner_password"] + "\n")
    if args.register_hermes:
        hermes = shutil.which("hermes")
        if not hermes:
            raise SystemExit("Hermes executable was not found; service files are prepared.")
        write_private(env_path, merge_env(env_path.read_text() if env_path.exists() else "",
                                         {"ROBOTOS_HUB_MCP_TOKEN": config["mcp_token"]}, []))
        for key, value in mcp_entry(sys.executable, source, config["port"]).items():
            subprocess.run([hermes, "config", "set", "mcp_servers.robotos." + key, config_value(value)],
                           check=True, stdout=subprocess.DEVNULL)
    if sys.platform == "darwin":
        service_file = Path.home() / "Library/LaunchAgents" / (LABEL + ".plist")
        write_private(service_file, plistlib.dumps(launch_agent(sys.executable, source, config_path, state)).decode())
        if not args.no_start:
            domain = f"gui/{os.getuid()}"
            subprocess.run(["launchctl", "bootout", domain + "/" + LABEL], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            subprocess.run(["launchctl", "bootstrap", domain, str(service_file)], check=True)
    elif sys.platform.startswith("linux"):
        service_file = Path.home() / ".config/systemd/user/robotos-hub.service"
        write_private(service_file, systemd_unit(sys.executable, source, config_path))
        if not args.no_start:
            subprocess.run(["systemctl", "--user", "daemon-reload"], check=True)
            subprocess.run(["systemctl", "--user", "enable", "--now", "robotos-hub.service"], check=True)
            subprocess.run(["systemctl", "--user", "restart", "robotos-hub.service"], check=True)
    else:
        raise SystemExit("User-service installation supports macOS and Linux; use python -m robotos_hub elsewhere.")
    if args.register_hermes and not args.no_start:
        subprocess.run([hermes, "gateway", "restart"], check=True, stdout=subprocess.DEVNULL)
    print("robotOS service files: " + str(source))
    print("Protected configuration: " + str(config_path))
    print("Owner password saved locally: " + str(state / "owner-password.txt"))
    print("Console origin: " + config["public_origin"])
    print("Service prepared." if args.no_start else "Service startup requested. Check /api/v1/health before pairing.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
