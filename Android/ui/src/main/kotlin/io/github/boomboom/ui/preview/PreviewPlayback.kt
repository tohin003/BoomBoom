package io.github.boomboom.ui.preview

import io.github.boomboom.logic.KotlinSoundMath
import io.github.boomboom.logic.LevelMeter
import io.github.boomboom.logic.PlaybackEngine
import io.github.boomboom.logic.SoundLevels
import io.github.boomboom.logic.SoundProcessing
import io.github.boomboom.logic.SoundSettings
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * A stand-in for the audio engine's sound processor, for previews and screenshots: it hears nothing, but answers
 * with levels and a spectrum shaped like music's, moved by the settings the way the real processor moves them (the
 * boost and preamp raise the level, the limiter holds it under full scale and reports how far it turned it down,
 * without the limiter loud passages go past full scale, the bands shape the spectrum). Everything follows a counter,
 * not a clock or chance, so the same steps draw the same pictures.
 *
 * @param musicPeak The song's own peaks before any processing, 1 = full scale (0.7 is a typical loud master).
 */
class PreviewSoundSink(
    var musicPeak: Float = 0.7f,
    override var isHeadTrackingAvailable: Boolean = false,
) : SoundProcessing {
    private var settings = SoundSettings()
    private var reading = 0

    override fun apply(settings: SoundSettings) {
        this.settings = settings
    }

    /** Decibels the processing adds before the limiter. */
    private val gain: Float
        get() {
            val sound = settings
            if (!sound.isEnabled) return 0f
            val bands = sound.gains.maxOrNull()?.coerceAtLeast(0f) ?: 0f
            return sound.boost + sound.preamp + bands * 0.5f - sound.headroom(KotlinSoundMath)
        }

    override fun takeLevels(): SoundLevels {
        reading += 1
        // A beat: louder every eighth reading, with a slower swell over it.
        val beat = if (reading % 8 < 2) 1f else 0.78f
        val swell = 0.9f + 0.1f * sin(reading * 0.21f)
        val raw = musicPeak * beat * swell * 10f.pow(gain / 20f)
        val ceiling = 0.966f    // −0.3 dBFS
        val isLimiting = settings.isEnabled && settings.isLimiterEnabled
        val out = if (isLimiting) min(raw, ceiling) else raw
        val reduction = if (isLimiting && raw > ceiling) 20 * log10(raw / ceiling) else 0f
        val rms = out * 0.36f
        return SoundLevels(
            peakLeft = out, peakRight = out * 0.94f, rmsLeft = rms, rmsRight = rms * 0.95f, gainReduction = reduction,
        )
    }

    override fun spectrum(bands: Int): FloatArray {
        val low = LevelMeter.SPECTRUM_RANGE.start
        val high = LevelMeter.SPECTRUM_RANGE.endInclusive
        val frequencies = DoubleArray(bands) { low * (high / low).pow((it + 0.5) / bands) }
        val shaping = if (settings.isEnabled) KotlinSoundMath.response(settings, frequencies) else DoubleArray(bands)
        val level = 20 * log10(max(musicPeak, 0.001f)) + gain
        return FloatArray(bands) { index ->
            val frequency = frequencies[index]
            // Music's spectrum falls about 4.5 dB an octave above 150 Hz, and a little below 60 Hz.
            val octavesAbove = log10(frequency / 150) / log10(2.0)
            val tilt = if (octavesAbove > 0) -4.5 * octavesAbove else -1.5 * (-octavesAbove).pow(1.6)
            val ripple = 3 * sin(index * 1.7 + reading * 0.35)
            (-14 + level + tilt + ripple + shaping[index]).toFloat().coerceIn(LevelMeter.SPECTRUM_FLOOR, 0f)
        }
    }
}

/**
 * Another engine (the preview's simulated one) that also says which audio format it plays, as the real engine
 * measures it from the stream: for previews of the player's format line.
 */
class FormatReportingEngine(
    private val inner: PlaybackEngine,
    override val formatDescription: String? = "Opus · 141 kbps · 48 kHz",
    override val outputDescription: String? = "Headphones · 48 kHz · not resampled",
) : PlaybackEngine by inner
