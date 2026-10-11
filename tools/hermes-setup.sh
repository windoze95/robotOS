#!/usr/bin/env bash
# Makes Hermes the agent behind the R1, from this Mac, in one command (docs/HERMES.md):
#
#   tools/hermes-setup.sh --host user@hermes-host [--owner +1XXXXXXXXXX]
#
# Needs: the R1 on USB running robotOS with its Tailscale app signed in to your tailnet; SSH access to
# the macOS host that runs Hermes Agent (with Homebrew's tailscale). Safe to re-run; keys are kept.
# --owner: your own phone number. Its texts to the R1 go to Hermes, which texts back.
set -euo pipefail
source "$(dirname "$0")/lib.sh"

HOST="" OWNER=""
while [ $# -gt 0 ]; do
  case "$1" in
    --host) HOST="$2"; shift 2 ;;
    --owner) OWNER="$2"; shift 2 ;;
    *) die "Usage: tools/hermes-setup.sh --host user@hermes-host [--owner +1XXXXXXXXXX]" ;;
  esac
done
[ -n "$HOST" ] || die "Usage: tools/hermes-setup.sh --host user@hermes-host [--owner +1XXXXXXXXXX]"
need adb; need ssh; need rsync; need jq

say "Finding the R1's tailnet address"
R1=$(adb shell ip -4 addr show tun0 2>/dev/null | awk '/inet /{split($2, a, "/"); print a[1]}' | tr -d '\r')
[ -n "$R1" ] || die "The R1 has no tailnet address. Open its Tailscale app, sign in to your tailnet, and allow the VPN."

say "Setting up Hermes on $HOST (approve any Tailscale link it prints)"
STAGE=".robotos-setup"
rsync -a --delete --exclude __pycache__ --exclude tests "$ROOT/server/robotos_mcp" "$ROOT/server/hermes" "$HOST:$STAGE/"
ssh -t "$HOST" "PY=\$(command -v /opt/homebrew/bin/python3 || command -v python3); \"\$PY\" $STAGE/hermes/setup_macos.py --source $STAGE --r1 $R1 ${OWNER:+--owner $OWNER}; rc=\$?; rm -rf $STAGE; exit \$rc"

say "Pointing the R1 at Hermes"
JSON=$(ssh "$HOST" 'cat "$HOME/Library/Application Support/robotOS/r1-hermes.json"')
URL=$(jq -r .providers.hermes.baseUrl <<<"$JSON")
B64=$(printf '%s' "$JSON" | base64 | tr -d '\n')
unset JSON
RESULT=$(adb shell am broadcast -n dev.r1ptt/.data.ConfigReceiver -a dev.r1ptt.CONFIG --es json_b64 "$B64" | tr -d '\r')
unset B64
case "$RESULT" in *'data="ok"'*) ;; *) die "The R1 rejected the settings: $(sed -n 's/.*data="\([^"]*\)".*/\1/p' <<<"$RESULT")" ;; esac
adb shell am start -n dev.r1ptt/.HomeActivity >/dev/null 2>&1

say "Checking the R1 can reach Hermes"
for _ in 1 2 3 4 5 6; do
  HEALTH=$(adb shell curl -s -m 10 "${URL%/v1}/health" | tr -d '\r') && [ -n "$HEALTH" ] && break
  sleep 5
done
case "${HEALTH:-}" in *'"ok"'*) say "Done. Hold the button and ask Hermes something." ;;
  *) warn "The R1 couldn't reach $URL yet. Check that its Tailscale app is connected, then run this again." ;; esac
