"""Install the native robotOS workspace and its Hermes connection.

The default connects an existing local Hermes installation. Use --hub-only for a separate
Hermes host, or --mcp-only to install just the scoped adapter beside Hermes.
No credentials are printed. Works on macOS launchd and Linux systemd --user.
"""
import argparse
import codecs
import json
import os
from pathlib import Path
import plistlib
import re
import secrets
import shutil
import stat
import subprocess
import sys
import tempfile
import time
import urllib.request
import urllib.parse

from .setup_macos import config_value


LABEL = "ai.robotos.hub"


def environment_assignment(line):
    match = re.match(r"^\s*(?:export\s+)?(?:'([^']+)'|([A-Za-z_][A-Za-z_0-9]*))\s*=(.*)$", line)
    return (match.group(1) or match.group(2), match.group(3)) if match else (None, None)


def environment_value(raw):
    """Single-line dotenv values, with dotenv's comment/escape rules rather than shell rules."""
    value = raw.lstrip()
    if value.startswith(("'", '"')):
        quote = value[0]
        match = re.fullmatch(quote + r"((?:\\.|[^" + quote + r"\\])*)" + quote + r"\s*(?:#.*)?", value)
        if not match:
            raise ValueError("An installer-managed Hermes environment setting is malformed or multiline")
        escapes = r"\\[\\']" if quote == "'" else r'''\\[\\'"abfnrtv]'''
        value = re.sub(escapes, lambda item: codecs.decode(item.group(), "unicode-escape"), match.group(1))
    else:
        if raw[:1].isspace() and value.startswith("#"):
            # python-dotenv versions disagree about an initial '#' after whitespace.
            raise ValueError("Quote installer-managed Hermes values beginning with #; whitespace before # is ambiguous")
        value = re.sub(r"\s+#.*", "", value).rstrip()
    if "${" in value:
        raise ValueError("Installer-managed Hermes environment settings must use literal values, not variable interpolation")
    return value


def hermes_environment(path):
    """Read the few installer-owned dotenv keys, including quoted/export assignments."""
    result = {}
    if path.exists():
        for line in path.read_text().removeprefix("\ufeff").splitlines():
            key, value = environment_assignment(line)
            if key in {"API_SERVER_KEY", "API_SERVER_PORT", "OPENAI_API_KEY", "ROBOTOS_HUB_MCP_TOKEN"}:
                result[key] = environment_value(value)
    return result


def managed_environment(text, settings):
    """Replace only managed assignments, removing duplicate/export variants."""
    lines = [line for line in text.removeprefix("\ufeff").splitlines() if environment_assignment(line)[0] not in settings]
    # JSON double quoting is compatible with dotenv and keeps '#', spaces and quotes literal.
    lines.extend(key + "=" + json.dumps(value) for key, value in settings.items())
    return "\n".join(lines).rstrip("\n") + "\n"


def hermes_executable():
    return shutil.which("hermes") or next((str(path) for path in [Path.home() / ".local/bin/hermes"]
                                          if path.is_file() and os.access(path, os.X_OK)), None)


def environment_token(value, label, minimum=1):
    if (not isinstance(value, str) or len(value) < minimum or "${" in value or
            any(not 33 <= ord(character) <= 126 for character in value)):
        raise ValueError(label + " must be a literal ASCII token without whitespace or variable interpolation")
    return value


def local_hermes(config, existing, environment, explicit_token=None, explicit_url=False):
    """Keep the local API port/key; never turn a split installation into a local one implicitly."""
    address = config["hermes_url"]
    parsed = urllib.parse.urlsplit(address)
    if parsed.scheme != "http" or parsed.hostname not in ("127.0.0.1", "localhost", "::1") or parsed.path not in ("", "/", "/v1"):
        raise ValueError("This hub uses a separate Hermes endpoint; use --hub-only to preserve that architecture")
    try:
        port = parsed.port or 80
        if not explicit_url and not existing.get("hermes_url") and environment.get("API_SERVER_PORT"):
            port = int(environment["API_SERVER_PORT"])
        if not 1 <= port <= 65535:
            raise ValueError()
    except ValueError:
        raise ValueError("Hermes API port must be between 1 and 65535") from None
    saved_key = environment.get("API_SERVER_KEY", "")
    if explicit_token is not None and saved_key and explicit_token != saved_key:
        raise ValueError("The supplied Hermes token differs from the local API key; existing API credentials are never rotated by setup")
    key = saved_key or explicit_token or existing.get("hermes_token") or secrets.token_urlsafe(36)
    environment_token(key, "The local Hermes API key")
    config.update(hermes_url=f"http://127.0.0.1:{port}", hermes_token=key)
    return {"API_SERVER_ENABLED": "true", "API_SERVER_HOST": "127.0.0.1", "API_SERVER_PORT": str(port), "API_SERVER_KEY": key}


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


def mcp_entry(python, source, port=8787, hub_url=None):
    return {"command": str(python), "args": ["-m", "robotos_mcp"], "env": {
        "PYTHONPATH": str(source), "ROBOTOS_HUB_URL": hub_url or f"http://127.0.0.1:{port}",
        "ROBOTOS_HUB_MCP_TOKEN": "${ROBOTOS_HUB_MCP_TOKEN}", "ROBOTOS_HUB_MCP_TOKEN_FILE": ""},
        "timeout": 90, "connect_timeout": 30, "enabled": True}


def install_code(source, destination, packages=("robotos_hub", "robotos_mcp")):
    destination.mkdir(parents=True, exist_ok=True, mode=0o700)
    for name in packages:
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


def endpoint(value, label):
    parsed = urllib.parse.urlsplit(value)
    if (parsed.scheme not in ("https", "http") or not parsed.hostname or parsed.username or
            parsed.password or parsed.query or parsed.fragment or
            (parsed.scheme == "http" and parsed.hostname not in ("localhost", "127.0.0.1", "::1"))):
        raise ValueError(label + " must use HTTPS (or loopback HTTP), without credentials or query parameters")
    return value.rstrip("/")


def token_file(path):
    # Explicit secret-file paths also support projected container Secret volumes.
    # Ordinary hub.json remains subject to the stricter private-file check.
    try:
        fd = os.open(Path(path).expanduser(), os.O_RDONLY | getattr(os, "O_NONBLOCK", 0))
        with os.fdopen(fd) as stream:
            info = os.fstat(stream.fileno())
            if not stat.S_ISREG(info.st_mode) or info.st_mode & 0o022:
                raise ValueError("Token file must be regular and not group/world writable")
            value = stream.read(4097).rstrip("\r\n")
    except (OSError, UnicodeError):
        raise ValueError("Could not read the supplied token file") from None
    if not value or len(value) > 4096 or any(c.isspace() for c in value):
        raise ValueError("Token file must contain one token, at most 4096 characters")
    return value


def register_hermes(hermes_home, source, hub_url, token, skill_source):
    environment_token(token, "The scoped hub MCP token", 32)
    hermes = hermes_executable()
    if not hermes:
        raise ValueError("Hermes executable was not found; add its bin directory to PATH")
    env_path = hermes_home / ".env"
    write_private(env_path, managed_environment(env_path.read_text() if env_path.exists() else "",
                                               {"ROBOTOS_HUB_MCP_TOKEN": token, "ROBOTOS_HUB_MCP_TOKEN_FILE": ""}))
    for key, value in mcp_entry(sys.executable, source, hub_url=hub_url).items():
        subprocess.run([hermes, "config", "set", "mcp_servers.robotos." + key, config_value(value)],
                       check=True, stdout=subprocess.DEVNULL)
    write_private(hermes_home / "skills/robotos/SKILL.md", skill_source.read_text())
    return hermes


def start_hermes(hermes):
    # Hermes resolves profile-specific service names. --if-missing preserves an existing unit.
    subprocess.run([hermes, "gateway", "install", "--if-missing", "--start-now", "--start-on-login"],
                   check=True, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL)
    subprocess.run([hermes, "gateway", "restart"], check=True, stdout=subprocess.DEVNULL)


def preflight_service_manager(platform):
    command = ["launchctl", "print", f"gui/{os.getuid()}"] if platform == "darwin" else [
        "systemctl", "--user", "show", "--property=Version"]
    result = subprocess.run(command, check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if result.returncode != 0:
        raise ValueError("The user service manager is unavailable; sign into the macOS desktop or enable the Linux user service session before installing, or use --no-start")


def wait_for_hermes(config, timeout=60):
    from types import SimpleNamespace
    from robotos_hub.upstream import Client, UpstreamError
    from robotos_hub.store import HubError
    client = Client(SimpleNamespace(**config))
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            if not client.capabilities().get("runs"):
                raise RuntimeError("Hermes does not support the Runs API required by the shared workspace; update Hermes")
            return
        except UpstreamError as error:
            if error.status in (401, 403):
                raise RuntimeError("Hermes rejected the configured API credential; native setup is incomplete") from None
            if error.status == 404:
                raise RuntimeError("Hermes does not expose the required capabilities endpoint; update Hermes") from None
        except HubError:
            raise RuntimeError("Hermes returned incompatible API capabilities; native setup is incomplete") from None
        time.sleep(0.5)
    raise RuntimeError("Authenticated Hermes API did not become ready; inspect the gateway log before pairing devices")


def start_launch_agent(service_file):
    domain = f"gui/{os.getuid()}"
    subprocess.run(["launchctl", "bootout", domain + "/" + LABEL],
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    # launchd can return from bootout before it releases the old registration.
    # Retry the same bootstrap, not another install or a second service identity.
    for attempt in range(8):
        result = subprocess.run(["launchctl", "bootstrap", domain, str(service_file)],
                                stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True)
        if result.returncode == 0:
            return
        if result.returncode != 5 or attempt == 7:
            raise RuntimeError("Could not start robotOS with launchd; inspect the service log and launchctl status")
        time.sleep(0.5)


def wait_for_hub(config, timeout=15):
    host = "[::1]" if config["bind"] == "::1" else "127.0.0.1"
    url = f"http://{host}:{config['port']}/api/v1/ready"
    deadline = time.monotonic() + timeout
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    while time.monotonic() < deadline:
        try:
            with opener.open(url, timeout=1) as response:
                if response.status == 200 and json.load(response).get("status") == "ok":
                    return
        except (OSError, ValueError):
            pass
        time.sleep(0.25)
    raise RuntimeError("robotOS did not become healthy; inspect the service log before restarting Hermes")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--state", type=Path, default=Path.home() / (
        "Library/Application Support/robotOS/hub" if sys.platform == "darwin" else ".local/share/robotos-hub-service"))
    parser.add_argument("--public-origin", help="Exact HTTPS browser origin, including nondefault port")
    parser.add_argument("--hermes-url", help="Hermes API base URL on this or another host; remote connections require HTTPS")
    parser.add_argument("--hermes-token-file", type=Path, help="Read the Hermes API key from a file; never pass credentials in arguments")
    parser.add_argument("--hub-url", help="Hub base URL for the MCP adapter; remote connections require HTTPS")
    parser.add_argument("--mcp-token-file", type=Path, help="Read the scoped hub MCP token from a file")
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument("--hub-only", action="store_true", help="Install only the hub; do not configure or restart a local Hermes gateway")
    modes.add_argument("--mcp-only", action="store_true", help="Install/register only the adapter on the Hermes machine, without a hub or owner credentials")
    parser.add_argument("--register-hermes", action="store_true", help="Compatibility alias for the default complete same-host native installation")
    parser.add_argument("--no-start", action="store_true", help="Prepare private configuration and service files without starting/restarting services or checking readiness")
    args = parser.parse_args(argv)
    try:
        if args.hermes_url:
            args.hermes_url = endpoint(args.hermes_url, "hermes-url")
        if args.hub_url:
            args.hub_url = endpoint(args.hub_url, "hub-url")
        hermes_token = token_file(args.hermes_token_file) if args.hermes_token_file else None
        mcp_token = token_file(args.mcp_token_file) if args.mcp_token_file else None
    except ValueError as error:
        parser.error(str(error))
    full = not (args.hub_only or args.mcp_only)
    if args.register_hermes and not full:
        parser.error("register-hermes selects the complete native installation and cannot be combined with hub-only or mcp-only")
    if args.mcp_only and (not args.hub_url or args.public_origin or args.hermes_url or args.hermes_token_file):
        parser.error("mcp-only requires hub-url and cannot configure a hub service")
    if args.hub_url and not args.mcp_only:
        parser.error("hub-url configures a separate hub; use mcp-only on the Hermes machine")
    if args.mcp_token_file and args.hub_only:
        parser.error("mcp-token-file is not used in hub-only mode")
    if args.public_origin:
        parsed = urllib.parse.urlsplit(args.public_origin)
        if (parsed.scheme != "https" or not parsed.hostname or parsed.path not in ("", "/") or
                parsed.username or parsed.password or parsed.query or parsed.fragment):
            parser.error("public-origin must be a bare HTTPS origin")
        args.public_origin = args.public_origin.rstrip("/")
    state = args.state.expanduser().resolve()
    config_path, source = state / "hub.json", state / "service"
    hermes_home = Path(os.environ.get("HERMES_HOME", str(Path.home() / ".hermes"))).expanduser().resolve()
    env_path = hermes_home / ".env"
    skill_source = args.source / "hermes/skills/robotos/SKILL.md"
    packages = ("robotos_mcp",) if args.mcp_only else ("robotos_hub", "robotos_mcp")
    if any(not (args.source / package).is_dir() for package in packages):
        parser.error("The supplied source must contain the required robotOS service packages")
    if not args.mcp_only and sys.platform != "darwin" and not sys.platform.startswith("linux"):
        parser.error("Native user services support macOS and Linux; use Docker Compose on other hosts")
    hermes = hermes_executable() if not args.hub_only else None
    if not args.hub_only and (not hermes or not skill_source.is_file()):
        parser.error("Hermes executable and the bundled robotOS skill are required; install Hermes first or use hub-only")
    if full and not (hermes_home / "config.yaml").is_file():
        parser.error("Configure Hermes with hermes setup before installing the complete native stack")
    if not args.no_start and not args.mcp_only:
        manager = "launchctl" if sys.platform == "darwin" else "systemctl"
        if not shutil.which(manager):
            parser.error("Native installation requires " + manager + "; use Docker Compose or prepare files with no-start")
    try:
        environment = hermes_environment(env_path)
    except ValueError as error:
        parser.error(str(error))
    if args.mcp_only:
        mcp_token = mcp_token or environment.get("ROBOTOS_HUB_MCP_TOKEN", "")
        if len(mcp_token) < 32:
            parser.error("mcp-only requires a scoped mcp-token-file on first installation")
        try:
            environment_token(mcp_token, "The scoped hub MCP token", 32)
        except ValueError as error:
            parser.error(str(error))
        state.mkdir(parents=True, exist_ok=True, mode=0o700)
        os.chmod(state, 0o700)
        install_code(args.source.resolve(), source, packages=("robotos_mcp",))
        hermes = register_hermes(hermes_home, source, args.hub_url, mcp_token, skill_source)
        if not args.no_start:
            subprocess.run([hermes, "gateway", "restart"], check=True, stdout=subprocess.DEVNULL)
        print("robotOS MCP adapter installed: " + str(source))
        print("Hub endpoint: " + args.hub_url)
        print("Scoped MCP credential saved in the protected Hermes .env; no hub service or owner credentials created.")
        return 0
    existing = json.loads(config_path.read_text()) if config_path.exists() else {}
    config = configuration(existing, state, args.public_origin, environment)
    if args.hermes_url:
        config["hermes_url"] = args.hermes_url
    if hermes_token is not None:
        config["hermes_token"] = hermes_token
    if full and urllib.parse.urlsplit(config["public_origin"]).scheme != "https":
        parser.error("Complete native setup requires --public-origin https://HOST (or an existing HTTPS origin); configure its HTTPS proxy separately")
    if full and mcp_token is not None and mcp_token != config['mcp_token']:
        parser.error("The supplied MCP token does not match this hub; use mcp-only with the correct remote hub-url")
    api_settings = None
    if full:
        try:
            api_settings = local_hermes(config, existing, environment, hermes_token, bool(args.hermes_url))
            if int(api_settings["API_SERVER_PORT"]) == int(config["port"]):
                raise ValueError("Hermes and robotOS must use different local ports")
            if not config["mcp_token"]:
                config["mcp_token"] = secrets.token_urlsafe(36)
            environment_token(config["mcp_token"], "The scoped hub MCP token", 32)
        except ValueError as error:
            parser.error(str(error))
    if not args.no_start:
        try:
            preflight_service_manager(sys.platform)
        except ValueError as error:
            parser.error(str(error))
    state.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(state, 0o700)
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
    if api_settings:
        write_private(env_path, managed_environment(env_path.read_text() if env_path.exists() else "", api_settings))
    if sys.platform == "darwin":
        service_file = Path.home() / "Library/LaunchAgents" / (LABEL + ".plist")
        write_private(service_file, plistlib.dumps(launch_agent(sys.executable, source, config_path, state)).decode())
        if not args.no_start:
            start_launch_agent(service_file)
    elif sys.platform.startswith("linux"):
        service_file = Path(os.environ.get("XDG_CONFIG_HOME", str(Path.home() / ".config"))) / "systemd/user/robotos-hub.service"
        write_private(service_file, systemd_unit(sys.executable, source, config_path))
        if not args.no_start:
            subprocess.run(["systemctl", "--user", "daemon-reload"], check=True)
            subprocess.run(["systemctl", "--user", "enable", "--now", "robotos-hub.service"], check=True)
            subprocess.run(["systemctl", "--user", "restart", "robotos-hub.service"], check=True)
    else:
        raise SystemExit("User-service installation supports macOS and Linux; use python -m robotos_hub elsewhere.")
    if not args.no_start:
        wait_for_hub(config)
    if full:
        hermes = register_hermes(hermes_home, source, f"http://127.0.0.1:{config['port']}",
                                 mcp_token or config["mcp_token"], skill_source)
        if not args.no_start:
            start_hermes(hermes)
            wait_for_hermes(config)
    print("robotOS service files: " + str(source))
    print("Protected configuration: " + str(config_path))
    print("Owner password saved locally: " + str(state / "owner-password.txt"))
    print("Console origin: " + config["public_origin"])
    if args.no_start:
        print("Configuration prepared. Services were not started and readiness was not checked.")
    elif full:
        print("Native hub and authenticated Hermes Runs API are ready.")
    else:
        print("Hub is ready. The separate Hermes API and adapter were not configured or checked.")
    print("HTTPS routing and R1 enrollment are not performed or verified by this installer.")
    print("Route the exact console HTTPS origin to the hub, then sign in and pair each R1 with its own one-time code.")
    if sys.platform.startswith("linux"):
        print("For availability after logout and before login, enable user lingering with loginctl enable-linger USER.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
