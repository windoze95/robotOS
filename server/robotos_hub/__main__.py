"""Run with python -m robotos_hub --config /private/hub.json."""
import argparse
import fcntl
import os
import signal
import sys
import threading
from .config import Config
from .http import HubServer


def main(argv=None):
    parser = argparse.ArgumentParser(description='robotOS workspace and device management host')
    parser.add_argument('--config', help='Private JSON configuration (chmod 600)')
    parser.add_argument('--check-config', action='store_true', help='Validate configuration without starting a listener')
    args = parser.parse_args(argv)
    os.umask(0o077)
    try:
        config = Config.load(args.config)
        if args.check_config:
            print('robotOS hub configuration is valid')
            return 0
        # One process owns each workspace. A second instance must not run startup recovery.
        lock_file = open(config.data_dir / '.service.lock', 'a+')
        try:
            fcntl.flock(lock_file.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise ValueError('Another robotOS hub process already owns this data directory') from None
        server = HubServer(config)
    except (OSError, ValueError, TypeError) as exc:
        print('robotOS hub configuration/startup failed: ' + str(exc), file=sys.stderr)
        return 2
    def stop(signum, frame):
        threading.Thread(target=server.shutdown, daemon=True).start()
    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    server.workers.start()
    print('robotOS hub listening on loopback port ' + str(config.port), flush=True)
    try:
        server.serve_forever()
    finally:
        server.workers.stop()
        server.server_close()
        lock_file.close()
    return 0


if __name__ == '__main__':
    sys.exit(main())
