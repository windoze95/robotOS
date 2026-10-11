#!/usr/bin/env bash
# Legacy direct-device setup (docs/HERMES_DIRECT.md). Shared workspaces use Docker Compose
# or tools/hub-setup.sh; they must never receive Hermes's API key on the R1.
#
#   tools/hermes-setup.sh --direct --host user@hermes-host [--owner +1XXXXXXXXXX]
#
# Needs: the R1 on USB running robotOS with its Tailscale app signed in to your tailnet; SSH access to
# the macOS host that runs Hermes Agent (with Homebrew's tailscale). Safe to re-run; keys are kept.
# --owner: your own phone number. Its texts to the R1 go to Hermes, which texts back.
set -euo pipefail
source "$(dirname "$0")/lib.sh"

HOST="" OWNER="" DIRECT=0
USAGE="Usage: tools/hermes-setup.sh --direct --host user@hermes-host [--owner +1XXXXXXXXXX]. For shared workspaces, see docs/DEPLOYMENT.md."
while [ $# -gt 0 ]; do
  case "$1" in
    --direct) DIRECT=1; shift ;;
    --host) [ $# -ge 2 ] || die "$USAGE"; HOST="$2"; shift 2 ;;
    --owner) [ $# -ge 2 ] || die "$USAGE"; OWNER="$2"; shift 2 ;;
    --help|-h) say "$USAGE"; exit 0 ;;
    *) die "$USAGE" ;;
  esac
done
[ "$DIRECT" = 1 ] || die "Direct setup requires --direct. Use the shared-workspace quickstart in docs/DEPLOYMENT.md."
[[ "$HOST" =~ ^[A-Za-z0-9_][A-Za-z0-9_.@:-]*$ ]] || die "Use a plain SSH host or user@host. $USAGE"
[[ -z "$OWNER" || "$OWNER" =~ ^\+[1-9][0-9]{6,14}$ ]] || die "--owner must use international form, like +14055550123"
need adb; need ssh; need rsync; need jq

say "Finding the R1's tailnet address"
# Tailscale's VPN interface is tun0 or tun1 depending on how often it has reconnected.
R1=$(adb shell ip -4 addr 2>/dev/null | awk '/^[0-9]+: tun/{tun=1; next} /^[0-9]+:/{tun=0} tun && /inet /{split($2, a, "/"); print a[1]; exit}' | tr -d '\r')
[ -n "$R1" ] || die "The R1 has no tailnet address. Open its Tailscale app, sign in to your tailnet, and allow the VPN."

say "Setting up Hermes on $HOST (approve any Tailscale link it prints)"
STAGE=".robotos-setup"
SETUP="PY=\$(command -v /opt/homebrew/bin/python3 || command -v python3); \"\$PY\" $STAGE/hermes/setup_macos.py --direct --source $STAGE --r1 $R1"
rsync -a --delete --exclude __pycache__ --exclude tests "$ROOT/server/robotos_mcp" "$ROOT/server/hermes" "$HOST:$STAGE/"
trap 'ssh "$HOST" "rm -rf $STAGE" >/dev/null 2>&1 || true' EXIT
ssh -t "$HOST" "$SETUP ${OWNER:+--owner $OWNER}"

say "Pointing the R1 at Hermes"
JSON=$(ssh "$HOST" 'cat "$HOME/Library/Application Support/robotOS/r1-hermes.json"')
URL=$(jq -r .agent.baseUrl <<<"$JSON")
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
case "${HEALTH:-}" in
  *'"ok"'*)
    # Once, while the R1 has no name for it: Hermes tells the R1 what the user calls it.
    say "Letting Hermes tell the R1 its name"
    ssh "$HOST" "$SETUP --introduce" || warn "Hermes didn't set a name; set one on the R1 under Hermes → Name."
    say "Done. Hold the button and talk." ;;
  *) warn "The R1 couldn't reach $URL yet. Check that its Tailscale app is connected, then run this again." ;; esac
