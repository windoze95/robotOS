#!/usr/bin/env bash
# Render public screenshots from the app's Android views and synthetic demo data.
set -euo pipefail
source "$(dirname "$0")/lib.sh"

if [ -z "${JAVA_HOME:-}" ] && [ -d /opt/homebrew/opt/openjdk@21 ]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
fi

say "Rendering the GitHub screenshot gallery"
(cd "$ROOT" && ./gradlew :app:testDebugUnitTest --tests dev.r1ptt.ui.ScreensSnapshotTest.githubGallery --rerun-tasks)

mkdir -p "$ROOT/docs/screenshots"
for screen in home listening conversation menu chats messages quick-controls settings; do
  cp "$ROOT/app/build/ui-snapshots/github/$screen.png" "$ROOT/docs/screenshots/$screen.png"
done
say "Eight screenshots written to docs/screenshots/"
