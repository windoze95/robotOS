# robotOS screenshots

These PNGs show the app's Android views at the R1's 480×640 panel size and 190 dpi
system density. The app applies its normal UI scaling. Robolectric's native renderer
captures each screen using fictional chats, recipients and SMS records.

The battery, network and listening state are staged. The example Hermes reply is a
saved transcript fixture; no request or text message is sent. These images are UI
examples, not device, speech or carrier acceptance evidence.

| Screen | Full-size image |
|---|---|
| Home | [home.png](home.png) |
| Listening | [listening.png](listening.png) |
| Conversation | [conversation.png](conversation.png) |
| Menu | [menu.png](menu.png) |
| Saved chats | [chats.png](chats.png) |
| Messages | [messages.png](messages.png) |
| Quick controls | [quick-controls.png](quick-controls.png) |
| Settings | [settings.png](settings.png) |
| Native Prompt Builder | [workspace-builder.png](workspace-builder.png) |
| Web Prompt Builder | [workspace-console.jpg](workspace-console.jpg) |

The native workspace image uses `ScreensSnapshotTest` with staged notes. The web console image
was captured from a disposable local host with fictional notes and synthetic audio. It demonstrates
the browser UI, not an enrolled physical R1 or a live Hermes execution.

## Refresh

With JDK 17+ and the Android SDK configured, run from the repository root:

```sh
tools/screenshots.sh
```

The script runs `ScreensSnapshotTest.githubGallery`, then copies its eight captures
from `app/build/ui-snapshots/github/` into this directory. It does not use ADB or read
data from a connected device. Review the images before committing; timestamps in
the conversation reflect the capture time. Keep the fixture content in
[`ScreensSnapshotTest.kt`](../../app/src/test/java/dev/r1ptt/ui/ScreensSnapshotTest.kt).
