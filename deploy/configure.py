#!/usr/bin/env python3
"""Create credentials for a new deployment; never replace existing credentials.

Files are readable inside Docker secret mounts. Their host directory is owner-only.
Existing Hermes and speech credentials are read from files, never command arguments.
"""
import argparse
import os
from pathlib import Path
import secrets
import stat
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'server'))
from robotos_hub.config import secret_file


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, required=True)
    parser.add_argument('--hermes-token-file', type=Path, help='Existing Hermes API key; omit only for a new Hermes gateway')
    parser.add_argument('--speech-token-file', type=Path)
    args = parser.parse_args(argv)
    folder = args.directory.expanduser().resolve()
    folder.mkdir(mode=0o700, parents=True, exist_ok=True)
    if stat.S_IMODE(folder.stat().st_mode) & 0o077:
        parser.error('Credential directory must be private (chmod 700)')
    values = {name: secrets.token_urlsafe(36) for name in ('owner-password', 'owner-token', 'mcp-token', 'hermes-token')}
    if args.hermes_token_file:
        values['hermes-token'] = secret_file(args.hermes_token_file)
    values['speech-token'] = secret_file(args.speech_token_file) if args.speech_token_file else ''
    for name, value in values.items():
        target = folder / name
        try:
            fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o400)
        except FileExistsError:
            # No accidental credential rotation, including when a symlink already exists.
            continue
        with os.fdopen(fd, 'w') as file:
            file.write(value + '\n')
            file.flush()
            os.fsync(file.fileno())
            os.fchmod(file.fileno(), 0o444)
    print('Credentials prepared in private directory: ' + str(folder))
    print('Existing files were retained. Sign in using the owner-password file locally.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
