package io.github.boomboom.engine

/**
 * What the sound should be: `BoomSoundParams` in `Packages/BoomEngine/Sources/CBoomAudio/include/BoomDSP.h`, field for
 * field. The defaults are the iPhone app's: off, and everything at its neutral place.
 */
data class SoundParams(
    /** The master switch. Off: nothing below applies. */
    val enabled: Boolean = false,
    /** Decibels per band: the response wanted at each band's centre ([Sound.bandFrequencies]). Ten values. */
    val bandGainDb: List<Float> = List(BANDS) { 0f },
    /** Decibels applied to the whole signal. */
    val preampDb: Float = 0f,
    /** Decibels (0 or more) the level is lowered to leave the bands room. */
    val headroomDb: Float = 0f,
    /** A low shelf at 100 Hz, in decibels. */
    val bassDb: Float = 0f,
    /** A high shelf at 10 kHz, in decibels. */
    val trebleDb: Float = 0f,
    /** The amplifier: decibels added ahead of the limiter. */
    val boostDb: Float = 0f,
    /** Hold peaks under the ceiling (0.3 dB below full scale). */
    val limiter: Boolean = true,
    /** -1 (left only) .. 0 .. +1 (right only). */
    val balance: Float = 0f,
    /** 0 (mono) .. 1 (as recorded) .. 2 (wide). */
    val width: Float = 1f,
    /** 0 (off) .. 1: harmonics of the lowest notes, for small loudspeakers and earphones. */
    val deepBass: Float = 0f,
    /** 0 (off) .. 1: a little harmonic content in the top octave. */
    val air: Float = 0f,
    /** 0 (off) .. 1: for headphones, a little of each side fed to the other. */
    val crossfeed: Float = 0f,
    /** 0 (off) or 1 (on): for headphones, the two sides heard as two loudspeakers in front. */
    val spatial: Float = 0f,
) {
    init {
        require(bandGainDb.size == BANDS) { "there are $BANDS bands" }
    }

    /** The flat form the native side reads (see `core/API.md`). */
    internal fun toArray(): FloatArray = floatArrayOf(
        if (enabled) 1f else 0f, *bandGainDb.toFloatArray(),
        preampDb, headroomDb, bassDb, trebleDb, boostDb, if (limiter) 1f else 0f,
        balance, width, deepBass, air, crossfeed, spatial,
    )

    companion object {
        const val BANDS = 10
    }
}

/** How loud the output is, as the meters show it. */
data class SoundLevels(
    /** The largest sample per channel since the last reading (1 = full scale). */
    val peakLeft: Float,
    val peakRight: Float,
    /** The level of the last moment (about 20 ms) per channel, root mean square. */
    val rmsLeft: Float,
    val rmsRight: Float,
    /** The most the limiter turned the level down since the last reading, in decibels. */
    val gainReductionDb: Float,
)

/** The sound processor's arithmetic, without an engine: the curves the equaliser screen draws. */
object Sound {
    /** The pipeline's rate, which the processor runs at. */
    const val SAMPLE_RATE = 48_000.0

    /** The bands' centre frequencies in hertz. */
    val bandFrequencies: List<Double> by lazy { BoomCore.soundBandFrequencies().toList() }

    /** The gain in decibels the bands, bass and treble of [params] give at each frequency. */
    fun response(params: SoundParams, frequencies: DoubleArray, sampleRate: Double = SAMPLE_RATE): DoubleArray =
        BoomCore.soundResponse(params.toArray(), frequencies, sampleRate)

    /** The largest gain of that curve between 20 Hz and 20 kHz, in decibels; never below 0. */
    fun peakResponse(params: SoundParams, sampleRate: Double = SAMPLE_RATE): Double =
        BoomCore.soundPeakResponse(params.toArray(), sampleRate)
}
