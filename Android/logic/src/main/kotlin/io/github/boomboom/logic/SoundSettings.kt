package io.github.boomboom.logic

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.ceil

/**
 * How the stores write and read their JSON. Reading is tolerant: a value saved by an older version lacks the newer
 * fields (they take their defaults), a newer version's extra fields are ignored, and a null where a value is
 * expected also means the default. Writing keeps the defaults, so a saved value says everything.
 */
internal val BoomJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    encodeDefaults = true
}

/** Reads a saved value, or null when it is missing or unreadable (the iPhone's `try?` on a `JSONDecoder`). */
internal fun <T> decodeOrNull(serializer: KSerializer<T>, text: String): T? =
    try {
        BoomJson.decodeFromString(serializer, text)
    } catch (failure: IllegalArgumentException) {
        // `SerializationException` is one.
        null
    }

/**
 * What a sound profile's response curve and peak are worked out with. The iPhone app asks a C function
 * (`boom_dsp_response`); the Android engine will answer from the same C file, natively. [KotlinSoundMath] is the same
 * arithmetic in Kotlin, for tests and previews.
 *
 * Implementations read only [SoundSettings.isEnabled], [SoundSettings.gains], [SoundSettings.bass] and
 * [SoundSettings.treble]: the curve is what the ten bands and the two shelves make, at [SAMPLE_RATE].
 */
interface SoundMath {
    /** The gain in decibels at each frequency, exactly as the audio gets it. All zeros with the switch off. */
    fun response(settings: SoundSettings, frequencies: DoubleArray): DoubleArray

    /** The largest gain of that curve between 20 Hz and 20 kHz, in decibels; never below 0. */
    fun peakResponse(settings: SoundSettings): Double

    companion object {
        /** Everything in the engine runs at 48 kHz. */
        const val SAMPLE_RATE = 48_000.0
    }
}

/** The whole sound profile: what the audio engine is told to do. */
@Serializable
data class SoundSettings(
    /** The master switch. Off: the audio passes untouched (bit for bit). */
    val isEnabled: Boolean = false,
    /** Decibels per band, -12...+12: the response at each band's centre, whatever its neighbours are set to. */
    val gains: List<Float> = FLAT_GAINS,
    /** Decibels applied to the whole signal, -12...+12. */
    val preamp: Float = 0f,
    /** A low shelf at 100 Hz, -12...+12 dB. */
    val bass: Float = 0f,
    /** A high shelf at 10 kHz, -12...+12 dB. */
    val treble: Float = 0f,
    /** The amplifier: extra loudness beyond full volume, 0...+12 dB, into the limiter. */
    val boost: Float = 0f,
    /** Holds peaks just under full scale so boosts never clip. Works whenever the master switch is on. */
    val isLimiterEnabled: Boolean = true,
    /**
     * Lowers the level by the curve's largest boost, so the bands alone never push into the limiter. Off unless the
     * listener asks for it: with it on, lifting the bass turns everything else down instead, and the limiter holds
     * the rare peak that a boost pushes too far anyway.
     */
    val isAutoHeadroomEnabled: Boolean = false,
    /**
     * Turns louder songs down to a common level, using the loudness YouTube Music reports for each song.
     * Independent of the master switch.
     */
    val isLoudnessNormalizationEnabled: Boolean = false,
    /** -1 (left only) ... 0 ... +1 (right only). */
    val balance: Float = 0f,
    /** 0 (mono) ... 1 (as recorded) ... 2 (wide). */
    val width: Float = 1f,
    /** "Deep bass", 0 (off) ... 1: adds harmonics of the lowest notes, so small speakers and earphones carry them. */
    val deepBass: Float = 0f,
    /** "Air", 0 (off) ... 1: adds a little harmonic content to the top octave. */
    val air: Float = 0f,
    /** Headphone crossfeed, 0 (off) ... 1: a little of each side reaches the other ear, as in front of loudspeakers. */
    val crossfeed: Float = 0f,
    /**
     * Spatial, for headphones: the two sides are heard as two loudspeakers standing in front of the listener, each
     * reaching both ears the way it would in a room (from measurements on a dummy head).
     */
    val isSpatialEnabled: Boolean = false,
    /**
     * With spatial on and headphones that report head movement: the loudspeakers stay where they are while the
     * head turns.
     */
    val isHeadTrackingEnabled: Boolean = true,
) {
    /** True when the profile changes nothing, so the engine passes the audio straight through. */
    val isTransparent: Boolean
        get() = !isEnabled || (gains.all { it == 0f } && preamp == 0f && bass == 0f && treble == 0f && boost == 0f &&
            balance == 0f && width == 1f && deepBass == 0f && air == 0f && crossfeed == 0f && !isSpatialEnabled)

    /** True when the head's movement should steer the spatial stage. */
    val wantsHeadTracking: Boolean get() = isEnabled && isSpatialEnabled && isHeadTrackingEnabled

    /**
     * Decibels the level is lowered when auto headroom is on: the highest point of the curve the bands, bass and
     * treble make together (to a tenth of a decibel, rounded up).
     */
    fun headroom(math: SoundMath): Float {
        if (!isEnabled || !isAutoHeadroomEnabled) return 0f
        val peak = math.peakResponse(this)
        return if (peak < 0.05) 0f else (ceil(peak * 10) / 10).toFloat()
    }

    /** The settings in the audio engine's terms. */
    fun profile(math: SoundMath): SoundProfile {
        val bands = FloatArray(SoundProfile.BAND_COUNT)
        for (index in 0 until minOf(gains.size, bands.size)) bands[index] = gains[index]
        return SoundProfile(
            isEnabled = isEnabled,
            bandGains = bands.toList(),
            preamp = preamp,
            headroom = headroom(math),
            bassGain = bass,
            trebleGain = treble,
            boost = boost,
            isLimiterEnabled = isLimiterEnabled,
            balance = balance,
            width = width,
            deepBass = deepBass,
            air = air,
            crossfeed = crossfeed,
            isSpatial = isSpatialEnabled,
        )
    }

    /** The gain in decibels at each frequency: the bands, bass and treble together, exactly as the audio gets them. */
    fun response(frequencies: DoubleArray, math: SoundMath): DoubleArray = math.response(this, frequencies)

    companion object {
        internal val FLAT_GAINS: List<Float> = List(SoundProfile.BAND_COUNT) { 0f }

        /** What a fresh install starts from. */
        val DEFAULT = SoundSettings()
    }
}

/** What the equaliser and amplifier are set to, in the audio engine's terms (the iPhone engine's `SoundProfile`). */
data class SoundProfile(
    /** Off: the audio passes untouched. */
    val isEnabled: Boolean = false,
    /** Decibels per band, in the order of [bandFrequencies]: the response wanted at each band's centre. */
    val bandGains: List<Float> = SoundSettings.FLAT_GAINS,
    /** Decibels applied to the whole signal. */
    val preamp: Float = 0f,
    /** Decibels the level is lowered to leave the bands room. */
    val headroom: Float = 0f,
    /** A low shelf at 100 Hz, in decibels. */
    val bassGain: Float = 0f,
    /** A high shelf at 10 kHz, in decibels. */
    val trebleGain: Float = 0f,
    /** The amplifier: decibels added ahead of the limiter. */
    val boost: Float = 0f,
    /** Hold peaks just under full scale. */
    val isLimiterEnabled: Boolean = true,
    /** -1 (left only) ... 0 ... +1 (right only). */
    val balance: Float = 0f,
    /** 0 (mono) ... 1 (as recorded) ... 2 (wide). */
    val width: Float = 1f,
    /** 0 (off) ... 1: adds harmonics of the lowest notes. */
    val deepBass: Float = 0f,
    /** 0 (off) ... 1: adds a little harmonic content to the top octave. */
    val air: Float = 0f,
    /** 0 (off) ... 1: for headphones, feeds a little of each side to the other. */
    val crossfeed: Float = 0f,
    /** For headphones: the two sides are heard as two loudspeakers standing in front of the listener. */
    val isSpatial: Boolean = false,
) {
    /** True when nothing would change the audio. */
    val isTransparent: Boolean
        get() = !isEnabled || (bandGains.all { it == 0f } && preamp == 0f && headroom == 0f && bassGain == 0f &&
            trebleGain == 0f && boost == 0f && balance == 0f && width == 1f && deepBass == 0f && air == 0f &&
            crossfeed == 0f && !isSpatial)

    companion object {
        const val BAND_COUNT = 10

        /**
         * The bands' centre frequencies in hertz. The iPhone app asks the C file (`kBandFrequency` in `BoomDSP.c`);
         * these are its values, and the native engine's tests are to check that they still are.
         */
        val bandFrequencies: List<Double> =
            listOf(32.0, 64.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)
    }
}

/** How loud the output is, as the meters show it. */
data class SoundLevels(
    /** The largest sample per channel since the last reading (1 = full scale). */
    val peakLeft: Float = 0f,
    val peakRight: Float = 0f,
    /** The level of the last moment per channel (root mean square). */
    val rmsLeft: Float = 0f,
    val rmsRight: Float = 0f,
    /** The most the limiter turned the level down since the last reading, in decibels (0: it did nothing). */
    val gainReduction: Float = 0f,
)

/** What the equaliser screen drives. The audio engine implements it. */
interface SoundProcessing {
    fun apply(settings: SoundSettings)

    /** The output levels since the last call, after the limiter. */
    fun takeLevels(): SoundLevels

    /**
     * The output's spectrum over the last moment: [bands] values, evenly spaced on a logarithmic scale from 20 Hz to
     * 20 kHz, in decibels where 0 is a full-scale sine.
     */
    fun spectrum(bands: Int): FloatArray

    /** True when the headphones in use report head movement. */
    val isHeadTrackingAvailable: Boolean
}

/** One slider of the graphic equaliser. */
data class EqualizerBand(
    val id: Int,
    /** Centre frequency in hertz. */
    val frequency: Double,
    /** "32", "1K", "16K". */
    val label: String,
)

@Serializable
data class EqualizerPreset(
    val id: String,
    val name: String,
    /** Decibels per band, in the order of [EqualizerStore.bands]. */
    val gains: List<Float>,
    val isBuiltIn: Boolean,
    /**
     * The rest of the sound as it was when the listener saved the preset: amplifier, tone, enhancers and stereo.
     * Built-in presets are band curves only and have none: choosing one leaves all of that as it is.
     */
    val sound: Sound? = null,
) {
    /**
     * Everything in the sound profile besides the ten bands, the master switch and loudness normalising (which is
     * about the songs, not about a sound). A preset saved by an earlier version lacks the newer values: those take
     * their defaults.
     */
    @Serializable
    data class Sound(
        val preamp: Float = SoundSettings.DEFAULT.preamp,
        val bass: Float = SoundSettings.DEFAULT.bass,
        val treble: Float = SoundSettings.DEFAULT.treble,
        val boost: Float = SoundSettings.DEFAULT.boost,
        val isLimiterEnabled: Boolean = SoundSettings.DEFAULT.isLimiterEnabled,
        val isAutoHeadroomEnabled: Boolean = SoundSettings.DEFAULT.isAutoHeadroomEnabled,
        val deepBass: Float = SoundSettings.DEFAULT.deepBass,
        val air: Float = SoundSettings.DEFAULT.air,
        val balance: Float = SoundSettings.DEFAULT.balance,
        val width: Float = SoundSettings.DEFAULT.width,
        val crossfeed: Float = SoundSettings.DEFAULT.crossfeed,
        val isSpatialEnabled: Boolean = SoundSettings.DEFAULT.isSpatialEnabled,
    ) {
        internal fun applyTo(settings: SoundSettings): SoundSettings = settings.copy(
            preamp = preamp,
            bass = bass,
            treble = treble,
            boost = boost,
            isLimiterEnabled = isLimiterEnabled,
            isAutoHeadroomEnabled = isAutoHeadroomEnabled,
            deepBass = deepBass,
            air = air,
            balance = balance,
            width = width,
            crossfeed = crossfeed,
            isSpatialEnabled = isSpatialEnabled,
        )

        companion object {
            fun of(settings: SoundSettings) = Sound(
                preamp = settings.preamp,
                bass = settings.bass,
                treble = settings.treble,
                boost = settings.boost,
                isLimiterEnabled = settings.isLimiterEnabled,
                isAutoHeadroomEnabled = settings.isAutoHeadroomEnabled,
                deepBass = settings.deepBass,
                air = settings.air,
                balance = settings.balance,
                width = settings.width,
                crossfeed = settings.crossfeed,
                isSpatialEnabled = settings.isSpatialEnabled,
            )
        }
    }

    /**
     * True when the settings are what this preset would set: its bands, and for a preset that carries the whole
     * sound, the rest as well.
     */
    fun matches(settings: SoundSettings): Boolean {
        // Element by element with `==` on Float, so that -0 and 0 are the same, as they are on the iPhone.
        if (settings.gains.size != gains.size || settings.gains.indices.any { settings.gains[it] != gains[it] }) {
            return false
        }
        val sound = sound ?: return true
        return sound == Sound.of(settings)
    }
}
