#!/system/bin/sh
# r1ptt-system: power and UI defaults for a push-to-talk R1, re-applied after every boot.
# service.log (next to this file) records each command's exit status.
#
# Each command's own output goes to /dev/null: `cmd`/`settings` hand their stdio to system_server
# over binder, and SELinux refuses a file under /data/adb ("Failed transaction"), so redirecting
# them into the log would make every call fail.
MODDIR=${0%/*}
LOG=$MODDIR/service.log

run() {
  "$@" </dev/null >/dev/null 2>&1
  echo "$? $*" >>"$LOG"
}

(
  until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 2; done
  sleep 5
  echo "== boot $(date)" >"$LOG"

  # Give the 2.9" screen back: drop the keyboard's own back / IME-switch strip.
  run cmd overlay fabricate --target android --name R1PttImeNavBar android:bool/config_imeDrawsImeNavBar 0x12 0x0
  run cmd overlay enable com.android.shell:R1PttImeNavBar

  # 2 MB log buffers instead of 256 KB: minutes of Android logs become about an hour. Applies from
  # the next boot; provisioning resizes the running buffers. robotOS keeps its own event log too.
  run setprop persist.logd.size 2097152

  # Airplane mode never touches Wi-Fi; the app owns Wi-Fi's idle cut and keeps the modem off.
  run settings put global airplane_mode_radios cell,bluetooth,nfc,wimax,uwb

  # No background scanning, no location, no Bluetooth.
  run settings put global wifi_scan_always_enabled 0
  run settings put global ble_scan_always_enabled 0
  run settings put global wifi_wakeup_enabled 0
  run settings put global network_recommendations_enabled 0
  run settings put global mobile_data_always_on 0
  run settings put secure location_mode 0
  run cmd bluetooth_manager disable

  # Nothing lights the screen except the button.
  run settings put secure doze_enabled 0
  run settings put secure doze_always_on 0
  run settings put secure doze_pick_up_gesture 0
  run settings put secure doze_tap_gesture 0
  run settings put secure wake_gesture_enabled 0
  run settings put global stay_on_while_plugged_in 0
  run settings put system screen_brightness_mode 0

  # Doze sooner: light idle right after screen-off, deep idle 30 s later. Android 12+ reads
  # these from DeviceConfig; the global setting covers older builds.
  for kv in light_after_inactive_to=0 inactive_to=30000 sensing_to=0 locating_to=0 \
            motion_inactive_to=0 idle_after_inactive_to=0; do
    run device_config put device_idle "${kv%%=*}" "${kv#*=}"
  done
  run settings put global device_idle_constants \
    light_after_inactive_to=0,inactive_to=30000,sensing_to=0,locating_to=0,motion_inactive_to=0,idle_after_inactive_to=0

  # Shorter animations: less GPU time per screen change on the A53 cores.
  run settings put global window_animation_scale 0.5
  run settings put global transition_animation_scale 0.5
  run settings put global animator_duration_scale 0.5

  # Record which keylayouts won, to debug the button and wheel remaps.
  dumpsys input | grep -E 'KeyLayoutFile: .*(mtk-kpd|och1970)' >>"$LOG"
) &
