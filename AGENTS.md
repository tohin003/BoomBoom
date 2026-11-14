# BoomBoom: notes for everyone working in this repository

BoomBoom is a native iPhone music app for one person's own iPhone 17 (iOS 26 and later, signed with a free Apple ID,
never sent to the App Store). It looks and works like a YouTube Music-style player with a **yellow** theme and its
own name and icon. Behind the screens runs a hidden, ad-blocking browser tab on `music.youtube.com`:

- **Data** (search, suggestions, home, albums, artists, playlists, library, likes) comes from YouTube Music's own web
  API ("InnerTube"), called from Swift with the hidden tab's cookies, so the listener's Google sign-in applies.
- **Audio** is fetched by the real YouTube player inside the hidden tab (ad-free). The audio bytes are captured as
  the player hands them to the browser's media pipeline and are decoded and played **natively** (AVAudioEngine), which
  is what makes the equaliser, the amplifier, background playback and Lock Screen / Dynamic Island controls possible.

Quality and accuracy come first. No YouTube or YouTube Music logos, names or artwork anywhere in the app.

## Layout and who owns what

| Path | What | Owner |
|---|---|---|
| `Packages/BoomCore/Sources/BoomModels` | The shared models and the `MusicAPI` protocol. **Frozen contract.** | lead |
| `Packages/BoomCore/Sources/InnerTube` | The YouTube Music data client (`InnerTubeClient: MusicAPI`) | data worker |
| `Packages/BoomEngine` | Hidden browser, audio capture, demuxer, decoder, audio engine, equaliser, player state | lead |
| `Packages/BoomEngine/Sources/BoomPlayer` | What screens talk to: `AppServices`, `PlayerStore`, `EqualizerStore`, `SessionStore`. **Frozen contract.** | lead |
| `Packages/BoomFeatures/Sources/BoomDesign/{Theme,ArtworkView,Navigator}.swift` | Tokens, the cover view, the navigator. **Frozen contract.** | lead |
| `Packages/BoomFeatures/Sources/BoomDesign/Components` | Shared rows, cards, shelves, pills, menus | browse UI worker |
| `Packages/BoomFeatures/Sources/FeatureBrowse`, `Gallery/Browse` | The app's frame and the browsing screens; their demo app | browse UI worker |
| `Packages/BoomFeatures/Sources/FeaturePlayer`, `Gallery/Player` | Mini player, full player, equaliser; their demo app | player UI worker |
| `App/` | The app target: a thin shell that wires the packages together | lead |
| `Tools/IconGen`, `App/Resources/Assets.xcassets` | Icon and colour assets | icon worker |
| `Renewer/`, `scripts/renew.sh` | The Mac menu-bar item that renews the 7-day signing | renewer worker |
| `project.yml`, every `Package.swift`, `Config/` | Project structure | lead |

## Rules

1. **Stay in your own paths.** Other workers edit this checkout at the same time. Do not edit, move or reformat
   files outside the paths your task names. If a contract (`BoomModels`, a `Package.swift`) needs changing, do not
   change it: say exactly what you need in your final report.
2. **No git commits, no pushes, no `git init`.** The lead handles version control.
3. **Swift 6 language mode, strict concurrency, iOS 26 / macOS 26 SDK APIs** (Xcode 27, Swift 6.4). No third-party
   dependencies without the lead's say-so. No force unwraps or `try!` on data that comes from the network.
4. **Verify before you report.** Build what you wrote and run its tests. Say plainly what you ran and what you could
   not verify. A wrong "done" costs more than an honest "not verified".
5. **Builds:** give `xcodebuild` your own `-derivedDataPath build/dd-<your-task>` so parallel builds do not collide.
   Simulators: use only the simulator your task names (each worker has its own, so screenshots do not show another
   worker's app); if none is named, "iPhone 17". Never install on or launch anything on the physical iPhone.
   No simulator is kept on this Mac (all were removed on 14 November 2026 to free 20 GB): make the one you need
   first, `xcrun simctl create "iPhone 17" "iPhone 17"`, and delete it when the work is done. On a simulator that
   is busy playing a song, a synthetic tap on a switch or a system dialog is often ignored (an idle app takes every
   one): repeat a tap that changed nothing, as `BrowseUITests.flip` does, before calling it a fault.
6. **Never write secrets, cookies, tokens or the person's e-mail address** into files, logs or reports.
7. Comments say what the code is for, in plain words. No comment noise.

## Signing

Each person signs with their own Personal Team and an app identifier of their own, both in
`Config/Local.xcconfig` (written by `install.sh`, not in git; `Config/Base.xcconfig` holds no one's values).
