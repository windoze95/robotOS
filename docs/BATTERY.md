# Battery

The R1 has a 1000 mAh battery (about 3.8 Wh). For battery life, what matters is how little the
device draws while it sits idle; how long each conversation takes matters much less. Some rough
arithmetic:

- **Screen-on time:** 30 conversations a day at about 20 s each is about 10 minutes of screen at
  low brightness. That costs roughly 1–2% of the battery.
- **Idle draw:** an idle current of just 10 mA costs 24% a day. At 3 mA it's about 7% a day.

So the work goes into making idle as close to "everything off" as possible.

On external power (any charger) robotOS skips the idle cut: Wi-Fi and the modem stay up, so texts
arrive and Hermes can reach the R1 at any time ([HERMES.md](HERMES.md)). Unplugging re-arms the normal
cut. Plugging in only changes availability; it turns no feature on or off. An eight-hour powered soak
and a matched unplugged battery comparison remain to be measured.

## What robotOS does

| Drain | Approach |
|---|---|
| Cellular modem | Kept off permanently (airplane mode set up to leave Wi-Fi alone). `power.cellular: true` turns it back on if a SIM is in. |
| Wi-Fi | Off after `power.wifiIdleMinutes` (default 3) with the screen dark, never while on external power; on again at the next press or screen-on. Reconnection overlaps speaking; its latency on the current build still needs the read-only acceptance workflow in [ACCEPTANCE.md](ACCEPTANCE.md). While connecting, words are buffered within a bounded queue and failures are surfaced. |
| Bluetooth, location, scanning | Off: no Wi-Fi or BLE "always scanning", no Wi-Fi auto-wakeup, no network recommendations. |
| Background apps | Rabbit's product partition is removed and LineageOS extras are disabled (`tools/debloat.txt`). Screen-off pocket mode has no polling. VPN clients (Tailscale) have their own power behavior. |
| Doze | Enters light idle immediately and deep idle about 30 s after screen-off (module `service.sh`). |
| Screen | On only while a turn is active, then a 15 s timeout at brightness 60/255. No ambient display or lift/tap-to-wake. |
| Button | Read by a root `dd` blocked on the input device: zero CPU until a press. The kernel's key interrupt wakes the SoC. |
| Network per turn | Voice turns (speech-to-speech) stream raw 24 kHz audio only while you hold the button, plus the spoken reply. The session opens when the screen wakes (so it's ready by the time you talk) and closes at screen-off or 20 s after an exchange; set `live.warm: false` to connect only on a hold. Dictation streams 24 kHz audio to `gpt-live-transcribe` only while you hold the button (about 64 KB per second of talking); the connection opens at the press and closes once the text is in. Chat and speech share one HTTP/2 connection. |
| CPU per turn | No on-device speech models; the cloud (or your server) does the heavy work. |

## Measuring

For repeatable current-build acceptance, use the explicit-device, read-only collector and the
overnight matched-configuration procedure in [ACCEPTANCE.md](ACCEPTANCE.md). Hardware results
remain pending. The legacy report below uses an implicit ADB target and includes diagnostic
output; it was not run for the reliability acceptance work.

The app logs battery level at every screen on/off. Those logs bracket the idle periods without
waking the device to take samples.

1. Charge the R1, then unplug it. USB keeps it awake and charging, which would hide the drain.
2. Use it normally, or just leave it overnight.
3. Plug it in and run `tools/battery-report.sh`. It reports:
   - idle drain per hour, counted only over unplugged screen-off periods of 30 minutes or more;
   - successful and failed suspends (counts that never move mean the SoC never reached deep
     sleep);
   - Android's screen-on and screen-off discharge;
   - the top kernel and app wakelocks.

For a quick look on the device, go to Settings (gear icon) → Battery.

## Results

To be filled in on hardware. These targets are first guesses, to be adjusted after measuring:

| Setup | Target idle drain | Measured |
|---|---|---|
| Wi-Fi cut after 3 min (default) | < 0.5 %/h | |
| Wi-Fi always on (`wifiIdleMinutes: 0`) | < 1.5 %/h | |
| Cellular on (`cellular: true`) | — | |

## If idle drain is high

- **`suspend_stats` success never increases:** something is holding a wakelock. Look at the
  report's kernel wakelock list. On GSIs this is often a vendor service that doesn't match the
  system.
  - If it can't be fixed, fall back to rooted stock rabbitOS. Its kernel, vendor and system match;
    the same module and app work there.
- **Wi-Fi drains even when idle:** lower `wifiIdleMinutes` to 1.
- **Drain only after using it:** check that the screen actually turns off (screen timeout in
  `power.screenTimeoutSec`) and that nothing holds `r1ptt:turn` (`dumpsys power | grep r1ptt`).
