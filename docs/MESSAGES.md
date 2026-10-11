# Messages

With the **Hermes Agent** provider, robotOS does not interpret texting requests itself: Hermes
sends texts through its `robotos` tools, and your own texts to the R1 go to Hermes. See
[HERMES.md](HERMES.md). The on-device assistant below applies to the other providers.

Messages is a personal SMS handler inside the existing robotOS APK. It uses Android’s SMS
service and your SIM. You can choose robotOS as the default SMS app through Android’s role
dialog, or use its optional SMS companion controls. It does not replace the GSI, enable the stock
Messaging app, or add a messaging backend.

## Use

Open the message icon on Home. **New text** opens a single-recipient draft. **Review text**, the
keyboard action, and a side-button tap in Messages all stop at recipient/message review. **Send SMS**
in that review dispatches a manual draft. Separately enabled assistant commands can send directly.
Long messages are split by Android; the review shows the part
count. The limit is ten SMS parts and 1,600 draft characters. Pictures, attachments, group MMS,
RCS, and importing old SMS are outside this version.

Messages → **Options** has separate opt-ins:

- **Enable SMS sending** requests Android `SEND_SMS` access. A valid default SMS SIM and the
  existing **Use cellular data (SIM)** setting are required. The SIM is checked again at send.
- **Enable incoming texts** requests `RECEIVE_SMS` and saves new SMS after companion opt-in.
  As the default SMS app, reception stays enabled until you choose another default app.
- **Make robotOS the default SMS app** explains the SMS-only limits and opens Android’s
  `RoleManager.ROLE_SMS` chooser. The role grants the declared SMS access: send, receive,
  read, and receive MMS notices. `READ_SMS` is used only to recover an exact known app-owned
  system-storage row after a crash; existing inboxes and contacts are not imported or scanned.
- **SMS diagnostics** shows the selected subscription, carrier, device SMS capability, SIM/airplane
  state, and SMS permissions. A sent attempt’s **Message details** preserves the Android result,
  radio error when supplied, and dispatch-time evidence. Older failed attempts remain intact;
  fields the older build did not capture are marked unavailable. Device capability and working
  mobile data do not prove the carrier plan includes SMS.
- **Enable dictation for this visit** discloses the configured transcription destinations.
  Audio leaves the device only after this opt-in and a hold in the compose screen. Release
  inserts words into the draft. The draft, recipient, and prior texts are not supplied as context.
  Changing endpoints invalidates consent. Messages dictation clips are excluded from `saveClips`.
- **Enable assistant SMS sending** recognizes completed requests like “Tell Sam I’m on my way”
  and “Send a text to +15551234567”. It writes a short, natural message from your intent, adding a
  greeting or paraphrasing as appropriate. With no topic, it writes a brief neutral greeting.
  Say “Text Sam exactly: MESSAGE” or “Text Sam word for word: MESSAGE” to preserve the entire
  supplied message verbatim. Numbers may be spoken digit by digit or in groups (“four oh five,
  five five five…”); the AI copies them as said and robotOS converts and checks the digits locally,
  adding +1 on a US SIM. Leading words such as “Okay,” “Hey robot,” or “Try again.” are fine, and
  “my mom” matches a saved “Mom”. When a request can't be carried out, the reply says why: the
  recipient didn't match what you said, it wasn't a direct request, or the exact wording was unclear. Completed requests send directly without a review step when the
  recipient is an exact number or a unique saved name. Android SMS access, a default SMS SIM,
  and the cellular setting are still required. The
  old draft-only opt-in does not enable direct sending; enable this option once. Voice uses the
  configured transcription endpoint, followed by the selected chat provider. This adds an action
  interpretation step before the reply. Contextual requests such as “send that to her”, scheduled
  sends, and multiple recipients require clarification.
- **Recent assistant outcomes** shows at most 50 local outcomes from the last three days. Each
  contains a random action ID, timestamp, input source, result/category, and optional numeric
  error code. No recipient, body, transcript, URL, API key, or raw provider error is retained here.
  Older builds did not record these outcomes, so earlier attempts cannot be reconstructed.
- **Saved recipients** stores names and exact numbers you enter on this device. The assistant
  receives no contact list. Exact names match without case sensitivity; different numbers under
  the same name require a local choice. Missing names leave the phone field empty. No phone
  number is inferred from an AI response or invented.

While assistant sending is enabled, every completed request—including `Text NAME: MESSAGE`—uses
a bounded, stateless interpretation/composition request at the selected chat endpoint, with only
the current user input. The full response must finish normally and copy one recipient from that
input. The AI may compose the body but cannot invent or expand a recipient. It is instructed to
convey the requested meaning without inventing facts, names, times, or commitments. Explicit exact
controls use a separate response mode checked against the entire locally identified literal body;
they cannot fall through to paraphrasing. Invalid or incomplete responses report no text sent. No
action is executed from partial streamed output. The interpreter receives no contact list, SMS
database, or conversation history; Hermes uses a fresh session. Saved recipient names resolve
locally after extraction.

The current GPT-Live speech-to-speech protocol has no client device-tool bridge. While assistant
SMS sending is enabled, Home voice turns use completed transcription → intent → native action
or ordinary chat/TTS. This applies to OpenAI, Hermes, and custom chat providers. GPT-Live remains
available when assistant sending is off. Interrupted or failed turns do not send. A command
is consumed once; a new press or turn invalidates a pending recipient lookup. Missing or ambiguous
recipients leave a clarification on Home instead of switching into a private draft. Repeat the
complete request with an exact number, or save the recipient in Messages first. Assistant requests
preserve any existing manual draft.
Completed recognized commands are not copied into AI chat history. Provider speech is not evidence
of SMS dispatch: Messages opens the stored attempt and displays Android's actual send and delivery
status. Model replies, incoming texts, compose intents, and restored activities cannot trigger an
assistant send.

Unclear requests, interpretation failures, local recipient problems, and send outcomes leave a generic
explanation in the Home conversation and speak it when voice replies and the network are available.
Speech starts after the executor reports its result; it never submits or retries a text. A new press
cancels old feedback. The intercepted request, recipient, and body are not added to chat history.
Ordinary chat instructions such as “Reply in one sentence”
remain chat even though they contain words also used for messaging.

The outcome journal distinguishes request resolution, clarification, cancellation, API/network
failure, Android handoff, and native sent/delivery results. New clarification entries distinguish
missing or unsupported request details (`REQUEST`) from local recipient lookup (`RECIPIENT`).
Older clarification entries cannot distinguish these causes. A completed AI turn never means a text
was sent. A handoff with no result after two minutes displays unknown and must not be retried
automatically. Native status callbacks remain authoritative; success requires sent callbacks for
every SMS part, and delivery requires a carrier report.

## Delivery, privacy, and sleep

Drafts, recipients, and conversations live in the private `messages.db`, separate from AI history.
No received texts are forwarded to AI, spoken aloud automatically, or included in notifications.
Notifications contain generic text only; message bodies and recipients are never previewed. The recent view shows the newest 100 conversations
and 100 texts per conversation; older records remain stored. There is no export or backup UI yet.

An outgoing attempt and its per-part identities are committed before calling `SmsManager`.
Sent and delivery callbacks use explicit, non-exported receivers and unique per-attempt/part
PendingIntents. Incoming broadcasts require Android's `BROADCAST_SMS` sender permission. The
external `SENDTO` activity accepts one bounded SMS draft. The protected `RESPOND_VIA_MESSAGE`
service queues a durable call-reply draft and private notification; the user must review it in
Messages and confirm **Send SMS**. Neither entry point transmits automatically. Replayed incoming
PDUs and duplicate status callbacks are idempotent.

**v0.3.0 callback defect:** its receiver called `goAsync()` before reading `resultCode`. Android
clears the receiver’s pending result during that call, so even a successful callback was saved as
zero and shown as “Not sent.” The receiver now captures the result first. Old zero-valued rows
retain their original evidence but display an unknown status, not a failure claim. A recipient’s
confirmation is separate from a native carrier delivery report; no historical result is fabricated.

**Sent** requires success for every part; **Delivered** requires a positive carrier report for every
part. A callback without a valid successful status PDU is not delivery proof. Missing send results
become **Send status unknown** after two minutes. Partial send and carrier-reported delivery failure
have separate states. There are no automatic retries, including after process death or reboot.
“Use as a new draft” warns that an uncertain previous text may have arrived and still requires review.
The in-app updater waits for an active SMS handoff to settle or reach its bounded uncertainty window.

SMS does not wait for internet connectivity. An explicit send asks the existing radio policy to
wake radios it put to sleep; it does not modify that policy, APNs, roaming, or background limits.
There is no receiving foreground service, polling job, SMS wake lock, or continuous modem lease.
The existing idle cut can therefore delay or prevent reception. Carriers may retry queued SMS
after the modem reconnects, subject to their retention and retry policy; this device/carrier path
has not been validated. SMS entitlement is separate from having working mobile data.

Android marks SMS permissions as hard restricted. If normal setup is denied, Messages retains
drafts and reports unavailable access. The explicit role control uses Android’s supported chooser;
it does not alter permission allowlists, root permissions, or app-ops.

While holding the default role, `SMS_DELIVER` is authoritative; the observer `SMS_RECEIVED` path
is ignored. New incoming and outgoing SMS are journaled locally and copied to Android’s SMS
provider. Callback status changes update only that known row. Interrupted provider-copy work can
recover on the next Messages visit without resending. Earlier companion history is never backfilled.

MMS remains unsupported. The protected `WAP_PUSH_DELIVER` receiver retains bounded original
MMS push data locally and presents a persistent unsupported-MMS notice. It does not download,
acknowledge, or claim to import MMS into the system inbox. Choose an MMS-capable default app and
ask the sender to resend if needed; switching apps alone is not a recovery guarantee. The role
chooser disclosure makes this limitation explicit. `mms:`/`mmsto:` compose requests are rejected
with a visible explanation rather than silently converted into SMS.

## Validation and live acceptance boundary

JVM and Robolectric tests use synthetic data and a fake transport: address validation, explicit
command parsing, natural intent extraction with mocked streaming providers, typed/completed-voice
dispatch, cancellation and malformed/incomplete responses, bounded content-free outcomes,
local/ambiguous recipient resolution, stale and repeated
completion rejection, draft ownership, durable SQLite transactions,
process restart, replay protection, part aggregation, uncertain failures, permission/SIM changes,
manifest protection, role entry points, version-one data migration, exact system-provider copies,
MMS-notice persistence, call-reply drafts, result diagnostics, and compose/button behavior. The existing voice, configuration, updater,
release tooling, builds, and lint checks remain in the suite. These are not carrier acceptance tests.

Before a live-device SMS test, obtain explicit approval for installation, the precise SMS runtime
permission changes, the consenting test recipient and number, the message content, and any return
text. Only change the default role if explicitly approved. Do not enable the stock app, RCS, or hidden
permission workarounds. Then verify:

1. Opening Messages asks for no permissions. Decline setup; drafts remain usable and no text sends.
2. Enable only the agreed access. Prepare a synthetic text to the agreed recipient; cancel review
   once and verify no send. Confirm once and check both local sent status and the recipient's report.
3. Repeat multipart/Unicode only if included in the test approval. Do not infer delivery from
   “Sent”; missing carrier receipts remain unconfirmed.
4. Test optional incoming reception while awake and after the normal radio idle cut. Verify the
   modem still sleeps; document actual retry delay and any missed/expired text instead of promising it.
5. Check direct assistant sending only with a separately approved real recipient and body. Use fake
   transport tests for missing/duplicate-name clarification and stale/repeated completion. Check cloud-dictation
   consent, Home PTT/audio, app update fences, and draft recovery after app restart.

No live SMS send, incoming-message inspection, default-role change, or SMS permission grant is
performed by the build or automated tests.

## Platform references

- [Android roles: required SMS components](https://source.android.com/docs/core/permissions/android-roles)
- [Android RoleManager: supported consent flow](https://developer.android.com/reference/android/app/role/RoleManager)
- [Android Telephony: default-role and SMS_RECEIVED behavior](https://developer.android.com/reference/android/provider/Telephony)
- [Android 14 BroadcastReceiver: goAsync/resultCode lifecycle](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android14-release/core/java/android/content/BroadcastReceiver.java)
- [SmsManager: send results and delivery reports](https://developer.android.com/reference/android/telephony/SmsManager)
- [Android SMS permission restrictions](https://developer.android.com/reference/android/Manifest.permission#SEND_SMS)
- [Android IMS single registration: RCS privilege/provisioning requirements](https://source.android.com/docs/core/connect/ims-single-registration)
