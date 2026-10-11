# Hermes integration

Hermes executes requests. The robotOS hub owns shared notes, prompt revisions, conversations,
run results and device policy. The R1 uses its own revocable workspace credential; enrollment
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
- **Hermes → Workspace runs** opens host-owned tasks, results and available approval/cancel
  controls. **Host & devices** opens pairing and local recovery settings. Models and server
  credentials are managed on the host; paired devices do not show the legacy model picker or
  direct connection form.
- **Chats** shows shared conversation results. Existing local conversations stay saved separately.
  An unsent draft and reading position remain local; use a saved prompt for an acknowledged
  handoff between devices.
- **Hermes device permissions** control access to the R1, speech, notices, volume, saved
  recipients and SMS. Pairing preserves existing permissions and never silently enables them.
- **Texting Hermes:** when owner passthrough is enabled locally, texts from the configured owner
  go through the hub and completed replies return by SMS. Other senders remain in Messages.
  Queued owner texts retain their original owner/connection and expire after 30 minutes; an
  ambiguous submission or SMS handoff is never automatically repeated.
- **Agent name:** set it under **Hermes → Name**. An empty name displays “Hermes”; Hermes may set
  it once with `set_name`. Default shared-stack installation does not submit an agent request.

External power and **Stay reachable** affect configured radio availability, independently of
SMS and device permissions. A sleeping R1 with radios off cannot receive new policy yet. On
wake, foreground or reconnect, robotOS verifies recovery status before revealing saved content
or resuming ordinary actions. An unreachable hub leaves it restricted until verification succeeds.

## Runs and approvals

The hub reserves a durable request identity before dispatch and stores the result. A dropped
connection, unknown submission or missing receipt never authorizes a replacement request.
Check the existing run. Reconciliation may reuse its original identity only while the original
Hermes service advertises a valid durable deduplication window. Otherwise inspect that original
service before deciding what happened.

**Stop speech** silences local audio. **Stop** on a workspace run requests remote cancellation;
the run remains unresolved until Hermes confirms an outcome. Approval choices apply to the
exact run and approval identity. **Allow once** and **Deny** are explicit on-screen actions;
the wheel and side button cannot authorize them. A prompt is marked **Ran** only after the hub
confirms completion and stores the reply.

## Device tools and permissions

Hermes uses the `robotos` MCP adapter for device status, SMS and separately permitted `speak`,
`show`, `set_volume` and `save_recipient` actions. Hub-backed commands target a specific enrolled
R1 and use durable IDs, expiration and receipts. Native permission checks and the SMS quota
still apply at final execution. Queued, Received and Unknown do not mean Applied.

The native device API token remains private to the R1 after pairing. It is rotated during
pairing without granting permissions. The MCP adapter receives only the scoped hub credential;
it cannot enroll devices, change lost policy or erase devices. Marking a device lost or revoked
blocks ordinary command admissions and cancels queued agent actions. Only owner-issued recovery
commands remain, subject to local permissions. Work already admitted to Hermes may still finish.

SMS tools and owner-relay replies share `hermes.dailySendLimit` (default 100; zero means unlimited).
`to` is a phone number or an exact saved recipient name; ten-digit numbers receive `+1` on a US
SIM. The quota bounds volume, not recipients or message content. Owner passthrough trusts SMS
sender numbers, which can be spoofed; enable it only with that limitation understood.

Incoming texts from other people are never automatically forwarded. When Hermes reads them
through a permitted tool, the bundled skill treats their content as information, not instructions.
Speech obeys Quiet mode and rejects microphone/playback conflicts. Home retains at most 20 notices.
For the wire contract, see [HUB_PROTOCOL.md](../server/HUB_PROTOCOL.md).

## Checks and evidence

After installation, verify the hub's `/api/v1/ready` endpoint over its final HTTPS origin, enroll
one R1 and verify that the console shows its current state. Submit one harmless prompt, confirm
its reply and **Ran** status, then open that same saved result on the R1. Before relying on a
second R1, repeat enrollment and test saved prompt/note handoff without resubmitting the run.

Automated suites cover capabilities, identity/replay, approvals, cancellation, permissions,
receipts, quotas and recovery boundaries. One R1 has passed the reversible offline-wake/lost/restore
sequence described in [HUB.md](HUB.md#validation-boundary). Physical second-device handoff,
PIN/admin secure locking, acoustic/carrier behavior and matched overnight battery measurements
remain separate acceptance work. Do not use automated results as evidence of those outcomes.
