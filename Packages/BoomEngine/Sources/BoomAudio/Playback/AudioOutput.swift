import AVFoundation
import CBoomAudio
import Foundation

/// What the equaliser and amplifier are set to, in the audio engine's terms.
public struct SoundProfile: Sendable, Equatable {
    /// Off: the audio passes untouched.
    public var isEnabled = false
    /// Decibels per band, in the order of `bandFrequencies`: the response wanted at each band's centre.
    public var bandGains: [Float] = Array(repeating: 0, count: SoundProfile.bandCount)
    /// Decibels applied to the whole signal.
    public var preamp: Float = 0
    /// Decibels the level is lowered to leave the bands room.
    public var headroom: Float = 0
    /// A low shelf at 100 Hz, in decibels.
    public var bassGain: Float = 0
    /// A high shelf at 10 kHz, in decibels.
    public var trebleGain: Float = 0
    /// The amplifier: decibels added ahead of the limiter.
    public var boost: Float = 0
    /// Hold peaks just under full scale.
    public var isLimiterEnabled = true
    /// -1 (left only) ... 0 ... +1 (right only).
    public var balance: Float = 0
    /// 0 (mono) ... 1 (as recorded) ... 2 (wide).
    public var width: Float = 1
    /// 0 (off) ... 1: adds harmonics of the lowest notes, so small speakers and earphones carry them.
    public var deepBass: Float = 0
    /// 0 (off) ... 1: adds a little harmonic content to the top octave.
    public var air: Float = 0
    /// 0 (off) ... 1: for headphones, feeds a little of each side to the other, as loudspeakers do.
    public var crossfeed: Float = 0
    /// For headphones: the two sides are heard as two loudspeakers standing in front of the listener.
    public var isSpatial = false

    public init() {}

    public static let bandCount = Int(BOOM_DSP_BANDS)
    /// The bands' centre frequencies in hertz.
    public static let bandFrequencies: [Double] = (0..<bandCount).map { boom_dsp_band_frequency(UInt32($0)) }

    /// True when nothing would change the audio.
    public var isTransparent: Bool {
        !isEnabled || (bandGains.allSatisfy { $0 == 0 } && preamp == 0 && headroom == 0 && bassGain == 0
            && trebleGain == 0 && boost == 0 && balance == 0 && width == 1 && deepBass == 0 && air == 0
            && crossfeed == 0 && !isSpatial)
    }

    /// The gain in decibels that the bands, bass and treble give at each frequency: the curve a screen draws. It is
    /// the arithmetic the audio goes through, so the picture and the sound agree.
    public func response(at frequencies: [Double], sampleRate: Double = PlaybackPipeline.sampleRate) -> [Double] {
        guard !frequencies.isEmpty else { return [] }
        var parameters = parameters
        var result = [Double](repeating: 0, count: frequencies.count)
        boom_dsp_response(&parameters, sampleRate, frequencies, UInt32(frequencies.count), &result)
        return result
    }

    /// The largest gain of that curve between 20 Hz and 20 kHz, in decibels; never below 0.
    public func peakResponse(sampleRate: Double = PlaybackPipeline.sampleRate) -> Double {
        var parameters = parameters
        return boom_dsp_peak_response(&parameters, sampleRate)
    }

    var parameters: BoomSoundParams {
        var result = BoomSoundParams()
        result.enabled = isEnabled
        withUnsafeMutableBytes(of: &result.band_gain_db) { raw in
            let gains = raw.bindMemory(to: Float.self)
            for index in 0..<min(gains.count, bandGains.count) { gains[index] = bandGains[index] }
        }
        result.preamp_db = preamp
        result.headroom_db = headroom
        result.bass_db = bassGain
        result.treble_db = trebleGain
        result.boost_db = boost
        result.limiter = isLimiterEnabled
        result.balance = balance
        result.width = width
        result.deep_bass = deepBass
        result.air = air
        result.crossfeed = crossfeed
        result.spatial = isSpatial ? 1 : 0
        return result
    }
}

/// How loud the output is, as the meters show it.
public struct SoundLevels: Sendable, Equatable {
    /// The largest sample per channel since the last reading (1 = full scale).
    public var peakLeft: Float = 0
    public var peakRight: Float = 0
    /// The level of the last moment per channel (root mean square).
    public var rmsLeft: Float = 0
    public var rmsRight: Float = 0
    /// The most the limiter turned the level down since the last reading, in decibels (0: it did nothing).
    public var gainReduction: Float = 0

    public init() {}
}

/// The audio engine: pulls samples from the playback pipeline, sends them through the equaliser and amplifier
/// (`BoomDSP`, in C) and out to the hardware.
///
///     pipeline (48 kHz float) → level, ten bands, bass, treble → balance, width → limiter → mixer → output
///
/// With the profile off the samples reach the mixer exactly as decoded, a constant 5 ms later.
public final class AudioOutput: @unchecked Sendable {
    public let pipeline: PlaybackPipeline

    private let engine = AVAudioEngine()
    private let source: AVAudioSourceNode
    private let processor: Processor
    private let format: AVAudioFormat
    private let lock = NSLock()
    private var profile = SoundProfile()
    private var wantsRunning = false
    private var observer: NSObjectProtocol?

    /// Owns the C processor. The render thread uses the pointer; this object outlives the engine's last render.
    private final class Processor: @unchecked Sendable {
        let pointer: OpaquePointer

        init(sampleRate: Double) {
            guard let pointer = boom_dsp_create(sampleRate) else { fatalError("no memory for the sound processor") }
            self.pointer = pointer
        }

        deinit { boom_dsp_destroy(pointer) }
    }

    public init(pipeline: PlaybackPipeline) {
        self.pipeline = pipeline
        guard let format = AVAudioFormat(standardFormatWithSampleRate: PlaybackPipeline.sampleRate, channels: 2) else {
            fatalError("48 kHz stereo is always a valid format")
        }
        self.format = format
        let processor = Processor(sampleRate: PlaybackPipeline.sampleRate)
        self.processor = processor
        nonisolated(unsafe) let dsp = processor.pointer
        source = AVAudioSourceNode(format: format) { [pipeline] isSilence, _, frameCount, bufferList -> OSStatus in
            let buffers = UnsafeMutableAudioBufferListPointer(bufferList)
            guard buffers.count >= 2,
                  let left = buffers[0].mData?.assumingMemoryBound(to: Float.self),
                  let right = buffers[1].mData?.assumingMemoryBound(to: Float.self)
            else { return kAudioUnitErr_FormatNotSupported }
            let isHolding = pipeline.render(left: left, right: right, frames: frameCount)
            if boom_dsp_process(dsp, left, right, frameCount, isHolding) { isSilence.pointee = true }
            return noErr
        }

        engine.attach(source)
        engine.connect(source, to: engine.mainMixerNode, format: format)
        engine.mainMixerNode.outputVolume = 1
        // When the hardware runs at another rate than the 48 kHz of the streams, the mixer converts. It does so at
        // the best quality it has rather than the default middle setting.
        engine.mainMixerNode.auAudioUnit.renderQuality = 127

        observer = NotificationCenter.default.addObserver(
            forName: .AVAudioEngineConfigurationChange, object: engine, queue: nil
        ) { [weak self] _ in
            // The route changed (headphones, AirPlay, a new sample rate): the engine stopped itself.
            self?.restartIfWanted()
        }
    }

    deinit {
        if let observer { NotificationCenter.default.removeObserver(observer) }
        engine.stop()
    }

    // MARK: Running

    /// Renders without hardware, for tests: call before `start()`, then pull with `renderOffline`.
    public func enableOfflineRendering(maximumFrames: AVAudioFrameCount = 4096) throws {
        engine.stop()
        try engine.enableManualRenderingMode(.offline, format: format, maximumFrameCount: maximumFrames)
    }

    public func renderOffline(frames: AVAudioFrameCount) throws -> AVAudioPCMBuffer {
        guard let buffer = AVAudioPCMBuffer(pcmFormat: engine.manualRenderingFormat, frameCapacity: frames) else {
            throw DecodeError.failed("no room to render")
        }
        _ = try engine.renderOffline(frames, to: buffer)
        return buffer
    }

    public func start() throws {
        lock.lock()
        defer { lock.unlock() }
        wantsRunning = true
        guard !engine.isRunning else { return }
        engine.prepare()
        try engine.start()
    }

    public func stop() {
        lock.lock()
        defer { lock.unlock() }
        wantsRunning = false
        engine.stop()
    }

    public var isRunning: Bool { engine.isRunning }

    /// Seconds between a sample leaving the engine and being heard (larger over Bluetooth).
    public var outputLatency: TimeInterval { engine.outputNode.presentationLatency }

    /// The rate the hardware takes the sound at, in hertz. Equal to the pipeline's 48 kHz when nothing is resampled.
    public var hardwareSampleRate: Double { engine.outputNode.outputFormat(forBus: 0).sampleRate }

    /// Frames the equaliser and amplifier hold a sample for (the limiter's look-ahead). Constant.
    public var processingLatency: Int { Int(boom_dsp_latency(processor.pointer)) }

    private func restartIfWanted() {
        lock.lock()
        defer { lock.unlock() }
        guard wantsRunning, !engine.isRunning else { return }
        engine.prepare()
        try? engine.start()
    }

    // MARK: Sound

    /// Sets the equaliser and amplifier. The change glides in over a few hundredths of a second.
    public func apply(_ profile: SoundProfile) {
        lock.lock()
        defer { lock.unlock() }
        guard profile != self.profile else { return }
        self.profile = profile
        var parameters = profile.parameters
        boom_dsp_set_params(processor.pointer, &parameters)
    }

    /// For the spatial stage with head tracking: how far the listener's head is turned to the right of where the two
    /// loudspeakers stand, in radians. Any thread. 0 without head tracking.
    public func setHeadTurn(_ radians: Float) {
        boom_dsp_set_head_turn(processor.pointer, radians)
    }

    /// The engine's own output volume, 0...1. Left at 1 in the app (the phone's buttons set the loudness); a
    /// diagnostic run sets 0 to go through the whole chain without making a sound.
    public func setOutputVolume(_ volume: Float) {
        engine.mainMixerNode.outputVolume = min(max(volume, 0), 1)
    }

    // MARK: Meters

    /// The output levels since the last call, measured after the limiter and before the output volume. Free to
    /// leave unread. One reader at a time.
    public func takeLevels() -> SoundLevels {
        var raw = BoomLevels()
        boom_dsp_take_levels(processor.pointer, &raw)
        var levels = SoundLevels()
        levels.peakLeft = raw.peak_left
        levels.peakRight = raw.peak_right
        levels.rmsLeft = raw.rms_left
        levels.rmsRight = raw.rms_right
        levels.gainReduction = raw.gain_reduction_db
        return levels
    }

    /// The output's spectrum over the last moment: `bands` values spaced evenly on a logarithmic scale from `low`
    /// to `high` hertz, in decibels where 0 is a full-scale sine. One reader at a time.
    public func spectrum(bands: Int, from low: Double = 20, to high: Double = 20_000) -> [Float] {
        guard bands > 0 else { return [] }
        var result = [Float](repeating: -120, count: bands)
        lock.lock()
        defer { lock.unlock() }
        boom_dsp_spectrum(processor.pointer, &result, UInt32(bands), low, high)
        return result
    }
}
