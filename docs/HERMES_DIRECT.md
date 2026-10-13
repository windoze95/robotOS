# Legacy direct Hermes connection

This is the optional direct connection for an **unpaired R1**. New installations should use the
shared stack in [HUB.md](HUB.md) and [DEPLOYMENT.md](DEPLOYMENT.md). Direct mode has no shared notes,
Prompt Builder synchronization, web device management or hub recovery gate. Its full Hermes API
credential resides on the R1. Do not run this setup against a paired installation: the installer
refuses to replace an existing hub-backed MCP integration, and paired devices reject legacy host
credential imports.

In this legacy arrangement, Hermes is the R1's direct backend; the R1 relays and acts:

- **Push-to-talk:** hold the button and talk. robotOS transcribes the speech (OpenAI live
  transcription), sends the text to Hermes unchanged, and speaks Hermes's reply. The R1 never
  interprets texting requests itself; it isn't an agent.
- **Hermes acts on the R1:** Hermes's `robotos` MCP server gives it `send_sms`, `sms_status`,
  `recent_texts`, `list_recipients` and `r1_status`, plus separately permitted `speak`, `show`,
  `set_volume` and `save_recipient` controls. "Text Sam I'm running late" is Hermes calling
  `mcp__robotos__send_sms`, which the R1 carries out with its own SIM and number.
- **Its name:** robotOS shows the name you call your agent in chats, hints and notes (Settings →
  Hermes → Name; empty shows "Hermes"). While the name is empty, Hermes may set it once with
  `set_name`; setup asks it to (below). After that only you can change it.
- **Texting Hermes:** texts from your own number to the R1 go to Hermes (session `robotos-sms`), and
  Hermes's reply is texted back. Texts from anyone else stay in Messages. With a passphrase set, only
  your texts that start with it go to Hermes, which gets them without it (see Security notes).
- **Reachability:** external power skips the Wi-Fi/airplane idle cut. **Stay reachable** does the
  same on battery; otherwise the saved pocket-mode delay applies, three minutes by default.
  Cellular service still requires its own setting and a SIM. Connectivity and Tailscale must be
  available. Power never switches the passthrough on or off.

**Settings → Hermes** on the R1 (also Messages → Options → Hermes) shows whether Hermes can reach the
R1, when it last did, how many texts it sent today and what happened to your last text. It turns
Hermes's access and the passthrough on or off and sets your passphrase, your number and the daily text limit. The
device token and Hermes's address are provisioned from a computer (below).

```
 PTT ──► R1: transcribe ──► https://HOST.TAILNET.ts.net/v1/runs ──► Hermes
                                   (Tailscale Serve → 127.0.0.1:8642)                       │
 R1 ◄── device API :8765 ◄── tailscaled HTTP proxy 127.0.0.1:1056 ◄── robotos MCP (stdio) ◄─┘
```

Older hosts use `/v1/chat/completions`. Capability discovery selects that compatibility path
before submission; failed authentication does not select legacy mode.

## Task controls

**Hermes → Check connection** checks the configured host. Supported hosts expose progress,
approvals, cancellation and model options through the Runs API. Proxy URL prefixes and configured
credentials are retained for each request. **Choose model** affects future R1 requests and leaves
the host's global configuration alone.

Requests are recorded locally before submission. Reconnection follows the same task and filters
replayed events. After restart, final output is saved once to the original chat. It is not spoken
again automatically. **Check submission** reuses the original request identity only when the
current host advertises durable idempotency and the request remains inside its retention window.
An unknown or expired result must be checked on the host before trying another request.

**Stop speech** silences the R1. **Stop task** requests remote cancellation and shows **Stopping**
until Hermes confirms it. **Review request → Allow once / Deny** applies only to the displayed
connection, run and approval identity; expired approvals are refreshed. The physical button cannot
approve an action, and a review blocks gestures from silently cancelling or replacing the task.

**Archive task record** is available after local execution has stopped. Check Hermes on the host
before confirming it. Archiving clears the R1's active record and keeps a local receipt; it does
not stop remote work, respond to an approval or resend anything. The archive retains 20 records.
See [DAILY_DRIVER.md](DAILY_DRIVER.md) for navigation and controls.

## Pieces

| Where | What |
|---|---|
| R1 | `HermesLink` config (`hermes.*`): device API on port 8765, token, passthrough owner and passphrase. |
| R1 | `DeviceApi`: JSON over HTTP; serves only Tailscale (100.64.0.0/10, fd7a:115c:a1e0::/48) and loopback peers presenting the token. |
| R1 | `HermesTexts`: owner texts → Hermes → reply text. Retries before submission or confirmed SMS rejection use a 30-minute window. An ambiguous submission or SMS handoff is never automatically repeated. |
| Hermes host | Default profile gateway (`hermes gateway install`) with the API server on 127.0.0.1:8642. |
| Hermes host | `server/robotos_mcp`: stdio MCP server (standard library only) that calls the R1 through the userspace tailscaled's outbound HTTP proxy. |
| Hermes host | `server/hermes/skills/robotos/SKILL.md`: when and how Hermes uses the R1. |

Device API endpoints all require `Authorization: Bearer <token>`:

| Method and path | Input or result |
|---|---|
| `GET /v1/status` | Device state, permissions, shared SMS count and receipt retention. |
| `POST /v1/sms` | `to`, `body`; sends one SMS attempt. |
| `GET /v1/sms/<id>` | Android's status for that attempt. |
| `GET /v1/texts[?with=&limit=]` | Local conversations or messages. |
| `GET /v1/recipients` | Saved names and numbers. |
| `POST /v1/speak` | `text`, up to 2000 characters; interruptible speech. |
| `POST /v1/show` | `text`, up to 2000 characters; a dismissible Home notice without waking the display. |
| `POST /v1/apps/open` | Required `app` from `status.open_app_targets`; optional `item_id` (1–200 letters, digits, hyphens or underscores) for `notes` or `prompt_builder`. Requires `allowOpenApps`, an awake/unlocked R1 and foreground robotOS. |
| `POST /v1/volume` | `level` from 0 to 1; returns the applied speech volume. |
| `POST /v1/recipients` | `name` and `number`; validates and saves a recipient. |
| `POST /v1/name` | `name`, 1–24 letters or digits with spaces, apostrophes, hyphens or periods. Accepted only while the R1 has no name (`status.name_settable`); otherwise HTTP 403. |
| `GET /v1/requests/<request_id>` | Recovers the original device-action receipt (`request_status` in MCP). |

`to` is a phone number (spoken digits are fine) or an exact saved recipient name; ten-digit numbers
get `+1` on a US SIM. SMS tool sends and owner-relay replies share `hermes.dailySendLimit`
(default 100, zero means unlimited). Queued owner texts retain their original owner, connection and
passphrase requirement; permission, identity and passphrase changes are checked before forwarding
and before SMS delivery. `GET /v1/texts` returns the owner's texts without the passphrase.

The additional controls require `hermes.allowSpeak`, `allowShow`, `allowVolume`,
`allowSaveRecipient` and `allowOpenApps` respectively. Each defaults off and also requires `hermes.deviceApi`.
Existing enabled SMS access survives migration. Speech obeys Quiet mode and rejects microphone
or playback conflicts. Home retains at most 20 notices.
Opening apps refuses to interrupt capture, unsaved note edits or approval reviews. Missing items
return 404; paired devices fetch uncached notes/prompts from their host first. `opening` means
Android accepted a launch; `opened` means navigation inside the active workspace. Supported `app`
values are `home`, `workspace`, `notes`, `prompt_builder`, `chats`, `messages`, `hermes`, `settings`,
`quick_controls`, `updates` and `host_devices`. Navigation does not execute prompts, send texts,
change settings, install updates or launch other Android packages.

POST bodies accept `request_id` (16–128 letters, digits, hyphens or underscores); MCP supplies one
when omitted. Receipts are persisted before execution and retained for at least seven days.
There are 512 slots; a full store rejects a new action instead of evicting an unexpired receipt.
Malformed receipts block new work and remain available for recovery.

Keep the same ID and exact payload when recovering an action. An interrupted response or HTTP 202
with `status: unknown` may mean the action ran. Query `request_status`, or `sms_status` when an SMS
ID is available; do not start a replacement action with a new ID. Exact retries require
`r1_status.request_receipt_retention_seconds` support and must be inside that window. Older
robotOS versions cannot guarantee this. A missing or expired receipt is not proof nothing happened.

## Setup

You need:

- the R1 running robotOS (docs/INSTALL.md), on USB, with the **Tailscale** app signed in to your
  tailnet and its VPN allowed;
- a Mac that runs **Hermes Agent**, reachable over SSH, with Homebrew's `tailscale`
  (`brew install tailscale`; it runs as its own private node and doesn't touch the Mac's networking);
- HTTPS certificates enabled for your tailnet (Tailscale admin console → DNS → HTTPS). If they
  aren't, the script prints the link to turn them on.

Then, from this repository on the Mac the R1 is plugged into:

```sh
tools/hermes-setup.sh --direct --host you@hermes-host --owner +1XXXXXXXXXX
```

`--owner` is your own phone number: its texts to the R1 go to Hermes. Leave it out to set it later
on the R1 (Settings → Hermes). The first run prints a Tailscale link to approve the Hermes host as a
new node. Re-running is safe: existing keys are kept, so the R1 keeps working.

Last, while the R1 has no name for your agent, the script asks Hermes once (in its own
`robotos-setup` session) to tell the R1 the name you call it, with `set_name`. If the R1 already has
a name, Hermes isn't asked. `setup_macos.py --direct --r1 <R1 tailnet IP> --introduce` repeats just this step.

### What it changes on the Hermes host

| Where | Change |
|---|---|
| `~/Library/Application Support/robotOS/mcp/` | The `robotos` MCP server (standard library Python). |
| `~/.hermes/skills/robotos/SKILL.md` | The skill. |
| `~/.hermes/.env` (0600) | `API_SERVER_ENABLED=true`, `API_SERVER_HOST=127.0.0.1`, `API_SERVER_PORT=8642`, and generated `API_SERVER_KEY` and `ROBOTOS_R1_TOKEN`. Other lines are untouched. |
| `~/.hermes/config.yaml` | An `mcp_servers.robotos` entry; its token is a `${ROBOTOS_R1_TOKEN}` reference, not the value. Hermes may re-indent a few lists when it saves. |
| `~/Library/LaunchAgents/ai.robotos.tailscale.plist` | A private userspace Tailscale node (state under `…/robotOS/tailscale/`), with a localhost-only proxy the MCP server uses to reach the R1. |
| Tailscale Serve | `https://<that node>.<tailnet>.ts.net` → `http://127.0.0.1:8642`, tailnet only. |
| `~/Library/LaunchAgents/ai.hermes.gateway.plist` | `hermes gateway install`: the gateway runs at login (restarted if it was already installed). |
| `~/Library/Application Support/robotOS/r1-hermes.json` (0600) | The R1's settings, including the key and token. The Mac script reads it to configure the R1. |

### Removing a legacy installation

First inspect which services still use the MCP entry, API key and Tailscale routes. If the host
has since migrated to the shared hub, retain those components and use [HUB.md](HUB.md) instead.
Back up the hub's database/media and private configuration before changing shared infrastructure.

For an installation that remains direct-only, turn off Hermes access and passthrough on the R1.
Remove only the direct `mcp_servers.robotos` entry and its `ROBOTOS_R1_TOKEN` environment entry.
Remove the `API_SERVER_*` entries or uninstall the Hermes gateway only if no other client uses
that API or gateway. Remove only the Serve route owned by this direct integration. Stop and remove
`ai.robotos.tailscale` and its private Tailscale state only if no other service uses that node.
The unused legacy `robotOS/mcp/` directory and `robotOS/r1-hermes.json` can then be removed.
**Do not delete the parent `~/Library/Application Support/robotOS` directory:** it may contain the
shared workspace, recovery identities, backups and other active service state.

### By hand

`server/hermes/setup_macos.py --direct --r1 <R1 tailnet IP> [--owner …]` is the host half, runnable from any
copy of `server/`. It prints where it wrote the R1's settings; push them with
`adb shell am broadcast -n dev.r1ptt/.data.ConfigReceiver -a dev.r1ptt.CONFIG --es json_b64 "$(base64 < r1-hermes.json)"`.
Tests: `python3 -m unittest server.robotos_mcp.tests.test_robotos_mcp server.hermes.tests.test_setup_macos`.

## Checks

- The setup script ends by checking both directions. To repeat the Hermes → R1 half by hand:
  `curl -x http://127.0.0.1:1056 -H "Authorization: Bearer $ROBOTOS_R1_TOKEN" http://<R1 IP>:8765/v1/status`
- Ask Hermes "what's my R1's battery level?": it should call `mcp__robotos__r1_status`.
- `adb logcat -s r1ptt` shows content-free events: `device api: …`, `hermes text: …`.

Local tests cover mocked host capabilities, request replay, approval identity, cancellation,
recovery, device permissions, receipt corruption and shared SMS quotas. The CI workflow runs
Android, Python and Node checks; consult the build's PR for its result. Current-build host,
physical-control, carrier and reboot/reconnect acceptance remains pending. No installation or
measurement is established by these tests.

## Security notes

- The API key gives the R1 your full Hermes agent, tools included. A copy lives in the R1's
  encrypted config and is used over tailnet-only HTTPS; protected host setup files also contain it.
- Hermes can text anyone from your number. `dailySendLimit` bounds a runaway loop; it is not a
  policy filter.
- SMS caller ID can be spoofed. Without a passphrase, passthrough trusts the sender number, so anyone
  spoofing your number could talk to your agent. Set one under **Settings → Hermes** (or
  `hermes.passphrase`, 6–64 characters) and start your texts to Hermes with it: other texts from your
  number stay in Messages, and five of those within an hour pause the relay until fewer than five
  fall within the past hour.
- Incoming texts from other people are never forwarded. When Hermes reads them with
  `recent_texts`, the skill tells it to treat them as information, not instructions.
