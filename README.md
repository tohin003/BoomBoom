# BoomBoom

A native iPhone music player for personal use: a YouTube Music-style app with its own name, a yellow theme, a
ten-band equaliser and an amplifier. It plays ad-free, in the background, with Lock Screen and Dynamic Island
controls. It is installed from Xcode with a free Apple ID (never from the App Store) and re-signed every 7 days by a
small Mac menu-bar item.

**To install it on your own iPhone, follow [INSTALL.md](INSTALL.md):** five small steps, no programming.

BoomBoom is a personal project and is not affiliated with YouTube or Google. It plays YouTube Music without ads and
in the background, which YouTube's terms do not allow; use it for your own listening and at your own risk.

## How it works

Behind the screens runs a hidden browser tab on `music.youtube.com`, signed in with the listener's own Google account
if they choose to sign in.

| Part | What it does |
|---|---|
| **Hidden tab** (`BoomPlayer/Web`) | One `WKWebView`. A script injected at document start (`Resources/boom-tap.js`) removes the ad instructions from the player's answers, keeps the page silent, narrows the formats to the best audio on offer, and copies the audio the page's own player appends to the browser's media pipeline over to the app. |
| **Capture broker** (`BoomPlayer/Capture/StreamBroker.swift`) | Tells the page's player which song to load, demuxes what arrives, and keeps moving the paused player's playhead so a whole song arrives in seconds. Fetches the playing song first, then the next, then a few more. Finished songs are kept on disk. |
| **Audio engine** (`BoomAudio`, `CBoomAudio`) | WebM/Opus and fragmented-MP4/AAC demuxers, Apple's decoders, and the real-time side in portable C: a lock-free ring buffer, the render routine, and the sound processor (`BoomDSP`): level → ten bands, bass, treble → deep bass, air → width, balance, crossfeed → a look-ahead true-peak limiter, with meters and a spectrum for the screen. Everything runs at 48 kHz in 64-bit arithmetic with no resampling; with the equaliser off, or on but flat, the samples pass through bit for bit, 5 ms later. |
| **Data client** (`BoomCore/InnerTube`) | Search, suggestions, Home, Explore, albums, artists, playlists, the library and likes, from YouTube Music's own web API, using the hidden tab's cookies. |
| **Screens** (`BoomFeatures`) | SwiftUI: the frame and browsing screens (`FeatureBrowse`), the player, queue, lyrics and equaliser (`FeaturePlayer`), shared pieces (`BoomDesign`). |
| **App** (`App/`) | Wires the packages together. |

Audio quality: the script leaves the page's player exactly one audio format to choose, the best stereo one its
answer offers (about 130-150 kbps Opus on a normal account, 256 kbps with Premium), so it can never fall back to a
low-bitrate stream. The player's details line shows what is really being played, measured from the captured bytes.

`AGENTS.md` has the layout, ownership and rules for anyone (or any agent) working in the repository.

`Android/` holds the start of an Android version (same page script and sound processor, a Rust engine core, Kotlin
screens). It is not usable yet.

## Building and testing

Needs Xcode 27. XcodeGen (`/opt/homebrew/bin/xcodegen`) is needed only when `project.yml` changes (the generated
`BoomBoom.xcodeproj` is kept in the repository), and ffmpeg only for regenerating the test tones. Signing values
(your team and an app identifier of your own) go in `Config/Local.xcconfig`, which `install.sh` writes.

```sh
# The engine: demuxers, decoding, pipeline, equaliser, player logic (on the Mac, no simulator)
swift test --package-path Packages/BoomEngine

# The same, plus two tests against the real service: a whole-song capture at full quality, and the complete
# player (play, pause, jump, hand-over to the next song) rendered silently through the whole engine
BOOM_LIVE=1 swift test --package-path Packages/BoomEngine --filter LiveCaptureTests

# The data client: recorded answers, request shapes; with BOOM_LIVE=1 also every public endpoint for real
swift test --package-path Packages/BoomCore
BOOM_LIVE=1 swift test --package-path Packages/BoomCore

# The app, for a simulator
xcodegen generate
xcodebuild -project BoomBoom.xcodeproj -scheme BoomBoom -destination 'platform=iOS Simulator,name=iPhone 17' build
```

This Mac has 8 GB of memory: run one simulator at a time and keep parallel builds few.

Launch arguments for checking the engine without touching the screen: `-BoomPlay <videoId>` plays a song at launch,
`-BoomTrace 1` prints a line a second, `-BoomSilent 1` runs the whole audio chain without sound, `-BoomShow
player|equalizer|signin` opens that screen after a few seconds. `-BoomSoundTest 1` (with `-BoomPlay`) plays the same stretch of a song
under a dozen sound settings and prints how loud each comes out; `-BoomEval64 <base64 JavaScript>` asks the hidden
tab a question (`window.__boom.report()` says what its player is doing); `-BoomDump 1` keeps the first megabyte of
the newest captured stream as `boom-dump.bin` in Documents. The app also keeps a short engine log in its
Documents folder (`boom-trace.log`; Settings › Share diagnostic log): times, video ids, formats and states only.

## Installing on the iPhone and keeping it signed

A free Apple ID signs an app for 7 days.

```sh
# First install, and any time you want what is in the working tree on the phone (iPhone connected and unlocked)
scripts/renew.sh --from-tree

# The menu-bar item that shows the days left and renews by itself
swift build --package-path Renewer -c release --product BoomBoomRenewer
Renewer/install.sh
```

`renew.sh --from-tree` copies the working tree to a snapshot in `~/Library/Application Support/BoomBoomRenewer/` and
builds and installs from there; the menu-bar item later rebuilds that same snapshot, so half-finished edits never
reach the phone. See `Renewer/README.md`.

## Things to know

- This app depends on how YouTube Music's web player behaves. It leans on web standards (Media Source Extensions,
  fetch) and one long-lived player call rather than on the page's internals, but a change on YouTube's side can
  still break playback until the script is adjusted.
- Using YouTube this way (ad-free, in the background, captured audio) is against YouTube's terms of service. It is
  built for one person's own listening, on their own device and account.
- No YouTube or YouTube Music names, logos or artwork are used in the app itself.
- The ad rules (`BoomPlayer/Resources/ad-rules.json`) must never match the media requests themselves
  (`googlevideo.com/videoplayback`). A rule that blocked requests marked `ctier=A`, taken for an ad marker, blocked
  every stream of a Premium account: the songs stayed on "loading". Ads are removed from the player's answers by the
  script; the rules only refuse ad and tracking hosts.
- A signed-in page brings back the last song of its own session a few seconds after it loads, which takes the
  player away from a song asked for in that moment. The capture broker sees the page turn and asks again at once.
