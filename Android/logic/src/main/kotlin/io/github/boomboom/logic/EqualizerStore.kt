package io.github.boomboom.logic

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.util.UUID
import kotlin.math.log10

/** One reading of the meters, as the equaliser screen draws them. */
data class MeterState(
    /**
     * Peak level per channel in decibels relative to full scale, -60...0 (above 0 only with the limiter off). Rises at
     * once and falls back steadily, like a hardware meter.
     */
    val left: Float = LevelMeter.FLOOR,
    val right: Float = LevelMeter.FLOOR,
    /** The average (RMS) level per channel in decibels, -60...0: how loud it sounds rather than how high it peaks. */
    val rmsLeft: Float = LevelMeter.FLOOR,
    val rmsRight: Float = LevelMeter.FLOOR,
    /** The highest peak of the last second and a half per channel, for a "peak hold" mark. */
    val holdLeft: Float = LevelMeter.FLOOR,
    val holdRight: Float = LevelMeter.FLOOR,
    /** How far the limiter is turning the level down right now, in decibels: 0 (idle) upward. */
    val gainReduction: Float = 0f,
    /** True for a moment after the output went past full scale (possible only with the limiter off). */
    val isClipping: Boolean = false,
    /**
     * The output's spectrum: [LevelMeter.SPECTRUM_BANDS] values from 20 Hz to 20 kHz on a logarithmic scale, in
     * decibels (-90...0). Each bar rises at once and falls back steadily.
     */
    val spectrum: List<Float> = List(LevelMeter.SPECTRUM_BANDS) { LevelMeter.SPECTRUM_FLOOR },
)

/**
 * Live output level, for the meters and the spectrum on the equaliser screen. Updated about thirty times a second
 * while a screen has asked for it ([EqualizerStore.startMetering]). It has a flow of its own, apart from the store's,
 * so that only the views showing meters recompose thirty times a second.
 */
class LevelMeter {
    private val _state = MutableStateFlow(MeterState())
    val state: StateFlow<MeterState> = _state.asStateFlow()

    val left: Float get() = _state.value.left
    val right: Float get() = _state.value.right
    val rmsLeft: Float get() = _state.value.rmsLeft
    val rmsRight: Float get() = _state.value.rmsRight
    val holdLeft: Float get() = _state.value.holdLeft
    val holdRight: Float get() = _state.value.holdRight
    val gainReduction: Float get() = _state.value.gainReduction
    val isClipping: Boolean get() = _state.value.isClipping
    val spectrum: List<Float> get() = _state.value.spectrum

    internal var holdUntilLeft = Long.MIN_VALUE
    internal var holdUntilRight = Long.MIN_VALUE
    internal var clipUntil = Long.MIN_VALUE

    internal fun update(change: (MeterState) -> MeterState) {
        _state.value = change(_state.value)
    }

    internal fun rest() {
        _state.value = MeterState()
    }

    companion object {
        const val FLOOR = -60f
        const val SPECTRUM_BANDS = 48
        const val SPECTRUM_FLOOR = -90f

        /** The frequency range of the spectrum, in hertz. */
        val SPECTRUM_RANGE: ClosedFloatingPointRange<Double> = 20.0..20_000.0
    }
}

/** What the equaliser screens read, apart from the meters. It changes when the listener changes something. */
data class EqualizerState(
    val settings: SoundSettings,
    /** Built-in presets first, then the listener's own. */
    val presets: List<EqualizerPreset>,
    /** The preset the settings match, null once anything the preset sets was changed by hand. */
    val selectedPresetId: String?,
    /** The presets the listener starred, oldest first. Built-in ones and the listener's own alike. */
    val favoritePresetIds: List<String>,
    /** True while the listener is holding "compare": the sound is as the song was made, the settings untouched. */
    val isComparing: Boolean = false,
    /** True when the headphones in use can steer the spatial stage with the head's movement. */
    val isHeadTrackingAvailable: Boolean = false,
) {
    /** The starred presets in the order of [presets]. */
    val favoritePresets: List<EqualizerPreset>
        get() {
            val starred = favoritePresetIds.toSet()
            return presets.filter { it.id in starred }
        }

    fun isFavorite(presetId: String): Boolean = presetId in favoritePresetIds
}

/**
 * The equaliser and amplifier as the screens see it. Changes apply to the sound at once and are remembered.
 *
 * Observe [state] for the controls and [meter] for the levels. The store lives on one thread, the one [scope] runs on;
 * the meters' pace goes through [scope] and their ballistics through [time], so a test controls both. The response
 * curve is worked out by [math].
 */
class EqualizerStore(
    private val math: SoundMath,
    private val storage: KeyValueStore,
    private val scope: CoroutineScope,
    private val time: Clock = Clock.System,
    sink: SoundProcessing? = null,
) {
    private var sink: SoundProcessing? = sink
    private val _state = MutableStateFlow(load())
    val state: StateFlow<EqualizerState> = _state.asStateFlow()
    val meter = LevelMeter()

    private var meterJob: Job? = null
    private var meterClients = 0

    val settings: SoundSettings get() = _state.value.settings
    val presets: List<EqualizerPreset> get() = _state.value.presets
    val selectedPresetId: String? get() = _state.value.selectedPresetId
    val favoritePresetIds: List<String> get() = _state.value.favoritePresetIds
    val isComparing: Boolean get() = _state.value.isComparing
    val isHeadTrackingAvailable: Boolean get() = _state.value.isHeadTrackingAvailable
    val favoritePresets: List<EqualizerPreset> get() = _state.value.favoritePresets

    /** Decibels the level is lowered when auto headroom is on (see [SoundSettings.headroom]). */
    val headroom: Float get() = settings.headroom(math)

    /** The settings in the audio engine's terms. */
    val profile: SoundProfile get() = settings.profile(math)

    private fun update(change: EqualizerState.() -> EqualizerState) {
        _state.value = _state.value.change()
    }

    private object Keys {
        const val SETTINGS = "sound.settings"
        const val CUSTOM_PRESETS = "sound.customPresets"
        const val SELECTED_PRESET = "sound.selectedPreset"
        const val FAVORITE_PRESETS = "sound.favoritePresets"
        const val SETTINGS_VERSION = "sound.settingsVersion"
    }

    /** What was remembered, brought into line with what this version expects. */
    private fun load(): EqualizerState {
        var loaded = storage.getString(Keys.SETTINGS)?.let { decodeOrNull(SoundSettings.serializer(), it) } ?: SoundSettings()
        if (loaded.gains.size != bands.size) loaded = loaded.copy(gains = SoundSettings.FLAT_GAINS)
        // Version 2: auto headroom is no longer on from the start. Settings from before had it on only because it was
        // the default then, so they are brought in line once.
        if ((storage.getInt(Keys.SETTINGS_VERSION) ?: 0) < 2) {
            loaded = loaded.copy(isAutoHeadroomEnabled = false)
            storage.putString(Keys.SETTINGS, BoomJson.encodeToString(SoundSettings.serializer(), loaded))
            storage.putInt(Keys.SETTINGS_VERSION, 2)
        }
        val custom = storage.getString(Keys.CUSTOM_PRESETS)
            ?.let { decodeOrNull(ListSerializer(EqualizerPreset.serializer()), it) } ?: emptyList()
        val all = builtInPresets + custom.filter { it.gains.size == bands.size }
        val known = all.map { it.id }.toSet()
        val favourites = storage.getString(Keys.FAVORITE_PRESETS)
            ?.let { decodeOrNull(ListSerializer(String.serializer()), it) } ?: emptyList()
        return EqualizerState(
            settings = loaded,
            presets = all,
            selectedPresetId = storage.getString(Keys.SELECTED_PRESET),
            favoritePresetIds = favourites.filter { it in known },
        )
    }

    init {
        this.sink?.apply(settings)
    }

    /** Connects the audio engine once it exists and sends it the remembered profile. */
    fun attach(sink: SoundProcessing) {
        this.sink = sink
        sink.apply(settings)
    }

    // MARK: Changing the sound

    fun setEnabled(isEnabled: Boolean) {
        change { it.copy(isEnabled = isEnabled) }
    }

    /** Sets one band, in decibels. Turns the equaliser on, since moving a slider means wanting to hear it. */
    fun setGain(decibels: Float, band: Int) {
        if (band !in settings.gains.indices) return
        change {
            val gains = it.gains.toMutableList()
            gains[band] = clamp(decibels, GAIN_RANGE)
            it.copy(gains = gains, isEnabled = true)
        }
    }

    fun setPreamp(decibels: Float) {
        change { it.copy(preamp = clamp(decibels, GAIN_RANGE)) }
    }

    fun setBass(decibels: Float) {
        change { it.copy(bass = clamp(decibels, TONE_RANGE)) }
    }

    fun setTreble(decibels: Float) {
        change { it.copy(treble = clamp(decibels, TONE_RANGE)) }
    }

    /**
     * The amplifier: 0 is full volume as recorded, +12 dB is four times the level (held under full scale by the
     * limiter, so music already mastered loud gains less than quiet music does).
     */
    fun setBoost(decibels: Float) {
        change { it.copy(boost = clamp(decibels, BOOST_RANGE)) }
    }

    /** -1 (left only) ... 0 ... +1 (right only). */
    fun setBalance(balance: Float) {
        change { it.copy(balance = clamp(balance, BALANCE_RANGE)) }
    }

    /** 0 (mono) ... 1 (as recorded) ... 2 (wide). */
    fun setWidth(width: Float) {
        change { it.copy(width = clamp(width, WIDTH_RANGE)) }
    }

    /** 0 (off) ... 1: harmonics of the lowest notes, for small speakers and earphones. */
    fun setDeepBass(amount: Float) {
        change { it.copy(deepBass = clamp(amount, AMOUNT_RANGE)) }
    }

    /** 0 (off) ... 1: harmonic content for the top octave. */
    fun setAir(amount: Float) {
        change { it.copy(air = clamp(amount, AMOUNT_RANGE)) }
    }

    /** 0 (off) ... 1: headphone crossfeed. */
    fun setCrossfeed(amount: Float) {
        change { it.copy(crossfeed = clamp(amount, AMOUNT_RANGE)) }
    }

    /** For headphones: hear the two sides as two loudspeakers in front of you. */
    fun setSpatialEnabled(isEnabled: Boolean) {
        change { it.copy(isSpatialEnabled = isEnabled) }
    }

    /** With spatial on: keep the loudspeakers in place while the head turns (needs headphones that report it). */
    fun setHeadTrackingEnabled(isEnabled: Boolean) {
        change { it.copy(isHeadTrackingEnabled = isEnabled) }
    }

    /** Looks again at whether the headphones in use report head movement (they may just have been put in). */
    fun refreshHeadTrackingAvailability() {
        val available = sink?.isHeadTrackingAvailable ?: false
        if (available != isHeadTrackingAvailable) update { copy(isHeadTrackingAvailable = available) }
    }

    fun setLimiterEnabled(isEnabled: Boolean) {
        change { it.copy(isLimiterEnabled = isEnabled) }
    }

    fun setAutoHeadroomEnabled(isEnabled: Boolean) {
        change { it.copy(isAutoHeadroomEnabled = isEnabled) }
    }

    fun setLoudnessNormalizationEnabled(isEnabled: Boolean) {
        change { it.copy(isLoudnessNormalizationEnabled = isEnabled) }
    }

    /**
     * While on, the listener hears the song as it was made; the settings stay as they are and come back when it goes
     * off. Changing any setting ends it.
     */
    fun setComparing(isComparing: Boolean) {
        if (isComparing == this.isComparing) return
        update { copy(isComparing = isComparing) }
        sink?.apply(heard)
    }

    /**
     * Applies a preset and turns the equaliser on. A built-in preset sets the ten bands; one of the listener's own
     * sets everything it was saved with (amplifier, tone, enhancers, stereo).
     */
    fun select(preset: EqualizerPreset) {
        if (preset.gains.size != bands.size) return
        setSelected(preset.id)
        change {
            val banded = it.copy(gains = preset.gains.map { gain -> clamp(gain, GAIN_RANGE) })
            (preset.sound?.applyTo(banded) ?: banded).copy(isEnabled = true)
        }
    }

    /**
     * Saves the whole sound as it stands (bands, amplifier, tone, enhancers, stereo) as a preset of the listener's own
     * and selects it.
     */
    fun saveCurrentAsPreset(name: String): EqualizerPreset {
        val preset = EqualizerPreset(
            id = "custom-${UUID.randomUUID().toString().uppercase()}",
            name = presetName(name),
            gains = settings.gains,
            isBuiltIn = false,
            sound = EqualizerPreset.Sound.of(settings),
        )
        update { copy(presets = presets + preset) }
        saveCustomPresets()
        setSelected(preset.id)
        return preset
    }

    /** Replaces what one of the listener's own presets holds with the sound as it stands now, and selects it. */
    fun overwritePreset(id: String) {
        if (presets.none { it.id == id && !it.isBuiltIn }) return
        val now = settings
        update {
            copy(presets = presets.map {
                if (it.id == id) it.copy(gains = now.gains, sound = EqualizerPreset.Sound.of(now)) else it
            })
        }
        saveCustomPresets()
        setSelected(id)
    }

    // MARK: Favourites

    fun isFavorite(presetId: String): Boolean = _state.value.isFavorite(presetId)

    fun setFavorite(isFavorite: Boolean, presetId: String) {
        if (presets.none { it.id == presetId } || isFavorite == (presetId in favoritePresetIds)) return
        update {
            copy(favoritePresetIds = if (isFavorite) favoritePresetIds + presetId else favoritePresetIds - presetId)
        }
        saveFavourites()
    }

    /** Renames one of the listener's own presets. Built-in ones keep their names. */
    fun renamePreset(id: String, name: String) {
        if (presets.none { it.id == id && !it.isBuiltIn }) return
        update { copy(presets = presets.map { if (it.id == id) it.copy(name = presetName(name)) else it }) }
        saveCustomPresets()
    }

    /** Deletes one of the listener's own presets. Built-in ones stay. */
    fun deletePreset(id: String) {
        if (presets.none { it.id == id && !it.isBuiltIn }) return
        update { copy(presets = presets.filter { it.id != id }) }
        saveCustomPresets()
        // The preset is gone from `presets`, so `setFavorite` could not find it: take it out of the favourites here.
        if (id in favoritePresetIds) {
            update { copy(favoritePresetIds = favoritePresetIds - id) }
            saveFavourites()
        }
        if (selectedPresetId == id) setSelected(null)
    }

    /** Everything back to flat, centred and unamplified. The master switch and loudness normalising stay as they are. */
    fun reset() {
        setSelected(builtInPresets.first().id)
        change {
            SoundSettings(isEnabled = it.isEnabled, isLoudnessNormalizationEnabled = it.isLoudnessNormalizationEnabled)
        }
    }

    /** The one way the settings change: the new settings are kept, shown and sent to the engine. */
    private fun change(change: (SoundSettings) -> SoundSettings) {
        val current = settings
        val next = change(current)
        if (next == current) return
        update { copy(settings = next, isComparing = false) }
        // A preset stays selected only while the settings are still what it sets.
        selectedPresetId?.let { id ->
            val preset = presets.firstOrNull { it.id == id }
            if (preset != null && !preset.matches(next)) setSelected(null)
        }
        storage.putString(Keys.SETTINGS, BoomJson.encodeToString(SoundSettings.serializer(), next))
        sink?.apply(heard)
    }

    private fun setSelected(id: String?) {
        update { copy(selectedPresetId = id) }
        if (id != null) storage.putString(Keys.SELECTED_PRESET, id) else storage.remove(Keys.SELECTED_PRESET)
    }

    /** What the engine is told: the settings, or (while comparing) the same with the master switch off. */
    private val heard: SoundSettings
        get() = if (isComparing) settings.copy(isEnabled = false) else settings

    private fun saveCustomPresets() {
        val custom = presets.filter { !it.isBuiltIn }
        storage.putString(Keys.CUSTOM_PRESETS, BoomJson.encodeToString(ListSerializer(EqualizerPreset.serializer()), custom))
    }

    private fun saveFavourites() {
        storage.putString(
            Keys.FAVORITE_PRESETS, BoomJson.encodeToString(ListSerializer(String.serializer()), favoritePresetIds),
        )
    }

    // MARK: Drawing the curve

    /**
     * The combined gain in decibels at each frequency: what the curve on the equaliser screen draws. Includes the
     * bands, bass and treble; not the preamp, boost or headroom (those shift the whole curve).
     */
    fun response(frequencies: DoubleArray): DoubleArray = settings.response(frequencies, math)

    // MARK: Meters

    /** Call when a view showing the meters or the spectrum appears; pair with [stopMetering]. */
    fun startMetering() {
        meterClients += 1
        refreshHeadTrackingAvailability()
        if (meterJob != null) return
        meterJob = scope.launch {
            var last = time.nowMillis()
            while (isActive) {
                delay(33)
                val now = time.nowMillis()
                readMeters(now, minOf((now - last) / 1000f, 0.25f))
                last = now
            }
        }
    }

    fun stopMetering() {
        meterClients = maxOf(0, meterClients - 1)
        if (meterClients != 0) return
        meterJob?.cancel()
        meterJob = null
        meter.rest()
    }

    /** One reading of the engine's levels, with a meter's manners: up at once, back down steadily. */
    private fun readMeters(now: Long, elapsed: Float) {
        val sink = sink ?: return
        val levels = sink.takeLevels()
        val fall = 26 * elapsed    // decibels a bar falls in this time
        val peakLeft = decibels(levels.peakLeft)
        val peakRight = decibels(levels.peakRight)
        val old = meter.state.value
        var holdLeft = old.holdLeft
        if (peakLeft >= holdLeft) {
            holdLeft = peakLeft
            meter.holdUntilLeft = now + 1500
        } else if (now >= meter.holdUntilLeft) {
            holdLeft = maxOf(peakLeft, holdLeft - fall, LevelMeter.FLOOR)
        }
        var holdRight = old.holdRight
        if (peakRight >= holdRight) {
            holdRight = peakRight
            meter.holdUntilRight = now + 1500
        } else if (now >= meter.holdUntilRight) {
            holdRight = maxOf(peakRight, holdRight - fall, LevelMeter.FLOOR)
        }
        if (maxOf(levels.peakLeft, levels.peakRight) > 1.0001f) meter.clipUntil = now + 1000
        meter.update {
            it.copy(
                left = maxOf(peakLeft, it.left - fall, LevelMeter.FLOOR),
                right = maxOf(peakRight, it.right - fall, LevelMeter.FLOOR),
                rmsLeft = maxOf(decibels(levels.rmsLeft), it.rmsLeft - fall, LevelMeter.FLOOR),
                rmsRight = maxOf(decibels(levels.rmsRight), it.rmsRight - fall, LevelMeter.FLOOR),
                holdLeft = holdLeft,
                holdRight = holdRight,
                gainReduction = maxOf(levels.gainReduction, it.gainReduction - 12 * elapsed, 0f),
                isClipping = now < meter.clipUntil,
            )
        }

        val fresh = sink.spectrum(LevelMeter.SPECTRUM_BANDS)
        if (fresh.size != LevelMeter.SPECTRUM_BANDS) return
        val barFall = 45 * elapsed
        meter.update { reading ->
            reading.copy(
                spectrum = List(LevelMeter.SPECTRUM_BANDS) { index ->
                    maxOf(fresh[index], reading.spectrum[index] - barFall, LevelMeter.SPECTRUM_FLOOR)
                },
            )
        }
    }

    private fun decibels(linear: Float): Float =
        if (linear > 0.001f) maxOf(LevelMeter.FLOOR, 20 * log10(linear)) else LevelMeter.FLOOR

    companion object {
        val bands: List<EqualizerBand> = SoundProfile.bandFrequencies.mapIndexed { index, hertz ->
            val label = if (hertz >= 1000) "${(hertz / 1000).toInt()}K" else "${hertz.toInt()}"
            EqualizerBand(id = index, frequency = hertz, label = label)
        }

        /** Bands and preamp, in decibels. */
        val GAIN_RANGE = -12f..12f

        /** Bass and treble, in decibels. */
        val TONE_RANGE = -12f..12f

        /** The amplifier's boost, in decibels. */
        val BOOST_RANGE = 0f..12f
        val BALANCE_RANGE = -1f..1f
        val WIDTH_RANGE = 0f..2f

        /** Deep bass, air and crossfeed: 0 (off) to 1 (full). */
        val AMOUNT_RANGE = 0f..1f

        /** Where the bass and treble shelves turn, in hertz. */
        const val BASS_FREQUENCY = 100.0
        const val TREBLE_FREQUENCY = 10_000.0

        /** The gain at each frequency for any settings, without a store. */
        fun response(settings: SoundSettings, frequencies: DoubleArray, math: SoundMath): DoubleArray =
            settings.response(frequencies, math)

        private fun presetName(name: String): String {
            val trimmed = name.trim()
            return trimmed.ifEmpty { "My preset" }
        }

        /** Adding zero turns a negative zero into a plain one, so that "no change" is never mistaken for one. */
        private fun clamp(value: Float, range: ClosedFloatingPointRange<Float>): Float =
            minOf(maxOf(value, range.start), range.endInclusive) + 0f

        val builtInPresets: List<EqualizerPreset> = listOf(
            Triple("flat", "Flat", listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
            Triple("bass-boost", "Bass boost", listOf(6f, 5f, 4f, 2.5f, 1f, 0f, 0f, 0f, 0f, 0f)),
            Triple("bass-cut", "Bass reducer", listOf(-6f, -5f, -4f, -2.5f, -1f, 0f, 0f, 0f, 0f, 0f)),
            Triple("treble-boost", "Treble boost", listOf(0f, 0f, 0f, 0f, 0f, 1f, 2.5f, 4f, 5f, 6f)),
            Triple("treble-cut", "Treble reducer", listOf(0f, 0f, 0f, 0f, 0f, -1f, -2.5f, -4f, -5f, -6f)),
            Triple("vocal", "Vocal boost", listOf(-2f, -3f, -3f, 1f, 3.5f, 3.5f, 3f, 1.5f, 0f, -1.5f)),
            Triple("loudness", "Loudness", listOf(6f, 4f, 0f, 0f, -2f, 0f, -1f, -4f, 4.5f, 1.5f)),
            Triple("rock", "Rock", listOf(5f, 4f, 3f, 1.5f, -0.5f, -1f, 0.5f, 2.5f, 3.5f, 4.5f)),
            Triple("pop", "Pop", listOf(-1.5f, -1f, 0f, 2f, 4f, 4f, 2f, 0f, -1f, -1.5f)),
            Triple("hip-hop", "Hip-hop", listOf(5f, 4f, 1.5f, 3f, -1f, -1f, 1.5f, -0.5f, 2f, 3f)),
            Triple("electronic", "Electronic", listOf(4f, 3.5f, 1f, 0f, -2f, 2f, 1f, 1.5f, 4f, 4.5f)),
            Triple("dance", "Dance", listOf(3.5f, 6.5f, 5f, 0f, 2f, 3.5f, 5f, 4.5f, 3.5f, 0f)),
            Triple("rnb", "R&B", listOf(2.5f, 7f, 5.5f, 1.5f, -2f, -1.5f, 2.5f, 2.5f, 3f, 3.5f)),
            Triple("jazz", "Jazz", listOf(4f, 3f, 1.5f, 2f, -1.5f, -1.5f, 0f, 1.5f, 3f, 3.5f)),
            Triple("classical", "Classical", listOf(4.5f, 3.5f, 3f, 2.5f, -1.5f, -1.5f, 0f, 2f, 3f, 3.5f)),
            Triple("acoustic", "Acoustic", listOf(5f, 5f, 4f, 1f, 2f, 2f, 3.5f, 4f, 3.5f, 2f)),
            Triple("piano", "Piano", listOf(3f, 2f, 0f, 2.5f, 3f, 1.5f, 3.5f, 4.5f, 3f, 3.5f)),
            Triple("deep", "Deep", listOf(5f, 3.5f, 1.5f, 1f, 3f, 2.5f, 1.5f, -2f, -3.5f, -4.5f)),
            Triple("late-night", "Late night", listOf(-2f, -1f, 0f, 1.5f, 3f, 3f, 2f, 1f, 0f, -1f)),
            Triple("small-speakers", "Small speakers", listOf(5.5f, 4.5f, 3.5f, 2.5f, 1.5f, 0.5f, -0.5f, -1.5f, -2.5f, -3.5f)),
            Triple("spoken", "Spoken word", listOf(-3.5f, -0.5f, 0f, 0.5f, 3.5f, 4.5f, 5f, 4.5f, 2.5f, 0f)),
        ).map { (id, name, gains) -> EqualizerPreset(id = id, name = name, gains = gains, isBuiltIn = true) }
    }
}
