package io.github.boomboom.logic

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `KotlinSoundMath` against numbers from the C file itself: `BoomDSP.c` was compiled with clang on the Mac and asked
 * (`boom_dsp_response`, `boom_dsp_peak_response` at 48 kHz) for these settings at these frequencies.
 *
 * Two builds gave numbers: one without fused multiply-adds (`-ffp-contract=off`, which is how the JVM computes) and one
 * as the iPhone app is built (clang on arm64 fuses `a*b+c`). This port agrees with the first to 1.7e-4 dB at worst
 * (at 32 Hz; 6e-6 dB or better from 250 Hz up) and with the second as closely. The difference is the arithmetic, not
 * the port: at low frequencies a filter's numerator and denominator are the difference of nearly equal numbers
 * (about 1e-11 of the size of their terms at 20 Hz), so one last-bit difference in `sin`, `cos` or `pow` between two
 * libraries shows in the fourth decimal of a decibel. Nothing a listener or a screen could tell apart, but worth
 * knowing when the native engine's curve is compared with this one.
 */
class KotlinSoundMathTests {
    private val frequencies = doubleArrayOf(20.0, 32.0, 45.0, 100.0, 250.0, 1000.0, 3000.0, 8000.0, 10000.0, 16000.0, 20000.0)

    private class Reference(
        val settings: SoundSettings,
        val peak: Double,
        val response: DoubleArray,
        val fusedPeak: Double,
        val fusedResponse: DoubleArray,
    )

    private fun settings(gains: List<Float>, bass: Float, treble: Float, enabled: Boolean = true) =
        SoundSettings(isEnabled = enabled, gains = gains, bass = bass, treble = treble)

    private val references = mapOf(
        "flat, bass 9, treble 4" to Reference(
            settings(List(10) { 0f }, 9f, 4f),
            peak = 8.982925544485, response = doubleArrayOf(8.982925544485, 8.889646662410, 8.587776804145, 4.499999957347, 0.263213033366, 0.001284240993, 0.018627497800, 0.984060068946, 2.000000056635, 3.846934194106, 3.992611159786),
            fusedPeak = 8.982925649682, fusedResponse = doubleArrayOf(8.982925649682, 8.889646712694, 8.587776857390, 4.499999969917, 0.263213033354, 0.001284241003, 0.018627497800, 0.984060068946, 2.000000056635, 3.846934194106, 3.992611159786),
        ),
        "band 5 at +6" to Reference(
            settings(listOf(0f, 0f, 0f, 0f, 0f, 6f, 0f, 0f, 0f, 0f), 0f, 0f),
            peak = 5.997887433458, response = doubleArrayOf(0.000001975223, 0.000122504847, 0.000552504225, -0.006144988603, -0.001174064126, 5.998073061071, -0.273375864270, 0.000653608241, -0.007739456002, -0.000167448609, 0.001300755608),
            fusedPeak = 5.997887351883, fusedResponse = doubleArrayOf(0.000006366243, 0.000133478110, 0.000561049980, -0.006146106814, -0.001174970993, 5.998072979128, -0.273375874096, 0.000653606271, -0.007739457331, -0.000167449027, 0.001300755426),
        ),
        "ten bands at +6" to Reference(
            settings(List(10) { 6f }, 0f, 0f),
            peak = 6.134034769994, response = doubleArrayOf(2.897107166842, 5.999728922534, 5.883091067279, 5.801358936435, 6.000275048995, 5.999646433424, 5.713932299889, 6.001321684581, 6.113295709009, 5.999523703558, 4.221512140247),
            fusedPeak = 6.134024065824, fusedResponse = doubleArrayOf(2.897091454810, 5.999713864733, 5.883090118080, 5.801362992123, 6.000275889795, 5.999646457896, 5.713932297465, 6.001321683975, 6.113295708664, 5.999523703526, 4.221512140261),
        ),
        "bass boost preset" to Reference(
            settings(listOf(6f, 5f, 4f, 2.5f, 1f, 0f, 0f, 0f, 0f, 0f), 0f, 0f),
            peak = 6.064018622836, response = doubleArrayOf(2.941358086480, 5.999056043314, 5.460786195552, 4.198258140155, 2.500924687874, 0.000302175952, 0.004571471425, -0.000098140617, 0.000040106049, 0.000021812232, 0.000005924038),
            fusedPeak = 6.064000801026, fusedResponse = doubleArrayOf(2.941352539405, 5.999045764002, 5.460774624986, 4.198254230784, 2.500924382618, 0.000302192544, 0.004571486259, -0.000098135146, 0.000040109882, 0.000021813377, 0.000005924511),
        ),
        "rock with shelves" to Reference(
            settings(listOf(5f, 4f, 3f, 1.5f, -0.5f, -1f, 0.5f, 2.5f, 3.5f, 4.5f), -3f, 2.5f),
            peak = 6.904507603904, response = doubleArrayOf(-0.536273412089, 2.031323931872, 1.609526244718, 1.727471023042, 1.424041159018, -1.000717438266, 1.666941233904, 4.111977026136, 5.180173116191, 6.905425478645, 5.759082830361),
            fusedPeak = 6.904507603928, fusedResponse = doubleArrayOf(-0.536292489395, 2.031302591695, 1.609513533502, 1.727470834514, 1.424041279337, -1.000717418786, 1.666941238925, 4.111977026960, 5.180173116640, 6.905425478666, 5.759082830325),
        ),
        "zigzag at the limits" to Reference(
            settings(listOf(12f, -12f, 12f, -12f, 12f, -12f, 12f, -12f, 12f, -12f), 0f, 0f),
            peak = 12.063921923417, response = doubleArrayOf(5.788463822196, 11.999335751387, 1.197142038740, 3.751072709893, -12.000810832795, -12.000923583760, -1.769214407629, 11.998536755803, 2.878703617391, -11.999859377555, -10.297297548812),
            fusedPeak = 12.063940740693, fusedResponse = doubleArrayOf(5.788470109723, 11.999368490857, 1.197175984197, 3.751092842754, -12.000807242595, -12.000926502019, -1.769217409773, 11.998533946725, 2.878701582260, -11.999860836752, -10.297298304507),
        ),
        "switched off" to Reference(
            settings(listOf(5f, 4f, 3f, 1.5f, -0.5f, -1f, 0.5f, 2.5f, 3.5f, 4.5f), 5f, 5f, enabled = false),
            peak = 0.000000000000, response = doubleArrayOf(0.000000000000, 0.000000000000, 0.000000000000, 0.000000000000, 0.000000000000, 0.000000000000, 0.000000000000, 0.000000000000, 0.000000000000, 0.000000000000, 0.000000000000),
            fusedPeak = 0.000000000000, fusedResponse = doubleArrayOf(0.000000000000, 0.000000000000, 0.000000000000, 0.000000000000, 0.000000000000, 0.000000000000, 0.000000000000, 0.000000000000, 0.000000000000, 0.000000000000, 0.000000000000),
        ),
    )

    @Test
    fun theCurveAgreesWithTheCFile() {
        for ((name, reference) in references) {
            val response = KotlinSoundMath.response(reference.settings, frequencies)
            for (index in frequencies.indices) {
                val tolerance = if (frequencies[index] < 250) 5e-4 else 2e-5
                assertTrue(
                    abs(response[index] - reference.response[index]) < tolerance,
                    "$name at ${frequencies[index]} Hz: ${response[index]}, the C file says ${reference.response[index]}",
                )
                assertTrue(
                    abs(response[index] - reference.fusedResponse[index]) < tolerance,
                    "$name at ${frequencies[index]} Hz: ${response[index]}, the fused build says ${reference.fusedResponse[index]}",
                )
            }
        }
    }

    @Test
    fun thePeakAgreesWithTheCFile() {
        for ((name, reference) in references) {
            val peak = KotlinSoundMath.peakResponse(reference.settings)
            assertTrue(abs(peak - reference.peak) < 5e-4, "$name: $peak, the C file says ${reference.peak}")
            assertTrue(abs(peak - reference.fusedPeak) < 5e-4, "$name: $peak, the fused build says ${reference.fusedPeak}")
        }
    }
    @Test
    fun theBandCentresAreTheCFilesAndEveryBandReachesItsGainThereWhateverItsNeighboursAre() {
        assertEquals(listOf(32.0, 64.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0), SoundProfile.bandFrequencies)
        val wanted = listOf(12f, -12f, 12f, -12f, 12f, -12f, 12f, -12f, 12f, -12f)
        val response = KotlinSoundMath.response(
            SoundSettings(isEnabled = true, gains = wanted), SoundProfile.bandFrequencies.toDoubleArray(),
        )
        for (index in wanted.indices) assertTrue(abs(response[index] - wanted[index]) < 0.01, "band $index: ${response[index]}")
    }

    @Test
    fun nothingToDrawGivesNothing() {
        assertEquals(0, KotlinSoundMath.response(SoundSettings(isEnabled = true), DoubleArray(0)).size)
        assertEquals(0.0, KotlinSoundMath.peakResponse(SoundSettings(isEnabled = true)))
        // Cutting only: the curve never rises, so the peak is the floor of 0.
        assertEquals(0.0, KotlinSoundMath.peakResponse(SoundSettings(isEnabled = true, gains = List(10) { -6f })))
    }
}
