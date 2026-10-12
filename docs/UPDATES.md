# App updates and signed releases

This pipeline updates only the `dev.r1ptt` Android APK. It does not update Android, the kernel,
firmware partitions or the `r1ptt_system` Magisk module. Package/data identities and backend
configuration stay the same. There is no root installer, automatic uninstall, data reset,
background update polling, boot download or silent installation.

Release-specific changes and upgrade requirements are recorded in
[the v0.4.0 notes](releases/v0.4.0.md). The release tag supplies the APK version; ordinary local
builds keep their development version. When `docs/releases/vMAJOR.MINOR.PATCH.md` exists at the
tagged commit, the signed release workflow includes it in the published release notes.

The initial implementation has offline tests and local build/lint coverage. An R1 was not
attached during implementation. Installed-certificate verification, first bootstrap, Android's
installer UI and device recovery acceptance still need the explicit steps below. A passing CI
build does not establish successful installation, voice behavior or battery life on the R1.

## What runs in GitHub

- `Android checks` runs unit/tool tests, debug/release builds and lint on pull requests and on
  pushes to `main`, once per change. `Container checks and images` follows the same triggers.
  Release tags don't rerun them, because a tag must point at a commit already on `main`.
  `Android checks` has a read-only repository token, no release secrets and no device access.
  Its release-variant build uses an ephemeral runner debug identity for build validation only;
  that APK is not published or offered by the updater.
- `Signed app release` responds only to `v*` tag pushes in `windoze95/robotOS`, and is disabled
  unless the owner sets `ROBOTOS_RELEASES_ENABLED` to exactly `true`.
- Tags must be `vMAJOR.MINOR.PATCH`, without prerelease/build suffixes or leading zeros.
  `versionCode = major * 1,000,000 + minor * 1,000 + patch`, with major 0–2099, minor/patch
  0–999 and a result greater than 1. For example, `v0.2.0` is version code 2000. Tags must point
  to a commit already on `main` and increase the version code beyond every published release.
  A duplicate tag/release, unknown historical version format or downgrade fails closed.
- Validation runs before signing secrets are available. The signing/publishing job uses the
  `release` environment and a job-scoped `contents: write` token. No personal token is needed.
  Configure the environment protection described below **before** enabling the workflow.
- Releases are serialized. Version ordering is checked before validation, after environment
  approval and again before publishing. Assets are uploaded to a draft first; the release is
  made public/latest only after all uploads succeed. A failed draft is left for the owner to
  inspect; retries never replace an existing release automatically. Avoid simultaneous manual
  publishing outside this workflow, which cannot participate in its concurrency lock.
- The workflow uploads exactly `robotOS.apk`, `update.json`, `signer.cer` (public certificate)
  and `SHA256SUMS`. It does not upload a keystore, passwords, a Magisk ZIP or OS images.
  Third-party actions are pinned to full commit IDs. Release build credentials exist only in
  the signing step; the temporary keystore is removed on step exit.

Ordinary local `tools/build.sh` behavior remains unchanged: version 0.1.0/code 1, local debug
signer, `out/r1ptt.apk` and `out/r1ptt-system.zip`. A tagged build requires both
`-PreleaseTag=v…` and `-PreleaseSigning=true`; missing signing inputs cannot fall back to a
new debug key. Release build/signing inputs come from environment variables, not repository files.
After a tagged release is installed, a default local code-1 APK is older and Android will reject
it as a downgrade. Use the approved signer and a higher release tag for subsequent local builds;
do not force a downgrade or uninstall. The existing configuration broadcast action and on-device
settings remain available. Before rerunning legacy provisioning, explicitly prepare a compatible
APK at its existing `out/r1ptt.apk` path; provisioning itself has not gained an updater bypass.

## Owner bootstrap — requires explicit action

No signing secret, release environment, protection rule, repository variable or device install
permission was configured by this change. Follow these steps only when ready to approve that
specific action. Never paste a private key, keystore, password or base64 keystore into a chat,
PR, issue, command log or committed file. Base64 is an encoding, not encryption.

1. **Verify the installed public certificate and version first.** With the R1 attached, list
   devices and select its exact serial. Read its model and package path before pulling only the
   installed APK. These commands do not install or grant anything:

   ```sh
   adb devices -l
   adb -s SERIAL shell getprop ro.product.model
   adb -s SERIAL shell pm path dev.r1ptt
   adb -s SERIAL pull DEVICE_APK_PATH out/installed-r1ptt.apk
   "$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs out/installed-r1ptt.apk
   "$ANDROID_HOME/build-tools/35.0.0/aapt2" dump badging out/installed-r1ptt.apk
   ```

   Use the exact returned `base.apk` path and require one signer. Record the public SHA-256
   certificate fingerprint and installed version code. Do not pull app-private data or keys.

2. **Choose signing continuity.** The earlier app configuration signs release builds with the
   local debug key, but that alone does not prove which certificate is on this R1. The release
   signer must match the installed certificate exactly. Have the owner compare the public
   certificate of the existing signing key using a secure local keystore tool. If it matches,
   the owner can explicitly approve securely storing that same identity for CI. Consider that
   a general development debug keystore may be shared by other local builds before using it
   in hosted CI. If the key is missing or differs, stop: generating a new key does not preserve
   update compatibility. This updater deliberately rejects multisigner APKs and key rotation;
   a separately reviewed signing-lineage migration would be needed. Never uninstall/reset to
   work around a mismatch; that risks losing encrypted settings and conversation data.

3. **Configure GitHub protection, then hand off secrets securely.** The owner creates a
   `release` environment, requires an approving reviewer (preferably preventing self-review),
   restricts deployment to intended `v*` tags, and restricts who can push release tags. Do not
   enable releases before these protections exist. Keep the default workflow token read-only;
   the reviewed release job requests only `contents: write` for its duration. No broad token,
   root access, write access from forks or permanent device service is needed.

   Add these **environment secrets** via GitHub's secret UI or another owner-approved secure
   local handoff, without exposing their values to the assistant:

   | Secret | Value |
   |---|---|
   | `ROBOTOS_KEYSTORE_BASE64` | Base64 of the approved existing signing keystore |
   | `ROBOTOS_STORE_PASSWORD` | Keystore password |
   | `ROBOTOS_KEY_ALIAS` | Approved signing alias |
   | `ROBOTOS_KEY_PASSWORD` | Key password |

   Add environment variable `ROBOTOS_SIGNER_SHA256` with the verified installed certificate's
   lowercase 64-character hex fingerprint, without colons. The release script checks the APK
   and metadata signer against this public fingerprint. Keep a secure offline backup of the
   signing identity; the pipeline cannot recover a lost key.

4. **Enable and publish only after approval.** Set repository variable
   `ROBOTOS_RELEASES_ENABLED=true` only after the signing/protection checks. Review/merge the
   implementation, choose the next valid version above the installed and published codes,
   push that tag deliberately, review validation and approve its environment job. This is the
   point that creates a release; implementation/merge alone does not create one. Inspect all
   four public assets and the release run before treating the build as distributable.

5. **Bootstrap the updater once on the R1.** The old installed app has no updater. After a
   separately approved device installation, install the first verified higher-code APK with
   the same certificate through Android or `adb -s SERIAL install -r PATH_TO_VERIFIED_APK`.
   Do not use uninstall, downgrade (`-d`), grant-all (`-g`), root install or flashing commands.
   Preserve the existing app/data if installation fails. Verify the displayed name, installed
   version/certificate and acceptance checks before subsequent in-app updates.

6. **Approve Android's install-source setting on the device.** This change declares
   `REQUEST_INSTALL_PACKAGES`; that declaration does not grant permission to install silently.
   When the user presses **Install update**, robotOS explains the request and offers to open
   Android's “allow from this source” settings. The user must choose whether to enable it, then
   return and press Install again. Each session explicitly requires Android user confirmation.
   The permission can be turned off afterward. Provisioning does not grant it and this change
   adds no privileged install/update-ownership permission.

## What the app trusts

**Settings → App updates → Check for updates** fetches
`https://github.com/windoze95/robotOS/releases/latest/download/update.json` without a token or
provider credentials. There are no automatic checks. Only HTTPS on the canonical repository's
release paths and GitHub's `release-assets.githubusercontent.com` / `objects.githubusercontent.com`
asset hosts is allowed, with bounded redirects, transfer sizes and timeouts.

`update.json` is an envelope with base64 `payload` and `signature`. The signature covers the
exact payload bytes (SHA256withRSA or SHA256withECDSA according to the installed APK's public
key). The app obtains its trust anchor from its own current APK signing certificate, never
from the downloaded `signer.cer`. It verifies the signature before reading release-controlled
fields, then validates schema 1, repository, package, tag/version mapping, APK filename, size,
SHA-256 digest, certificate fingerprint, Android minimum and source commit. New metadata advances
a persistent highest-seen version fence; older metadata/installed-version downgrades are rejected.
This cannot prove freshness when a server withholds a release or when the device is offline.

Downloads go to app-private cache. Size and SHA-256 must match the signed payload; only a complete
verified file is promoted from `.part`. Android's archive parser must report the expected package,
version, minimum SDK and sole current signer. The app reauthenticates metadata and rechecks the
file immediately before staging. Android performs the final APK signature/install validation.

To inspect a public bundle independently, place its four assets in one directory, set
`ROBOTOS_SIGNER_SHA256` to the previously trusted fingerprint, and run:

```sh
node tools/release/manifest.mjs verify PATH_TO_RELEASE_DIRECTORY
```

`SHA256SUMS` is convenient for transfer checks, but an unsigned checksum alone is not an identity
check. The signature in `update.json` plus the independently trusted certificate is the authority.

## Voice, cancellation and recovery

- Checks/downloads run only while the update screen is foreground. Leaving it, locking the
  screen or starting a voice/typed turn cancels network work. Partial files are removed; retry
  starts a new transfer. No wake lock, wake alarm, radio override or background polling is added.
- Installation is admitted on the main thread only while foreground and the voice controller
  is idle. A fence then blocks new voice turns until the
  installer completes or is cancelled. Existing recordings/replies are never interrupted to
  start an install. Unsent typed text is retained. A press blocked by the fence stays blocked
  through its release, even if installation ends mid-press.
- The app stages a full APK, fsyncs it and commits an Android PackageInstaller session with
  `USER_ACTION_REQUIRED`. A non-exported receiver accepts only the saved session's result via
  an explicit mutable PendingIntent. Pending user action is shown on the update screen; Android
  confirmation is launched only after the user's button press. Nothing launches from background.
- Network, malformed metadata, bad signatures, checksum/identity mismatches, insufficient space,
  unsupported Android, permission denial, cancellation and installer failures are surfaced as
  retryable failures without uninstalling or clearing app data. No unsafe filename/URL or provider
  error body is shown as executable guidance.
- A bounded five-minute installer handoff timeout attempts to abandon its own session. Process
  restart abandons a saved incomplete session instead of resuming installation automatically.
  If cancellation cannot be confirmed, the install fence stays held and App updates offers
  another cancellation attempt. Installer acceptance/cleanup behavior on the R1 still needs
  device testing; cancellation after Android has begun committing may race completion.
- Failed or interrupted installation should leave Android's previous app/data in place, but
  hardware acceptance must verify this. After an installed regression, prefer a **forward fix**
  with a higher version code and the same signer. The app deliberately cannot downgrade. If it
  cannot open, a separately authorized same-signer higher-code `adb install -r` is the recovery
  route. Keep a known-good source tag/signing backup and rebuild it at a higher code when needed.
  This is not an automatic rollback system or a guarantee of data recovery after a bad release.

## Validation and required device acceptance

Run these without secrets or a device:

```sh
node --test tools/acceptance.test.mjs tools/release/*.test.mjs
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug
actionlint .github/workflows/*.yml
shellcheck tools/release/build-release.sh
```

Regression tests use synthetic APK bytes and ephemeral in-memory test keys (no operational
credentials). They cover authentication/tampering, malformed/oversized metadata, wrong repository,
package/certificate/version/SDK, replay/downgrade, exact/truncated/oversized/wrong-hash downloads,
interrupted/offline streams, cancellation cleanup, foreground/busy admission, stale/wrong-session
installer callbacks, pending/failed/unknown installer statuses, redirect policy,
version ordering across paginated release history and release-APK inspection.

Before relying on in-app delivery, complete these on an explicitly selected R1 using an
approved same-signer test release. **These device checks have not been performed:**

| Scenario | Required observation |
|---|---|
| First bootstrap and normal update | Certificate/package match; code increases; settings/history and PTT still work |
| No Wi-Fi / interrupted transfer / screen off | Clear retry state, no installable partial file, no continued background network |
| Active capture, dictation, reply or typed send | Install refused; conversation completes normally; unsent text preserved |
| Hold starts during download or installer handoff | Download cancels for voice; installer fence prevents capture until finished/cancelled |
| Install-source permission denied/revoked | No session commits; app explains the user-controlled setting |
| Android confirmation cancelled, backgrounded or timed out | Own session abandoned; voice works afterward; no unexpected install |
| App process killed during staging/confirmation | Next startup recovers safely; no automatic retry; app data unchanged |
| Tampered APK/metadata, wrong signer, older version | Rejected before install; current app remains usable |
| Low storage / Android install failure | Useful failure; session/files cleaned; retry works |
| Successful replacement and forward-fix recovery | Fresh version/certificate readback, retained encrypted config/history, PTT/power acceptance |

Official references: [Android app signing](https://developer.android.com/studio/publish/app-signing),
[versioning](https://developer.android.com/studio/publish/versioning),
[PackageInstaller user action](https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams#setRequireUserAction(int)),
[pending user action](https://developer.android.com/reference/android/content/pm/PackageInstaller#STATUS_PENDING_USER_ACTION),
[GitHub token permissions](https://docs.github.com/actions/reference/authentication-in-a-workflow),
[protected environments](https://docs.github.com/actions/deployment/targeting-different-environments/using-environments-for-deployment),
and [Actions security](https://docs.github.com/en/actions/reference/security/secure-use).
