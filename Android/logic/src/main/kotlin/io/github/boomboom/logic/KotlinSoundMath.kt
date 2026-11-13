package io.github.boomboom.logic

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt

/**
 * The equaliser's response curve in Kotlin: a faithful port of the few functions of `BoomDSP.c` that draw it
 * (`design_peaking`, `design_shelf`, `gain_at`, `solve_bands`, `boom_dsp_response`, `boom_dsp_peak_response`), so a
 * preview or a test shows the same curve as the audio gets. The engine answers [SoundMath] from the C file itself;
 * its tests are to hold the two together (this class's tests hold it to numbers taken from the C file).
 */
object KotlinSoundMath : SoundMath {
    private const val BANDS = SoundProfile.BAND_COUNT
    private const val FILTERS = BANDS + 2   // the ten bands, then the bass shelf, then the treble shelf
    private const val BASS = BANDS
    private const val TREBLE = BANDS + 1

    /** How wide each band is, in octaves. At 1.4, ten bands set alike give a level response. */
    private const val BAND_OCTAVES = 1.4
    private const val BASS_FREQUENCY = 100.0
    private const val TREBLE_FREQUENCY = 10_000.0
    private val bandFrequency = SoundProfile.bandFrequencies.toDoubleArray()

    /** One filter's coefficients, normalised so that a0 is 1. */
    private class Coef(var b0: Double = 0.0, var b1: Double = 0.0, var b2: Double = 0.0, var a1: Double = 0.0, var a2: Double = 0.0)

    private fun designPeaking(frequency: Double, octaves: Double, decibels: Double, sampleRate: Double): Coef {
        val amplitude = 10.0.pow(decibels / 40)
        val omega = 2 * Math.PI * frequency / sampleRate
        val cosine = cos(omega)
        // The width is corrected for how a digital filter squeezes frequencies together toward the top of its range,
        // so the 16 kHz band is as wide as the others.
        val alpha = sin(omega) * sinh(0.34657359027997264 * octaves * omega / sin(omega))
        val a0 = 1 + alpha / amplitude
        return Coef(
            b0 = (1 + alpha * amplitude) / a0,
            b1 = (-2 * cosine) / a0,
            b2 = (1 - alpha * amplitude) / a0,
            a1 = (-2 * cosine) / a0,
            a2 = (1 - alpha / amplitude) / a0,
        )
    }

    private fun designShelf(high: Boolean, frequency: Double, decibels: Double, sampleRate: Double): Coef {
        val amplitude = 10.0.pow(decibels / 40)
        val omega = 2 * Math.PI * frequency / sampleRate
        val cosine = cos(omega)
        val root = 2 * sqrt(amplitude) * (sin(omega) / 2 * 1.4142135623730951)
        val sign = if (high) -1.0 else 1.0   // the two shelves mirror each other
        val a0 = (amplitude + 1) + sign * (amplitude - 1) * cosine + root
        return Coef(
            b0 = amplitude * ((amplitude + 1) - sign * (amplitude - 1) * cosine + root) / a0,
            b1 = sign * 2 * amplitude * ((amplitude - 1) - sign * (amplitude + 1) * cosine) / a0,
            b2 = amplitude * ((amplitude + 1) - sign * (amplitude - 1) * cosine - root) / a0,
            a1 = -sign * 2 * ((amplitude - 1) + sign * (amplitude + 1) * cosine) / a0,
            a2 = ((amplitude + 1) + sign * (amplitude - 1) * cosine - root) / a0,
        )
    }

    private fun designFilter(index: Int, decibels: Double, sampleRate: Double): Coef =
        if (index < BANDS) {
            designPeaking(bandFrequency[index], BAND_OCTAVES, decibels, sampleRate)
        } else {
            designShelf(index == TREBLE, if (index == BASS) BASS_FREQUENCY else TREBLE_FREQUENCY, decibels, sampleRate)
        }

    /** A filter's gain in decibels at a frequency. */
    private fun gainAt(c: Coef, frequency: Double, sampleRate: Double): Double {
        val limit = sampleRate / 2 - 1
        val omega = 2 * Math.PI * (if (frequency < limit) frequency else limit) / sampleRate
        val cos1 = cos(omega)
        val cos2 = cos(2 * omega)
        val numerator = c.b0 * c.b0 + c.b1 * c.b1 + c.b2 * c.b2 +
            2 * (c.b0 * c.b1 + c.b1 * c.b2) * cos1 + 2 * c.b0 * c.b2 * cos2
        val denominator = 1 + c.a1 * c.a1 + c.a2 * c.a2 +
            2 * (c.a1 + c.a1 * c.a2) * cos1 + 2 * c.a2 * cos2
        if (numerator <= 0 || denominator <= 0) return 0.0
        return 10 * log10(numerator / denominator)
    }

    /**
     * Works out each band filter's own gain so that the ten together give exactly `wanted[i]` at band i's centre.
     * A band's filter also lifts its neighbours a little; left alone, ten sliders at +6 would give nearly +9.
     */
    private fun solveBands(wanted: FloatArray, sampleRate: Double, filterDb: DoubleArray) {
        var flat = true
        for (i in 0 until BANDS) {
            filterDb[i] = wanted[i].toDouble()
            if (wanted[i] != 0f) flat = false
        }
        if (flat) return
        for (pass in 0 until 48) {
            val coefs = Array(BANDS) { j -> designPeaking(bandFrequency[j], BAND_OCTAVES, filterDb[j], sampleRate) }
            var worst = 0.0
            val next = DoubleArray(BANDS)
            for (i in 0 until BANDS) {
                var response = 0.0
                for (j in 0 until BANDS) response += gainAt(coefs[j], bandFrequency[i], sampleRate)
                val error = wanted[i] - response
                if (abs(error) > worst) worst = abs(error)
                val value = filterDb[i] + 0.7 * error
                next[i] = if (value > 36) 36.0 else if (value < -36) -36.0 else value
            }
            if (worst < 0.002) break
            next.copyInto(filterDb)
        }
    }

    /** The twelve filter gains a set of settings comes to. All zero with the master switch off. */
    private fun filterGains(settings: SoundSettings, sampleRate: Double): DoubleArray {
        val filterDb = DoubleArray(FILTERS)
        if (!settings.isEnabled) return filterDb
        val wanted = FloatArray(BANDS)
        for (index in 0 until minOf(settings.gains.size, BANDS)) wanted[index] = settings.gains[index]
        solveBands(wanted, sampleRate, filterDb)
        filterDb[BASS] = settings.bass.toDouble()
        filterDb[TREBLE] = settings.treble.toDouble()
        return filterDb
    }

    override fun response(settings: SoundSettings, frequencies: DoubleArray): DoubleArray {
        val sampleRate = SoundMath.SAMPLE_RATE
        val filterDb = filterGains(settings, sampleRate)
        val used = BooleanArray(FILTERS) { filterDb[it] != 0.0 }
        val coefs = Array(FILTERS) { j -> if (used[j]) designFilter(j, filterDb[j], sampleRate) else null }
        return DoubleArray(frequencies.size) { i ->
            var total = 0.0
            for (j in 0 until FILTERS) coefs[j]?.let { total += gainAt(it, frequencies[i], sampleRate) }
            total
        }
    }

    override fun peakResponse(settings: SoundSettings): Double {
        val points = 121   // 20 Hz to 20 kHz, twelve to the octave
        val frequencies = DoubleArray(points) { 20 * 1000.0.pow(it.toDouble() / (points - 1)) }
        val decibels = response(settings, frequencies)
        var peak = 0.0
        for (value in decibels) if (value > peak) peak = value
        return peak
    }
}
