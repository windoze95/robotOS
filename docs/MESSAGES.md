# Messages

robotOS never interprets texting requests itself; the R1 is not an agent. Hermes sends texts through
its `robotos` tools, and your own texts to the R1 go to Hermes ([HERMES.md](HERMES.md)). Everything
below is the R1's own SMS handling: drafts you write, Android's sending, receiving and receipts.

Messages is a personal SMS handler inside the existing robotOS APK. It uses Android’s SMS
service and your SIM. You can choose robotOS as the default SMS app through Android’s role
dialog, or use its optional SMS companion controls. It does not replace the GSI, enable the stock
Messaging app, or add a messaging backend.

## Use

Open the message icon on Home. **New text** opens a single-recipient draft. **Review text**, the
keyboard action, and a side-button tap in Messages all stop at recipient/message review. **Send SMS**
in that review dispatches a manual draft.
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
- **Saved recipients** stores names and exact numbers you enter on this device, so Hermes can text
  someone by name. Names match without case sensitivity (“my mom” finds a saved “Mom”); a name
  saved with two numbers needs the number instead. Spoken numbers (“four oh five, five five five…”)
  are converted locally and get +1 on a US SIM. No number is ever guessed.

## Delivery, privacy, and sleep

Drafts, recipients, and conversations live in the private `messages.db`, separate from AI history.
Received texts are not forwarded to AI, spoken aloud automatically, or included in notifications,
except texts from your own number when Hermes passthrough is on ([HERMES.md](HERMES.md)).
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

JVM and Robolectric tests use synthetic data and a fake transport: address validation, spoken and
written numbers, local/ambiguous recipient resolution, Hermes's device API and passthrough, draft
ownership, durable SQLite transactions,
process restart, replay protection, part aggregation, uncertain failures, permission/SIM changes,
manifest protection, role entry points, records saved by older builds, exact system-provider copies,
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
5. Check cloud-dictation consent, Home PTT/audio, app update fences, and draft recovery after app
   restart. Hermes's texting has its own checks in [HERMES.md](HERMES.md).

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
