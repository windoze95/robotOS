#!/usr/bin/env bash
set -euo pipefail
umask 077
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
TAG=${1:?Release tag required}
COMMIT=${2:?Commit SHA required}
DEST=${3:?Output directory required}
: "${ROBOTOS_KEYSTORE_PATH:?Explicit stable signing keystore required}"
: "${ROBOTOS_STORE_PASSWORD:?Store password required}"
: "${ROBOTOS_KEY_ALIAS:?Key alias required}"
: "${ROBOTOS_KEY_PASSWORD:?Key password required}"
: "${ROBOTOS_SIGNER_SHA256:?Verified installed signer fingerprint required}"
node "$ROOT/tools/release/version.mjs" "$TAG" >/dev/null
mkdir -p "$DEST"
DEST=$(cd "$DEST" && pwd)
cd "$ROOT"
./gradlew --no-daemon --no-build-cache --console=plain -PreleaseTag="$TAG" -PreleaseSigning=true :app:assembleRelease
cp app/build/outputs/apk/release/app-release.apk "$DEST/robotOS.apk"
# R8 mapping, so stack traces and lock samples from installed builds can be decoded.
cp app/build/outputs/mapping/release/mapping.txt "$DEST/mapping.txt"
node tools/release/manifest.mjs create "$DEST" "$TAG" "$COMMIT"
java tools/release/SignManifest.java "$DEST/payload.json" "$DEST/update.json" "$DEST/signer.cer"
node tools/release/manifest.mjs verify "$DEST"
(cd "$DEST" && shasum -a 256 robotOS.apk update.json signer.cer mapping.txt > SHA256SUMS)
rm "$DEST/payload.json"
