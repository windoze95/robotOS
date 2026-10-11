"""Container probes use the local listener, never an upstream API or a credential."""
import json
import os
import sys
import urllib.request


def main():
    probe = sys.argv[1] if len(sys.argv) > 1 else 'health'
    if probe not in {'health', 'ready'}:
        return 2
    port = int(os.environ.get('ROBOTOS_HUB_PORT', '8787'))
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(f'http://127.0.0.1:{port}/api/v1/{probe}', timeout=3) as response:
            return 0 if response.status == 200 and json.load(response).get('status') == 'ok' else 1
    except Exception:
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
