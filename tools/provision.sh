#!/usr/bin/env bash
# Turns a rooted R1 (LineageOS GSI + Magisk) into the push-to-talk terminal. Safe to re-run, e.g.
# after changing tools/r1ptt.json or rebuilding the app.
#
# Usage: tools/provision.sh [--config tools/r1ptt.json] [--no-debloat] [--undo-debloat] [--density N]
#
#   --config        push settings and API keys from a JSON file (see tools/r1ptt.example.json)
#   --no-debloat    leave the system apps alone
#   --undo-debloat  re-enable everything in tools/debloat.txt, then stop
#   --density N     screen density (default 190: comfortable text on the 2.88" screen; 0 = leave)
set -euo pipefail
source "$(dirname "$0")/lib.sh"

CONFIG=""
DEBLOAT=1
UNDO=0
DENSITY=190
while [ $# -gt 0 ]; do
  case "$1" in
    --config) CONFIG=$2; shift ;;
    --no-debloat) DEBLOAT=0 ;;
    --undo-debloat) UNDO=1 ;;
    --density) DENSITY=$2; shift ;;
    *) die "unknown option $1" ;;
  esac
  shift
done

need adb
PKG=dev.r1ptt
adb wait-for-device

# Root for setup: adbd itself if the build allows it (LineageOS GSIs are userdebug, so no prompt on
# the R1), otherwise Magisk's su.
adb root >/dev/null 2>&1 || true
adb wait-for-device
if [ "$(adb shell id -u | tr -d '\r')" = 0 ]; then
  rootsh() { adb shell "$*"; }
else
  rootsh() { adb shell "su -c '$*'"; }
fi

if [ "$UNDO" = 1 ]; then
  say "Re-enabling debloated packages"
  grep -v '^\s*#' "$ROOT/tools/debloat.txt" | awk '{print $1}' | grep . | while read -r p; do
    adb shell -n pm enable "$p" >/dev/null 2>&1 && echo "  enabled $p" || true
  done
  exit 0
fi

say "Checking root (approve the Magisk prompt for Shell on the R1 if one appears)"
for i in 1 2 3 4 5 6; do
  rootsh id 2>/dev/null | grep -q 'uid=0' && break
  [ "$i" = 6 ] && die "No root over adb. Open Magisk → Superuser and allow Shell, then re-run."
  sleep 5
done

[ -f "$OUT/r1ptt.apk" ] && [ -f "$OUT/r1ptt-system.zip" ] || "$ROOT/tools/build.sh"

# A boot image patched from the command line leaves /data/adb/magisk empty until the Magisk app's
# "additional setup" runs, and `magisk --install-module` refuses ("Incomplete Magisk install").
# Do the same file copy from the Magisk APK here.
if ! rootsh 'test -x /data/adb/magisk/busybox && test -f /data/adb/magisk/util_functions.sh'; then
  APK=$(find "$ROOT/firmware" -maxdepth 1 -name 'Magisk-*.apk' 2>/dev/null | sort | tail -1)
  [ -n "$APK" ] || die "Open the Magisk app on the R1 once (it finishes its setup and reboots), then re-run."
  say "Finishing Magisk's setup from $(basename "$APK")"
  T=$(mktemp -d)
  unzip -q -j "$APK" 'assets/*.sh' 'assets/stub.apk' 'lib/arm64-v8a/*' -d "$T"
  for f in "$T"/lib*.so; do n=$(basename "$f" .so); mv "$f" "$T/${n#lib}"; done
  adb push "$T/." /data/local/tmp/magiskbin/ >/dev/null
  rm -rf "$T"
  rootsh 'mkdir -p /data/adb/modules /data/adb/magisk && chmod 700 /data/adb && cp -af /data/local/tmp/magiskbin/. /data/adb/magisk/ && chown -R 0:0 /data/adb/magisk && chmod -R 755 /data/adb/magisk && rm -rf /data/local/tmp/magiskbin'
fi

say "Installing the Magisk module (button remap + power defaults)"
adb push "$OUT/r1ptt-system.zip" /data/local/tmp/r1ptt-system.zip >/dev/null
rootsh magisk --install-module /data/local/tmp/r1ptt-system.zip

say "Installing the app"
adb install -r -g "$OUT/r1ptt.apk" >/dev/null
adb shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow

say "Granting the app root without a prompt"
APP_UID=$(adb shell pm list packages -U "$PKG" | tr -d '\r' | sed -n "s/^package:$PKG uid:\([0-9]*\).*/\1/p")
[ -n "$APP_UID" ] || die "Couldn't find the app's uid."
rootsh "magisk --sqlite \"REPLACE INTO policies (uid,policy,until,logging,notification) VALUES($APP_UID,2,0,1,0)\""

say "Making it the home screen (preserving Android lock and administrator settings)"
adb shell cmd package set-home-activity "$PKG/.HomeActivity" >/dev/null

say "Keeping more Android log history"
adb shell logcat -G 2M >/dev/null 2>&1 || warn "Couldn't resize the log buffers; they grow after the next reboot."
say "Display defaults"
adb shell settings put system screen_off_timeout 15000
adb shell settings put system screen_brightness_mode 0
adb shell settings put system screen_brightness 60
[ "$DENSITY" != 0 ] && adb shell wm density "$DENSITY"
# Android's own screens and the stock keyboard (its default Material You theme follows the system)
# go dark, colored from the R1's orange, so they match robotOS instead of flashing white.
THEME='{"android.theme.customization.system_palette":"FF4F12","android.theme.customization.accent_color":"FF4F12","android.theme.customization.color_source":"preset","android.theme.customization.theme_style":"VIBRANT"}'
adb shell cmd uimode night yes >/dev/null
adb shell "settings put secure theme_customization_overlay_packages '$THEME'"

if [ "$DEBLOAT" = 1 ]; then
  say "Disabling unneeded system apps (reversible: --undo-debloat)"
  INSTALLED=$(adb shell pm list packages | tr -d '\r' | sed 's/^package://')
  grep -v '^\s*#' "$ROOT/tools/debloat.txt" | awk '{print $1}' | grep . | while read -r p; do
    if printf '%s\n' "$INSTALLED" | grep -qx "$p"; then
      adb shell -n pm disable-user --user 0 "$p" >/dev/null 2>&1 && echo "  disabled $p" || warn "could not disable $p"
    fi
  done
fi

if [ -n "$CONFIG" ]; then
  [ -f "$CONFIG" ] || die "No such config: $CONFIG"
  say "Pushing settings and keys from $CONFIG"
  B64=$(base64 < "$CONFIG" | tr -d '\n')
  RESULT=$(adb shell am broadcast -n "$PKG/.data.ConfigReceiver" -a dev.r1ptt.CONFIG --es json_b64 "$B64" | tr -d '\r')
  echo "$RESULT" | grep -q 'data="ok"' || die "Config rejected: $RESULT"
fi

say "Rebooting to apply the module"
adb reboot
say "Done. After it boots, enroll under Host & devices using your workspace console. Paired devices verify access before use."
