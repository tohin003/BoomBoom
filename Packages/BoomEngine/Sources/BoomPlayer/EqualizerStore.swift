import BoomAudio
import Foundation
import Observation

/// One slider of the graphic equaliser.
public struct EqualizerBand: Identifiable, Hashable, Sendable {
    public let id: Int
    /// Centre frequency in hertz.
    public let frequency: Double
    /// "32", "1K", "16K".
    public let label: String
}

public struct EqualizerPreset: Identifiable, Codable, Hashable, Sendable {
    public var id: String
    public var name: String
    /// Decibels per band, in the order of `EqualizerStore.bands`.
    public var gains: [Float]
    public var isBuiltIn: Bool
    /// The rest of the sound as it was when the listener saved the preset: amplifier, tone, enhancers and stereo.
    /// Built-in presets are band curves only and have none: choosing one leaves all of that as it is.
    public var sound: Sound?

    public init(id: String, name: String, gains: [Float], isBuiltIn: Bool, sound: Sound? = nil) {
        self.id = id
        self.name = name
        self.gains = gains
        self.isBuiltIn = isBuiltIn
        self.sound = sound
    }

    /// Everything in the sound profile besides the ten bands, the master switch and loudness normalising (which is
    /// about the songs, not about a sound).
    public struct Sound: Codable, Hashable, Sendable {
        public var preamp: Float
        public var bass: Float
        public var treble: Float
        public var boost: Float
        public var isLimiterEnabled: Bool
        public var isAutoHeadroomEnabled: Bool
        public var deepBass: Float
        public var air: Float
        public var balance: Float
        public var width: Float
        public var crossfeed: Float
        public var isSpatialEnabled: Bool

        public init(_ settings: SoundSettings) {
            preamp = settings.preamp
            bass = settings.bass
            treble = settings.treble
            boost = settings.boost
            isLimiterEnabled = settings.isLimiterEnabled
            isAutoHeadroomEnabled = settings.isAutoHeadroomEnabled
            deepBass = settings.deepBass
            air = settings.air
            balance = settings.balance
            width = settings.width
            crossfeed = settings.crossfeed
            isSpatialEnabled = settings.isSpatialEnabled
        }

        /// A preset saved by an earlier version lacks the newer values: those take their defaults.
        public init(from decoder: any Decoder) throws {
            let values = try decoder.container(keyedBy: CodingKeys.self)
            let fresh = Sound(SoundSettings())
            preamp = try values.decodeIfPresent(Float.self, forKey: .preamp) ?? fresh.preamp
            bass = try values.decodeIfPresent(Float.self, forKey: .bass) ?? fresh.bass
            treble = try values.decodeIfPresent(Float.self, forKey: .treble) ?? fresh.treble
            boost = try values.decodeIfPresent(Float.self, forKey: .boost) ?? fresh.boost
            isLimiterEnabled = try values.decodeIfPresent(Bool.self, forKey: .isLimiterEnabled) ?? fresh.isLimiterEnabled
            isAutoHeadroomEnabled = try values.decodeIfPresent(Bool.self, forKey: .isAutoHeadroomEnabled)
                ?? fresh.isAutoHeadroomEnabled
            deepBass = try values.decodeIfPresent(Float.self, forKey: .deepBass) ?? fresh.deepBass
            air = try values.decodeIfPresent(Float.self, forKey: .air) ?? fresh.air
            balance = try values.decodeIfPresent(Float.self, forKey: .balance) ?? fresh.balance
            width = try values.decodeIfPresent(Float.self, forKey: .width) ?? fresh.width
            crossfeed = try values.decodeIfPresent(Float.self, forKey: .crossfeed) ?? fresh.crossfeed
            isSpatialEnabled = try values.decodeIfPresent(Bool.self, forKey: .isSpatialEnabled) ?? fresh.isSpatialEnabled
        }

        func apply(to settings: inout SoundSettings) {
            settings.preamp = preamp
            settings.bass = bass
            settings.treble = treble
            settings.boost = boost
            settings.isLimiterEnabled = isLimiterEnabled
            settings.isAutoHeadroomEnabled = isAutoHeadroomEnabled
            settings.deepBass = deepBass
            settings.air = air
            settings.balance = balance
            settings.width = width
            settings.crossfeed = crossfeed
            settings.isSpatialEnabled = isSpatialEnabled
        }
    }

    /// True when the settings are what this preset would set: its bands, and for a preset that carries the whole
    /// sound, the rest as well.
    public func matches(_ settings: SoundSettings) -> Bool {
        guard settings.gains == gains else { return false }
        guard let sound else { return true }
        return sound == Sound(settings)
    }
}

/// The whole sound profile: what the audio engine is told to do.
public struct SoundSettings: Codable, Equatable, Sendable {
    /// The master switch. Off: the audio passes untouched (bit for bit).
    public var isEnabled = false
    /// Decibels per band, -12...+12: the response at each band's centre, whatever its neighbours are set to.
    public var gains: [Float] = Array(repeating: 0, count: EqualizerStore.bands.count)
    /// Decibels applied to the whole signal, -12...+12.
    public var preamp: Float = 0
    /// A low shelf at 100 Hz, -12...+12 dB.
    public var bass: Float = 0
    /// A high shelf at 10 kHz, -12...+12 dB.
    public var treble: Float = 0
    /// The amplifier: extra loudness beyond full volume, 0...+12 dB, into the limiter.
    public var boost: Float = 0
    /// Holds peaks just under full scale so boosts never clip. Works whenever the master switch is on.
    public var isLimiterEnabled = true
    /// Lowers the level by the curve's largest boost, so the bands alone never push into the limiter. Off unless
    /// the listener asks for it: with it on, lifting the bass turns everything else down instead, and the limiter
    /// holds the rare peak that a boost pushes too far anyway.
    public var isAutoHeadroomEnabled = false
    /// Turns louder songs down to a common level, using the loudness YouTube Music reports for each song.
    /// Independent of the master switch.
    public var isLoudnessNormalizationEnabled = false
    /// -1 (left only) ... 0 ... +1 (right only).
    public var balance: Float = 0
    /// 0 (mono) ... 1 (as recorded) ... 2 (wide).
    public var width: Float = 1
    /// "Deep bass", 0 (off) ... 1: adds harmonics of the lowest notes, so small speakers and earphones carry them.
    public var deepBass: Float = 0
    /// "Air", 0 (off) ... 1: adds a little harmonic content to the top octave.
    public var air: Float = 0
    /// Headphone crossfeed, 0 (off) ... 1: a little of each side reaches the other ear, as in front of loudspeakers.
    public var crossfeed: Float = 0
    /// Spatial, for headphones: the two sides are heard as two loudspeakers standing in front of the listener, each
    /// reaching both ears the way it would in a room (from measurements on a dummy head).
    public var isSpatialEnabled = false
    /// With spatial on and headphones that report head movement (AirPods): the loudspeakers stay where they are
    /// while the head turns.
    public var isHeadTrackingEnabled = true

    public init() {}

    /// Settings saved by an earlier version lack the newer values: those take their defaults.
    public init(from decoder: any Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        let fresh = SoundSettings()
        isEnabled = try values.decodeIfPresent(Bool.self, forKey: .isEnabled) ?? fresh.isEnabled
        gains = try values.decodeIfPresent([Float].self, forKey: .gains) ?? fresh.gains
        preamp = try values.decodeIfPresent(Float.self, forKey: .preamp) ?? fresh.preamp
        bass = try values.decodeIfPresent(Float.self, forKey: .bass) ?? fresh.bass
        treble = try values.decodeIfPresent(Float.self, forKey: .treble) ?? fresh.treble
        boost = try values.decodeIfPresent(Float.self, forKey: .boost) ?? fresh.boost
        isLimiterEnabled = try values.decodeIfPresent(Bool.self, forKey: .isLimiterEnabled) ?? fresh.isLimiterEnabled
        isAutoHeadroomEnabled = try values.decodeIfPresent(Bool.self, forKey: .isAutoHeadroomEnabled)
            ?? fresh.isAutoHeadroomEnabled
        isLoudnessNormalizationEnabled = try values.decodeIfPresent(Bool.self, forKey: .isLoudnessNormalizationEnabled)
            ?? fresh.isLoudnessNormalizationEnabled
        balance = try values.decodeIfPresent(Float.self, forKey: .balance) ?? fresh.balance
        width = try values.decodeIfPresent(Float.self, forKey: .width) ?? fresh.width
        deepBass = try values.decodeIfPresent(Float.self, forKey: .deepBass) ?? fresh.deepBass
        air = try values.decodeIfPresent(Float.self, forKey: .air) ?? fresh.air
        crossfeed = try values.decodeIfPresent(Float.self, forKey: .crossfeed) ?? fresh.crossfeed
        isSpatialEnabled = try values.decodeIfPresent(Bool.self, forKey: .isSpatialEnabled) ?? fresh.isSpatialEnabled
        isHeadTrackingEnabled = try values.decodeIfPresent(Bool.self, forKey: .isHeadTrackingEnabled)
            ?? fresh.isHeadTrackingEnabled
    }

    /// True when the profile changes nothing, so the engine passes the audio straight through.
    public var isTransparent: Bool {
        !isEnabled || (gains.allSatisfy { $0 == 0 } && preamp == 0 && bass == 0 && treble == 0 && boost == 0
            && balance == 0 && width == 1 && deepBass == 0 && air == 0 && crossfeed == 0 && !isSpatialEnabled)
    }

    /// True when the head's movement should steer the spatial stage.
    public var wantsHeadTracking: Bool { isEnabled && isSpatialEnabled && isHeadTrackingEnabled }

    /// Decibels the level is lowered when auto headroom is on: the highest point of the curve the bands, bass and
    /// treble make together (to a tenth of a decibel, rounded up).
    public var headroom: Float {
        guard isEnabled, isAutoHeadroomEnabled else { return 0 }
        let peak = curve.peakResponse()
        return peak < 0.05 ? 0 : Float((peak * 10).rounded(.up) / 10)
    }

    /// The bands, bass and treble alone: what shapes the response curve.
    private var curve: SoundProfile {
        var profile = SoundProfile()
        profile.isEnabled = isEnabled
        for index in 0..<min(gains.count, profile.bandGains.count) { profile.bandGains[index] = gains[index] }
        profile.bassGain = bass
        profile.trebleGain = treble
        return profile
    }

    /// The settings in the audio engine's terms.
    public var profile: SoundProfile {
        var profile = curve
        profile.preamp = preamp
        profile.headroom = headroom
        profile.boost = boost
        profile.isLimiterEnabled = isLimiterEnabled
        profile.balance = balance
        profile.width = width
        profile.deepBass = deepBass
        profile.air = air
        profile.crossfeed = crossfeed
        profile.isSpatial = isSpatialEnabled
        return profile
    }

    /// The gain in decibels at each frequency: the bands, bass and treble together, exactly as the audio gets them.
    public func response(at frequencies: [Double]) -> [Double] {
        curve.response(at: frequencies)
    }
}

/// The output's levels as the audio engine reports them, for whoever implements `SoundProcessing`.
public typealias SoundLevels = BoomAudio.SoundLevels

/// What the equaliser screen drives. The audio engine implements it.
@MainActor
public protocol SoundProcessing: AnyObject {
    func apply(_ settings: SoundSettings)
    /// The output levels since the last call, after the limiter.
    func takeLevels() -> SoundLevels
    /// The output's spectrum over the last moment: `bands` values, evenly spaced on a logarithmic scale from 20 Hz
    /// to 20 kHz, in decibels where 0 is a full-scale sine.
    func spectrum(bands: Int) -> [Float]
    /// True when the headphones in use report head movement (AirPods Pro, AirPods Max, AirPods 3 and later).
    var isHeadTrackingAvailable: Bool { get }
}

/// Live output level, for the meters and the spectrum on the equaliser screen. Updated about thirty times a second
/// while a screen has asked for it (`EqualizerStore.startMetering()`).
@MainActor
@Observable
public final class LevelMeter {
    /// Peak level per channel in decibels relative to full scale, -60...0 (above 0 only with the limiter off).
    /// Rises at once and falls back steadily, like a hardware meter.
    public internal(set) var left: Float = LevelMeter.floor
    public internal(set) var right: Float = LevelMeter.floor
    /// The average (RMS) level per channel in decibels, -60...0: how loud it sounds rather than how high it peaks.
    public internal(set) var rmsLeft: Float = LevelMeter.floor
    public internal(set) var rmsRight: Float = LevelMeter.floor
    /// The highest peak of the last second and a half per channel, for a "peak hold" mark.
    public internal(set) var holdLeft: Float = LevelMeter.floor
    public internal(set) var holdRight: Float = LevelMeter.floor
    /// How far the limiter is turning the level down right now, in decibels: 0 (idle) upward.
    public internal(set) var gainReduction: Float = 0
    /// True for a moment after the output went past full scale (possible only with the limiter off).
    public internal(set) var isClipping = false
    /// The output's spectrum: `LevelMeter.spectrumBands` values from 20 Hz to 20 kHz on a logarithmic scale, in
    /// decibels (-90...0). Each bar rises at once and falls back steadily.
    public internal(set) var spectrum: [Float] = Array(repeating: LevelMeter.spectrumFloor, count: LevelMeter.spectrumBands)

    public nonisolated static let floor: Float = -60
    public nonisolated static let spectrumBands = 48
    public nonisolated static let spectrumFloor: Float = -90
    /// The frequency range of `spectrum`, in hertz.
    public nonisolated static let spectrumRange: ClosedRange<Double> = 20...20_000

    @ObservationIgnored fileprivate var holdUntilLeft = Date.distantPast
    @ObservationIgnored fileprivate var holdUntilRight = Date.distantPast
    @ObservationIgnored fileprivate var clipUntil = Date.distantPast

    public init() {}

    fileprivate func rest() {
        left = Self.floor
        right = Self.floor
        rmsLeft = Self.floor
        rmsRight = Self.floor
        holdLeft = Self.floor
        holdRight = Self.floor
        gainReduction = 0
        isClipping = false
        spectrum = Array(repeating: Self.spectrumFloor, count: Self.spectrumBands)
    }
}

/// The equaliser and amplifier as the screens see them. Changes apply to the sound at once and are remembered.
@MainActor
@Observable
public final class EqualizerStore {
    public nonisolated static let bands: [EqualizerBand] = SoundProfile.bandFrequencies.enumerated().map { index, hertz in
        let label = hertz >= 1000 ? "\(Int(hertz / 1000))K" : "\(Int(hertz))"
        return EqualizerBand(id: index, frequency: hertz, label: label)
    }

    /// Bands and preamp, in decibels.
    public nonisolated static let gainRange: ClosedRange<Float> = -12...12
    /// Bass and treble, in decibels.
    public nonisolated static let toneRange: ClosedRange<Float> = -12...12
    /// The amplifier's boost, in decibels.
    public nonisolated static let boostRange: ClosedRange<Float> = 0...12
    public nonisolated static let balanceRange: ClosedRange<Float> = -1...1
    public nonisolated static let widthRange: ClosedRange<Float> = 0...2
    /// Deep bass, air and crossfeed: 0 (off) to 1 (full).
    public nonisolated static let amountRange: ClosedRange<Float> = 0...1
    /// Where the bass and treble shelves turn, in hertz.
    public nonisolated static let bassFrequency: Double = 100
    public nonisolated static let trebleFrequency: Double = 10_000

    public private(set) var settings: SoundSettings
    /// Built-in presets first, then the listener's own.
    public private(set) var presets: [EqualizerPreset]
    /// The preset the settings match, nil once anything the preset sets was changed by hand.
    public private(set) var selectedPresetID: String?
    /// The presets the listener starred, oldest first. Built-in ones and the listener's own alike.
    public private(set) var favoritePresetIDs: [String]
    /// True while the listener is holding "compare": the sound is as the song was made, the settings untouched.
    public private(set) var isComparing = false
    /// True when the headphones in use can steer the spatial stage with the head's movement. Refreshed when a
    /// screen starts the meters, and by `refreshHeadTrackingAvailability()`.
    public private(set) var isHeadTrackingAvailable = false
    public let meter = LevelMeter()

    @ObservationIgnored private weak var sink: (any SoundProcessing)?
    @ObservationIgnored private let defaults: UserDefaults
    @ObservationIgnored private var meterTask: Task<Void, Never>?
    @ObservationIgnored private var meterClients = 0

    private enum Keys {
        static let settings = "sound.settings"
        static let customPresets = "sound.customPresets"
        static let selectedPreset = "sound.selectedPreset"
        static let favoritePresets = "sound.favoritePresets"
        static let settingsVersion = "sound.settingsVersion"
    }

    public init(sink: (any SoundProcessing)? = nil, defaults: UserDefaults = .standard) {
        self.sink = sink
        self.defaults = defaults
        var loaded = defaults.data(forKey: Keys.settings)
            .flatMap { try? JSONDecoder().decode(SoundSettings.self, from: $0) } ?? SoundSettings()
        if loaded.gains.count != Self.bands.count {
            loaded.gains = Array(repeating: 0, count: Self.bands.count)
        }
        // Version 2: auto headroom is no longer on from the start. Settings from before had it on only because
        // it was the default then, so they are brought in line once.
        if defaults.integer(forKey: Keys.settingsVersion) < 2 {
            loaded.isAutoHeadroomEnabled = false
            if let data = try? JSONEncoder().encode(loaded) { defaults.set(data, forKey: Keys.settings) }
            defaults.set(2, forKey: Keys.settingsVersion)
        }
        settings = loaded
        let custom = defaults.data(forKey: Keys.customPresets)
            .flatMap { try? JSONDecoder().decode([EqualizerPreset].self, from: $0) } ?? []
        let all = Self.builtInPresets + custom.filter { $0.gains.count == Self.bands.count }
        presets = all
        selectedPresetID = defaults.string(forKey: Keys.selectedPreset)
        let known = Set(all.map(\.id))
        favoritePresetIDs = (defaults.stringArray(forKey: Keys.favoritePresets) ?? []).filter { known.contains($0) }
        sink?.apply(settings)
    }

    /// Connects the audio engine once it exists and sends it the remembered profile.
    public func attach(_ sink: any SoundProcessing) {
        self.sink = sink
        sink.apply(settings)
    }

    // MARK: Changing the sound

    public func setEnabled(_ isEnabled: Bool) {
        update { $0.isEnabled = isEnabled }
    }

    /// Sets one band, in decibels. Turns the equaliser on, since moving a slider means wanting to hear it.
    public func setGain(_ decibels: Float, band: Int) {
        guard settings.gains.indices.contains(band) else { return }
        update {
            $0.gains[band] = Self.clamp(decibels, Self.gainRange)
            $0.isEnabled = true
        }
    }

    public func setPreamp(_ decibels: Float) {
        update { $0.preamp = Self.clamp(decibels, Self.gainRange) }
    }

    public func setBass(_ decibels: Float) {
        update { $0.bass = Self.clamp(decibels, Self.toneRange) }
    }

    public func setTreble(_ decibels: Float) {
        update { $0.treble = Self.clamp(decibels, Self.toneRange) }
    }

    /// The amplifier: 0 is full volume as recorded, +12 dB is four times the level (held under full scale by the
    /// limiter, so music already mastered loud gains less than quiet music does).
    public func setBoost(_ decibels: Float) {
        update { $0.boost = Self.clamp(decibels, Self.boostRange) }
    }

    /// -1 (left only) ... 0 ... +1 (right only).
    public func setBalance(_ balance: Float) {
        update { $0.balance = Self.clamp(balance, Self.balanceRange) }
    }

    /// 0 (mono) ... 1 (as recorded) ... 2 (wide).
    public func setWidth(_ width: Float) {
        update { $0.width = Self.clamp(width, Self.widthRange) }
    }

    /// 0 (off) ... 1: harmonics of the lowest notes, for small speakers and earphones.
    public func setDeepBass(_ amount: Float) {
        update { $0.deepBass = Self.clamp(amount, Self.amountRange) }
    }

    /// 0 (off) ... 1: harmonic content for the top octave.
    public func setAir(_ amount: Float) {
        update { $0.air = Self.clamp(amount, Self.amountRange) }
    }

    /// 0 (off) ... 1: headphone crossfeed.
    public func setCrossfeed(_ amount: Float) {
        update { $0.crossfeed = Self.clamp(amount, Self.amountRange) }
    }

    /// For headphones: hear the two sides as two loudspeakers in front of you.
    public func setSpatialEnabled(_ isEnabled: Bool) {
        update { $0.isSpatialEnabled = isEnabled }
    }

    /// With spatial on: keep the loudspeakers in place while the head turns (needs headphones that report it).
    public func setHeadTrackingEnabled(_ isEnabled: Bool) {
        update { $0.isHeadTrackingEnabled = isEnabled }
    }

    /// Looks again at whether the headphones in use report head movement (they may just have been put in).
    public func refreshHeadTrackingAvailability() {
        let available = sink?.isHeadTrackingAvailable ?? false
        if available != isHeadTrackingAvailable { isHeadTrackingAvailable = available }
    }

    public func setLimiterEnabled(_ isEnabled: Bool) {
        update { $0.isLimiterEnabled = isEnabled }
    }

    public func setAutoHeadroomEnabled(_ isEnabled: Bool) {
        update { $0.isAutoHeadroomEnabled = isEnabled }
    }

    public func setLoudnessNormalizationEnabled(_ isEnabled: Bool) {
        update { $0.isLoudnessNormalizationEnabled = isEnabled }
    }

    /// While on, the listener hears the song as it was made; the settings stay as they are and come back when it
    /// goes off. Changing any setting ends it.
    public func setComparing(_ isComparing: Bool) {
        guard isComparing != self.isComparing else { return }
        self.isComparing = isComparing
        sink?.apply(heard)
    }

    /// Applies a preset and turns the equaliser on. A built-in preset sets the ten bands; one of the listener's own
    /// sets everything it was saved with (amplifier, tone, enhancers, stereo).
    public func select(_ preset: EqualizerPreset) {
        guard preset.gains.count == Self.bands.count else { return }
        selectedPresetID = preset.id
        defaults.set(preset.id, forKey: Keys.selectedPreset)
        update {
            $0.gains = preset.gains.map { Self.clamp($0, Self.gainRange) }
            preset.sound?.apply(to: &$0)
            $0.isEnabled = true
        }
    }

    /// Saves the whole sound as it stands (bands, amplifier, tone, enhancers, stereo) as a preset of the listener's
    /// own and selects it.
    @discardableResult
    public func saveCurrentAsPreset(named name: String) -> EqualizerPreset {
        let preset = EqualizerPreset(
            id: "custom-\(UUID().uuidString)", name: Self.presetName(name), gains: settings.gains, isBuiltIn: false,
            sound: EqualizerPreset.Sound(settings))
        presets.append(preset)
        saveCustomPresets()
        selectedPresetID = preset.id
        defaults.set(preset.id, forKey: Keys.selectedPreset)
        return preset
    }

    /// Replaces what one of the listener's own presets holds with the sound as it stands now, and selects it.
    public func overwritePreset(id: String) {
        guard let index = presets.firstIndex(where: { $0.id == id && !$0.isBuiltIn }) else { return }
        presets[index].gains = settings.gains
        presets[index].sound = EqualizerPreset.Sound(settings)
        saveCustomPresets()
        selectedPresetID = id
        defaults.set(id, forKey: Keys.selectedPreset)
    }

    // MARK: Favourites

    /// The starred presets in the order of `presets`.
    public var favoritePresets: [EqualizerPreset] {
        let starred = Set(favoritePresetIDs)
        return presets.filter { starred.contains($0.id) }
    }

    public func isFavorite(_ presetID: String) -> Bool {
        favoritePresetIDs.contains(presetID)
    }

    public func setFavorite(_ isFavorite: Bool, presetID: String) {
        guard presets.contains(where: { $0.id == presetID }), isFavorite != favoritePresetIDs.contains(presetID) else { return }
        if isFavorite {
            favoritePresetIDs.append(presetID)
        } else {
            favoritePresetIDs.removeAll { $0 == presetID }
        }
        defaults.set(favoritePresetIDs, forKey: Keys.favoritePresets)
    }

    /// Renames one of the listener's own presets. Built-in ones keep their names.
    public func renamePreset(id: String, to name: String) {
        guard let index = presets.firstIndex(where: { $0.id == id && !$0.isBuiltIn }) else { return }
        presets[index].name = Self.presetName(name)
        saveCustomPresets()
    }

    /// Deletes one of the listener's own presets. Built-in ones stay.
    public func deletePreset(id: String) {
        guard let index = presets.firstIndex(where: { $0.id == id && !$0.isBuiltIn }) else { return }
        presets.remove(at: index)
        saveCustomPresets()
        setFavorite(false, presetID: id)
        if favoritePresetIDs.contains(id) {
            // The preset is gone from `presets`, so `setFavorite` could not find it.
            favoritePresetIDs.removeAll { $0 == id }
            defaults.set(favoritePresetIDs, forKey: Keys.favoritePresets)
        }
        if selectedPresetID == id {
            selectedPresetID = nil
            defaults.removeObject(forKey: Keys.selectedPreset)
        }
    }

    /// Everything back to flat, centred and unamplified. The master switch and loudness normalising stay as they are.
    public func reset() {
        selectedPresetID = Self.builtInPresets.first?.id
        defaults.set(selectedPresetID, forKey: Keys.selectedPreset)
        update {
            let wasEnabled = $0.isEnabled
            let normalization = $0.isLoudnessNormalizationEnabled
            $0 = SoundSettings()
            $0.isEnabled = wasEnabled
            $0.isLoudnessNormalizationEnabled = normalization
        }
    }

    private func update(_ change: (inout SoundSettings) -> Void) {
        var next = settings
        change(&next)
        guard next != settings else { return }
        settings = next
        isComparing = false
        // A preset stays selected only while the settings are still what it sets.
        if let id = selectedPresetID, let preset = presets.first(where: { $0.id == id }), !preset.matches(next) {
            selectedPresetID = nil
            defaults.removeObject(forKey: Keys.selectedPreset)
        }
        if let data = try? JSONEncoder().encode(next) { defaults.set(data, forKey: Keys.settings) }
        sink?.apply(heard)
    }

    /// What the engine is told: the settings, or (while comparing) the same with the master switch off.
    private var heard: SoundSettings {
        guard isComparing else { return settings }
        var plain = settings
        plain.isEnabled = false
        return plain
    }

    private func saveCustomPresets() {
        let custom = presets.filter { !$0.isBuiltIn }
        if let data = try? JSONEncoder().encode(custom) { defaults.set(data, forKey: Keys.customPresets) }
    }

    private static func presetName(_ name: String) -> String {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? "My preset" : trimmed
    }

    private static func clamp(_ value: Float, _ range: ClosedRange<Float>) -> Float {
        min(max(value, range.lowerBound), range.upperBound)
    }

    // MARK: Drawing the curve

    /// The combined gain in decibels at each frequency: what the curve on the equaliser screen draws.
    /// Includes the bands, bass and treble; not the preamp, boost or headroom (those shift the whole curve).
    public func response(at frequencies: [Double]) -> [Double] {
        settings.response(at: frequencies)
    }

    public nonisolated static func response(of settings: SoundSettings, at frequencies: [Double]) -> [Double] {
        settings.response(at: frequencies)
    }

    // MARK: Meters

    /// Call when a view showing the meters or the spectrum appears; pair with `stopMetering()`.
    public func startMetering() {
        meterClients += 1
        refreshHeadTrackingAvailability()
        guard meterTask == nil else { return }
        meterTask = Task { [weak self] in
            var last = Date()
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(33))
                guard let self, !Task.isCancelled else { return }
                let now = Date()
                self.readMeters(now: now, elapsed: Float(min(now.timeIntervalSince(last), 0.25)))
                last = now
            }
        }
    }

    public func stopMetering() {
        meterClients = max(0, meterClients - 1)
        guard meterClients == 0 else { return }
        meterTask?.cancel()
        meterTask = nil
        meter.rest()
    }

    /// One reading of the engine's levels, with a meter's manners: up at once, back down steadily.
    private func readMeters(now: Date, elapsed: Float) {
        guard let sink else { return }
        let levels = sink.takeLevels()
        let fall = 26 * elapsed    // decibels a bar falls in this time
        let peakLeft = Self.decibels(levels.peakLeft), peakRight = Self.decibels(levels.peakRight)
        meter.left = max(peakLeft, meter.left - fall, LevelMeter.floor)
        meter.right = max(peakRight, meter.right - fall, LevelMeter.floor)
        meter.rmsLeft = max(Self.decibels(levels.rmsLeft), meter.rmsLeft - fall, LevelMeter.floor)
        meter.rmsRight = max(Self.decibels(levels.rmsRight), meter.rmsRight - fall, LevelMeter.floor)
        if peakLeft >= meter.holdLeft {
            meter.holdLeft = peakLeft
            meter.holdUntilLeft = now.addingTimeInterval(1.5)
        } else if now >= meter.holdUntilLeft {
            meter.holdLeft = max(peakLeft, meter.holdLeft - fall, LevelMeter.floor)
        }
        if peakRight >= meter.holdRight {
            meter.holdRight = peakRight
            meter.holdUntilRight = now.addingTimeInterval(1.5)
        } else if now >= meter.holdUntilRight {
            meter.holdRight = max(peakRight, meter.holdRight - fall, LevelMeter.floor)
        }
        meter.gainReduction = max(levels.gainReduction, meter.gainReduction - 12 * elapsed, 0)
        if max(levels.peakLeft, levels.peakRight) > 1.0001 { meter.clipUntil = now.addingTimeInterval(1) }
        let clipping = now < meter.clipUntil
        if clipping != meter.isClipping { meter.isClipping = clipping }

        let fresh = sink.spectrum(bands: LevelMeter.spectrumBands)
        guard fresh.count == LevelMeter.spectrumBands else { return }
        var bars = meter.spectrum
        let barFall = 45 * elapsed
        for index in bars.indices {
            bars[index] = max(fresh[index], bars[index] - barFall, LevelMeter.spectrumFloor)
        }
        meter.spectrum = bars
    }

    private static func decibels(_ linear: Float) -> Float {
        linear > 0.001 ? max(LevelMeter.floor, 20 * log10(linear)) : LevelMeter.floor
    }

    // MARK: Presets

    public nonisolated static let builtInPresets: [EqualizerPreset] = [
        ("flat", "Flat", [0, 0, 0, 0, 0, 0, 0, 0, 0, 0]),
        ("bass-boost", "Bass boost", [6, 5, 4, 2.5, 1, 0, 0, 0, 0, 0]),
        ("bass-cut", "Bass reducer", [-6, -5, -4, -2.5, -1, 0, 0, 0, 0, 0]),
        ("treble-boost", "Treble boost", [0, 0, 0, 0, 0, 1, 2.5, 4, 5, 6]),
        ("treble-cut", "Treble reducer", [0, 0, 0, 0, 0, -1, -2.5, -4, -5, -6]),
        ("vocal", "Vocal boost", [-2, -3, -3, 1, 3.5, 3.5, 3, 1.5, 0, -1.5]),
        ("loudness", "Loudness", [6, 4, 0, 0, -2, 0, -1, -4, 4.5, 1.5]),
        ("rock", "Rock", [5, 4, 3, 1.5, -0.5, -1, 0.5, 2.5, 3.5, 4.5]),
        ("pop", "Pop", [-1.5, -1, 0, 2, 4, 4, 2, 0, -1, -1.5]),
        ("hip-hop", "Hip-hop", [5, 4, 1.5, 3, -1, -1, 1.5, -0.5, 2, 3]),
        ("electronic", "Electronic", [4, 3.5, 1, 0, -2, 2, 1, 1.5, 4, 4.5]),
        ("dance", "Dance", [3.5, 6.5, 5, 0, 2, 3.5, 5, 4.5, 3.5, 0]),
        ("rnb", "R&B", [2.5, 7, 5.5, 1.5, -2, -1.5, 2.5, 2.5, 3, 3.5]),
        ("jazz", "Jazz", [4, 3, 1.5, 2, -1.5, -1.5, 0, 1.5, 3, 3.5]),
        ("classical", "Classical", [4.5, 3.5, 3, 2.5, -1.5, -1.5, 0, 2, 3, 3.5]),
        ("acoustic", "Acoustic", [5, 5, 4, 1, 2, 2, 3.5, 4, 3.5, 2]),
        ("piano", "Piano", [3, 2, 0, 2.5, 3, 1.5, 3.5, 4.5, 3, 3.5]),
        ("deep", "Deep", [5, 3.5, 1.5, 1, 3, 2.5, 1.5, -2, -3.5, -4.5]),
        ("late-night", "Late night", [-2, -1, 0, 1.5, 3, 3, 2, 1, 0, -1]),
        ("small-speakers", "Small speakers", [5.5, 4.5, 3.5, 2.5, 1.5, 0.5, -0.5, -1.5, -2.5, -3.5]),
        ("spoken", "Spoken word", [-3.5, -0.5, 0, 0.5, 3.5, 4.5, 5, 4.5, 2.5, 0]),
    ].map { EqualizerPreset(id: $0.0, name: $0.1, gains: $0.2, isBuiltIn: true) }
}
