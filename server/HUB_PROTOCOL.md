# robotOS shared workspace protocol

Implementation contract for the host, Android and web clients. Version 1 uses JSON over HTTPS
(loopback HTTP is allowed for development). The host owns canonical state. All IDs are UUIDs or
opaque server IDs. Timestamps are Unix milliseconds. Lists use `{ "items": [...] }`. Errors use
`{ "error": "readable message", "code": "stable_code" }` and appropriate HTTP status.

## Authentication

Base path `/api/v1`. Browser owner sessions use HttpOnly SameSite=Strict cookies and CSRF headers
(`X-CSRF-Token`) on mutations. `POST /session {password}` returns `{csrf_token}`; `GET /session`
returns authenticated owner/session details and the token; `POST /logout` ends the session.
The owner password is supplied by protected host configuration. Never put it in a URL.

Owner and MCP bearer tokens may be supplied from protected configuration for local integration.
MCP authority covers workspace and individually allowed device tools, never enrollment, lost-mode
administration, credential rotation, or erasure. Each R1 has separate workspace and recovery tokens.
Recovery tokens can only check in that device, receive its commands, and acknowledge those commands.
Lost/revoked devices lose workspace and agent access immediately; recovery communication survives.
This denies new submissions; work already admitted to Hermes may finish.

Owner `POST /enrollments {name}` returns `{code, expires_at}`. Device
`POST /enrollments/claim {code, name}` atomically consumes the short-lived code and returns
`{device_id, device_token, recovery_token}`. Code claims are rate limited. Credentials are shown
once, stored encrypted on Android, and stored hashed on the host.

## Notes and media

- `GET /notes` and `GET /notes/{id}` return notes.
- `POST /notes {id, title, kind, text, media_id?}` creates an idempotent note.
- `PUT /notes/{id} {base_revision, title, text}` updates a note, rejecting stale revisions.
- `DELETE /notes/{id}` requires `If-Match: <revision>`; existing prompt snapshots remain intact.
- `POST /media` accepts bounded audio bytes with Content-Type and X-Upload-Id (stable UUID).
  Returns `{id, sha256, size, content_type}` only after bytes are durably stored. Exact retry is
  idempotent; different bytes with the same ID conflict. `GET /media/{id}` downloads authorized media.
- `POST /notes/{id}/transcribe {base_revision}` transcribes through configured host speech endpoint;
  errors preserve original media. Return updated note; pending transcription is never fabricated.

Note shape: `{id,title,kind:"text"|"audio",text,media_id,revision,created_at,updated_at}`.
Audio notes are not synced until their media upload is confirmed. Keep local audio until then.

## Prompt Builder

- `GET /prompts` / `GET /prompts/{id}`.
- `POST /prompts {id,title,instruction,blocks:[]}` creates a draft.
- `POST /prompts/{id}/lease {takeover:false|true}` returns `{lease_token,expires_at,revision}`.
  A takeover fences the previous editor. Same owner/device renews without changing the token.
- `PUT /prompts/{id} {base_revision,lease_token,title,instruction,blocks}` commits a revision.
- `POST /prompts/{id}/release {lease_token}` releases only the matching editor lease.
- `POST /prompts/{id}/run {revision,request_id,run_again:false|true}` reserves one run atomically.
  Default Run attaches to the existing run for the revision. Explicit Run again creates a new
  attempt, still deduplicated by request_id. The request uses the immutable saved revision.
- `GET /runs` / `GET /runs/{id}`; `POST /runs/{id}/stop {request_id}`;
  `POST /runs/{id}/approval {request_id,approval_id,choice:"once"|"deny"}`.
- `POST /runs/{id}/reconcile {}` checks an uncertain submission with its original identity and
  payload, only within both original and freshly advertised Hermes durable deduplication windows.
  Different runs for the same prompt/session cannot overlap; same-revision default Run still attaches.
- `GET /changes?after=<sequence>` returns `{cursor,items}`. Clients also refresh on foreground.
- `GET /resume` / `PUT /resume {prompt_id,block_id,scroll_y,base_revision}` saves last shared place.

Prompt blocks: `{id, note_id?, kind:"text"|"audio", text, media_id?}`. Blocks are ordered;
their text is a snapshot editable independently from the source note. Original media remains
attached. Preview is instruction followed by nonempty block text separated by blank lines.
Run is rejected while an audio block lacks a usable transcript. New notes can be created directly
inside the builder. Removing a block never deletes its source note.

Prompt shape: `{id,title,instruction,blocks,revision,created_at,updated_at,last_run_revision,
last_run_id,state:"draft"|"running"|"ran"|"needs_attention",changed_since_run}`.
Run shape: `{id,prompt_id,prompt_revision,status,output,error,created_at,updated_at,approval?,snapshot}`.
Snapshot includes the immutable saved prompt plus exact `preview`; session ID remains stable per prompt.
Statuses: queued, submitting, submission_unknown, running, waiting_for_approval, stopping,
completed, failed, cancelled, interrupted. Mark Ran only after completed output is stored.
Unknown submissions keep the original identity and are never blindly replaced.

## Device management

- Owner `GET /devices`, `GET /devices/{id}`.
- Recovery `POST /devices/self/check-in {name?,app_version,battery,charging,capabilities,
  settings_revision?,settings?,location?,lost_applied?}` returns `{device_id,lost,lost_message,
  settings_revision,settings,commands:[...]}`. Capabilities are an object of boolean values.
  Location, when permitted, includes latitude,longitude,accuracy,observed_at; never invent a fix.
- Owner `PATCH /devices/{id} {base_revision,name?,settings?,lost?,lost_message?,revoked?}` updates
  desired state. Lost is a persistent policy. Desired settings remain distinguishable from applied.
- Owner `POST /devices/{id}/commands {id,type,payload,expires_at,confirmation?}` creates a durable
  targeted command; `GET /devices/{id}/commands` lists its receipts. Types: ring, stop_ring,
  show_message, lock, locate, clear_workspace, factory_reset, update_app, reboot.
  Dangerous commands require recent owner authentication and exact `confirmation: device_id`.
  Unsupported capabilities are rejected rather than shown as applied.
- Recovery `POST /devices/self/commands/{id}/ack {status,result?}`; statuses received, applied,
  failed, unknown. `result` is an object: use `{message:"..."}` for explanatory text or
  `{http_status,body}` for a native device-tool response. Android persists intent before execution
  and never repeats uncertain actions. A permanent conflicting receipt is retained for review
  without preventing unrelated workspace synchronization.
- Owner `GET /audit` returns sanitized administrative history (no prompt bodies or credentials).

### Scoped MCP device actions

- MCP/owner `GET /mcp/devices` returns eligible devices without credentials or management details.
- MCP/owner `POST /device-actions {id,device_id,tool,arguments,expires_at}` queues an allowlisted
  native device tool. Mutating tools require `arguments.request_id == id`. Reusing the ID requires
  the same target, tool and arguments, and retains the original expiration. Never pick an arbitrary
  device when multiple devices are eligible.
- MCP/owner `GET /device-actions/{id}` returns the durable receipt. `queued` is not `sent` or `applied`.
  Android executes through its existing DeviceApi, permissions and SMS quota. The `device_tool`
  capability depends on local native API permission, independently of new management permissions.
  Lost/revoked devices cannot receive agent actions; recovery credentials cannot issue them.

Local management is separately opt-in. Remote lock requires a configured secure Android lock;
factory reset requires supported device-policy enrollment and explicit local permission. Update
commands only install verified signed robotOS releases, honoring the established installer policy.
Clearing workspace revokes host workspace access, removes local shared data and audio, and requires
new enrollment to restore shared access. It preserves the server workspace and recovery identity.
`update_app` payload is `{version_code: positive integer}` and follows the official signed release
feed. Installer handoff remains unknown until a later check-in confirms that version is installed.
Radio recovery check-ins are off by default and must use the existing RadioPolicy.
Recordings and keys must not appear in application logs. Do not claim physical proof from tests.

The console is served from `/`. Host binds loopback by default and is exposed through existing
tailnet HTTPS. Neither R1 nor browser uses the Hermes provider/API secret; the host calls Hermes.
Deployment keeps workspace storage outside source directories; backups include database and media.
