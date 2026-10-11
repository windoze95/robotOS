"""Protected host-only configuration; no device receives upstream credentials."""
from dataclasses import dataclass, field
from pathlib import Path
import json
import os
import stat
from urllib.parse import urlsplit


def safe_endpoint(value):
    if not value:
        return ''
    parsed = urlsplit(value)
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError('Endpoint must not contain userinfo, query, or fragment')
    if parsed.scheme not in ('https', 'http') or not parsed.hostname:
        raise ValueError('Endpoint must use HTTPS or loopback HTTP')
    if parsed.scheme == 'http' and parsed.hostname not in ('localhost', '127.0.0.1', '::1'):
        raise ValueError('Remote endpoint must use HTTPS')
    return value.rstrip('/')


@dataclass
class Config:
    data_dir: Path
    owner_password: str
    bind: str = '127.0.0.1'
    port: int = 8788
    owner_token: str = ''
    mcp_token: str = ''
    mcp_allowed_commands: tuple = ('ring', 'stop_ring', 'show_message')
    public_origin: str = ''
    hermes_url: str = ''
    hermes_token: str = ''
    hermes_model: str = ''
    hermes_provider: str = ''
    hermes_instructions: str = ''
    speech_url: str = ''
    speech_token: str = ''
    speech_model: str = 'whisper-1'
    max_audio_bytes: int = 32 * 1024 * 1024
    max_json_bytes: int = 1024 * 1024
    lease_seconds: int = 90
    session_seconds: int = 12 * 3600
    recent_auth_seconds: int = 300
    worker_interval: float = 2.0

    def __post_init__(self):
        self.data_dir = Path(self.data_dir).expanduser().resolve()
        if len(self.owner_password) < 12:
            raise ValueError('Owner password must contain at least 12 characters')
        if self.bind not in ('127.0.0.1', '::1', 'localhost'):
            raise ValueError('Bind must be loopback; expose the service through tailnet HTTPS')
        for name in ('owner_token', 'mcp_token'):
            value = getattr(self, name)
            if value and len(value) < 32:
                raise ValueError(name + ' must contain at least 32 characters')
        if self.owner_token and self.owner_token == self.mcp_token:
            raise ValueError('Owner and MCP credentials must be different')
        self.hermes_url = safe_endpoint(self.hermes_url)
        self.speech_url = safe_endpoint(self.speech_url)
        self.public_origin = safe_endpoint(self.public_origin)
        if self.public_origin and urlsplit(self.public_origin).path not in ('', '/'):
            raise ValueError('Public origin must not include a path')
        self.mcp_allowed_commands = tuple(self.mcp_allowed_commands)
        if not set(self.mcp_allowed_commands) <= {'ring', 'stop_ring', 'show_message'}:
            raise ValueError('MCP management authority only supports ring, stop_ring, show_message')
        self.data_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
        if stat.S_IMODE(self.data_dir.stat().st_mode) & 0o077:
            raise ValueError('Data directory must be private (chmod 700)')

    @classmethod
    def load(cls, path=None):
        values = {}
        if path:
            file = Path(path).expanduser()
            if file.is_symlink() or stat.S_IMODE(file.stat().st_mode) & 0o077:
                raise ValueError('Configuration file must be private (chmod 600) and not a symlink')
            values = json.loads(file.read_text())
        fields = cls.__dataclass_fields__
        for key in fields:
            env = os.environ.get('ROBOTOS_HUB_' + key.upper())
            if env is not None:
                if key in ('port', 'max_audio_bytes', 'max_json_bytes', 'lease_seconds', 'session_seconds', 'recent_auth_seconds'):
                    env = int(env)
                elif key == 'worker_interval':
                    env = float(env)
                elif key == 'mcp_allowed_commands':
                    env = tuple(x.strip() for x in env.split(',') if x.strip())
                values[key] = env
        values.setdefault('data_dir', str(Path.home() / '.local/share/robotos-hub'))
        return cls(**values)
