package io.github.boomboom.engine

import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The engine on a real Android audio stack, through its Kotlin face: the iPhone engine's Opus test tone (440 Hz left,
 * 660 Hz right, one eighth of full scale) is fed through a [Demuxer] into a [Track] in pieces, played through AAudio
 * with the sound processor on, paused, and jumped in. Every number is written to the log under "BoomEngineTest".
 *
 *   ./gradlew connectedDebugAndroidTest    (or install both APKs and `adb shell am instrument -w ...`)
 */
@RunWith(AndroidJUnit4::class)
class EngineOnDeviceTest {
    private val report = Bundle()

    private fun note(key: String, value: Any) {
        Log.i("BoomEngineTest", "$key: $value")
        report.putString(key, value.toString())
    }

    private fun waitFor(seconds: Double, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + (seconds * 1e9).toLong()
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    @Test
    fun playsTheTestToneThroughAAudio() {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("sine-opus.webm").use { it.readBytes() }
        val track = Track()
        Demuxer.forMimeType("audio/webm; codecs=\"opus\"")!!.use { demuxer ->
            var offset = 0
            var packets = 0
            while (offset < bytes.size) {
                val length = minOf(16_384, bytes.size - offset)
                packets += demuxer.feed(bytes, track, offset, length)
                offset += length
            }
            track.seal()
            note("fed", "${bytes.size} bytes in pieces of 16 KB, $packets packets")
            assertEquals(601, packets)
        }
        val info = track.info!!
        note("track", info)
        assertTrue(track.isComplete)
        assertEquals(TrackInfo.Codec.OPUS, info.codec)

        val events = mutableListOf<PipelineEvent>()
        fun collect() = events.addAll(Engine.pollEvents(0))

        // The sound processor on, with a tone control that leaves 440 and 660 Hz where they are (a shelf at 10 kHz),
        // and the limiter: the processor is in the path, and the level is still the tone's own.
        Engine.applySound(SoundParams(enabled = true, trebleDb = 3f, limiter = true))
        assertTrue("the output did not start", Engine.startOutput())
        val before = Engine.outputInfo
        note("output", before)
        Engine.takeLevels()

        Engine.load(track, item = 1L, seconds = 0.0, autoplay = true)
        assertTrue("never started playing", waitFor(3.0) { collect(); events.contains(PipelineEvent.Playing(1L)) })
        val startedAt = System.nanoTime()
        Thread.sleep(3_000)
        val played = Engine.position
        val elapsed = (System.nanoTime() - startedAt) / 1e9
        val levels = Engine.takeLevels()
        val during = Engine.outputInfo
        note("after 3 s", "position $played s over $elapsed s of wall time")
        note("levels", levels)
        note("output while playing", during)
        val spectrum = Engine.spectrum(48)
        note("spectrum at 440 / 660 Hz", "${spectrum[band(440.0)]} / ${spectrum[band(660.0)]} dB")
        assertTrue("the position did not advance: $played", played > 2.0 && played < 4.0)
        assertTrue(during.frames > before.frames + 48_000)
        assertTrue(during.soundingFrames > before.soundingFrames + 48_000)
        assertTrue("left peak ${levels.peakLeft}", abs(levels.peakLeft - 0.125f) < 0.01f)
        assertTrue("right peak ${levels.peakRight}", abs(levels.peakRight - 0.125f) < 0.01f)
        assertTrue("left rms ${levels.rmsLeft}", abs(levels.rmsLeft - 0.125f / sqrt(2f)) < 0.01f)

        // Pause holds the place. The renderer fades out over the output's next callback and then takes nothing more:
        // wait for that callback and one after it (the emulator's callbacks come at uneven times).
        Engine.pause()
        val pausedAt = Engine.outputInfo.callbacks
        assertTrue("the output stopped calling", waitFor(3.0) { Engine.outputInfo.callbacks >= pausedAt + 2 })
        val held = Engine.position
        val heldSounding = Engine.outputInfo.soundingFrames
        Thread.sleep(1_000)
        val stillHeld = Engine.position
        val afterHold = Engine.outputInfo
        note("pause", "position $held s, then $stillHeld s a second later; sounding frames ${afterHold.soundingFrames - heldSounding} in that second, frames pulled ${afterHold.frames - during.frames} since")
        assertEquals(held, stillHeld, 0.0)
        assertEquals(heldSounding, afterHold.soundingFrames)

        // A jump while paused lands at once, then plays from there.
        Engine.seek(8.0)
        val reported = Engine.position
        Engine.play()
        val resumed = waitFor(3.0) { collect(); events.count { it == PipelineEvent.Playing(1L) } >= 2 }
        Thread.sleep(500)
        val afterJump = Engine.position
        note("seek", "asked 8.0 s, reported at once $reported s, 0.5 s after playing again $afterJump s")
        assertEquals(8.0, reported, 0.0)
        assertTrue(resumed)
        assertTrue("landed at $afterJump", afterJump > 8.0 && afterJump < 8.6)

        // It plays out to the end.
        assertTrue("never ended", waitFor(6.0) { collect(); events.contains(PipelineEvent.Ended(1L)) })
        note("events", events)
        note("end", "position ${Engine.position} s, duration ${Engine.duration} s")
        assertTrue(abs(Engine.position - 12.0) < 0.15)
        assertTrue(events.none { it is PipelineEvent.Failed })

        val last = Engine.outputInfo
        note("output at the end", last)
        Engine.stop()
        Engine.stopOutput()
        track.close()
        InstrumentationRegistry.getInstrumentation().sendStatus(0, report)
    }

    @Test
    fun theCurvesNeedNoEngine() {
        val params = SoundParams(enabled = true, bandGainDb = List(10) { if (it == 4) 12f else 0f })
        val curve = Sound.response(params, doubleArrayOf(500.0, 440.0))
        note("curve", "500 Hz ${curve[0]} dB, 440 Hz ${curve[1]} dB, peak ${Sound.peakResponse(params)} dB")
        assertEquals(12.0, curve[0], 0.01)
        assertEquals(10.568, curve[1], 0.01)
        assertEquals(listOf(32.0, 64.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0), Sound.bandFrequencies)
        assertEquals(247, Engine.processingLatency)
    }

    private fun band(hertz: Double) = (48 * kotlin.math.ln(hertz / 20) / kotlin.math.ln(1000.0)).toInt()
}
