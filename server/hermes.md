# Using Hermes Agent as the backend

Run `tools/hermes-setup.sh --host you@hermes-host` ([docs/HERMES.md](../docs/HERMES.md)). It makes Hermes the agent: every push-to-talk turn goes to
Hermes, Hermes texts people through the R1 with its `robotos` MCP tools, and texts from your own number
reach Hermes. It connects the R1 and Hermes over Tailscale (tailnet-only HTTPS through Tailscale Serve)
and keeps Hermes's API server bound to `127.0.0.1`.

The R1 talks to Hermes through its API server's OpenAI-compatible `/v1/chat/completions` endpoint, with
the active provider set to `hermes`:

```json
{
  "activeProvider": "hermes",
  "providers": {
    "openai": { "apiKey": "sk-..." },
    "hermes": { "baseUrl": "https://HOST.TAILNET.ts.net/v1", "apiKey": "<API_SERVER_KEY>", "model": "hermes-agent" }
  }
}
```

Keep the OpenAI key in the config even when Hermes is active. The R1 still uses OpenAI for
speech-to-text and text-to-speech, because Hermes' API server doesn't accept audio. To avoid
needing an OpenAI key at all, run your own speech server instead (see [speaches.md](speaches.md)).

## How conversations work

- The R1 sends only your newest message, plus the header
  `X-Hermes-Session-Id: r1ptt-<conversation id>`.
- Hermes keeps the transcript under that session, the same way it does for a Telegram chat.
- A double-tap on the button starts a new conversation, which gives Hermes a new session.
- Tool progress events (`hermes.tool.progress`) appear on the R1's status line while the agent
  works.

If that session doesn't keep context for you, switch to the stateless mode Hermes also supports.
Set `"session": "history"` on the hermes provider, and the R1 will send the recent messages itself.

## Security

The API key gives the R1 your agent's full toolset; that is the point of the design. Never expose the
API server as plain HTTP on a LAN or the internet: keep it on `127.0.0.1` and reach it through
Tailscale Serve (or an HTTPS reverse proxy that checks a credential, set as provider headers):

```json
"headers": { "CF-Access-Client-Id": "...", "CF-Access-Client-Secret": "..." }
```

Rotate the API key and the R1 device token if the R1 is lost. More in
[docs/HERMES.md](../docs/HERMES.md#security-notes).
