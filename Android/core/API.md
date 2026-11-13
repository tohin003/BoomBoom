# The engine's interface (`libboomcore.so`)

`Android/core` is BoomBoom's audio engine for Android, a port of the iPhone app's `BoomAudio`. The iPhone code is the
specification; where this file says nothing, the engine behaves as that code does. The Kotlin face is in
`app/src/main/kotlin/io/github/boomboom/engine/`: the rest of the app uses only the wrapper classes there (`Track`,
`Demuxer`, `Engine`, `Sound`, `SoundParams`), never `BoomCore` or a raw handle.

## Layout

| File | What it is | Ported from |
|---|---|---|
| `src/demux/` | The two stream parsers, fed in pieces of any size: `webm.rs` (WebM/Opus), `mp4.rs` (fragmented MP4/AAC), `cursor.rs` (bounds-checked readers), `mod.rs` (the types and `AudioDemuxer::for_mime_type`) | `Demux/*.swift` |
| `src/track.rs` | `TrackBuffer`: the thread-safe packet store (gaps, `seal`, `is_complete`, `has_reached_end`, `covered_fraction`, `first_gap`, files) | `TrackBuffer.swift` |
| `src/decode.rs` | `PacketDecoder` (libopus for Opus, Symphonia for AAC-LC) and `To48k` (the band-limited resampler) | `PacketDecoder.swift` (+ the pipeline's `AVAudioConverter`) |
| `src/pipeline.rs` | `PlaybackPipeline`: the decode thread, the lock-free ring, gapless hand-over, the events and output states | `PlaybackPipeline.swift` |
| `src/ffi.rs` | Declarations for the shared C and small safe wrappers (`RingPtr`, `Dsp`, `SoundParams`) | `CBoomAudio/include/*.h` |
| `src/output.rs` | `render_block` (renderer → sound processor, in place), shared by AAudio and the offline renderer the tests use | `AudioOutput.swift` (the render block) |
| `src/aaudio.rs` | The AAudio stream and its keeper thread (Android only) | `AudioOutput.swift` (the hardware side) |
| `src/engine.rs` | `Engine`, one per process: pipeline + sound processor + output + the event queue | `AudioOutput` + `PlaybackPipeline` as the app wires them |
| `src/jni_api.rs` | The `Java_io_github_boomboom_engine_BoomCore_*` functions | |
| `build.rs` | Compiles `BoomRing.c`, `BoomRenderer.c`, `BoomDSP.c`, `BoomHRIR.c` from `Packages/BoomEngine/Sources/CBoomAudio`, in place (never copied) | |
| `tests/` | The ported Swift tests and the real-capture checks (see "Tests") | `Tests/BoomAudioTests/*.swift` |

## Dependencies

| Crate | For |
|---|---|
| `opusic-sys` 0.7.7 | libopus **1.6.1**, the reference Opus decoder, built from the source the crate bundles with CMake (through the NDK's toolchain file for Android). Floating-point build, runtime NEON/SSE detection. |
| `symphonia-core`, `symphonia-codec-aac` 0.6.1 | A pure-Rust AAC-LC decoder (no SBR/PS: HE-AAC is refused as unsupported). |
| `rubato` 5.1 (no default features), `audioadapter-buffers` 5.2 | The windowed-sinc resampler that brings 44.1 kHz AAC to 48 kHz, and the buffer adapters its API takes. |
| `jni` 0.22.4 | The JNI types, and `with_env`, which catches every panic before it can reach the Java VM. |
| `cc` (build only) | Compiles the shared C. |

## Building

- On the Mac (tests): `~/.cargo/bin/cargo test` in `Android/core`. CMake comes from the Android SDK
  (`.cargo/config.toml` points at it unless `CMAKE` is set).
- For the phone: the Gradle task `:app:buildCore` (run before every app build, skipped when nothing in `core/` or the
  shared C changed) runs `cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 --platform 26 -o app/src/main/jniLibs build
  --release` with the SDK's CMake on the `PATH`. NDK r28c: the 64-bit libraries are 16 KB-page aligned.

## Formats and conventions

- **Handles** are `Long`s. A track is an `Arc<TrackBuffer>`: the engine holds its own reference to a track it plays,
  so closing a `Track` that is playing is safe. A demuxer is a boxed `Mutex`. `0` is never a valid handle.
- **Item ids** are chosen by Kotlin (any `Long` but 0) and come back in the events. 0 means "none".
- **Bytes** cross once per appended piece: `demuxerFeed` copies the Java array straight into the demuxer's own buffer
  (`GetByteArrayRegion` into its spare room). The packets are then copied once more, into the track's store, which is
  where they live.
- **Events** are queued in the engine and drained by Kotlin (`enginePollEvents`, which can wait for the first one).
  Chosen over calling into Kotlin from the decode thread because no native thread then ever attaches to the Java VM or
  holds a Java reference, a slow reader loses nothing (the queue keeps the last 512), and Kotlin reads them on a
  thread of its own choosing (a coroutine on `Dispatchers.IO` that waits in `pollEvents(250)` is enough).
- **The sound parameters** travel as a `FloatArray` of 23, in `BoomSoundParams` order: `enabled` (0/1), the ten band
  gains in dB, `preamp_db`, `headroom_db`, `bass_db`, `treble_db`, `boost_db`, `limiter` (0/1), `balance`, `width`,
  `deep_bass`, `air`, `crossfeed`, `spatial`. `SoundParams` in Kotlin mirrors the C struct field for field and makes
  the array.

## The functions (`BoomCore`, all `@JvmStatic external`)

### Tracks (packet stores)

| Function | Does |
|---|---|
| `trackCreate(): Long` | A new, empty track. |
| `trackRelease(track)` | Lets go of Kotlin's reference. |
| `trackInfo(track): DoubleArray?` | `[codec (0 AAC, 1 Opus), timescale, sample rate, channels, lead-in, stated packet duration (0: none), duration s (NaN: unknown), packet duration (0 until the first packet), expected packet count (-1: unknown)]`; null before the header. |
| `trackHeldPackets(track): Int`, `trackHeldBytes(track): Long` | What is held. |
| `trackIsComplete(track): Boolean` | Every packet of the song is held. |
| `trackCoveredFraction(track): Double` | How much is held, 0..1. |
| `trackFirstGap(track, index): Int` | The first missing packet at or after `index`, wrapping round; -1 when whole. |
| `trackFirstGapAt(track, seconds): Double` | The same in seconds (where the capture broker should steer the page's playhead); -1 when whole. |
| `trackContiguousEnd(track, index): Int` | The first missing packet at or after `index`. |
| `trackHasReachedEnd(track, index): Boolean` | `index` lies past the song's last packet. |
| `trackPacketIndexAt(track, seconds): Int`, `trackTimeOfPacket(track, index): Double` | Packet numbers and times. |
| `trackSeal(track)` | The capture reached the end of the stream. |
| `trackSetDuration(track, seconds)` | What the page says the song lasts (the stream's own header wins). |
| `trackWrite(track, path): Boolean` | Writes a whole song to a file (through `path.partial` and a rename). False unless complete. |
| `trackLoad(path): Long` | A new track from such a file; 0 when it is not one. |

The file: `BOOMTRKA`, a little-endian `u32` header length, the header (codec `u8`, timescale `u32`, sample rate `f64`,
channels `u32`, lead-in `i64`, packet duration `u32`, stated packet duration `u32` (0: none), duration `f64` (NaN:
none), magic cookie and AudioSpecificConfig (each `u32` length + bytes), packet count `u32`, each packet's size
`u32`), then the packets back to back. (The iPhone app's file has a JSON header; the two never meet.)

### Demuxers (one per media source buffer)

| Function | Does |
|---|---|
| `demuxerCreate(mimeType): Long` | For `audio/webm…` or `audio/mp4…`; 0 for anything else. |
| `demuxerFeed(demuxer, track, bytes, offset, length): Int` | Parses the bytes and adds what comes out to the track (its header, then its packets). Returns the number of new packets, or -1 corrupt, -2 media before the header, -3 unsupported, -4 bad arguments; the reason is in `demuxerLastError`. After an error the demuxer starts clean, as the Swift one does. |
| `demuxerReset(demuxer)` | The buffer's `abort()`: forgets partly received data; the header stays. |
| `demuxerLastError(demuxer): String` | Why the last feed failed. |
| `demuxerRelease(demuxer)` | Frees it. |

### The engine (one per process)

| Function | Does |
|---|---|
| `engineStartOutput(): Boolean`, `engineStopOutput()` | Opens and starts the AAudio stream / stops and closes it. |
| `engineLoad(track, item, seconds, autoplay, gain)` | Starts on a song at `seconds`, dropping what was playing; `gain` is the song's own linear level. |
| `engineSetNext(track, item, gain)` | The song that follows without a gap; `track` 0: nothing follows. |
| `enginePlay()`, `enginePause()`, `engineSeek(seconds)`, `engineStop()` | As in the Swift pipeline: a jump keeps the intent (playing carries on, paused stays paused), and `enginePosition` answers with the target at once. |
| `engineSetItemGain(item, gain)`, `engineSetLevel(gain)` | A song's own level; the overall level (both linear). |
| `enginePosition(): Double` | Seconds into the song being heard (what the ring has handed to the output). |
| `engineCurrentItem(): Long` | The song being heard; 0 when none. |
| `engineDuration(): Double`, `engineBufferedFraction(): Double` | Of the song being heard (NaN while unknown; 0..1). |
| `engineOutputInfo(): LongArray` | `[running, sample rate, device's own rate, resampled (0/1), channels, frames per burst, buffer frames, xruns, callbacks, frames pulled, frames for which the pipeline had audio (not paused or waiting), reopens after route changes]`. |
| `enginePollEvents(timeoutMs): LongArray` | The events since the last call as pairs `[kind, item, …]`: 1 playing, 2 advanced, 3 waiting, 4 ended, 5 failed (format not supported), 6 failed (could not be decoded). Waits up to `timeoutMs` when there are none. |

### Sound

| Function | Does |
|---|---|
| `soundApply(params: FloatArray)` | The whole set at once (see the layout above); glides in over about 30 ms. |
| `soundSetHeadTurn(radians)` | For spatial sound with head tracking. |
| `soundTakeLevels(): FloatArray` | `[peak left, peak right, rms left, rms right, gain reduction dB]` since the last call. |
| `soundSpectrum(bands, lowHz, highHz): FloatArray` | The output's spectrum over the last 85 ms, in dB (0 = a full-scale sine). |
| `soundResponse(params, frequencies, sampleRate): DoubleArray`, `soundPeakResponse(params, sampleRate): Double` | The equaliser's curve and its peak, without an engine. |
| `soundBandFrequencies(): DoubleArray`, `soundLatency(): Int` | The ten centres; the processor's constant delay (247 frames at 48 kHz). |

## Inside

- **Decoding.** Everything leaves the decoder as 48 kHz stereo float. Opus (always 48 kHz) goes through libopus with no
  resampling; the header's output gain is applied, mono is spread over both sides, and mapping families above 1 (more
  than two channels) are refused. AAC-LC goes through Symphonia, then `To48k`: rubato's windowed-sinc resampler (256
  taps, Blackman-Harris), fed packet by packet as one continuous stream per song. For 44.1 kHz the 160 output phases
  fall exactly on the filter's precomputed ones, so it runs as an exact polyphase filter (no interpolation between
  phases). Its delay is dropped at the start and its tail is flushed at the song's end, so the output has exactly
  `round(frames × 48000 / 44100)` frames. The Opus pre-skip and the AAC edit list's lead-in are dropped by the
  pipeline, as in Swift (`place`), not by the decoders.
- **Jumps** are placed by the packet length the store learnt from the stream, not by the 960 frames the iPhone
  pipeline assumes for Opus (the two agree for YouTube's 20 ms packets; a 60 ms stream lands right here and would
  not there), with at least 80 ms of Opus run-up whatever the packet length.
- **Threads.** The decode thread owns the decks; control calls are messages to it (as the Swift `DispatchQueue`), with
  a 20 ms tick. `position` and the current item are read from a small locked timeline, as in Swift. Rings replaced by a
  jump are freed a second later (the render thread may be inside the old one for one more call).
- **Output.** AAudio, float, stereo, shared mode, power-saving performance mode (a deep buffer: music, not a game), usage
  media and content type music (looked up at run time: they are Android 9 APIs and the app runs from Android 8). The
  stream is opened at the device's own rate when that is 48 kHz; otherwise at 48 kHz and the system converts (at the
  highest quality it offers on Android 14 and later), and `resampled` says so. The callback renders up to 2,048 frames
  per pass from preallocated buffers: no lock, no allocation, no wait. A disconnect (headphones, Bluetooth) wakes a
  keeper thread that closes the stream and opens a new one on the new route, started again if it was running.

## Tests

`cargo test` on the Mac (debug build; the dependencies are optimised even there):

- `tests/demux_and_decode.rs`: the Swift `DemuxAndDecodeTests` (header, gapless times, any piece sizes, reset
  mid-cluster/mid-fragment, garbage, decoding to the tones, Opus packet lengths) plus the resampler.
- `tests/pipeline.rs`: the Swift `PlaybackPipelineTests` and `TrackBufferTests`, pulled by hand at 16× real time, plus
  an Opus→AAC hand-over and a song that cannot be decoded.
- `tests/sound.rs`: the C processor from Rust (flat is bit-exact and 247 frames late; the limiter's ceiling; a band
  against `boom_dsp_response`) and the Swift `AudioOutputTests`' whole-chain checks, rendered offline.
- `tests/real_captures.rs`: with `BOOM_CAPTURES` (a folder holding `real-opus.webm`, `real-opus.reference.f32`,
  `real-aac.mp4`; music, never in the repository), Opus against libopus's own float decoding and the whole AAC song
  at 48 kHz; with `BOOM_AAC_REFERENCE` (ffmpeg's decoding of `real-aac.mp4` as `f32le`), AAC against ffmpeg's decoder.
  Run them with `--release`.

On a device: `app/src/androidTest/.../EngineOnDeviceTest.kt` plays the Opus test tone through AAudio with the sound
processor on, through the Kotlin face (`./gradlew connectedDebugAndroidTest`).
