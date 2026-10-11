# Reliability acceptance and measurement

The reliability changes have offline validation. Hardware acceptance is **pending**. Fixtures
under `tools/fixtures/acceptance/` are invented inputs for testing the analyzer; their durations
and battery levels are not R1 results. Historical observations in the README do not establish
acceptance of the current build.

## Evidence collection

Use an already installed, configured build on the intended R1. A human performs each workload.
The capture tool only verifies a selected device, reads structured telemetry, and optionally
reads the app's battery log. It does not install or flash, change settings, toggle radios, send
button events, restart services, clear logcat, read configuration/credentials, or call providers.

Use Node.js (the existing `/opt/homebrew/bin/node` works), Android platform-tools, and Bash.
No dependencies are installed. Check the serial and model yourself before capture:

```sh
adb devices -l
adb -s "$R1_SERIAL" shell getprop ro.product.model
tools/acceptance-capture.sh \
  --serial "$R1_SERIAL" --expect-model "$R1_MODEL" \
  --scenario voice_cold --config-label matched-baseline-a --build-label installed-build-id \
  --seconds 60 --out out/acceptance/cold-01
tools/acceptance-report.sh --run out/acceptance/cold-01
```

`--serial` and an exact `--expect-model` are required. The tool also checks that `dev.r1ptt`
is installed. An existing output directory is refused, preserving earlier evidence. The default
directory is a unique folder under the ignored `out/acceptance/`. Capture durations range from
1 to 3600 seconds; Ctrl-C preserves an incomplete run. An early ADB disconnect also marks the
run incomplete. Reports can be rerun offline and show whether capture completed.

`--config-label` and `--build-label` are short, manually assigned labels, never paths to config
files. Record the installed APK/build identity and these safe comparison conditions in a local
acceptance worksheet: Hermes model, transcription and speech models, Wi-Fi idle interval, cellular
enabled, screen timeout/brightness, output volume, OS/kernel build, access point placement,
ambient temperature, and starting battery range. Omit keys, URLs, session identifiers and
utterance/reply text. Use the same device and conditions for before/after comparisons. If any
condition differs, give it a new configuration label and keep that cohort separate.

The tool saves `run.json`, `telemetry.jsonl`, optional `battery.csv`, and the offline report
files `report.json`/`turns.csv`. Device serials are stored only as SHA-256 identifiers. ADB
stderr, raw logcat, transcripts, keys, endpoints and runtime config files are never saved.
The filter retains only approved INFO events from the `r1ptt` tag with integer fields. Unexpected
events/fields and pre-capture records are discarded. Avoid adding raw diagnostic captures to
the evidence folder. Review safe artifacts before sharing them.

The legacy `tools/wifi-wake-test.sh` deliberately toggles Wi-Fi, `tools/smoke-test.sh` reads
credentials and calls the real speech and Hermes services, and `tools/battery-report.sh` uses an implicit ADB target.
They are outside this read-only workflow. They were not run to produce acceptance evidence.

## Telemetry definitions

The app emits `metric event=<token> t_ms=<elapsedRealtime> turn=<integer>` with optional safe
integer counters. The capture parser adds the numeric PID from `logcat -v brief`. `connection`
identifies a session within one process; turn IDs are process-wide. `mode` is 1 for a transcribed
press (a voice turn for Hermes, or dictation; reported as `voice`) and 2 for typed turns; 0 appears
only in captures from the retired live speech-to-speech mode (reported as `live`). `warm`/`ready`
are 0 or 1; current builds open a fresh transcription connection per press, so `warm` is 0.
Service starts and clock regressions split lifetimes, so reused connection/turn IDs cannot borrow
timing from another service lifetime, PID or reboot. A cold `turn_start` may have `connection=0`
before allocation; the analyzer resolves it only if exactly one positive connection ID appears on
that same turn. Missing markers stay blank in CSV and `null` in JSON; they never count as zero
latency.

| Row/metric | Definition | Limit of the evidence |
|---|---|---|
| Cohort | `turn_start.mode`, split further by `warm` for old captures | Check the human workload agrees with the mode; mode cohorts are separate. |
| Connection | `connect_ready - connect_start` for the same connection and lifetime | Only transcribed presses open a connection; typed turns have none. |
| Readiness after turn start | `connect_ready - turn_start`, or zero if `turn_start.ready=1` | Negative or missing pairs remain pending. Socket open alone does not mean protocol readiness. |
| Queue age | Largest `input_end_sent.max_queue_ms` in that turn | Zero requires an explicit zero counter; missing counters are pending. `queued_bytes` records queued input at input end. |
| Release gate | `release_gate_closed - release` | Local input admission barrier, before stopping AudioRecord; inspect the release-order flag separately. This does not measure the hardware mic stopping. |
| Input end | `input_end_sent - release` | Confirms input end is sent after queued audio; this is not server acknowledgment. |
| Reply receipt | `first_reply - release` | First reply data received; distinct from playback. |
| First reply playback | `playback_started - release` | Emitted only by the retired live mode; replies read aloud by text-to-speech don't report it, so this stays pending. Speaker acoustic onset needs external measurement. |
| Local interruption | `playback_flushed - interrupt_start` | Time to complete local player flush; does not prove the speaker was silent at that instant. |
| Audible interruption | External acoustic/video measurement of physical press to silence | Always pending in the telemetry report. Do not replace it with local flush latency. |
| Network wait | `network_available - network_wait` for matching turn/connection | Starts when the app notices it needs network; physical outage onset is a human/external observation. |
| Reconnect | A new connection's `connect_start` to `connect_ready`, followed by a successful fresh turn | A session failure is retained; success on a later turn does not erase it. |
| Service restart | `service_stopped`/`service_started` plus an operator's action and successful next turn | An unclean kill may omit `service_stopped`; record that explicitly. |

Approved events are `turn_start`, `connect_start`, `socket_open`, `connect_ready`, `hold_start`,
`release`, `release_gate_closed`, `input_end_sent`, `first_reply`, `playback_started`,
`interrupt_start`, `playback_flushed`, `network_wait`, `network_available`, `session_closed`,
`session_failed`, `exchange_complete`, `service_started`, and `service_stopped`. Approved
optional fields are `mode`, `warm`, `ready`, `connection`, `queued_bytes`, and `max_queue_ms`.

Reports include per-turn rows and nearest-rank p50/p95/max with the count of measured values.
The denominator includes failed turns; latency percentiles include only actual paired markers.
Check both counts. Use at least 20 ordinary turns per latency cohort and record all failures;
small samples are useful diagnostics but do not establish a stable p95.

## Manual workloads and release gates

Run each scenario in its own evidence folder. Select the corresponding `--scenario` value.
Keep questions short and equivalent in duration; record only duration/category and outcome,
not their text. Hardware/OS radio or service actions below are performed by the operator under
their own device authorization; the collector never performs them.

| Scenario | Repeatable human workload | Acceptance evidence |
|---|---|---|
| `voice_cold` | From screen off, hold, speak for a consistent 2–3 seconds, release, let Hermes's reply finish. Repeat 20 times. | New connection/readiness/queue age rows; release gate closes; no reply playback before release; all expected replies complete. Separate Wi-Fi already available from wake after the configured Wi-Fi idle cutoff. |
| `dictation` | Open keyboard manually. Hold and release 20 times, checking partial/final text is preserved and nothing is submitted until a deliberate send. | Dictation cohort kept separate; release gate/queue metrics; operator records final-text correctness and sends exactly once. Typed paths may have fewer markers and remain pending where absent. |
| `interruption` | While a reply is audibly playing, press/hold again; repeat during receipt and during playback, including quick tap and rapid successive holds. Use at least 10 audible interruptions. | Local flush latency; old queued/callback audio does not reappear; new turn works. External microphone/video provides physical-press-to-silence latency and detects residual sound. No acoustic result is inferred from telemetry. |
| `network_loss` | During connection, recording, and reply, manually make the access point unavailable, then restore it. Try a fresh hold after restoration. Repeat each phase three times. | Failure/no-network outcome is visible, no unbounded/stale audio replay; new connection becomes ready and a fresh turn completes. Record outage/restoration times externally; report network-wait and reconnect times separately. |
| `service_restart` | Manually stop/restart the app's service using the operator's established device workflow; test idle and active-turn cases three times each, then make a fresh turn. | Service start evidence or explicit missing unclean-stop marker; exactly one active listener/turn path; fresh connection succeeds; no old reply/input resumes. Collector records no restart action. |
| `standby` | Use the overnight procedure below. | Eight-hour unplugged periods, matched configuration and device; wake/first turn works; measured idle drain versus the stated battery targets. |

Required behavioral gates are: no playback before release, gate/mic closure on release,
bounded input queues with surfaced failures instead of stale replay, cancellation/flush on a
new press, and a successful fresh turn after restored network or service restart. Investigate
any reported `playback_before_release=true`; a missing release marker is pending evidence.
Record observed failures and missing metrics as failures/pending checks, never as passes.
Latency distributions are measurements, not an automatic pass decision. Set numeric latency
budgets in the worksheet before comparing runs; no universal provider/network latency budget
or acoustic acceptance is asserted here.

The implementation bounds serialized input across app and OkHttp queues to 2 MiB, with a
256 KiB send window and 4 KiB reserved control admission. Handshake/no-send-progress deadlines
are 30 seconds; the oldest app-queued input also expires after 30 seconds even if some sends progress.
Check failure behavior at these bounds manually; the collector does not synthesize that workload.
A failed voice transport is not replayed automatically.

## Overnight unplugged standby

1. Record the safe comparison conditions above, charge to a matched starting range, and make
   one normal turn. Disconnect USB/charging, let the screen turn off naturally, and leave the
   device idle for at least eight hours. Do not keep ADB connected during standby or poll it.
2. While still unplugged, wake it normally so `screen_on` records the unplugged endpoint.
   Note battery/wake/first-turn outcome. Only then reconnect USB to retrieve the log.
3. Capture a snapshot on the explicitly verified target:

   ```sh
   tools/acceptance-capture.sh \
     --serial "$R1_SERIAL" --expect-model "$R1_MODEL" \
     --scenario standby --config-label matched-baseline-a --build-label installed-build-id \
     --seconds 1 --with-battery --out out/acceptance/standby-01
   tools/acceptance-report.sh --run out/acceptance/standby-01 --min-hours 8 --attest-unplugged
   ```

   `--with-battery` reads only `/data/data/dev.r1ptt/files/battery.csv` through existing root.
   It does not request root installation or change device settings. A failed root read leaves
   the telemetry run available and reports the battery collection failure.
4. Use `--attest-unplugged` only if you observed continuous unplugged operation. Endpoint
   `plugged=false` values alone cannot rule out charging between samples. Without this flag,
   the report labels intervals as candidates. Only adjacent screen-off/on brackets with valid
   clocks/levels, no charging endpoints, and the minimum duration qualify. Safe `service_start`
   battery rows are preserved and break adjacency, so no idle interval spans a service restart.
   Reboots, clock
   changes, level increases and malformed rows are excluded; no full-battery-life extrapolation
   is made from whole-percent samples. Zero drop means below the observation's resolution.
5. Repeat at least three nights for baseline and candidate, using the same safe configuration
   label and matching device hash. Report each night plus total drop divided by total eligible
   hours. Whole-percent readings are noisy; an eight-hour period has roughly 0.125 percentage
   points/hour granularity. USB latency sessions do not count as standby evidence.

The initial battery targets remain `<0.5 %/h` with the default idle cut and `<1.5 %/h` with
Wi-Fi always on. These are targets, not measured results. Compare the candidate against a
matched baseline as well as the target; a configuration change starts a separate comparison.

## Results worksheet

| Check | Required evidence | Current result |
|---|---|---|
| Connection, readiness, queue age | 20 turns/cohort and failure counts | Pending hardware |
| Release gate | Per-turn rows plus human release observation | Pending hardware |
| Local interruption flush | 10 audible interruptions plus stale-audio check | Pending hardware |
| Acoustic reply onset/interruption silence | External recording/time reference | Pending acoustic test |
| Loss/reconnect and successful next turn | Three repetitions at each phase | Pending hardware |
| Service restart and successful next turn | Three idle and three active restarts | Pending hardware |
| Overnight matched standby | Three unplugged nights per matched build/config | Pending hardware |
| Analyzer/capture safety | Offline fixtures and mock ADB | Run `node --test tools/acceptance.test.mjs` |

All implementation here is independent repository work. No external PolyForm-licensed
implementation is used by the measurement tools.
