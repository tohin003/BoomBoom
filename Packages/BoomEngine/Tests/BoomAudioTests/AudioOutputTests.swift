import AVFoundation
import Foundation
import Testing
@testable import BoomAudio

// The whole chain without hardware: the engine renders offline, pulled the way a device would pull it.

private func loadedOutput(profile: SoundProfile) throws -> (AudioOutput, PlaybackPipeline, PipelineItem) {
    let url = try #require(Bundle.module.url(forResource: "sine-opus.webm", withExtension: nil, subdirectory: "Fixtures"))
    let buffer = TrackBuffer()
    for event in try WebMDemuxer().feed(try Data(contentsOf: url)) {
        switch event {
        case .track(let info): buffer.setTrack(info)
        case .packets(let packets): buffer.append(packets)
        }
    }
    buffer.seal()
    let pipeline = PlaybackPipeline()
    let output = AudioOutput(pipeline: pipeline)
    try output.enableOfflineRendering(maximumFrames: 480)
    output.apply(profile)
    try output.start()
    let item = PipelineItem(buffer: buffer)
    pipeline.load(item, at: 0, autoplay: true)
    return (output, pipeline, item)
}

/// Pulls `seconds` of sound through the engine, skipping the silence before playback starts.
private func render(_ output: AudioOutput, seconds: Double) throws -> (left: [Float], right: [Float]) {
    var left: [Float] = [], right: [Float] = []
    let wanted = Int(seconds * 48_000)
    let deadline = Date().addingTimeInterval(10)
    while left.count < wanted, Date() < deadline {
        let buffer = try output.renderOffline(frames: 480)
        guard let channels = buffer.floatChannelData, buffer.frameLength > 0 else { continue }
        let l = UnsafeBufferPointer(start: channels[0], count: Int(buffer.frameLength))
        let r = UnsafeBufferPointer(start: channels[1], count: Int(buffer.frameLength))
        if left.isEmpty, !l.contains(where: { $0 != 0 }), !r.contains(where: { $0 != 0 }) {
            usleep(2_000)
            continue
        }
        left.append(contentsOf: l)
        right.append(contentsOf: r)
        usleep(600)
    }
    return (left, right)
}

private func rms(_ samples: ArraySlice<Float>) -> Double {
    (samples.reduce(0.0) { $0 + Double($1) * Double($1) } / Double(max(1, samples.count))).squareRoot()
}

private func decibels(_ ratio: Double) -> Double { 20 * log10(ratio) }

@Suite(.serialized) struct AudioOutputTests {
    /// The test tones: 440 Hz on the left and 660 Hz on the right, at one eighth of full scale.
    private func reference() throws -> (left: [Float], right: [Float]) {
        let (output, pipeline, _) = try loadedOutput(profile: SoundProfile())
        defer { output.stop(); pipeline.stop() }
        return try render(output, seconds: 2)
    }

    private let body = 9_600..<86_400

    @Test func offMeansUntouched() throws {
        let plain = try reference()
        var profile = SoundProfile()
        profile.bandGains[4] = 12
        profile.boost = 12
        profile.width = 2
        profile.isEnabled = false
        let (output, pipeline, _) = try loadedOutput(profile: profile)
        defer { output.stop(); pipeline.stop() }
        let off = try render(output, seconds: 2)
        // Bit-for-bit the same samples as with nothing set.
        #expect(Array(off.left[0..<48_000]) == Array(plain.left[0..<48_000]))
        #expect(Array(off.right[0..<48_000]) == Array(plain.right[0..<48_000]))
        let level = rms(plain.left[body])
        #expect(abs(level - 0.125 / 2.0.squareRoot()) < 0.01)
    }

    @Test func onButFlatIsUntouchedToo() throws {
        let plain = try reference()
        var profile = SoundProfile()
        profile.isEnabled = true
        let (output, pipeline, _) = try loadedOutput(profile: profile)
        defer { output.stop(); pipeline.stop() }
        let flat = try render(output, seconds: 2)
        #expect(Array(flat.left[0..<48_000]) == Array(plain.left[0..<48_000]))
        #expect(Array(flat.right[0..<48_000]) == Array(plain.right[0..<48_000]))
    }

    @Test func aBandLiftsItsFrequenciesExactlyAsTheCurveSays() throws {
        let plain = try reference()
        var profile = SoundProfile()
        profile.isEnabled = true
        profile.isLimiterEnabled = false
        profile.bandGains[4] = 12
        let (output, pipeline, _) = try loadedOutput(profile: profile)
        defer { output.stop(); pipeline.stop() }
        let shaped = try render(output, seconds: 2)
        // The curve the equaliser screen draws and the sound must agree: 440 Hz is on the left, 660 Hz on the right.
        let curve = profile.response(at: [440, 660, 500])
        #expect(abs(curve[2] - 12) < 0.01)
        #expect(curve[0] > 6 && curve[0] < 12)
        let leftLift = decibels(rms(shaped.left[body]) / rms(plain.left[body]))
        #expect(abs(leftLift - curve[0]) < 0.1)
        let rightLift = decibels(rms(shaped.right[body]) / rms(plain.right[body]))
        #expect(abs(rightLift - curve[1]) < 0.1)
    }

    @Test func aFarAwayBandLeavesTheToneAlone() throws {
        let plain = try reference()
        var profile = SoundProfile()
        profile.isEnabled = true
        profile.isLimiterEnabled = false
        profile.bandGains[9] = 12
        let (output, pipeline, _) = try loadedOutput(profile: profile)
        defer { output.stop(); pipeline.stop() }
        let shaped = try render(output, seconds: 2)
        #expect(abs(decibels(rms(shaped.left[body]) / rms(plain.left[body]))) < 0.1)
    }

    @Test(arguments: [true, false])
    func theAmplifierDoublesTheLevelPerSixDecibels(limiter: Bool) throws {
        let plain = try reference()
        var profile = SoundProfile()
        profile.isEnabled = true
        profile.isLimiterEnabled = limiter
        profile.boost = 6
        let (output, pipeline, _) = try loadedOutput(profile: profile)
        defer { output.stop(); pipeline.stop() }
        let loud = try render(output, seconds: 2)
        // A quarter of full scale after the boost: well under the ceiling, so the limiter has nothing to do.
        #expect(abs(decibels(rms(loud.left[body]) / rms(plain.left[body])) - 6) < 0.02)
        #expect(output.takeLevels().gainReduction == 0)
    }

    @Test func theLimiterHoldsPeaksUnderFullScale() throws {
        let plain = try reference()
        var profile = SoundProfile()
        profile.isEnabled = true
        profile.isLimiterEnabled = true
        // Twenty-four decibels on a tone at one eighth of full scale: twice full scale without the limiter.
        profile.preamp = 12
        profile.boost = 12
        let (output, pipeline, _) = try loadedOutput(profile: profile)
        defer { output.stop(); pipeline.stop() }
        let loud = try render(output, seconds: 2)
        let peak = loud.left[body].map(abs).max() ?? 0
        #expect(peak <= 0.96606)
        // The tone now sits at the ceiling: 17.8 dB above where it started, held 6.3 dB under what was asked.
        #expect(abs(decibels(rms(loud.left[body]) / rms(plain.left[body])) - 17.76) < 0.3)
        let reduction = output.takeLevels().gainReduction
        #expect(reduction > 5.5 && reduction < 7.5)
    }

    @Test func headroomMakesRoomForABoostedBand() throws {
        let plain = try reference()
        var profile = SoundProfile()
        profile.isEnabled = true
        profile.isLimiterEnabled = false
        profile.bandGains[9] = 9
        profile.headroom = 9
        let (output, pipeline, _) = try loadedOutput(profile: profile)
        defer { output.stop(); pipeline.stop() }
        let shaped = try render(output, seconds: 2)
        // The tone is far from the lifted band, so it comes out lower by the headroom.
        #expect(abs(decibels(rms(shaped.left[body]) / rms(plain.left[body])) + 9) < 0.1)
    }

    @Test func widthAndBalance() throws {
        var profile = SoundProfile()
        profile.isEnabled = true
        profile.width = 0
        var (output, pipeline, _) = try loadedOutput(profile: profile)
        let mono = try render(output, seconds: 2)
        output.stop(); pipeline.stop()
        // Mono: both sides carry the same mix of the two tones.
        #expect(zip(mono.left[body], mono.right[body]).allSatisfy { abs($0 - $1) < 1e-6 })
        #expect(abs(rms(mono.left[body]) - 0.0625) < 0.002)

        profile.width = 1
        profile.balance = 1
        (output, pipeline, _) = try loadedOutput(profile: profile)
        defer { output.stop(); pipeline.stop() }
        let right = try render(output, seconds: 2)
        #expect((right.left[body].map(abs).max() ?? 1) < 1e-5)
        #expect(abs(rms(right.right[body]) - 0.125 / 2.0.squareRoot()) < 0.002)
    }

    @Test func metersReportTheOutputLevel() throws {
        let (output, pipeline, _) = try loadedOutput(profile: SoundProfile())
        defer { output.stop(); pipeline.stop() }
        _ = try render(output, seconds: 1)
        let levels = output.takeLevels()
        #expect(abs(levels.peakLeft - 0.125) < 0.005)
        #expect(abs(levels.peakRight - 0.125) < 0.005)
        #expect(abs(levels.rmsLeft - 0.125 / Float(2.0.squareRoot())) < 0.004)
        #expect(levels.gainReduction == 0)
        // Peaks are "since the last reading": nothing was rendered in between.
        #expect(output.takeLevels().peakLeft == 0)
    }

    @Test func theSpectrumShowsTheTwoTones() throws {
        let (output, pipeline, _) = try loadedOutput(profile: SoundProfile())
        defer { output.stop(); pipeline.stop() }
        _ = try render(output, seconds: 1)
        let bands = 48
        let spectrum = output.spectrum(bands: bands)
        func band(_ hertz: Double) -> Int { Int(Double(bands) * log(hertz / 20) / log(1000)) }
        // Each tone is at one eighth of full scale on one side, so one sixteenth (-24 dB) in the mono mix.
        #expect(abs(spectrum[band(440)] + 24.1) < 2)
        #expect(abs(spectrum[band(660)] + 24.1) < 2)
        #expect(spectrum[band(5_000)] < -70)
        #expect(spectrum[band(60)] < -70)
    }
}
