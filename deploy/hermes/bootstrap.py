"""Prepare the scoped robotOS adapter, then run Hermes as an unprivileged process.

Only the robotos MCP stanza and its skill are managed. Existing model/provider and
other MCP configuration is retained. Credentials are never written into config.yaml.
"""
import os
from pathlib import Path
import shutil
import stat
import sys
import tempfile
import urllib.parse

sys.path.insert(0, '/opt/robotos')
try:
    import hermes_yaml as yaml
except ImportError:  # Compatible with earlier official images using PyYAML.
    import yaml


def read_secret(path):
    import stat
    fd = os.open(path, os.O_RDONLY | os.O_NONBLOCK)
    with os.fdopen(fd, 'rb') as file:
        info = os.fstat(file.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_mode & 0o022:
            raise ValueError('Secret must be regular and not group/world writable')
        value = file.read(16385).decode().rstrip('\r\n')
    if not value or len(value) > 16384 or any(c in value for c in '\n\r\0'):
        raise ValueError('Invalid secret file')
    return value


def configure(home, source=Path('/opt/robotos')):
    url = os.environ.get('ROBOTOS_HUB_URL', '')
    parsed = urllib.parse.urlsplit(url)
    if (parsed.scheme != 'https' or not parsed.hostname or parsed.username or parsed.password
            or parsed.path not in ('', '/') or parsed.query or parsed.fragment):
        raise ValueError('ROBOTOS_HUB_URL must be an exact HTTPS origin')
    token_file = os.environ.get('ROBOTOS_HUB_MCP_TOKEN_FILE', '/run/secrets/mcp-token')
    if len(read_secret(token_file)) < 32:
        raise ValueError('MCP token must contain at least 32 characters')
    api_file = os.environ.get('API_SERVER_KEY_FILE', '/run/secrets/hermes-token')
    if os.environ.get('API_SERVER_KEY'):
        raise ValueError('Use API_SERVER_KEY_FILE, not both key and key file')
    os.environ['API_SERVER_KEY'] = read_secret(api_file)
    home.mkdir(mode=0o700, parents=True, exist_ok=True)
    if stat.S_IMODE(home.stat().st_mode) & 0o077:
        raise ValueError('Hermes state directory must be private (chmod 700)')
    # Hermes deliberately reloads profile .env with override=True. Retired native
    # API keys/listeners there must not shadow the container's mounted credential
    # and managed listener. Remove only these managed assignments, preserving
    # provider keys and all other profile configuration. The key stays in memory.
    env_file = home / '.env'
    if env_file.exists():
        if env_file.is_symlink():
            raise ValueError('Hermes profile .env must not be a symlink')
        from dotenv import dotenv_values, unset_key
        previous = dotenv_values(env_file)
        for name in ('API_SERVER_KEY', 'API_SERVER_HOST', 'API_SERVER_PORT', 'API_SERVER_ENABLED'):
            if name in previous:
                unset_key(env_file, name)
        env_file.chmod(0o600)
    config_path = home / 'config.yaml'
    config = yaml.safe_load(config_path.read_text()) if config_path.exists() else {}
    config = config or {}
    if not isinstance(config, dict) or not isinstance(config.get('mcp_servers', {}), dict):
        raise ValueError('Hermes configuration must contain a mapping')
    entry = {'command': sys.executable, 'args': ['-m', 'robotos_mcp'], 'env': {
        'PYTHONPATH': str(source), 'ROBOTOS_HUB_URL': url.rstrip('/'),
        'ROBOTOS_HUB_MCP_TOKEN': '', 'ROBOTOS_HUB_MCP_TOKEN_FILE': token_file},
        'timeout': 90, 'connect_timeout': 30, 'enabled': True}
    # Hermes supports env references; preserve a custom CA path for the child too.
    if os.environ.get('SSL_CERT_FILE'):
        entry['env']['SSL_CERT_FILE'] = os.environ['SSL_CERT_FILE']
    config.setdefault('mcp_servers', {})['robotos'] = entry
    fd, temporary = tempfile.mkstemp(prefix='.robotos-', dir=home)
    try:
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, 'w') as file:
            yaml.safe_dump(config, file, sort_keys=False)
            file.flush()
            os.fsync(file.fileno())
        os.replace(temporary, config_path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
    skill = home / 'skills' / 'robotos'
    skill.mkdir(mode=0o700, parents=True, exist_ok=True)
    shutil.copyfile(source / 'skills' / 'robotos' / 'SKILL.md', skill / 'SKILL.md')


def main():
    os.umask(0o077)
    try:
        configure(Path(os.environ.get('HERMES_HOME', '/opt/data/hermes')))
    except Exception:
        print('robotOS Hermes initialization failed; check mounted secrets, HTTPS origin and writable Hermes data.', file=sys.stderr)
        return 2
    command = sys.argv[1:] or ['gateway', 'run']
    os.execv(sys.executable, [sys.executable, '-m', 'hermes_cli.main', *command])


if __name__ == '__main__':
    raise SystemExit(main())
