package io.github.boomboom.engine

/** What the pipeline reports. [item] is the id given to [Engine.load] or [Engine.setNext]. */
sealed class PipelineEvent {
    abstract val item: Long

    /** The song's audio is now being heard (after a load, a jump, or a wait for more audio). */
    data class Playing(override val item: Long) : PipelineEvent()

    /** Playback moved on to the next song; [Playing] or [Waiting] for it follows at once. */
    data class Advanced(override val item: Long) : PipelineEvent()

    /** Nothing to hear at the song's place yet; playback starts by itself when the audio arrives. */
    data class Waiting(override val item: Long) : PipelineEvent()

    /** The last song played to its end. */
    data class Ended(override val item: Long) : PipelineEvent()

    data class Failed(override val item: Long, val reason: Reason) : PipelineEvent() {
        enum class Reason(val message: String) {
            UNSUPPORTED("This song's audio format isn't supported."),
            UNDECODABLE("This song's audio couldn't be decoded."),
        }
    }
}

/** What the audio output is doing. */
data class OutputInfo(
    val running: Boolean,
    /** The rate the stream takes the sound at: always the pipeline's 48 kHz. */
    val sampleRate: Int,
    /** The rate the device runs at on its own. */
    val nativeRate: Int,
    /** The system converts the 48 kHz stream to the device's rate. */
    val resampled: Boolean,
    val channels: Int,
    val framesPerBurst: Int,
    val bufferFrames: Int,
    val xruns: Int,
    val callbacks: Long,
    /** Frames pulled by the device so far. */
    val frames: Long,
    /** Of those, frames for which the pipeline had audio to give (not paused, not waiting). */
    val soundingFrames: Long,
    /** Times the stream was reopened after a route change (headphones, Bluetooth). */
    val reopens: Long,
)

/**
 * The audio engine, one per process: the playback pipeline (decoding a few seconds ahead), the sound processor and
 * the AAudio output. Every call is cheap and may come from any thread.
 *
 * Events wait in a queue: read them with [pollEvents] from a thread of your own (it can wait for the next one).
 */
object Engine {
    /** Opens and starts the audio output. False when the device refused. */
    fun startOutput(): Boolean = BoomCore.engineStartOutput()

    /** Stops the output and lets the device go. */
    fun stopOutput() = BoomCore.engineStopOutput()

    /** Starts on a song at [seconds], dropping whatever was playing. [gain] is the song's own level (linear). */
    fun load(track: Track, item: Long, seconds: Double = 0.0, autoplay: Boolean = true, gain: Float = 1f) {
        require(item != 0L) { "item ids are not 0" }
        track.withHandle { BoomCore.engineLoad(it, item, seconds, autoplay, gain) }
    }

    /** What follows the current song, so it joins on without a gap; null: nothing follows. */
    fun setNext(track: Track?, item: Long = 0L, gain: Float = 1f) {
        if (track == null) BoomCore.engineSetNext(0L, 0L, 1f)
        else {
            require(item != 0L) { "item ids are not 0" }
            track.withHandle { BoomCore.engineSetNext(it, item, gain) }
        }
    }

    fun play() = BoomCore.enginePlay()
    fun pause() = BoomCore.enginePause()

    /** Jumps; playing carries on from the new place, paused stays paused. [position] answers with it at once. */
    fun seek(seconds: Double) = BoomCore.engineSeek(seconds)
    fun stop() = BoomCore.engineStop()

    /** Changes the current or next song's own level (loudness normalisation switched on or off). */
    fun setItemGain(item: Long, gain: Float) = BoomCore.engineSetItemGain(item, gain)

    /** The overall level as a linear factor (1 = as recorded). */
    fun setLevel(gain: Float) = BoomCore.engineSetLevel(gain)

    /** Seconds into the song being heard. */
    val position: Double get() = BoomCore.enginePosition()

    /** The song being heard. */
    val currentItem: Long? get() = BoomCore.engineCurrentItem().takeIf { it != 0L }

    /** The length of the song being heard, once known. */
    val duration: Double? get() = BoomCore.engineDuration().takeUnless { it.isNaN() }

    /** How much of the song being heard is in hand, 0..1. */
    val bufferedFraction: Double get() = BoomCore.engineBufferedFraction()

    val outputInfo: OutputInfo
        get() = BoomCore.engineOutputInfo().let { v ->
            OutputInfo(
                running = v[0] != 0L, sampleRate = v[1].toInt(), nativeRate = v[2].toInt(), resampled = v[3] != 0L,
                channels = v[4].toInt(), framesPerBurst = v[5].toInt(), bufferFrames = v[6].toInt(), xruns = v[7].toInt(),
                callbacks = v[8], frames = v[9], soundingFrames = v[10], reopens = v[11],
            )
        }

    /** The events since the last call, waiting up to [timeoutMs] for one when there are none. */
    fun pollEvents(timeoutMs: Int = 0): List<PipelineEvent> {
        val flat = BoomCore.enginePollEvents(timeoutMs)
        return (flat.indices step 2).map { i ->
            val item = flat[i + 1]
            when (flat[i]) {
                BoomCore.EVENT_PLAYING -> PipelineEvent.Playing(item)
                BoomCore.EVENT_ADVANCED -> PipelineEvent.Advanced(item)
                BoomCore.EVENT_WAITING -> PipelineEvent.Waiting(item)
                BoomCore.EVENT_ENDED -> PipelineEvent.Ended(item)
                BoomCore.EVENT_FAILED_UNSUPPORTED -> PipelineEvent.Failed(item, PipelineEvent.Failed.Reason.UNSUPPORTED)
                else -> PipelineEvent.Failed(item, PipelineEvent.Failed.Reason.UNDECODABLE)
            }
        }
    }

    // Sound

    /** Sets the whole sound at once; it glides in over a few hundredths of a second. */
    fun applySound(params: SoundParams) = BoomCore.soundApply(params.toArray())

    /** For spatial sound with head tracking: radians the head is turned to the right of the loudspeakers. */
    fun setHeadTurn(radians: Float) = BoomCore.soundSetHeadTurn(radians)

    /** The output levels since the last call (after the limiter). One reader at a time. */
    fun takeLevels(): SoundLevels = BoomCore.soundTakeLevels().let { SoundLevels(it[0], it[1], it[2], it[3], it[4]) }

    /** The output's spectrum over the last moment: [bands] values, log-spaced from [lowHz] to [highHz], in dB. */
    fun spectrum(bands: Int, lowHz: Double = 20.0, highHz: Double = 20_000.0): FloatArray =
        BoomCore.soundSpectrum(bands, lowHz, highHz)

    /** Frames the sound processor holds a sample for (the limiter's look-ahead, 5 ms). */
    val processingLatency: Int get() = BoomCore.soundLatency()
}
