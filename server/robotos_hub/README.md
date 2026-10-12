# robotOS hub

A Python 3.9+ standard-library service for one shared robotOS workspace and individually enrolled
R1 devices. SQLite stores current prompts and revision numbers, editing leases, submission receipts, cached Hermes
replies, device command receipts and administrative history. Audio lives in private durable files
beside the database. Hermes owns agent execution, session history and scheduled jobs. Saving and
syncing workspace content run independently of Hermes and its MCP subprocess.

## Run

From `server/`, run:

```sh
python3 -m robotos_hub --config /private/path/hub.json
```

The configuration file must have mode `0600`; `data_dir` must have mode `0700` and must live outside
the source checkout. Example **field names** (replace each placeholder; never commit real values):

```json
{
  "data_dir": "/private/path/workspace",
  "bind": "127.0.0.1",
  "port": 8788,
  "owner_password": "replace-with-a-long-owner-password",
  "owner_token": "replace-with-a-random-token-at-least-32-characters",
  "mcp_token": "replace-with-a-different-random-token-at-least-32-characters",
  "public_origin": "https://your-tailnet-host.example",
  "hermes_url": "http://127.0.0.1:8642",
  "hermes_token": "host-only-hermes-api-credential",
  "speech_url": "http://127.0.0.1:8000/v1",
  "speech_token": "host-only-speech-credential",
  "speech_model": "whisper-1"
}
```

Every field also has a `ROBOTOS_HUB_<UPPERCASE_FIELD>` environment override. Optional fields include
`hermes_model`, `hermes_provider`, `hermes_instructions`, `lease_seconds` (90), `session_seconds`
(43200), `recent_auth_seconds` (300), and `max_audio_bytes` (33554432). `--check-config` validates
without starting a listener. The listener accepts only loopback binds; expose it through the existing
tailnet HTTPS endpoint with the exact `public_origin`. Upstream credentials never follow redirects.
Run a single service process per data directory; the CLI enforces an exclusive process lock.

The owner signs into `/`. `GET /api/v1/health` reports listener health without workspace details.
The console's owner session is HttpOnly and SameSite=Strict, uses a CSRF token for writes and rejects
cross-origin access. Configured public HTTPS uses Secure cookies. Host-header validation prevents
DNS rebinding. Do not point a public unauthenticated reverse proxy at the listener.

## State and recovery

- Enrollment codes expire after ten minutes and are atomically consumed once. Device workspace and
  recovery tokens are separate and stored only as SHA-256 hashes on the host.
- Marking a device lost or revoked immediately denies new workspace and agent requests, removes its editing
  leases and cancels queued device tool calls. Work already admitted to Hermes may finish. Recovery check-ins continue. Commands do not imply
  delivery or application until a device acknowledges them.
- A local workspace erase also revokes workspace access, preventing immediate cache repopulation.
  The R1 retains recovery enrollment and requires a fresh pairing before restoring shared content.
- Local permissions cannot be enabled through the web console. Desired settings are limited to quiet
  mode, brightness, text size and screen timeout; device-applied settings are tracked separately.
- Lock, erase, reset, update and reboot require a recent browser sign-in, exact device confirmation,
  a reported capability and its local permission. MCP cannot issue destructive management actions.
- Prompt Builder saves and edits reusable text. On the R1, Run in new chat attaches a prompt to a
  fresh Hermes conversation and waits for Send. The browser remains an editor. Submission receipts
  preserve the exact sent text and source revision; retries retain the same request identity.
  Follow-up messages retain their chat's Hermes session. Replies and approvals belong to chat.
- Restarted in-flight admissions become `submission_unknown`. `POST /runs/{id}/reconcile {}` only
  reuses the original ID and exact payload if both original and fresh Hermes capabilities advertise
  durable deduplication and the retained window still covers the submission. Otherwise inspect the
  original Hermes host; the hub never guesses that an uncertain request failed.
- Speech transcription calls the configured OpenAI-compatible `/audio/transcriptions` endpoint.
  Audio remains intact after failures; a transcript cannot overwrite a concurrent note edit.

Back up the protected configuration plus `workspace.sqlite3` and the entire `media/` directory.
Stop the service before a file-copy backup, or use SQLite's backup API for a live database snapshot
and copy its referenced immutable media. Include WAL state if taking a raw live filesystem snapshot;
a database-file-only copy while running is insufficient. Restore with the service stopped. Keep
backups private because they contain notes, recordings, session records and host credentials.

Schema 4 creates the current tables directly and upgrades older databases in one transaction.
It removes unused prompt-version history, the superseded `lost_applied` flag, an unused control
timestamp and redundant submission metadata. Submitted text, source blocks, replies, request
identities, credentials and recovery policy remain intact. Newer database versions are refused
without changing their schema. Restore a pre-upgrade backup before rolling back to an older hub.
The R1 also compacts unused draft and command-receipt fields in its local cache without clearing it.

## Scale

Version 1 returns full note and prompt snapshots. Prompt detail uses
`GET /prompts/{id}/submissions` for that prompt's receipts and cached Hermes replies; it does
not acquire an editing lease. `GET /runs` remains available for existing R1 synchronization and
MCP clients. It never silently truncates saved items; this fits a personal workspace shared by a
few R1s. Very large histories increase response size and device
memory use. Paginated snapshots are future scaling work.

`GET /changes?after=N` pages at 1000 events with an explicit cursor, and `more` says another page
follows. `after=latest` returns only the current head, so a client that has just loaded its lists
starts there instead of replaying history. Events older than seven days are pruned. A cursor from
before the oldest kept event, or past the head of a replaced database, gets `reset: true` with the
head, and the client reloads its lists. A check-in that changes nothing but `last_seen` adds no
event; `GET /devices` still reports it. The R1 reads the feed on each 30-second sync and downloads
only the collections it names.

`GET /devices/{id}/check-ins?limit=N` (owner only, newest first, up to 1000) returns a week of
that R1's check-ins as `{at, result, app_version}`. `result` is `ok` or the refused check-in's
error code. Requests, paths and credentials are never stored.

## Validation

```sh
PYTHONPATH=server python3 -m unittest discover -s server/robotos_hub/tests -v
```

The tests exercise concurrent two-device saves and run requests, lease fencing, immutable snapshots,
recovery after interrupted admissions, retention-limited idempotency, transcription races, interrupted
media writes/uploads, credential scopes, CSRF/origin checks, lost-mode quarantine, command receipts
and local permission enforcement. These are software checks, not physical R1 acceptance evidence.
