# Voice reliability boundaries

These changes are independent implementation work in robotOS. They do not import JackRabbit's
PolyForm Noncommercial code or add its Python runtime, flash distribution, camera or tool system.
The Android/kernel/vendor, button remaps and existing power settings remain the current platform.

## Transport admission and termination

`net/SocketPump.kt` owns the ordered serialized queue for live transcription.
It includes the app's queued strings and OkHttp's reported send backlog in a 2 MiB budget.
Each message must fit the 256 KiB wire window. Audio admission leaves 4 KiB for control messages.
The session update is a checked send sharing that budget; PCM waits for the protocol's
`session.updated` acknowledgment. Release appends COMMIT after all accepted PCM, so a full wire
cannot send the end marker ahead of buffered audio.

A rejected send, budget overflow, 30-second unacknowledged handshake, 30 seconds without send
progress, or 30-second-old app queue entry ends that transport once. The age limit applies even
when the wire makes slow progress. A peer close before a complete dictation result is a failure,
including a clean WebSocket close. No failure path depends on a polite server goodbye. Watchdog
polling is active only during handshake or while input is queued; an idle acknowledged socket
has no queue watchdog task. Existing WebSocket keepalive pings remain.

The limits are conservative safeguards, not tuned or measured latency improvements. The app
reports a short error and asks for a fresh voice turn rather than reconnecting and replaying
an utterance whose server receipt is uncertain.

## Exchange and capture ownership

Each press owns its microphone capture, transcription connection and callbacks. A new press,
a double-tap or a failure cancels the previous turn's capture, network calls and speech, and an
interrupted or failed turn sends nothing to Hermes. Service destruction cancels app-owned work and
releases the wake lock; activity pause/recreation continues to leave the app-owned turn intact.

Mic callbacks must cross the capture gate. Release closes it before stopping AudioRecord; no
accepted append can follow the end command. Dictation records at most two minutes of PCM with
at most ten seconds of pre-roll, and stops when its capture/transport fails. Partial callbacks
from a superseded press cannot alter the next text field or turn. An interrupted/incomplete
recording is never automatically submitted.

Capture failures are latched under that same gate before a UI callback is posted: release refuses
to commit a faulted capture even if the callback has not run, and final transcripts cannot override
that fault. Errors from an intentionally stopped microphone after the release barrier are ignored.

The existing recording fallback remains available only after a complete local release; this can
duplicate transcription work if the remote commit
was accepted but its final result was lost, as before. Keyboard-open dictation still waits for a
deliberate send; keyboard-closed transcription mode hands a complete transcript to Hermes.

Transcription's text handoff to the main thread is capped at 128 KiB and 256 callbacks, and
provisional text is limited to 65,536 characters.
Oversized events fail before protocol decoding. These bounds cover app-owned queues; OkHttp
must assemble a received WebSocket message before the app can reject its size.

Spoken replies (text-to-speech) have bounded 32-sentence and 32-chunk queues with cancellable
backpressure.
Playback termination closes sentence admission, wakes a blocked fetch worker and cancels its call
before a late finish callback arrives. Normal finish drains already admitted sentences in order.
Socket termination is recorded under its monitor, then failure observers are called after releasing
that monitor, so a synchronous capture-fault latch cannot deadlock a simultaneous microphone send.

## Secure configuration persistence

A save must encrypt successfully using Android Keystore AES-GCM, sync the complete encrypted
temporary file, and atomically replace `files/config.enc` before publishing the new settings.
Encryption or file errors leave the prior value and ciphertext unchanged. There is no plaintext
fallback and no non-atomic rename fallback. Existing encrypted SharedPreferences are a read-only
migration source until the first successful new-file save. Filesystem crash durability beyond
the synced file and atomic rename has not been hardware tested.

Unreadable, corrupt, unsupported or missing-key existing data is preserved and locks saving.
Settings and the launcher show safe recovery guidance; retry loading must succeed before
unblocking writes. Existing keys are never regenerated while a committed blob exists. Legacy
`plain:` or raw JSON storage is rejected rather than silently rewritten. Recover secure storage
access first; if impossible, explicitly clear app data and re-import a trusted configuration.
Clearing app data also removes local conversation history. No automatic reset/migration is done.
Provisioning errors return fixed guidance instead of echoing input/crypto exception text.
These changes do not rotate credentials, inspect real keys or alter provider endpoint policy.

## Validation status

Unit tests exercise fake clocks/wires, admission limits, ordered release, stalled/expired queues,
flush during in-flight playback, superseded callback tokens, held-button/delegation deadlines,
and encrypted storage failure/recovery. Loopback tests exercise real OkHttp terminal behavior
using fixture data only. The acceptance analyzer has synthetic telemetry/battery fixtures and
mock ADB tests, without a device or provider connection.

Run `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug`
and `node --test tools/acceptance.test.mjs` for local checks. See [ACCEPTANCE.md](ACCEPTANCE.md)
for explicit-device read-only measurement, cold/warm cohorts, release/playback/interruption,
reconnect/service restart, and matched unplugged overnight standby. Hardware behavior, acoustic
latency, Keystore/filesystem behavior on the R1 and battery changes remain pending. No latency,
idle-drain, or operating-time improvement is established by these offline checks.
