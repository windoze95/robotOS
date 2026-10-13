# Hermes integration

Hermes owns execution, sessions, conversation history and scheduled jobs. The robotOS hub owns
shared notes, prompt revisions and device policy. It retains submission receipts and confirmed
replies for shared R1 chats and device handoff; it does not mirror Hermes session history or
schedule jobs. The R1 uses its own revocable workspace credential; enrollment
never gives it the host's Hermes API key or owner password.

```text
R1 / web console ──HTTPS──> robotOS hub ──authenticated API──> Hermes
R1 <── device queue/receipts ── robotOS hub <── scoped MCP ──── Hermes
```

Remote server connections use HTTPS. When both services run natively on the same machine,
Hermes's authenticated API and the hub's MCP endpoint can use loopback. The MCP process runs
beside Hermes; R1 devices do not need inbound network access for queued hub commands.

## Setup

Use the complete Docker Compose stack in [DEPLOYMENT.md](DEPLOYMENT.md) for a new installation.
It runs the hub and Hermes with separate persistent volumes and scoped credentials. Configure
Hermes's model/provider, expose the required HTTPS routes, then enroll each R1 from the console.
[HUB.md](HUB.md) covers enrollment, the Prompt Builder and recovery policy.

If Hermes is already installed natively on macOS or Linux, the native alternative is:

```sh
tools/hub-setup.sh --public-origin https://YOUR-HOST.YOUR-TAILNET.ts.net:9443
```

That command installs the hub, configures the authenticated loopback Hermes API and scoped MCP
adapter, starts both services and checks their readiness. Existing credentials, model/provider
settings, other MCP entries and workspace data are retained. HTTPS proxy routes and device
permissions remain explicit setup steps. `--register-hermes` is a compatibility alias for this
default native behavior. Use `--hub-only` for a separate Hermes machine and `--mcp-only` on that
Hermes machine; see [deployment architectures](DEPLOYMENT.md).

The optional direct-to-Hermes setup requires an explicit `--direct` flag and an unpaired R1.
Its configuration, task controls and direct Device API are documented in
[HERMES_DIRECT.md](HERMES_DIRECT.md). It does not provide shared state or hub recovery protections.

## Using a paired R1

- **Push-to-talk:** after recovery verification succeeds, hold the button and talk. The R1
  transcribes speech, submits the text through the hub and speaks the completed reply. Dictation
  with the keyboard open inserts text without sending it.
- **Workspace → Prompt Builder** collects notes and saves prompts. **Run in new chat** opens
  a fresh chat with the prompt attached, then waits for **Send**. Tap the attachment to edit it
  in Prompt Builder; **Save and return to chat** updates the attachment. The browser console
  edits and saves prompts only.
- **Hermes → Chats** opens shared conversations. **Host & devices** opens pairing and local
  recovery settings. Models and server credentials are managed on the host; paired devices do
  not show the legacy model picker or
  direct connection form.
- **Chats** shows shared conversation results. Existing local conversations stay saved separately.
  Unsent drafts, prompt attachments and reading positions remain local; use a saved prompt for
  an acknowledged handoff between devices.
- **Hermes device permissions** control access to the R1, speech, notices, volume, saved
  recipients and SMS. Pairing preserves existing permissions and never silently enables them.
- **Texting Hermes:** when owner passthrough is enabled locally, texts from the configured owner
  go through the hub and completed replies return by SMS. Other senders remain in Messages. Set a
  passphrase under **Hermes → Your texts to Hermes** so a faked sender number can't reach Hermes
  (see [Device tools and permissions](#device-tools-and-permissions)).
  Queued owner texts retain their original owner/connection and expire after 30 minutes; an
  ambiguous submission or SMS handoff is never automatically repeated.
- **Agent name:** set it under **Hermes → Name**. An empty name displays “Hermes”; Hermes may set
  it once with `set_name`. Default shared-stack installation does not submit an agent request.

External power and **Stay reachable** affect configured radio availability, independently of
SMS and device permissions. A sleeping R1 with radios off cannot receive new policy yet. On
wake, foreground or reconnect, robotOS verifies recovery status before revealing saved content
or resuming ordinary actions. An unreachable hub leaves it restricted until verification succeeds.

## Chat replies and approvals

Replies appear in the chat that sent the request. **Chat activity** shows pending work,
approvals and cancellation controls. The hub reserves a durable submission
identity before dispatch and caches the confirmed result. A dropped
connection, unknown submission or missing receipt never authorizes a replacement request.
Open the original chat's activity to check its submission. Reconciliation may reuse its original
identity only while the original Hermes service advertises a valid durable deduplication window.
Otherwise inspect that original
service before deciding what happened.

**Stop speech** silences local audio. **Stop task** in Chat activity requests remote cancellation;
the run remains unresolved until Hermes confirms an outcome. Approval choices apply to the
exact run and approval identity. **Allow once** and **Deny** are explicit on-screen actions;
the wheel and side button cannot authorize them. Prompt Builder has no execution status.

Submission receipts retain the confirmed Hermes run and session identifiers when available. Older saved
replies may lack a session identifier; robotOS does not guess one. The Hermes dashboard has its
own address and authentication, separate from the API connection. Manage schedules in Hermes;
robotOS has no separate scheduler or global execution-history screen.

## Device tools and permissions

Hermes uses the `robotos` MCP adapter for device status, SMS and separately permitted `speak`,
`show`, `set_volume`, `save_recipient` and `open_app` actions. Hub-backed commands target a specific enrolled
R1 and use durable IDs, expiration and receipts. Native permission checks and the SMS quota
still apply at final execution. Queued, Received and Unknown do not mean Applied.

The native device API token remains private to the R1 after pairing. It is rotated during
pairing without granting permissions. The MCP adapter receives only the scoped hub credential;
it cannot enroll devices, change lost policy or erase devices. Marking a device lost or revoked
blocks ordinary command admissions and cancels queued agent actions. Only owner-issued recovery
commands remain, subject to local permissions. Work already admitted to Hermes may still finish.

SMS tools and owner-relay replies share `hermes.dailySendLimit` (default 100; zero means unlimited).
`to` is a phone number or an exact saved recipient name; ten-digit numbers receive `+1` on a US
SIM. The quota bounds volume, not recipients or message content.

SMS sender numbers can be spoofed, so owner passthrough can require a passphrase (6–64 characters,
`hermes.passphrase`). With one set, only texts from the owner's number that start with it are relayed,
for example `otter-42 what's on today?`. Case is ignored, and a space, colon or comma must follow it.
Hermes gets the text without the passphrase, and `recent_texts` leaves it out of the owner's texts.
Texts without it stay in Messages. Five of them from the owner's number within an hour pause the
relay until fewer than five fall within the past hour, so a short passphrase can't be guessed by
texting. Changing or clearing the passphrase cancels queued owner texts. Without a passphrase,
anyone who spoofs the owner's number can use Hermes and its tools.

Incoming texts from other people are never automatically forwarded. When Hermes reads them
through a permitted tool, the bundled skill treats their content as information, not instructions.
Speech obeys Quiet mode and rejects microphone/playback conflicts. Home retains at most 20 notices.
`open_app(app)` opens Home, Workspace, Notes, Prompt Builder, Chats, Messages, Hermes, Settings,
Quick controls, Updates or Host & devices. Use the tool's named `app` values; `r1_status` also lists
them as `open_app_targets`. For Notes or Prompt Builder, optional `item_id` opens a saved note or
prompt by its exact ID. Opening a prompt only shows its detail, without running it or acquiring an
edit lease. Enable **Hermes → Open apps on screen** on the R1 first; it defaults off. The device
must be awake, unlocked and showing robotOS. Active recordings, unsaved note edits and approval
reviews return a busy error. Shared items missing locally are fetched before navigation.
`opening` confirms Android accepted a screen launch; `opened` confirms navigation within an already
open workspace. Replaying the same request recovers its receipt without navigating again.
The tool cannot launch arbitrary Android packages, change settings or install an update.
For the wire contract, see [HUB_PROTOCOL.md](../server/HUB_PROTOCOL.md).

## Checks and evidence

After installation, verify the hub's `/api/v1/ready` endpoint over its final HTTPS origin, enroll
one R1 and verify that the console shows its current state. Save a harmless prompt in the browser
and open it on the R1. **Run in new chat** should attach it without sending. Tap the attachment,
edit and save back to the chat, then tap **Send** and confirm its reply there. Before relying on
a second R1, repeat enrollment and test saved prompt/note and completed chat handoff without
resubmitting the request.

Automated suites cover capabilities, identity/replay, approvals, cancellation, permissions,
receipts, quotas and recovery boundaries. One R1 has passed the reversible offline-wake/lost/restore
sequence described in [HUB.md](HUB.md#validation-boundary). Physical second-device handoff,
PIN/admin secure locking, acoustic/carrier behavior and matched overnight battery measurements
remain separate acceptance work. Do not use automated results as evidence of those outcomes.
