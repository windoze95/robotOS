# Hermes remote

With the **Hermes Agent** provider selected, robotOS is a remote for your Hermes agent. Hermes is
the agent; the R1 only relays and acts:

- **Push-to-talk:** hold the button and talk. robotOS transcribes the speech (OpenAI live
  transcription), sends the text to Hermes unchanged, and speaks Hermes's reply. The R1 never interprets texting requests itself; it isn't an agent.
- **Hermes acts on the R1:** Hermes's `robotos` MCP server gives it `send_sms`, `sms_status`,
  `recent_texts`, `list_recipients` and `r1_status`. "Text Sam I'm running late" is Hermes calling
  `mcp__robotos__send_sms`, which the R1 carries out with its own SIM and number.
- **Texting Hermes:** texts from your own number to the R1 go to Hermes (session `robotos-sms`), and
  Hermes's reply is texted back. Texts from anyone else stay in Messages.
- **Plugged in = radios stay up.** On external power the Wi-Fi/airplane idle cut is skipped, so texts
  arrive and Hermes can reach the R1 at any time. On battery the normal three-minute cut applies, and
  the R1 is reachable only while awake. Power never switches the passthrough on or off.

**Settings → Hermes** on the R1 (also Messages → Options → Hermes) shows whether Hermes can reach the
R1, when it last did, how many texts it sent today and what happened to your last text. It turns
Hermes's access and the passthrough on or off and sets your number and the daily text limit. The
device token and Hermes's address are provisioned from a computer (below).

```
 PTT ──► R1: transcribe ──► https://HOST.TAILNET.ts.net/v1/chat/completions ──► Hermes
                                   (Tailscale Serve → 127.0.0.1:8642)                       │
 R1 ◄── device API :8765 ◄── tailscaled HTTP proxy 127.0.0.1:1056 ◄── robotos MCP (stdio) ◄─┘
```

## Pieces

| Where | What |
|---|---|
| R1 | `HermesLink` config (`hermes.*`): device API on port 8765, token, passthrough owner. |
| R1 | `DeviceApi`: JSON over HTTP; serves only Tailscale (100.64.0.0/10, fd7a:115c:a1e0::/48) and loopback peers presenting the token. |
| R1 | `HermesTexts`: owner texts → Hermes → reply text; retried with backoff for 30 minutes, then one notice. Hermes is asked once per text. |
| Hermes host | Default profile gateway (`hermes gateway install`) with the API server on 127.0.0.1:8642. |
| Hermes host | `server/robotos_mcp`: stdio MCP server (standard library only) that calls the R1 through the userspace tailscaled's outbound HTTP proxy. |
| Hermes host | `server/hermes/skills/robotos/SKILL.md`: when and how Hermes uses the R1. |

Device API endpoints (all need `Authorization: Bearer <token>`): `GET /v1/status`, `POST /v1/sms`
`{"to","body"}`, `GET /v1/sms/<id>`, `GET /v1/texts[?with=&limit=]`, `GET /v1/recipients`.
`to` is a phone number (spoken digits are fine) or an exact saved recipient name; ten-digit numbers
get `+1` on a US SIM. Hermes may send at most `hermes.dailySendLimit` texts a day (default 100).

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
tools/hermes-setup.sh --host you@hermes-host --owner +1XXXXXXXXXX
```

`--owner` is your own phone number: its texts to the R1 go to Hermes. Leave it out to set it later
on the R1 (Settings → Hermes). The first run prints a Tailscale link to approve the Hermes host as a
new node. Re-running is safe: existing keys are kept, so the R1 keeps working.

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

To undo: remove the `mcp_servers.robotos` entry and the `API_SERVER_*`/`ROBOTOS_R1_TOKEN` lines,
`launchctl bootout gui/$(id -u)/ai.robotos.tailscale` and delete its plist and
`~/Library/Application Support/robotOS`, and `hermes gateway uninstall` if you don't otherwise use it.
On the R1, choose another backend in Settings.

### By hand

`server/hermes/setup_macos.py --r1 <R1 tailnet IP> [--owner …]` is the host half, runnable from any
copy of `server/`. It prints where it wrote the R1's settings; push them with
`adb shell am broadcast -n dev.r1ptt/.data.ConfigReceiver -a dev.r1ptt.CONFIG --es json_b64 "$(base64 < r1-hermes.json)"`.
Tests: `python3 -m unittest server.robotos_mcp.tests.test_robotos_mcp server.hermes.tests.test_setup_macos`.

## Checks

- The setup script ends by checking both directions. To repeat the Hermes → R1 half by hand:
  `curl -x http://127.0.0.1:1056 -H "Authorization: Bearer $ROBOTOS_R1_TOKEN" http://<R1 IP>:8765/v1/status`
- Ask Hermes "what's my R1's battery level?": it should call `mcp__robotos__r1_status`.
- `adb logcat -s r1ptt` shows content-free events: `device api: …`, `hermes text: …`.

## Security notes

- The API key gives the R1 your full Hermes agent, tools included. It lives only in the R1's
  encrypted config and is used over tailnet-only HTTPS.
- Hermes can text anyone from your number. `dailySendLimit` bounds a runaway loop; it is not a
  policy filter.
- Passthrough trusts the sender number. SMS caller ID can be spoofed, so anyone spoofing your number
  could talk to your agent. Turn off `hermes.passthrough` if that matters for your Hermes's tools.
- Incoming texts from other people are never forwarded. When Hermes reads them with
  `recent_texts`, the skill tells it to treat them as information, not instructions.
