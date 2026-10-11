# Connecting Hermes Agent

For shared notes, Prompt Builder, multiple R1s and lost-device recovery, use the
[robotOS hub](../docs/HUB.md). R1s connect to the hub with individual credentials; the hub connects
to Hermes. Hermes's small stdio MCP adapter connects back to the hub to queue device actions.
The hub and Hermes can run on different machines. See [deployment architectures](../docs/DEPLOYMENT.md)
for Docker Compose, k3s, native services and mixed deployments.

For a native hub using a remote Hermes API:

```sh
tools/hub-setup.sh --public-origin https://workspace.example.com \
  --hermes-url https://hermes.example.com \
  --hermes-token-file /private/hermes-api-key
```

On the separate Hermes machine, install only its adapter:

```sh
tools/hub-setup.sh --mcp-only --hub-url https://workspace.example.com \
  --mcp-token-file /private/robotOS-mcp-token
```

The second command installs the adapter and skill, stores only the scoped MCP token in Hermes's
protected `.env`, updates its MCP configuration and restarts its gateway. It does not install a
second hub or copy the owner's console credential. Add `--no-start` to prepare configuration
without restarting. Use the hub's `mcp_token`, not its `owner_token`, for the adapter. Keep source
token files private and transfer them through your protected secret-management channel.
Remote URLs require HTTPS. Both services retain independent data directories, credentials and
lifecycles; a sleeping R1 is not required for saving a draft or viewing a stored reply.

## Legacy direct connection

Run `tools/hermes-setup.sh --host you@hermes-host` ([docs/HERMES.md](../docs/HERMES.md)). Hermes is the
R1's only backend: every push-to-talk turn goes to Hermes, Hermes texts people through the R1 with its
`robotos` MCP tools, and texts from your own number reach Hermes. The script connects the R1 and
Hermes over Tailscale (tailnet-only HTTPS through Tailscale Serve) and keeps Hermes's API server bound
to `127.0.0.1`.

The R1 talks to Hermes through its API server's OpenAI-compatible `/v1/chat/completions` endpoint.
The script gives the R1 its address and key:

```json
"agent": { "baseUrl": "https://HOST.TAILNET.ts.net/v1", "apiKey": "<API_SERVER_KEY>", "model": "hermes-agent" }
```

The R1 still needs `openaiKey` from `tools/r1ptt.json` for speech: it uses OpenAI for speech-to-text
and text-to-speech, because Hermes's API server doesn't accept audio. To avoid needing an OpenAI key
at all, run your own speech server instead (see [speaches.md](speaches.md)).

## How conversations work

- The R1 sends only your newest message, plus the header
  `X-Hermes-Session-Id: r1ptt-<conversation id>`.
- Hermes keeps the transcript under that session, the same way it does for a Telegram chat.
- A double-tap on the button starts a new conversation, which gives Hermes a new session.
- Tool progress events (`hermes.tool.progress`) appear on the R1's status line while the agent
  works.

## Security

The API key gives the R1 your agent's full toolset; that is the point of the design. Never expose the
API server as plain HTTP on a LAN or the internet: keep it on `127.0.0.1` and reach it through
Tailscale Serve (or an HTTPS reverse proxy that checks a credential, set as `agent.headers`):

```json
"headers": { "CF-Access-Client-Id": "...", "CF-Access-Client-Secret": "..." }
```

Rotate the API key and the R1 device token if the R1 is lost. More in
[docs/HERMES.md](../docs/HERMES.md#security-notes).
