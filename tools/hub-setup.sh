#!/usr/bin/env bash
# Install the native hub plus its local Hermes connection. --hub-only installs only
# the hub; --mcp-only installs the adapter on a separate Hermes host.
# No device changes or public ingress configuration.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [ -x /opt/homebrew/bin/python3.13 ]; then
  HUB_PYTHON=/opt/homebrew/bin/python3.13
elif [ -x /opt/homebrew/bin/python3 ]; then
  HUB_PYTHON=/opt/homebrew/bin/python3
else
  HUB_PYTHON=python3
fi
PYTHONPATH="$ROOT/server${PYTHONPATH:+:$PYTHONPATH}" "$HUB_PYTHON" -m hermes.setup_hub --source "$ROOT/server" "$@"
