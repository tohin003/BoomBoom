import CBoomAudio
import Foundation
import Testing

// The equaliser, amplifier and limiter on their own (the C processor), fed with made-up signals.

private let sampleRate = 48_000.0

private func parameters(_ change: (inout BoomSoundParams) -> Void = { _ in }) -> BoomSoundParams {
    var result = BoomSoundParams()
    result.enabled = true
    result.limiter = true
    result.width = 1
    change(&result)
    return result
}

private func setBands(_ gains: [Float], in parameters: inout BoomSoundParams) {
    withUnsafeMutableBytes(of: &parameters.band_gain_db) { raw in
        let slots = raw.bindMemory(to: Float.self)
        for index in 0..<min(slots.count, gains.count) { slots[index] = gains[index] }
    }
}

private final class Processor {
    let pointer: OpaquePointer

    init(_ parameters: BoomSoundParams) {
        pointer = boom_dsp_create(sampleRate)!
        var parameters = parameters
        boom_dsp_set_params(pointer, &parameters)
    }

    deinit { boom_dsp_destroy(pointer) }

    func set(_ parameters: BoomSoundParams) {
        var parameters = parameters
        boom_dsp_set_params(pointer, &parameters)
    }

    /// Runs a stereo signal through in blocks, as a device would ask for it.
    func process(left: [Float], right: [Float], block: Int = 480) -> (left: [Float], right: [Float]) {
        var left = left, right = right
        var done = 0
        while done < left.count {
            let count = min(block, left.count - done)
            left.withUnsafeMutableBufferPointer { l in
                right.withUnsafeMutableBufferPointer { r in
                    _ = boom_dsp_process(pointer, l.baseAddress! + done, r.baseAddress! + done, UInt32(count), false)
                }
            }
            done += count
        }
        return (left, right)
    }

    var levels: BoomLevels {
        var levels = BoomLevels()
        boom_dsp_take_levels(pointer, &levels)
        return levels
    }
}

private func sine(_ hertz: Double, amplitude: Double, seconds: Double) -> [Float] {
    (0..<Int(seconds * sampleRate)).map { Float(amplitude * sin(2 * .pi * hertz * Double($0) / sampleRate)) }
}

private func rms(_ samples: ArraySlice<Float>) -> Double {
    (samples.reduce(0.0) { $0 + Double($1) * Double($1) } / Double(max(1, samples.count))).squareRoot()
}

private func decibels(_ ratio: Double) -> Double { 20 * log10(ratio) }

private func response(_ parameters: BoomSoundParams, at frequencies: [Double]) -> [Double] {
    var parameters = parameters
    var result = [Double](repeating: 0, count: frequencies.count)
    boom_dsp_response(&parameters, sampleRate, frequencies, UInt32(frequencies.count), &result)
    return result
}

private let centres = (0..<10).map { boom_dsp_band_frequency(UInt32($0)) }

/// The amplitude of the component at `hertz` in a stretch of signal.
private func part(_ samples: ArraySlice<Float>, at hertz: Double) -> Double {
    var inPhase = 0.0, quadrature = 0.0
    for (index, sample) in samples.enumerated() {
        let angle = 2 * Double.pi * hertz * Double(index) / sampleRate
        inPhase += Double(sample) * sin(angle)
        quadrature += Double(sample) * cos(angle)
    }
    return 2 * (inPhase * inPhase + quadrature * quadrature).squareRoot() / Double(samples.count)
}

/// The true peak of a stretch of signal: the wave rebuilt sixteen times finer with a long, accurate interpolator.
private func truePeak(_ samples: [Float], in range: Range<Int>) -> Double {
    let half = 48
    var peak = 0.0
    for index in range {
        for step in 0..<16 {
            let offset = Double(step) / 16
            var sum = 0.0
            for tap in (1 - half)...half {
                let distance = Double(tap) - offset
                let sinc = abs(distance) < 1e-9 ? 1 : sin(.pi * distance) / (.pi * distance)
                sum += Double(samples[index + tap]) * sinc * (0.5 + 0.5 * cos(.pi * distance / Double(half)))
            }
            peak = max(peak, abs(sum))
        }
    }
    return peak
}

@Suite struct SoundProcessorTests {
    @Test func flatPassesEverySampleUnchangedAndOnlyLater() {
        let processor = Processor(parameters())
        var generator = SystemRandomNumberGenerator()
        let left = (0..<48_000).map { _ in Float.random(in: -1...1, using: &generator) }
        let right = (0..<48_000).map { _ in Float.random(in: -1...1, using: &generator) }
        let out = processor.process(left: left, right: right)
        let latency = Int(boom_dsp_latency(processor.pointer))
        // The limiter's look-ahead (5 ms) and the reach of its true-peak measurement.
        #expect(latency == 247)
        #expect(Array(out.left[latency...]) == Array(left[..<(left.count - latency)]))
        #expect(Array(out.right[latency...]) == Array(right[..<(right.count - latency)]))
    }

    @Test func theCurvePassesThroughEverySlider() {
        let shapes: [[Float]] = [
            Array(repeating: 6, count: 10),
            [5, 4, 3, 1.5, -0.5, -1, 0.5, 2.5, 3.5, 4.5],
            [0, 0, 0, 0, 0, 12, 0, 0, 0, 0],
            (0..<10).map { $0 % 2 == 0 ? 12 : -12 },
        ]
        for shape in shapes {
            let curve = response(parameters { setBands(shape, in: &$0) }, at: centres)
            for (wanted, got) in zip(shape, curve) { #expect(abs(Double(wanted) - got) < 0.01) }
        }
        // Ten bands alike are level in between as well, not a row of humps.
        let between = response(parameters { setBands(Array(repeating: 6, count: 10), in: &$0) },
                               at: [45, 90, 177, 354, 707, 1414, 2828, 5657, 11_314])
        #expect(between.allSatisfy { abs($0 - 6) < 0.4 })
    }

    @Test func theSoundMatchesTheCurve() {
        let shape = parameters {
            setBands([5, 4, 3, 1.5, -0.5, -1, 0.5, 2.5, 3.5, 4.5], in: &$0)
            $0.bass_db = 4
            $0.treble_db = -3
            $0.limiter = false
        }
        for hertz in [50.0, 180, 1000, 3000, 12_000] {
            let tone = sine(hertz, amplitude: 0.05, seconds: 1.5)
            let out = Processor(shape).process(left: tone, right: tone)
            let measured = decibels(rms(out.left[36_000...]) / rms(tone[36_000...]))
            #expect(abs(measured - response(shape, at: [hertz])[0]) < 0.05)
        }
    }

    @Test func theLimiterNeverLetsASamplePastItsCeiling() {
        // Something like music: a kick drum twice a second over a steady bed, already close to full scale.
        var generator = SystemRandomNumberGenerator()
        let music: [Float] = (0..<Int(6 * sampleRate)).map { index in
            let time = Double(index) / sampleRate
            let beat = time.truncatingRemainder(dividingBy: 0.5)
            let kick = beat < 0.12 ? exp(-beat * 30) * sin(2 * .pi * 55 * time) : 0
            let bed = 0.18 * sin(2 * .pi * 220 * time) + 0.12 * sin(2 * .pi * 880 * time)
                + 0.05 * Double.random(in: -1...1, using: &generator)
            return Float(0.75 * kick + bed)
        }
        let half = music.count / 2
        var gains: [Double] = []
        for boost in [6.0, 12.0] {
            let processor = Processor(parameters { $0.boost_db = Float(boost) })
            let out = processor.process(left: music, right: music)
            #expect((out.left.map(abs).max() ?? 9) <= 0.96606)
            gains.append(decibels(rms(out.left[half...]) / rms(music[half...])))
            #expect(processor.levels.gain_reduction_db > 3)
        }
        // The boost is heard: clearly louder at +6, louder again at +12, though less than asked once the top is held.
        #expect(gains[0] > 3 && gains[0] < 6)
        #expect(gains[1] > 6 && gains[1] < 12)
    }

    @Test func aHeldDownBassNoteStaysClean() {
        // A full-scale 50 Hz tone pushed 12 dB into the limiter: it must come out as the same tone at the ceiling,
        // not reshaped wave by wave.
        let tone = sine(50, amplitude: 1, seconds: 3)
        let out = Processor(parameters { $0.boost_db = 12 }).process(left: tone, right: tone)
        let tail = Array(out.left[(out.left.count - 19_200)...])
        var inPhase = 0.0, quadrature = 0.0, total = 0.0
        for (index, sample) in tail.enumerated() {
            let angle = 2 * Double.pi * 50 * Double(index) / sampleRate
            inPhase += Double(sample) * sin(angle)
            quadrature += Double(sample) * cos(angle)
            total += Double(sample) * Double(sample)
        }
        let tonePower = 2 / Double(tail.count) * (inPhase * inPhase + quadrature * quadrature)
        let distortion = (max(0, total - tonePower) / total).squareRoot()
        #expect(distortion < 0.001)
        #expect(abs((tail.map(abs).max() ?? 0) - 0.96605) < 0.001)
    }

    @Test func changingSettingsUnderATonesMakesNoClick() {
        let processor = Processor(parameters())
        var worst: Float = 0
        var last: Float = 0
        for block in 0..<3000 {
            switch block {
            case 500: processor.set(parameters {
                setBands([0, 0, 12, 0, 0, 0, 0, -12, 0, 0], in: &$0)
                $0.boost_db = 6
                $0.bass_db = 9
            })
            case 1200: processor.set(parameters { $0.enabled = false })
            case 1800: processor.set(parameters { $0.width = 0; $0.balance = -1 })
            case 2400: processor.set(parameters())
            default: break
            }
            let tone = (0..<64).map { Float(0.2 * sin(2 * .pi * 125 * Double(block * 64 + $0) / sampleRate)) }
            let out = processor.process(left: tone, right: tone, block: 64)
            for sample in out.left {
                worst = max(worst, abs(sample - last))
                last = sample
            }
        }
        // The tone itself moves 0.0033 a sample; boosted by 27 dB it could move 0.073. A click would be far larger.
        #expect(worst < 0.08)
    }

    @Test func silenceInGivesSilenceOutAndItWakesAgain() {
        let processor = Processor(parameters {
            setBands([12, 0, 0, 0, 0, 0, 0, 0, 0, 0], in: &$0)
            $0.boost_db = 12
        })
        let tone = sine(60, amplitude: 0.9, seconds: 1)
        _ = processor.process(left: tone, right: tone)
        var quietCalls = 0
        var zeros = [Float](repeating: 0, count: 480), more = zeros
        for _ in 0..<400 {
            zeros = [Float](repeating: 0, count: 480)
            more = zeros
            if boom_dsp_process(processor.pointer, &zeros, &more, 480, true) { quietCalls += 1 }
        }
        #expect(quietCalls > 150)
        let again = processor.process(left: tone, right: tone)
        #expect(rms(again.left[24_000...]) > 0.3)
        #expect((again.left.map(abs).max() ?? 9) <= 0.96606)
    }

    @Test func theLimiterHoldsTheWaveBetweenTheSamplesToo() {
        // A 12 kHz tone whose samples all miss the crests: after a 2 dB boost the samples reach 0.80, the wave
        // itself 1.13. A limiter that only looked at samples would let it through.
        let tone: [Float] = (0..<96_000).map { Float(0.9 * sin(2 * .pi * 12_000 * Double($0) / sampleRate + .pi / 4)) }
        let processor = Processor(parameters { $0.boost_db = 2 })
        let out = processor.process(left: tone, right: tone)
        #expect((out.left[48_000...].map(abs).max() ?? 9) < 0.75)
        let peak = truePeak(out.left, in: 86_000..<90_000)
        #expect(peak <= 0.96605 * 1.006 && peak > 0.93)
        #expect(processor.levels.gain_reduction_db > 1)
        // And at the top of the audible range.
        for hertz in [15_000.0, 18_000, 20_000] {
            let high: [Float] = (0..<96_000).map { Float(0.9 * sin(2 * .pi * hertz * Double($0) / sampleRate + 0.3)) }
            let limited = Processor(parameters { $0.boost_db = 6 }).process(left: high, right: high)
            #expect(truePeak(limited.left, in: 86_000..<90_000) <= 0.96605 * 1.02)
        }
    }

    @Test func deepBassGivesALowNoteItsHarmonicsAtAnyLevel() {
        var ratios: [(second: Double, third: Double)] = []
        for amplitude in [0.25, 0.1] {
            let note = sine(50, amplitude: amplitude, seconds: 3)
            let out = Processor(parameters { $0.deep_bass = 1; $0.limiter = false }).process(left: note, right: note)
            let tail = out.left[96_000...]
            let fundamental = part(tail, at: 50)
            #expect(abs(decibels(fundamental / amplitude)) < 1.5)
            ratios.append((decibels(part(tail, at: 100) / fundamental), decibels(part(tail, at: 150) / fundamental)))
            // Nothing beyond the third harmonic to speak of.
            #expect(decibels(part(tail, at: 250) / fundamental) < -30)
        }
        #expect(ratios[0].second > -9 && ratios[0].second < -3)
        #expect(ratios[0].third > -13 && ratios[0].third < -6)
        #expect(abs(ratios[0].second - ratios[1].second) < 0.5)
        #expect(abs(ratios[0].third - ratios[1].third) < 0.5)
        // The midrange is not its business.
        let tone = sine(1000, amplitude: 0.25, seconds: 2)
        let untouched = Processor(parameters { $0.deep_bass = 1; $0.limiter = false }).process(left: tone, right: tone)
        let tail = untouched.left[48_000...]
        let kept = part(tail, at: 1000)
        let rest = max(0, rms(tail) * rms(tail) - kept * kept / 2).squareRoot()
        #expect(rest / (kept / 2.0.squareRoot()) < 1e-4)
    }

    @Test func airAddsOnlyTheUpperHarmonic() {
        let tone = sine(5000, amplitude: 0.25, seconds: 2)
        let out = Processor(parameters { $0.air = 1; $0.limiter = false }).process(left: tone, right: tone)
        let tail = out.left[48_000...]
        let fundamental = part(tail, at: 5000)
        #expect(abs(decibels(fundamental / 0.25)) < 0.2)
        let second = decibels(part(tail, at: 10_000) / fundamental)
        #expect(second > -16 && second < -9)
        #expect(decibels(part(tail, at: 15_000) / fundamental) < -60)
        #expect(decibels(part(tail, at: 100) / fundamental) < -90)
        // Two tones make a sum tone above them, and no difference tone below.
        let pair = zip(sine(5000, amplitude: 0.2, seconds: 2), sine(5500, amplitude: 0.2, seconds: 2)).map { $0 + $1 }
        let mixed = Processor(parameters { $0.air = 1; $0.limiter = false }).process(left: pair, right: pair)
        #expect(decibels(part(mixed.left[48_000...], at: 500) / 0.2) < -90)
        // A tone below the band it works on is left as it is.
        let low = sine(440, amplitude: 0.25, seconds: 2)
        let same = Processor(parameters { $0.air = 1; $0.limiter = false }).process(left: low, right: low)
        let kept = part(same.left[48_000...], at: 440)
        let rest = max(0, rms(same.left[48_000...]) * rms(same.left[48_000...]) - kept * kept / 2).squareRoot()
        #expect(rest / (kept / 2.0.squareRoot()) < 1e-4)
    }

    @Test func crossfeedLeavesTheMiddleAloneAndCarriesLowNotesAcross() {
        var generator = SystemRandomNumberGenerator()
        let middle = (0..<48_000).map { _ in Float.random(in: -1...1, using: &generator) }
        let processor = Processor(parameters { $0.crossfeed = 1; $0.limiter = false })
        let out = processor.process(left: middle, right: middle)
        let latency = Int(boom_dsp_latency(processor.pointer))
        #expect(Array(out.left[latency...]) == Array(middle[..<(middle.count - latency)]))
        #expect(out.left == out.right)

        func across(_ hertz: Double) -> Double {
            let tone = sine(hertz, amplitude: 0.5, seconds: 2)
            let silence = [Float](repeating: 0, count: tone.count)
            let fed = Processor(parameters { $0.crossfeed = 1; $0.limiter = false }).process(left: tone, right: silence)
            return decibels(part(fed.right[48_000...], at: hertz) / part(fed.left[48_000...], at: hertz))
        }
        // A bass note on one side is heard on the other only a few decibels down; a high note hardly at all.
        #expect(across(100) > -8 && across(100) < -4)
        #expect(across(8000) < -25)
    }

    @Test func everythingAtOnceStaysUnderTheCeiling() {
        var generator = SystemRandomNumberGenerator()
        let left = (0..<144_000).map { _ in Float.random(in: -1...1, using: &generator) }
        let right = left.enumerated().map { $1 * 0.6 + 0.3 * Float(sin(2 * .pi * 60 * Double($0) / sampleRate)) }
        let processor = Processor(parameters {
            setBands([-3, 0, 3, -3, 0, 3, -3, 0, 3, -3], in: &$0)
            $0.boost_db = 9
            $0.bass_db = 6
            $0.deep_bass = 1
            $0.air = 1
            $0.crossfeed = 1
            $0.width = 1.5
        })
        let out = processor.process(left: left, right: right)
        #expect((out.left.map(abs).max() ?? 9) <= 0.96606)
        #expect((out.right.map(abs).max() ?? 9) <= 0.96606)
    }

    // MARK: Spatial

    /// How many frames `first` leads `second` by, from the peak of their correlation.
    private func lead(_ first: ArraySlice<Float>, over second: ArraySlice<Float>) -> Int {
        let a = Array(first), b = Array(second)
        var best = 0
        var top = -Double.infinity
        for lag in -60...60 {
            var sum = 0.0
            for index in 100..<(a.count - 100) { sum += Double(a[index]) * Double(b[index + lag]) }
            if sum > top {
                top = sum
                best = lag
            }
        }
        return best
    }

    private func noise(_ count: Int, amplitude: Float = 0.3) -> [Float] {
        var generator = SystemRandomNumberGenerator()
        return (0..<count).map { _ in Float.random(in: -amplitude...amplitude, using: &generator) }
    }

    @Test func spatialPutsEachSideWhereALoudspeakerWouldBe() {
        // A voice in the middle: the same in both ears, at the level it had.
        let middle = noise(96_000)
        let centred = Processor(parameters { $0.spatial = 1; $0.limiter = false }).process(left: middle, right: middle)
        #expect(zip(centred.left[48_000...], centred.right[48_000...]).allSatisfy { abs($0 - $1) < 1e-6 })
        #expect(abs(decibels(rms(centred.left[48_000...]) / rms(middle[48_000...]))) < 0.2)

        // A sound on the left side only reaches the left ear about a quarter of a millisecond sooner, and louder:
        // what a loudspeaker 30 degrees to the left does.
        let silence = [Float](repeating: 0, count: 96_000)
        let side = Processor(parameters { $0.spatial = 1; $0.limiter = false }).process(left: middle, right: silence)
        let ahead = lead(side.left[48_000..<57_600], over: side.right[48_000..<57_600])
        #expect(ahead >= 9 && ahead <= 15)
        let louder = decibels(rms(side.left[48_000...]) / rms(side.right[48_000...]))
        #expect(louder > 5 && louder < 14)
    }

    @Test func turningTheHeadLeavesTheLoudspeakersWhereTheyAre() {
        let sound = noise(96_000)
        let silence = [Float](repeating: 0, count: 96_000)
        // Facing the left loudspeaker (the head 30 degrees to the left): both ears hear it alike.
        let facing = Processor(parameters { $0.spatial = 1; $0.limiter = false })
        boom_dsp_set_head_turn(facing.pointer, Float(-30 * Double.pi / 180))
        let straight = facing.process(left: sound, right: silence)
        #expect(abs(lead(straight.left[48_000..<57_600], over: straight.right[48_000..<57_600])) <= 1)
        #expect(abs(decibels(rms(straight.left[48_000...]) / rms(straight.right[48_000...]))) < 0.5)
        // Turned further, it is on the right.
        let past = Processor(parameters { $0.spatial = 1; $0.limiter = false })
        boom_dsp_set_head_turn(past.pointer, Float(-60 * Double.pi / 180))
        let right = past.process(left: sound, right: silence)
        #expect(lead(right.left[48_000..<57_600], over: right.right[48_000..<57_600]) <= -9)
        #expect(decibels(rms(right.left[48_000...]) / rms(right.right[48_000...])) < -5)
    }

    @Test func spatialSwitchesAndSweepsWithoutAClick() {
        let processor = Processor(parameters { $0.limiter = false })
        var worst: Float = 0
        var last: Float = 0
        for block in 0..<6000 {
            if block == 500 { processor.set(parameters { $0.spatial = 1; $0.limiter = false }) }
            if (1500..<4500).contains(block) {
                boom_dsp_set_head_turn(processor.pointer, Float(sin(Double(block - 1500) / 3000 * 6 * .pi) * 80 * .pi / 180))
            }
            if block == 5000 { processor.set(parameters { $0.limiter = false }) }
            let tone = (0..<64).map { Float(0.25 * sin(2 * .pi * 200 * Double(block * 64 + $0) / sampleRate)) }
            let out = processor.process(left: tone, right: tone, block: 64)
            for sample in out.left {
                if block > 0 { worst = max(worst, abs(sample - last)) }
                last = sample
            }
        }
        // The tone itself moves 0.0065 a sample.
        #expect(worst < 0.012)
    }
}
