import BoomModels
import BoomPlayer
import Foundation

/// For checking the engine from the command line, on a simulator or the iPhone:
///   -BoomPlay <videoId>   plays that song as soon as the app is up
///   -BoomTrace 1          prints one line a second about what the player is doing
///   -BoomSilent 1         goes through the whole audio chain without making a sound
///   -BoomShow player|equalizer   opens that screen a few seconds in (see RootView)
///   -BoomEval64 <base64>  evaluates that JavaScript expression in the hidden tab and prints the answer ("BOOM eval"),
///                         -BoomEvalAfter <seconds> after launch (12 by default) and again every ten seconds
///   -BoomSoundTest 1      with -BoomPlay: plays the same ten seconds of the song under several sound settings and
///                         prints how loud each comes out ("BOOM sound ..."). The listener's own settings are
///                         neither used nor changed.
/// Lines start with "BOOM" so they are easy to pick out of the device console.
@MainActor
final class Diagnostics {
    private var task: Task<Void, Never>?
    private var soundTest: Task<Void, Never>?
    private var evaluation: Task<Void, Never>?

    init?(runtime: BoomRuntime) {
        let defaults = UserDefaults.standard
        let videoId = defaults.string(forKey: "BoomPlay")
        guard videoId != nil || defaults.bool(forKey: "BoomTrace") || defaults.string(forKey: "BoomEval64") != nil
        else { return nil }
        let player = runtime.services.player
        if defaults.bool(forKey: "BoomSilent") { runtime.engine.setOutputVolume(0) }
        if let videoId {
            player.play(Song(videoId: videoId, title: "Diagnostics \(videoId)"))
        }
        if videoId != nil, defaults.bool(forKey: "BoomSoundTest") { soundTest = Task { await Self.runSoundTest(runtime) } }
        if let encoded = defaults.string(forKey: "BoomEval64"), let data = Data(base64Encoded: encoded),
           let expression = String(data: data, encoding: .utf8) {
            let wait = defaults.object(forKey: "BoomEvalAfter") == nil ? 12 : defaults.double(forKey: "BoomEvalAfter")
            evaluation = Task {
                try? await Task.sleep(for: .seconds(wait))
                while !Task.isCancelled {
                    let answer = await runtime.browser.inspect(expression)
                    // In pieces: the console cuts long lines.
                    var rest = Substring(answer)
                    var part = 0
                    repeat {
                        print("BOOM eval[\(part)] \(rest.prefix(900))")
                        rest = rest.dropFirst(900)
                        part += 1
                    } while !rest.isEmpty
                    try? await Task.sleep(for: .seconds(10))
                }
            }
        }
        let started = Date()
        task = Task {
            var last = ""
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(1))
                let format = runtime.engine.currentFormat.map { "\($0.codecName) \($0.bitrate / 1000) kbps (itag \($0.itag))" } ?? "-"
                let line = String(
                    format: "BOOM t=%.0f status=%@ song=%@ pos=%.1f/%.1f buffered=%.0f%% queue=%d format=%@ tab=%@",
                    Date().timeIntervalSince(started), String(describing: player.status),
                    player.current?.videoId ?? "-", player.clock.position, player.clock.duration,
                    player.clock.bufferedFraction * 100, player.queue.count, format,
                    String(describing: runtime.browser.state))
                if line != last { print(line) }
                last = line
            }
        }
    }

    /// Measures the equaliser and amplifier on this device: the same stretch of the song under each setting, with
    /// the level read after the limiter (so it works with the output volume at zero).
    private static func runSoundTest(_ runtime: BoomRuntime) async {
        let player = runtime.services.player
        let engine = runtime.engine
        // Wait for the song to be playing and fetched far enough to jump around in.
        for _ in 0..<120 {
            if player.status == .playing, player.clock.duration > 60, player.clock.bufferedFraction > 0.4 { break }
            try? await Task.sleep(for: .milliseconds(500))
        }
        guard player.status == .playing else {
            print("BOOM sound test: the song never started")
            return
        }
        func settings(_ change: (inout SoundSettings) -> Void) -> SoundSettings {
            var settings = SoundSettings()
            settings.isEnabled = true
            change(&settings)
            return settings
        }
        let bassBoost: [Float] = [6, 5, 4, 2.5, 1, 0, 0, 0, 0, 0]
        let cases: [(String, SoundSettings)] = [
            ("off", SoundSettings()),
            ("on, flat", settings { _ in }),
            ("boost +6 dB", settings { $0.boost = 6 }),
            ("boost +12 dB", settings { $0.boost = 12 }),
            ("boost +12 dB, limiter off", settings { $0.boost = 12; $0.isLimiterEnabled = false }),
            ("bass boost preset", settings { $0.gains = bassBoost }),
            ("bass boost preset, auto headroom", settings { $0.gains = bassBoost; $0.isAutoHeadroomEnabled = true }),
            ("bass +9, treble +6", settings { $0.bass = 9; $0.treble = 6 }),
            ("deep bass, full", settings { $0.deepBass = 1 }),
            ("air, full", settings { $0.air = 1 }),
            ("mono", settings { $0.width = 0 }),
            ("wide", settings { $0.width = 2 }),
            ("everything", settings {
                $0.gains = bassBoost; $0.boost = 9; $0.deepBass = 1; $0.air = 1; $0.crossfeed = 1; $0.width = 1.4
            }),
        ]
        var reference: Double?
        for (name, sound) in cases {
            engine.apply(sound)
            player.seek(to: 30)
            try? await Task.sleep(for: .milliseconds(1200))
            _ = engine.takeLevels()
            var squares = 0.0, readings = 0.0, peak: Float = 0, reduction: Float = 0
            var bass = 0.0, treble = 0.0
            for _ in 0..<70 {
                try? await Task.sleep(for: .milliseconds(100))
                let levels = engine.takeLevels()
                squares += Double(levels.rmsLeft * levels.rmsLeft + levels.rmsRight * levels.rmsRight) / 2
                readings += 1
                peak = max(peak, levels.peakLeft, levels.peakRight)
                reduction = max(reduction, levels.gainReduction)
                let spectrum = engine.spectrum(bands: 48)
                // Bands 0-14 are 20-170 Hz; bands 37-47 are 4-20 kHz.
                bass += Double(spectrum[4..<15].max() ?? -120)
                treble += Double(spectrum[37..<48].max() ?? -120)
            }
            let level = 10 * log10(max(squares / readings, 1e-12))
            if reference == nil { reference = level }
            print(String(
                format: "BOOM sound %@: level %.1f dBFS (%+.1f dB), peak %.3f, limiter up to %.1f dB, bass %.0f dB, treble %.0f dB",
                name, level, level - (reference ?? level), peak, reduction, bass / readings, treble / readings))
        }
        engine.apply(runtime.services.equalizer.settings)
        print("BOOM sound test done")
    }
}
