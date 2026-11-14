# BoomBoom for Android: brief for everyone working in `Android/`

Read the repository's `AGENTS.md` and `README.md` first: this is the same app as the iPhone one, built again for
Android. The iPhone code is the specification. Where this brief does not say otherwise, behave as it does.

## What is proven

The app plays. A WebView that no screen shows loads YouTube Music with **the iPhone app's page script unchanged**
(`Packages/BoomEngine/Sources/BoomPlayer/Resources/boom-tap.js`, packaged as an asset straight from there); the
capture broker asks its player for songs and receives the same bytes the iPhone app captures; the Rust engine plays
them through AAudio; a Media3 session keeps it going in the background. `MainActivity` puts the `:ui` screens on top.

Three things were learnt the hard way and must not be undone:

- **The unseen page needs frames.** A WebView that is never drawn gives its page under one animation frame a second,
  and the site does its own background work on frames: making its player, and the session check after which it hands
  out whole songs instead of their first minute. `HiddenBrowser` therefore "draws" the tab into a single pixel 30
  times a second while the page has work (a few times a second when idle). Without it a new install's first songs
  stop at 1:00 and every fetch is several times slower.
- **A failed tab must come back by itself**: `HiddenBrowser` loads it again after a pause, at once when the network
  returns, and once for any song that needs it.
- **The tab describes itself the same way everywhere** (user agent and client hints both say desktop Chrome on Linux).

## The parts

| Path | Language | What it is | Owner |
|---|---|---|---|
| `core/` | Rust (+ the C in `Packages/BoomEngine/Sources/CBoomAudio`, compiled in, never copied) | The engine: stream parsing (WebM/Opus, fragmented MP4/AAC), the packet store, decoding, the playback pipeline, the sound processor, audio output through AAudio, and the JNI surface | engine worker |
| `app/src/main/kotlin/io/github/boomboom/engine/` | Kotlin | The Kotlin face of `core/` (`external fun`s and small wrappers). Written by whoever writes `core/`, so the two cannot disagree | engine worker |
| `innertube/` | Kotlin, a plain JVM module (no Android) | The data client: the models and YouTube Music's web API (search, suggestions, Home, Explore, albums, artists, playlists, library, likes), a port of `Packages/BoomCore` with its tests and its recorded answers | data worker |
| `logic/` | Kotlin, a plain JVM module | The stores between the engine and the screens: the player and its queue, sound settings and presets, downloads, accounts, the cache (a port of `BoomPlayer`'s stores with their tests). State is exposed as `StateFlow`s; time is injected | logic worker |
| `app/.../web/`, `capture/`, `runtime/`, `service/` | Kotlin | The hidden WebView and the page script's events, the capture scheduler, the wiring of everything at process start, and the Media3 session service that keeps music playing in the background with notification and lock-screen controls | the lead |
| `app/.../MainActivity.kt` | Kotlin, Compose | The app's one window: `BoomApp` (the browsing frame), `MiniPlayerBar` in its slot, `NowPlayingOverlay` over it, `SoundScreen` over everything, the sign-in screen, the notification permission. `src/debug/` holds a plain test screen for debug builds | the lead |
| `ui/` | Kotlin, Jetpack Compose (an Android library module) | The look and the screens, checked with screenshot and behaviour tests that run on the Mac (Roborazzi on Robolectric), so they need no emulator | screens workers |

`./gradlew --stop` stops every worker's build on this Mac: do not run it while others are working.

## Rules

1. Stay inside the paths you were given. No git commands. Never edit the iPhone app's sources: read them, port them.
   The one shared thing is `Packages/BoomEngine/Sources/CBoomAudio` (C) and `.../BoomPlayer/Resources` (the page
   script and ad rules): Android builds use those files in place.
2. The house style of the iPhone code applies: explicit state enums (sealed classes in Kotlin, enums in Rust) with a
   single transition point for anything with exclusive states; comments that say why, in plain words; British
   spelling in anything the listener reads; no names, logos or artwork of YouTube or Google in the app.
3. Never write secrets, cookies, tokens, account names or e-mail addresses into files, logs or reports.
4. Verify before you report: say exactly what you ran and what it printed. A thing that compiles is not a thing that
   works.

## This Mac (8 GB of memory, 8 cores: be gentle)

- Rust is in `~/.cargo/bin` (not on the PATH: call `~/.cargo/bin/cargo`, or `export PATH="$HOME/.cargo/bin:$PATH"`
  in your own commands). Version 1.100, with the targets `aarch64-linux-android`, `armv7-linux-androideabi` and
  `x86_64-linux-android` installed.
- The Android SDK is `~/Library/Android/sdk`: platform 36, build-tools 36.1.0, the NDK at
  `ndk/28.2.13676358`, CMake 3.22.1 in `cmake/3.22.1/bin`. `local.properties` already points Gradle at the SDK.
- Java 21: `export JAVA_HOME=$(/usr/libexec/java_home)`. Gradle 8.14.3 through `./gradlew` in `Android/`; the Android
  Gradle Plugin is 8.13.0, Kotlin 2.2.0. `gradle.properties` caps Gradle's memory; do not raise it.
- Builds: one Gradle build or one cargo build at a time per worker, never `--parallel`. Never run `./gradlew --stop`
  while anyone else may be building: it stops every build on this Mac.
- A release build is `./gradlew :app:assembleRelease` (shrunk with R8; `app/proguard-rules.pro` keeps what the native
  library and the saved files find by name). It is signed when the release key is on this Mac (outside the
  repository); elsewhere it comes out unsigned.
- **The emulator** is the AVD `Medium_Phone_API_36.1` (Android 16, arm64, Google Play); `Boom_API_30` is Android 11
  for the older-phone checks. An emulator takes more than 2 GB, so only one may run at a time, only one worker may
  have it, and nobody else's simulator may be running. Do not start it unless your task says you may, and shut it
  down when you are done (`adb emu kill`). Start it without a window **and without sound**, with three cores (with
  one, its own system apps stop responding and every timing is meaningless):
  `~/Library/Android/sdk/emulator/emulator -avd Medium_Phone_API_36.1 -no-window -no-audio -no-boot-anim -no-snapshot-save -memory 2048 -cores 3 -gpu swiftshader_indirect`.
  Give it three or four minutes after it boots before timing anything, and run
  `adb shell settings put global hide_error_dialogs 1` so its own "isn't responding" dialogs do not take your taps.
- An iPhone is connected to this Mac. Never touch it. `adb` only sees Android devices, so plain `adb` commands are
  safe; never run `xcrun devicectl` or `xcodebuild` here.
