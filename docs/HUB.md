# Shared workspace and device management

The robotOS host service stores notes, recordings, prompt revisions and run results. R1 devices
and the browser use the same API. Hermes accesses it through the robotOS MCP adapter. The service
runs independently of Hermes, so saving and syncing do not require an agent turn.

The host database is authoritative. Each R1 keeps a local cache and pending captures. A local save
is not a cross-device save until the service acknowledges it. Audio is acknowledged only after
the host durably stores and verifies the upload. Offline notes remain local until connected.

| R1 Prompt Builder | Web console |
|---|---|
| [<img src="screenshots/workspace-builder.png" width="240" alt="Native R1 Prompt Builder with staged notes">](screenshots/workspace-builder.png) | [<img src="screenshots/workspace-console.jpg" width="600" alt="Web Prompt Builder showing a staged workshop checklist and exact preview">](screenshots/workspace-console.jpg) |

These examples use staged test data; see [screenshot evidence](screenshots/README.md).

## Install on the Hermes host

Requirements: Python 3.9 or later, the robotOS source, and an existing Hermes gateway if prompts
will be executed. The service itself uses the Python standard library. It supports macOS launchd
and Linux systemd user services. Use an always-available host for device handoff and recovery.

From this repository on that host:

```sh
tools/hub-setup.sh --public-origin https://YOUR-HOST.YOUR-TAILNET.ts.net:8443
```

The installer writes private configuration and code under `~/Library/Application Support/robotOS/hub`
on macOS, or `~/.local/share/robotos-hub-service` on Linux. It prints paths, never credentials. Read
the generated `owner-password.txt` locally to sign in. Keep `hub.json` and its parent directory at
0600 and 0700 respectively. Reinstallation retains secrets, workspace data and media.

Configuration contains `bind`, `port`, `data_dir`, `public_origin`, `owner_password`, `owner_token`,
`mcp_token`, `hermes_url`, `hermes_token`, `speech_url`, `speech_token`, and `speech_model`. Existing
Hermes `API_SERVER_KEY` and `OPENAI_API_KEY`, when present in its protected `.env`, initialize the
corresponding host credentials. Configure a compatible speech endpoint if that key is absent.
The R1 does not receive the host's Hermes or transcription credentials through enrollment.

The service binds loopback at port 8787. Expose that port through your existing tailnet HTTPS
proxy. With the private Tailscale node installed by robotOS's Hermes setup, for example:

```sh
tailscale --socket="$HOME/Library/Application Support/robotOS/tailscale/tailscaled.sock" \
  serve --bg --https=8443 http://127.0.0.1:8787
```

This uses a separate HTTPS port, preserving the existing Hermes route on 443. Set `public_origin`
to the exact browser origin, including `:8443`; the service checks browser origins and CSRF tokens.
Do not publish it with Tailscale Funnel. Device pairing requires HTTPS except for local development.

Check `/api/v1/health`, then open the console. Sign in, open Devices, create an enrollment code,
and enter the URL and code under Workspace settings on the R1. Codes are short-lived and single-use.
Name devices clearly so SMS and other device actions have an unambiguous target.

To register the host-backed MCP adapter and restart Hermes after the host is ready:

```sh
tools/hub-setup.sh --register-hermes
```

The MCP receives only its scoped token through an environment reference. It cannot enroll devices,
change lost state or erase devices. The legacy direct-to-R1 adapter still works when no host URL
is configured. Pairing clears the R1's legacy Hermes API credential and rotates its native device
API token. Paired PTT and owner-SMS conversations use the hub; device actions are queued for an
explicit enrolled device and still pass native permission, receipt and SMS-quota checks. Existing
speech settings for live dictation and spoken replies remain local; enrollment never copies host
credentials. Validate this boundary on both devices before relying on lost-mode protection.

During an active shared conversation the R1 checks for device commands while awaiting its reply.
The MCP adapter briefly reads the same command's receipt, without submitting it again. It waits
up to five seconds by default (`ROBOTOS_HUB_RECEIPT_WAIT_SECONDS`, 0–10 seconds); commands that
have not completed remain queued or received and can be checked by their original ID.

For a foreground development service, use a private configuration file and:

```sh
PYTHONPATH=server python3 -m robotos_hub --config /absolute/path/to/hub.json
```

The console is bundled static HTML, CSS and JavaScript. It requires no external font/CDN service
or JavaScript build chain. Browser sessions use HttpOnly cookies and CSRF protection; bearer
credentials are not placed in browser local storage or URLs.

## Prompt Builder

Capture written notes or recordings, select several, and add them to a new or existing prompt.
From an open builder, add an existing note, write another note or record another clip. Arrange
the blocks, correct transcripts and preview the exact request. Source notes remain reusable;
removing a block does not delete its note. Recording uploads and transcription failures retain
the original audio for recovery.

Saving creates a revision. Only one client holds the edit lease for a prompt. Continuing on a
second client fences the old editor; stale saves are rejected and the client retains its unsaved
work. Independent note captures can coexist. Offline captures may sync later, but canonical
prompt edits and Run require the host.

Run reserves an immutable prompt revision before dispatch. Duplicate taps attach to the same
run. Run again explicitly creates another attempt. A run becomes Ran only when the host confirms
completion and stores the reply. Changes after execution show Changed since last run. A dropped
connection or unknown submission never authorizes a replacement request. Stop requests remain
pending until Hermes confirms their outcome. Approvals apply to the exact run and approval ID.

The service permits only one active run per prompt/session, including across revisions. An
uncertain submission can be checked explicitly using its original identity only while Hermes's
advertised durable deduplication window remains valid. Otherwise it stays unresolved for inspection
on the original host. The service does not infer failure from a timeout.

Paired PTT uses shared conversations with immutable turn snapshots. Completed replies sync into
Chats on both R1s, and the host retains conversation context. An unsent local draft and scroll
position remain local. The last shared prompt is available through Continue; changing devices
does not silently replace an unsent draft. Existing conversations from before pairing remain local.

## Device management and recovery

The console shows device-reported state with observation times. Settings distinguish desired
values from acknowledged applied values. Device commands have stable IDs, a specific destination,
an expiration and a durable receipt. Queued and Received are not Applied. An unknown result is
preserved for reconciliation, never blindly retried as a new operation.

Each R1 has distinct workspace and recovery credentials. Marking it lost or revoked blocks shared
workspace access on the host immediately. Its restricted recovery identity can still check in,
receive its own recovery commands and acknowledge them. Offline cached data cannot be remotely
locked or cleared until the R1 receives the instruction. The shared workspace and other R1s are
not erased when local data is cleared.
The lost policy is durable and has no expiration. On boot, waking, returning to robotOS or reconnecting,
the R1 checks that policy before revealing cached content or resuming ordinary device actions. Once received,
it saves the restriction before processing recovery commands. A blocked device remains blocked
across app restarts and offline periods until it receives a newer policy restoring access. The
console distinguishes the owner's requested policy from the exact policy acknowledged by the R1;
a stale check-in cannot confirm a newer lost request. This is separate from command expirations.

Marking lost or revoked cancels queued agent actions (including MCP recovery requests), updates
and reboots. Only owner-issued recovery actions remain available; legacy queued commands without
issuer evidence are cancelled conservatively. Already-received
actions become unknown because they may have executed; a later final receipt is retained.
New ordinary command admissions are denied, including receipt handoffs racing with a lost request.
Ordinary settings changes are also disabled until the owner restores access.
Owner recovery commands such as ringing, return information and locally permitted locking remain
available after the R1 confirms that it has applied the policy. Local permissions still apply.
This prevents new agent requests; work already admitted to Hermes may finish and must be checked
in Runs. Revocation does not undo an external action that has already happened.
Clearing local workspace also revokes its workspace credential immediately and requires a new
enrollment code to restore shared access. Its recovery credential remains available.

Management capabilities depend on on-device permission and Android support. Ringing, return
information, location reporting, secure locking, clearing local workspace, app updates, reboot
and factory reset must advertise their actual availability. Unsupported operations are rejected.
Location requires a working provider, precise location permission and Android's “Allow all the
time” background permission, granted on the R1. Enable system location if provisioning disabled
it. A fix includes its timestamp and
accuracy; a missing fix is not the device's current position. Secure lock requires a PIN/password
and active device-admin support. Factory reset requires supported device-owner enrollment and
separate local authorization. The existing provisioning script disables the keyguard for the
original PTT workflow; configure and verify a secure credential before relying on remote locking.

Protection adapts to the security features actually enabled on the R1. Without a secure Android
credential, robotOS restricts its own screens and action entry points; this does not lock Android
or prevent access through other apps, debugging or a modified operating system. With a secure
credential, active device admin and locally enabled management, lost mode can also request an
Android lock. Capability checks refresh when robotOS resumes and reports its state, so enabling
those protections locally does not require re-enrollment. No local permission is silently granted.
The console reports app restrictions, secure credential availability, remote-lock support and
device-reported lock application separately, together with the last connection time. A lock
receipt is evidence that Android accepted a lock action, not proof that the device remains locked.

Recovery check-ins are opt-in and coordinate with the existing radio policy. They trade standby
battery life for opportunities to receive commands. A server cannot reach an R1 while all of its
radios are off. An alarm or location request sent to an offline device remains queued until a
connection is available, and may expire first. The rooted/unlocked platform does not establish
tamper-resistant theft protection.
Foreground, wake and reconnect checks protect normal use without enabling periodic background
radio wakeups. If the R1 cannot reach the host, it stays on the recovery screen until a successful
check verifies access. This also applies to a previously unrestricted device: a timeout or failed
connection never exposes cached notes or chats. The cache remains saved on disk, and network setup
and recovery checks remain available. Older robotOS clients must be updated to use this strict
verification gate, acknowledge versioned recovery policy and report the new security posture.

Update commands must follow the existing signed-release verifier and Android installer policy.
The command specifies the official release version code; arbitrary APK URLs are not accepted.
An installer handoff is not proof of installation and may require confirmation on the R1. Commands
stay unconfirmed until the installed version is reported. Full-device reset, reboot and installation
need physical acceptance; automated tests do not establish those results.

## Backups and recovery

Back up the live database using SQLite's online backup operation, together with every referenced
immutable recording. The supplied tool verifies database integrity and every recording hash:

```sh
python3 tools/hub-backup.py --data /private/hub/data --output /private/backups/robotos-2026-10-11
```

The destination must be new and outside the live data directory. Backups contain personal notes,
audio and device identity hashes; keep them private. Save `hub.json` separately with equivalent
protection. To restore, stop the host service, restore into a new 0700 data directory, verify its
contents, point `data_dir` there and restart. Retain the previous directory until verification.
Reconcile any unknown runs before submitting again. A restored older snapshot cannot prove that
external work or SMS did not happen after the snapshot was made.

## Validation boundary

The automated suites exercise host concurrency, permissions, persistence, device command receipts,
Android cache and capture recovery, and browser state handling. Physical acceptance still requires
two enrolled R1s: save/handoff, airplane/reconnect, power cycle, real audio, location, secure lock,
installer completion and matched overnight recovery-check-in battery measurements. Use disposable
device data for erasure tests and retain a verified recovery image before testing factory reset.

The wire contract is in [HUB_PROTOCOL.md](../server/HUB_PROTOCOL.md).
