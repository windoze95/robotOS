# Installing robotOS on a stock R1

This takes about an hour of hands-on time once Rabbit has approved developer mode.

**Know the risks before you start:**
- Unlocking permanently voids the R1's warranty.
- A bad flash on a MediaTek device can leave it unbootable. Rabbit's flash tool can always put
  stock rabbitOS back.

## What you need

- A Mac with this repo, plus:
  - `adb` and `fastboot` (`brew install android-platform-tools`)
  - `jq`
  - `uv` (for `tools/fastboot-entry.py`)
  - Google Chrome (for Rabbit's flash tool)
- A USB-C data cable.
- An OpenAI API key, for speech (transcription and reading replies aloud).
- An always-available host for Docker Compose and a model/provider for
  [Hermes Agent](https://github.com/NousResearch/hermes-agent). The primary setup runs the shared
  robotOS service and Hermes together. Native macOS/Linux, k3s and separate hosts are supported
  alternatives ([DEPLOYMENT.md](DEPLOYMENT.md)).
- Downloads, all saved into `firmware/` (git-ignores it):
  - **Stock rabbitOS v0.8.293**: `rabbit_OS_v0.8.293.zip` from
    https://github.com/rabbit-hmi-oss/firmware/releases. Unzip it to `firmware/stock/`.
  - **LineageOS 21 GSI**: `lineage-21.0-YYYYMMDD-UNOFFICIAL-arm64_bvN.img.gz` from
    https://sourceforge.net/projects/andyyan-gsi/files/ (folder `lineage-21-pre-qpr2-td`; the
    vanilla `arm64_bvN` file, not `bgN`, `a64` or `vndklite`).
  - **Magisk**: `Magisk-v30.x.apk` from https://github.com/topjohnwu/Magisk/releases.

## 0. Check speech from your Mac

```sh
cp tools/r1ptt.example.json tools/r1ptt.json      # set openaiKey for local speech; pairing supplies the workspace connection
tools/build.sh                                     # builds out/r1ptt.apk and out/r1ptt-system.zip
tools/smoke-test.sh --play                         # speech-to-text and text-to-speech (and Hermes, if reachable)
```

The example config contains only local speech settings and device preferences. Shared host
credentials do not belong there; enrollment supplies a separate workspace identity later.

If the smoke test fails, fix the config before touching the R1. For example, if a model name is
rejected, change `stt.model` or `tts.model`.

## 1. Developer mode and bootloader unlock (Rabbit's official route)

1. In OS3, go to **settings → r1**. Developer mode only appears once the R1 is linked to your
   account. Type the warranty acknowledgment, enter the R1's IMEI (on the R1: settings → about), and
   click **void warranty and enable developer mode**. Each R1 needs its own request.
2. With the R1 on and online, go to **settings → r1 → device modification** and choose **unlock**.
   This isn't an OTA: Rabbit's servers only switch on the R1's permission to be unlocked. Nothing is
   unlocked yet.
3. Enter fastboot mode with the R1 powered off. You can use either route:
   - **Chrome:** open https://rabbit-hmi-oss.github.io/flashing/ and click "Enter Fastboot Mode".
     Plug the R1 in, and select "MT65xx Preloader" within about 1.5 seconds.
   - **Terminal:** run `uv run --with pyserial tools/fastboot-entry.py`, then plug the R1 in.
4. Check that the permission arrived: `fastboot flashing get_unlock_ability` should print `1`.
   - If it prints `0`, reboot the R1 (`fastboot reboot`), let it sit online for a minute, and repeat
     step 2.
5. Run `fastboot flashing unlock`. **This wipes the R1.** If it asks for confirmation, confirm with
   the side button.
6. `fastboot getvar unlocked` should now say `yes`.

Optional, but cheap insurance: back up the partitions that hold the IMEI and radio calibration.
Nothing in this guide writes to them, but MediaTek devices are notorious for losing them.

```sh
uv tool install git+https://github.com/bkerler/mtkclient     # needs libusb: brew install libusb
mtk r nvram,nvdata,nvcfg,protect1,protect2,proinfo nvram.bin,nvdata.bin,nvcfg.bin,protect1.bin,protect2.bin,proinfo.bin
```

## 2. Stock baseline (usually skip it)

Skip this on an R1 that is simply up to date. `tools/flash-gsi.sh` keeps Rabbit's current kernel and
vendor, on whichever slot the R1 boots from, and those already match its bootloader. That's how the
first R1 was done (2026-10-03, Rabbit kernel from September 2026).

Flash the v0.8.293 baseline only if the R1 is in an unknown or broken state. Use either route:
- **Rabbit's flash tool → Flash Stock ROM.** It writes the full matching set, then **locks the
  bootloader again**, so run `fastboot flashing unlock` once more afterwards.
- **Command line:** with the R1 in fastboot, run `tools/flash-baseline.sh firmware/stock`. This leaves the
  bootloader chain alone, so on an R1 that has taken OTAs it pairs newer firmware with the older kernel.

## 3. LineageOS GSI

```sh
uv run --with pyserial tools/fastboot-entry.py     # then plug in the powered-off R1
tools/flash-gsi.sh firmware/lineage-21.0-*-arm64_bvN.img.gz firmware/stock
```

The stock folder only supplies the vbmeta and empty-userdata images. The script:
- turns verified boot off;
- wipes data the way Rabbit's restore does;
- removes Rabbit's `product` partition (its launcher and services) on the active slot;
- writes the GSI to the active slot's `system` and reboots.

The first boot takes a couple of minutes. The LineageOS GSI is a userdebug build with adb open, so
`adb devices` should list the R1 right away. Go through setup on the R1 and connect to Wi-Fi. If
everything is too big to tap, run `adb shell wm density 190`.

## 4. Root with Magisk

```sh
tools/root.sh
```

The script does this without any tapping on the R1:
- installs the Magisk app;
- copies the R1's own current boot image off over `adb root`;
- patches it with Magisk's own script, on the R1;
- flashes it to the active slot and reboots.

The unpatched image is kept in `out/`; flash it back with `tools/flash-boot.sh` to unroot.
Magisk shows "additional setup" until `provision.sh` finishes its file setup in the next step.

## 5. Provision

```sh
tools/provision.sh --config tools/r1ptt.json
```

The script makes these changes:
- Finishes Magisk's setup, and installs the Magisk module (button and wheel remaps, power defaults).
- Installs the app with its permissions.
- Grants the app root.
- Makes the app the home screen.
- Preserves the existing lock screen and device-admin state. Configure a PIN/password and grant
  device-admin access locally if you want Android secure locking; provisioning does not grant it.
- Sets a 15 s screen timeout and low brightness.
- Turns on Android's dark theme, colored from the R1's orange, so Android's own screens and the
  keyboard match robotOS.
- Disables the bloat in `tools/debloat.txt`.
- Pushes your local speech settings and device preferences from the supplied config.
- Reboots.

It's safe to re-run, e.g. after editing the config or rebuilding. `--undo-debloat` re-enables the
disabled apps.

Then follow the [Docker Compose quickstart](DEPLOYMENT.md#primary-setup-docker-compose-with-robotos-and-hermes)
to install the shared hub and Hermes from published images, initialize private credentials,
configure the model/provider and expose HTTPS. Sign in to the console with its locally saved
owner password. Create a separate enrollment code for each R1 and enter the hub URL and code
under **Workspace settings**. The host's Hermes key is never imported into a paired R1.

For an existing native Hermes installation, the alternative host command is:

```sh
tools/hub-setup.sh --public-origin https://YOUR-HOST.YOUR-TAILNET.ts.net:9443
```

This native command configures the hub, authenticated loopback Hermes API and scoped MCP adapter,
starts both services and checks them. It preserves existing credentials and data. HTTPS routes
remain explicit; see [HUB.md](HUB.md#native-installation-alternative). macOS services require a
logged-in session; Linux user services need lingering for availability after logout.

Keep the host awake and reachable. A paired R1 verifies recovery status on wake. Within 15 minutes
of a confirmed check it opens at once and confirms in the background; otherwise it checks before
showing saved content or accepting normal actions, and an unreachable host keeps it restricted.
Network and recovery controls remain available. Enable device tools and recovery permissions
on the R1 only as needed. Pairing does not enable SMS, location or remote locking.

## 6. Check that it works

Work through these with the R1 on USB:

1. **Voice, keyboard closed.** Hold the button, ask something, let go. Hermes's answer appears and
   is read aloud. The normal screen timeout still applies during remote work and playback.
2. **A follow-up** keeps the context (Hermes keeps the conversation). While Hermes uses a tool, the
   status line shows it ("Using …").
3. **Wake and recovery verification.** Let the screen turn off and wake it: within 15 minutes of
   the last check it opens at once. After longer, or after a restart, it shows the glowing robotOS
   mark while it checks and then opens; hold the button and speak. With the host unreachable, a
   quick wake opens and is taken back within 20 seconds, and a later wake stays on the logo until
   the host answers. Swipe the logo away and verify that network setup and **Check recovery
   status** are available during an outage.
4. **Dictation.** Tap the text field so the keyboard opens. Hold and speak: the words appear dimmed
   at the cursor as you talk, and on release they turn into the final text. Nothing is sent. A tap of the button sends it, and the reply is read aloud.
5. **Taps.** A tap with the screen on turns it off. A tap while it's talking stops it. A double-tap
   shows "New conversation".
6. **Scroll wheel.** It scrolls the visible page; during speech or with a volume slider selected,
   it adjusts volume. It must never approve an action.
7. **Wi-Fi cut.** After 3 minutes with the screen off, `adb shell cmd wifi status` shows Wi-Fi
   disabled. Wake the R1 and verify that Wi-Fi reconnects and the recovery check completes before
   normal actions resume. `tools/wifi-wake-test.sh` times the radio reconnect only; it does not prove
   recovery verification or acceptance of a spoken turn.
8. **Battery.** Unplug the R1 overnight, then plug it in and run `tools/battery-report.sh`. See
   [BATTERY.md](BATTERY.md).

## Troubleshooting

**The button does nothing.**
1. Check that the app's root reader is up: `adb logcat -s r1ptt` should show "side button reader
   up on /dev/input/eventN".
2. Run `adb shell su -c cat /proc/bus/input/devices`. The button should be listed as `mtk-kpd`; if
   it has another name, set `"buttonDevice"` in the config.
3. Run `adb shell dumpsys input | grep -A4 mtk-kpd`. `KeyLayoutFile` should be our `mtk-kpd.kl`.
   The module's own log is `/data/adb/modules/r1ptt_system/service.log`.

**It's sluggish, USB drops out after a replug, or Android keeps restarting.** Rabbit's vendor
runs the R1's USB in "CUA" mode, which adds a USB keyboard, mouse and WebUSB device for rabbitOS's
computer-use feature. On LineageOS that mode never comes up, and init and Android fight over the USB
config: 20-second stalls, the system process killed by its watchdog, and no adb after a replug. The
module's `system.prop` turns it off (`ro.vendor.cua.enable=0`). Check with
`adb shell getprop ro.vendor.cua.enable` (should be 0); the R1 should connect as `0e8d:201c`, not `0e8d:2303`.

**It boot-loops after installing the module.**
There are no volume keys, so Magisk's safe-mode combo is unavailable. Instead, while it loops, run:
```sh
adb wait-for-device shell magisk --remove-modules
```

**Back to stock.** Use Rabbit's flash tool → Flash Stock ROM. It works from any state that can
still reach the preloader.

**Mic or speech errors.** The status line shows the server's reason. "Bad API key" means fix the
key and re-run provision with `--config`.
