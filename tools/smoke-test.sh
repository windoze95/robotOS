#!/usr/bin/env bash
# Checks the services from this computer with the same requests the R1 makes, before any flashing:
# speech-to-text, a streamed Hermes reply, two-turn memory, and text-to-speech.
#
# Usage: tools/smoke-test.sh [config.json] [--play]     (default config: tools/r1ptt.json)
# Needs: curl, jq; on macOS it records a test question with `say`. Direct Hermes checks are legacy
# and only run with an explicit agent.baseUrl; test paired runs through the workspace console.
set -euo pipefail
source "$(dirname "$0")/lib.sh"
CFG="$ROOT/tools/r1ptt.json"
CONFIG_GIVEN=0
PLAY=0
for a in "$@"; do
  case "$a" in
    --play) PLAY=1 ;;
    --help|-h) say "Usage: tools/smoke-test.sh [config.json] [--play]"; exit 0 ;;
    --*) die "Unknown option: $a" ;;
    *) [ "$CONFIG_GIVEN" = 0 ] || die "Specify only one config file"; CFG="$a"; CONFIG_GIVEN=1 ;;
  esac
done
[ -f "$CFG" ] || die "No config at $CFG (copy tools/r1ptt.example.json and fill in your keys)."
need curl
need jq
mkdir -p "$OUT"

# Merge the example's defaults under the real config, like the app does.
DEFAULTS='{"agent":{"model":"hermes-agent"},
  "stt":{"baseUrl":"https://api.openai.com/v1","model":"gpt-transcribe"},
  "tts":{"baseUrl":"https://api.openai.com/v1","model":"gpt-4o-mini-tts-2025-12-15","voice":"marin","enabled":true}}'
C=$(jq -s '.[0] * .[1]' <(echo "$DEFAULTS") "$CFG")
cfg() { jq -r "$1 // empty" <<<"$C"; }

host() { sed -E 's#^[a-z]+://([^/:]+).*#\1#' <<<"$1"; }
# Same rule as the app: a speech endpoint without its own key uses openaiKey on api.openai.com.
key_for() { # key_for <url> <own key>
  if [ -n "$2" ]; then echo "$2"; elif [ "$(host "$1")" = api.openai.com ]; then cfg .openaiKey; fi
}

STT_URL=$(cfg .stt.baseUrl)
say "1. Speech-to-text ($(cfg .stt.model) at $(host "$STT_URL"))"
if command -v say >/dev/null && command -v afconvert >/dev/null; then
  say "   recording a test question with macOS speech"
  command say -o "$OUT/sample.aiff" "What is the capital of France?"
  afconvert -f m4af -d aac@16000 -c 1 "$OUT/sample.aiff" "$OUT/sample.m4a"
fi
[ -f "$OUT/sample.m4a" ] || die "No $OUT/sample.m4a to transcribe."
HEARD=$(curl -sS --fail-with-body "$STT_URL/audio/transcriptions" -H "Authorization: Bearer $(key_for "$STT_URL" "$(cfg .stt.apiKey)")" \
  -F model="$(cfg .stt.model)" -F response_format=json -F file=@"$OUT/sample.m4a" | jq -r .text)
echo "   heard: $HEARD"

AGENT=$(cfg .agent.baseUrl)
hermes() { # hermes <session id> <message> → prints the streamed reply
  local body
  body=$(jq -n --arg m "$(cfg .agent.model)" --arg t "$2" \
    '{model:$m, stream:true, messages:[{role:"system",content:"Be brief: one short sentence, plain text."},{role:"user",content:$t}]}')
  curl -sSN --fail-with-body "$AGENT/chat/completions" -H "Authorization: Bearer $(cfg .agent.apiKey)" \
    -H 'Content-Type: application/json' -H "X-Hermes-Session-Id: r1ptt-$1" -d "$body" |
    sed -n 's/^data: //p' | grep -v '^\[DONE\]' | jq -rj '.choices[0].delta.content // empty' 2>/dev/null
  echo
}
if [ -n "$AGENT" ] && curl -s -m 5 -o /dev/null "${AGENT%/v1}/health"; then
  say "2. Hermes reply ($(host "$AGENT"))"
  printf '   reply: '
  hermes "smoke$$" "$HEARD"
  say "3. Hermes memory"
  SID="mem$$$RANDOM"
  hermes "$SID" "My name is Sam. Just say ok." >/dev/null
  printf '   reply: '
  hermes "$SID" "What is my name?"
  echo "   (the reply should mention Sam)"
else
  if [ -z "$AGENT" ]; then say "2–3. Shared workspace: verify a prompt run through the hub console after enrollment."
  else warn "2–3. Skipped legacy direct Hermes: $AGENT is not reachable from here (see docs/HERMES_DIRECT.md)."; fi
fi

TTS_URL=$(cfg .tts.baseUrl)
say "4. Text-to-speech ($(cfg .tts.model), voice $(cfg .tts.voice))"
curl -sS --fail-with-body "$TTS_URL/audio/speech" -H "Authorization: Bearer $(key_for "$TTS_URL" "$(cfg .tts.apiKey)")" \
  -H 'Content-Type: application/json' \
  -d "$(jq -n --arg m "$(cfg .tts.model)" --arg v "$(cfg .tts.voice)" '{model:$m, voice:$v, input:"Paris is the capital of France.", response_format:"pcm"}')" \
  -o "$OUT/tts.pcm"
BYTES=$(wc -c < "$OUT/tts.pcm" | tr -d ' ')
echo "   got $BYTES bytes of 24 kHz PCM (~$((BYTES / 48000)) s of speech)"
if [ "$PLAY" = 1 ] && command -v afplay >/dev/null && command -v node >/dev/null; then
  node -e '
    const fs = require("fs"); const pcm = fs.readFileSync(process.argv[1]); const h = Buffer.alloc(44);
    h.write("RIFF",0); h.writeUInt32LE(36+pcm.length,4); h.write("WAVEfmt ",8); h.writeUInt32LE(16,16);
    h.writeUInt16LE(1,20); h.writeUInt16LE(1,22); h.writeUInt32LE(24000,24); h.writeUInt32LE(48000,28);
    h.writeUInt16LE(2,32); h.writeUInt16LE(16,34); h.write("data",36); h.writeUInt32LE(pcm.length,40);
    fs.writeFileSync(process.argv[2], Buffer.concat([h, pcm]));' "$OUT/tts.pcm" "$OUT/tts.wav"
  afplay "$OUT/tts.wav"
fi
say "All checks ran."
