# robotOS: a push-to-talk remote for Hermes on the Rabbit R1

Turns a Rabbit R1 into a push-to-talk remote for your [Hermes Agent](https://github.com/NousResearch/hermes-agent).
Hermes is the agent; the R1 listens, speaks, and acts only when Hermes asks. Hold the side button and
talk; on release, it either sends what you said or types it into the text field:

- **Keyboard closed:** your words go to Hermes. They're transcribed as you speak
  (`gpt-live-transcribe`), sent when you let go, and Hermes's reply is read aloud (`gpt-4o-mini-tts`)
  and shown on screen.
- **Keyboard open:** dictation. Your words appear in the text field as you speak, dimmed until you let
  go, and nothing is sent, so you can edit first. A tap of the button sends it to Hermes.

Hermes acts on the R1 through its `robotos` MCP tools: "text Sam I'm running late" is Hermes texting
from the R1's SIM. When enabled, texts from your own number to the R1 go to Hermes, which texts back.
Separate permissions allow announcements, Home notices, volume changes and saved recipients.
Plugged in, configured radios stay available. OpenAI is used only for speech. See
[docs/HERMES.md](docs/HERMES.md).

## Screenshots

The app at the R1's 480×640 screen size, rendered with staged demo data.

| Push to talk | Live transcription | Hermes reply |
|:---:|:---:|:---:|
| [<img src="docs/screenshots/home.png" width="240" alt="Home screen with hold-to-talk instructions">](docs/screenshots/home.png) | [<img src="docs/screenshots/listening.png" width="240" alt="Live speech transcription on the orange listening screen">](docs/screenshots/listening.png) | [<img src="docs/screenshots/conversation.png" width="240" alt="Example conversation asking Hermes to text Sam">](docs/screenshots/conversation.png) |
| **Menu** | **Messages** | **Quick controls** |
| [<img src="docs/screenshots/menu.png" width="240" alt="Menu with chats, messages, Hermes and settings">](docs/screenshots/menu.png) | [<img src="docs/screenshots/messages.png" width="240" alt="SMS inbox with fictional recipients and messages">](docs/screenshots/messages.png) | [<img src="docs/screenshots/quick-controls.png" width="240" alt="Quiet mode, spoken replies and reachability controls">](docs/screenshots/quick-controls.png) |

[All screenshots and capture instructions](docs/screenshots/README.md)

robotOS was previously called R1 PTT. Its repository is
[windoze95/robotOS](https://github.com/windoze95/robotOS). The Android package (`dev.r1ptt`),
Magisk module ID (`r1ptt_system`), and existing configuration and build filenames retain their
compatibility names.

> **Status: running on a real R1** (LineageOS 21 on Rabbit's September 2026 kernel). The side button,
> live dictation (final text ~0.4 s after release), push-to-talk and typed turns to Hermes, Hermes
> texting through the R1, and texting Hermes are verified on the device. Standby battery drain hasn't
> been measured ([docs/BATTERY.md](docs/BATTERY.md)).
>
> The current reliability changes add offline-tested transport/capture limits, interruption isolation and
> encrypted-save recovery. Its current-build hardware acceptance is pending; the observations above
> describe the earlier device-tested implementation. See [docs/RELIABILITY.md](docs/RELIABILITY.md).
>
> The daily-driver redesign adds saved chats, contextual wheel navigation, quick controls and
> recoverable Hermes task controls. Local automated coverage is in place; its physical UI,
> acoustic, carrier and overnight battery acceptance remain pending. See
> [docs/DAILY_DRIVER.md](docs/DAILY_DRIVER.md).

## What "firmware" means here

The R1 is an Android 13 phone underneath (MediaTek MT6765). The firmware is four layers:

| Layer | What |
|---|---|
| Kernel + vendor | Rabbit's own, kept from the R1's last OTA (the side button only works with Rabbit's kernel). |
| System | LineageOS 21 (Android 14) GSI, with Rabbit's apps removed and the bloat disabled. |
| Root + tweaks | Magisk, plus the `r1ptt-system` module: button and wheel remaps, Rabbit's USB keyboard/mouse mode off, no scanning/location/Bluetooth, faster Doze. |
| App | robotOS (`dev.r1ptt`): the launcher, push-to-talk service, speech, chat and power policy. |

## Using it

| Button | Keyboard closed | Keyboard open |
|---|---|---|
| **Hold** | Talk; release to send. The reply streams and is spoken. | Dictate; release to insert at the cursor. |
| **Tap** | Screen off. When speaking, stop. (A tap on a dark screen just wakes it.) | Send the typed text. |
| **Double-tap** | New conversation. | — |
| **Scroll wheel** | Scroll the visible page; volume during speech or with its slider selected. | Scroll the visible page; volume during speech or with its slider selected. |

Tap the text field to bring up the keyboard. The chat title opens saved **Chats**, the battery
readout opens **Quick controls**, and the menu opens **Messages**, **Hermes** and **Settings**.
While an approval review is open, button gestures cannot approve it or replace the pending task.

**Menu → Messages** opens the one-to-one SMS handler for texts you write yourself: drafts,
**Review text** and **Send SMS**, incoming texts, and Android's real send and delivery status. The
R1 never decides to send a text on its own; with Hermes, Hermes does (above). No SMS permission is
requested merely by opening Messages; Options can request Android's default SMS role. MMS is
explicitly unsupported; incoming MMS notices are retained locally and shown as warnings. Message
details exposes native failure codes without retrying a failed or uncertain attempt.
MMS, RCS, old inbox import, and live carrier acceptance are not included. See [docs/MESSAGES.md](docs/MESSAGES.md).

**Hermes** shows connection checks, saved task status, approvals, model selection, device permissions
and the shared daily SMS count. **Stop speech** stops audio; **Stop task** requests cancellation
on a compatible Hermes host. See [docs/DAILY_DRIVER.md](docs/DAILY_DRIVER.md) for controls and recovery.

## Battery

Idle time is where a 1000 mAh battery goes.

- **The cellular modem is off by default** (airplane mode with Wi-Fi left on). With **Use cellular
  data (SIM)** enabled, it wakes with the device and returns to airplane mode at the normal idle cut.
- **Wi-Fi switches off** after 3 minutes with the screen dark. The next button press turns it back
  on, and it reconnects while you're still talking.
- **Plugged in, configured radios stay available.** On external power the idle cut is skipped.
  **Stay reachable** extends that policy to battery operation; switching it off restores the saved
  pocket-mode delay. Reachability still depends on coverage, connectivity and Tailscale.
- **No continuous background polling in pocket mode:**
  - no polling, scanning, location or Bluetooth;
  - Rabbit's apps are removed;
  - Doze starts within seconds.
  - Optional incoming SMS use Android broadcasts while the modem is reachable. Messages does not
    keep it awake; carrier-queued delivery after wake is not guaranteed.
- **The screen is held on while listening.** It can time out during remote work, playback or an
  approval wait; the default timeout is 15 s at low brightness.
- **The network is used only when there's work:** a turn (voice streams only while you hold the
  button), a text to Hermes, or a request from Hermes.

[docs/BATTERY.md](docs/BATTERY.md) covers the design and how to measure the idle drain.
[docs/ACCEPTANCE.md](docs/ACCEPTANCE.md) defines current-build latency, interruption, reconnect,
service-restart and overnight standby checks. Hardware and acoustic acceptance remain pending;
the read-only capture and offline analyzer include synthetic fixtures, not hardware results.

## Getting started

1. **Check speech from your Mac first:**
   ```sh
   cp tools/r1ptt.example.json tools/r1ptt.json   # set openaiKey
   tools/smoke-test.sh --play
   ```
2. **Follow [docs/INSTALL.md](docs/INSTALL.md):** unlock (Rabbit's developer mode) → stock baseline →
   LineageOS GSI → Magisk → `tools/provision.sh --config tools/r1ptt.json`.
3. **Connect Hermes:** `tools/hermes-setup.sh --host you@hermes-host` ([docs/HERMES.md](docs/HERMES.md)).

For self-hosted speech instead of OpenAI, see [server/speaches.md](server/speaches.md).

## Repo layout

```
app/                    native Android app (Kotlin, OkHttp + coroutines; Robolectric tests)
magisk/r1ptt-system/    Magisk module: keylayout + boot-time power settings
tools/                  build, smoke-test, flash, provision, battery-report scripts
server/                 Hermes's robotos MCP server, skill and setup script; Hermes and Speaches notes
docs/                   install guide, Hermes remote, messages, battery notes
```

Build: `tools/build.sh` (JDK 17+ and the Android SDK; writes `out/r1ptt.apk` and
`out/r1ptt-system.zip`).
Tests: `./gradlew :app:testDebugUnitTest` and
`python3 -m unittest server.robotos_mcp.tests.test_robotos_mcp server.hermes.tests.test_setup_macos`.

App updates: **Settings → App updates** checks for signed APK releases when requested. The
release workflow is disabled until signing continuity and owner-controlled bootstrap are set up.
See [docs/UPDATES.md](docs/UPDATES.md) for release tags, secure signing setup, the first device
installation, Android confirmation and recovery. It updates the app only; hardware acceptance
of the updater remains pending.

## License

MIT; see [LICENSE](LICENSE). Parts are adapted from ClawPTT (also MIT); [NOTICE](NOTICE) has the
credits. Not affiliated with Rabbit Inc. Flashing can brick a device, and unlocking voids the R1's
warranty.
