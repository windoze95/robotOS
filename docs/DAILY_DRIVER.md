# Daily use

robotOS uses black glass, warm white type and the R1's high-vis orange for whatever is live or
primary; errors use a distinct rose so they never read as the accent. App controls avoid blue and
blue-gray. Headings use Space Grotesk and status labels JetBrains Mono (both bundled, SIL OFL).

Android runs at 190 dpi so its own screens fit, which made robotOS small on the 2.88-inch panel
(about 278 ppi). robotOS now lays itself out on a 300 dp canvas instead: type and touch targets keep
their intended size, and every control is at least 48 dp, about 7 mm. Android's own screens and
the keyboard are unchanged. Text size is adjustable under **Settings → Display & controls**; long
replies remain selectable. The palette is a readability choice, not a measured battery improvement.

## Home and chats

- Tap the chat title to open **Chats**. Create, resume, rename or remove a local conversation.
  Each chat keeps its own draft, reading position and Hermes session. Removing it from the R1
  leaves the server's history intact.
- The composer keeps unsent text through navigation and restart. It clears the submitted draft
  after Hermes accepts the request. A changed draft is preserved.
- Incoming text follows the bottom only while you are reading there. Scroll upward to keep your
  place; **Latest** returns to the newest text.
- Holding the button for Hermes floods the screen orange: your words as they are heard, a level
  meter, a timer and an arrow toward the button. It clears when you let go.
- Until those words are sent, including the moment just after you let go, the orange screen
  takes two gestures. A quick tap keeps the words for editing: they join the end of the draft and
  the keyboard opens, so a tap of the button sends them. A swipe throws the turn away: the screen
  follows your finger, the microphone closes and nothing reaches Hermes. A short swipe springs
  back. A long touch or a second finger does neither, so a thumb resting on the glass is ignored.
- While Hermes works or speaks, the text field becomes one large **Stop speech**, **Stop task** or
  **Disconnect** button. The keyboard button beside it still lets you type.
- A line above the text field says what the R1 is doing: dictation, speech, errors and setup.
  When it names a fix (Settings, microphone access, Wi-Fi), tap it.
- The menu is a full-screen grid: **New chat**, **Chats**, **Read reply**, **Messages**, **Hermes**
  and **Settings**. **Read reply** reads the last completed reply aloud.
- The battery readout opens **Quick controls**. A Wi-Fi mark appears in it only while offline;
  the fill turns orange while charging.
- Your agent's name appears in chats, the text field's hint, notes and task cards. Set it under
  **Hermes → Name**; left empty, it shows "Hermes" and Hermes may set it once during setup
  ([HERMES.md](HERMES.md)).
- Notices sent with Hermes's `show` tool appear on Home and can be dismissed. They do not replace
  the conversation or wake the display.

The wheel scrolls the visible page or dialog. While audio is actually playing, or the speech
volume slider is selected, it adjusts speech volume; during playback a readout appears at the
screen's right edge, beside the wheel. This also works when speech starts before a
legacy streamed reply finishes. Outside robotOS, Android's existing wheel behavior is unchanged.

## Quick controls

| Control | Behavior |
|---|---|
| Brightness | Previews while dragging; saves after the gesture. |
| Speech volume | Controls spoken output. Select its slider to adjust it with the wheel. |
| Quiet mode | Stops and mutes speech and feedback sounds. Text and Hermes tasks remain available. |
| Speak replies | Controls automatic reply playback. Explicit read-aloud remains available when Quiet mode is off. |
| Stay reachable | Keeps configured radios available on battery. Turning it off restores the saved pocket-mode delay. |
| Sleep | Turns the display off. Reachability still follows the selected radio policy. |

Quiet mode and Stay reachable are independent. Neither turns SMS relay or Hermes's device
permissions on. Network availability, successful contact with Hermes and inbound device access
are separate states; **Hermes → Check connection** checks the remote connection explicitly.

## Hermes tasks and approvals

On a compatible host, robotOS saves the request and task identity before it needs them for
recovery. **Hermes** shows the task, progress, model, pending approval and available controls.
Unsupported servers retain legacy chat; authentication failures are shown as failures.

**Stop speech** affects local audio. **Stop task** asks Hermes to stop remote work and remains
**Stopping** until the host confirms a terminal state. A replacement request cannot race an
unresolved task in the same conversation.

**Review request** shows the action with **Allow once** and **Deny** when the host supports them.
Decisions are bound to that connection, task and approval request. The wheel scrolls the review;
physical-button gestures cannot approve it or silently replace the task while the review is open.

After a dropped connection or restart, check the saved task instead of submitting the request
again. **Check submission** can recover an unconfirmed submission only while the host advertises
durable idempotency and the original request is inside its retention window. Expired or unsupported
recovery stays explicit. Recovered final text returns to its original local chat without automatic
speech replay.

**Archive task record** is a local recovery control after checking the host. It leaves remote work
running and retains one of the last 20 local task receipts. It does not stop, approve or retry a
request. **Choose model** applies to future R1 requests; **Use Hermes default** leaves selection to
the host.

## Device tools and messages

The four additional tools—`speak`, `show`, `set_volume` and `save_recipient`—each require a separate
permission under **Hermes**. They start off. Existing enabled SMS access is preserved. Speech
obeys Quiet mode and refuses microphone or playback conflicts; it remains interruptible.

Mutating device requests have persistent receipts. An interrupted response is an **unknown**
outcome, not permission to repeat the action. SMS tool sends and owner-relay replies share the
daily allowance. Queued relay work is tied to its original owner and connection, and permissions
are checked again before execution and delivery. [HERMES.md](HERMES.md) describes the protocol.

**Messages** keeps manual **Review text → Send SMS**, saved recipient names, drafts and real
Android send/delivery states. An uncertain send is never retried automatically. **App updates**
shows the next applicable action and progress. [MESSAGES.md](MESSAGES.md) and
[UPDATES.md](UPDATES.md) cover those workflows.

## Acceptance evidence

Local automated tests cover conversation persistence, navigation, settings, contextual input,
listening-screen gestures, task recovery, approvals, cancellation, device permissions, request
receipts and SMS limits.
These use Robolectric and mock servers. The Android CI workflow also runs debug/release builds,
lint, Python MCP/setup tests and the Node acceptance/release tests. Each PR's checks are the
record of its hosted CI result.

On 2026-10-11, the local `0.3.6-daily-driver-dev` acceptance APK (code 3006) was installed over
the existing app on an R1 at 480×640, density 190, with the same verified signing certificate.
Existing chat history survived. Home, Quick controls, Settings, native dialogs, the keyboard-open
composer and 23 sp text were inspected on the device. A draft survived navigation and a live
Hermes Runs request returned `Ready on R1.`; its saved result survived the final APK replacement.
The host's capabilities and model-options endpoints also returned successful authenticated responses.
The updated robotOS MCP module and skill were deployed to the existing Hermes host. A fresh,
idle gateway discovered all ten tool schemas; its credentials, configuration and service
definition were unchanged.
Text size and Quiet mode were restored after testing. Local screenshots stay under the ignored
`out/daily-driver-device/` directory; screenshots containing existing personal chats are not published.

That APK's SHA-256 is `f3e3cd216060fd1b6a043295136f785bdbd5784c39a7be77e802f1fee5e49211`.
It is an unpublished acceptance build, not an official tagged release. Its full local Android
suite passed 234 tests with debug/release build and lint coverage; the MCP/setup suite passed
15 tests and the Node acceptance/release suite passed 15 tests.

The redesigned interface is covered by Robolectric snapshots rendered at the R1's 480×640,
190 dpi geometry (`ScreensSnapshotTest`, written to `app/build/ui-snapshots/`). On 2026-10-11 a
local `0.3.6-luminous-dev` build was installed over the existing app on an R1 with the same
signing certificate; existing chats survived, and Home, the menu, Quick controls and Settings
rendered as in the snapshots. Button holds, playback and the keyboard have not yet been exercised
with this build.

Actual wheel/button handling, tapping or swiping the listening screen while the button is held,
audible interruption, reboot/reconnect recovery, approval review and carrier behavior still need
physical acceptance. Automated recovery and input tests do not establish those hardware results.
The R1 was powered during this check; no standby-drain claim is based on it.

Battery acceptance requires three matched unplugged nights for both baseline and candidate.
The pocket-mode target remains **less than 0.5% per hour**. It is a target, not a measurement.
Follow [BATTERY.md](BATTERY.md) and [ACCEPTANCE.md](ACCEPTANCE.md); keep display/latency checks,
powered operation and overnight standby results separate.
