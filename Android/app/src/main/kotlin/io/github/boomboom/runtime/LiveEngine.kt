package io.github.boomboom.runtime

import android.os.Handler
import android.os.Looper
import io.github.boomboom.capture.CapturedTrack
import io.github.boomboom.capture.StreamBroker
import io.github.boomboom.engine.Engine
import io.github.boomboom.engine.PipelineEvent
import io.github.boomboom.engine.Sound
import io.github.boomboom.engine.SoundParams
import io.github.boomboom.logic.DownloadBackend
import io.github.boomboom.logic.DownloadEvent
import io.github.boomboom.logic.EngineState
import io.github.boomboom.logic.PlaybackEngine
import io.github.boomboom.logic.PlaybackEngineDelegate
import io.github.boomboom.logic.SoundLevels
import io.github.boomboom.logic.SoundMath
import io.github.boomboom.logic.SoundProcessing
import io.github.boomboom.logic.SoundProfile
import io.github.boomboom.logic.SoundSettings
import io.github.boomboom.models.Song
import io.github.boomboom.web.AudioOffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The real player: fetches each song's audio through the hidden tab ([StreamBroker]) and plays it through the native
 * audio engine ([Engine]). A port of the iPhone app's `LiveEngine`; it is also the equaliser's sound processor and
 * curve maths, and the downloads' backend.
 *
 * Everything here runs on the main thread. The engine's events are read on a thread of their own and handed here.
 */
class LiveEngine(
    private val broker: StreamBroker,
    private val system: SystemAudio,
    private val network: NetworkWatch,
    private val scope: CoroutineScope,
) : PlaybackEngine, SoundProcessing, SoundMath, DownloadBackend {
    override var delegate: PlaybackEngineDelegate? = null

    private class Loaded(val song: Song, val captured: CapturedTrack, val item: Long, var gain: Float)

    /**
     * Whether there is audio at the listener's place in the current song. Together with [wantsPlay] (what the listener
     * asked for) this decides the state the screens see; [report] is the one place that works it out.
     */
    private sealed class Audio {
        /** No song is loaded. */
        data object None : Audio()

        /** Not in hand yet. [heardBefore] tells a song that has not started (loading) from one that has (buffering). */
        data class Awaited(val heardBefore: Boolean) : Audio()

        /** In hand: playing, or ready to. */
        data object Ready : Audio()

        /** The song cannot be played. The text is for the listener. */
        data class Failed(val message: String) : Audio()
    }

    private var current: Loaded? = null
        set(value) {
            if (field !== value) field?.captured?.release()
            field = value
        }
    private var next: Loaded? = null
        set(value) {
            if (field !== value) field?.captured?.release()
            field = value
        }
    private var audio: Audio = Audio.None
    private var wantsPlay = false
    private var reported: EngineState? = null

    /** The system took the audio away (a call) while the listener wanted it playing: resume when it is given back. */
    private var resumesAfterInterruption = false
    private var settings = SoundSettings()
    private var playheadJob: Job? = null
    private var outputRest: Job? = null
    private var isOutputRunning = false
    private var nextItem = 1L
    private val main = Handler(Looper.getMainLooper())

    override var onDownloadEvent: ((DownloadEvent) -> Unit)? = null
    override var onDownloadHoldChange: (() -> Unit)? = null
    override var allowsDownloadsOnMobileData: Boolean = false
        set(value) {
            if (value == field) return
            field = value
            updateDownloadHold()
        }
    override var isHoldingDownloadsForWiFi: Boolean = false
        private set

    init {
        Thread({
            while (true) {
                val events = Engine.pollEvents(250)
                if (events.isNotEmpty()) main.post { events.forEach(::handle) }
            }
        }, "boom-engine-events").apply {
            isDaemon = true
            start()
        }
        system.onInterruption = { began, shouldResume ->
            if (began) {
                resumesAfterInterruption = wantsPlay
                pause()
            } else if (shouldResume && resumesAfterInterruption) {
                resumesAfterInterruption = false
                play()
            }
        }
        system.onOutputLost = { pause() }
        network.onChange = { updateDownloadHold() }
        broker.onDownload = { event -> onDownloadEvent?.invoke(event) }
        updateDownloadHold()
    }

    // MARK: PlaybackEngine

    override fun load(song: Song, autoplay: Boolean, startAt: Double) {
        val captured = broker.track(song.videoId, StreamBroker.Purpose.PLAYING)
        captured.retain()
        val loaded = Loaded(song, captured, newItem(), songGain(captured))
        BoomTrace.note(
            "engine load ${song.videoId} at $startAt autoplay=$autoplay cached=${captured.isFromCache} " +
                "held=${captured.track.heldPackets}",
        )
        current = loaded
        next = null
        wantsPlay = autoplay
        reported = null
        resumesAfterInterruption = false
        watch(captured)
        broker.setPlayhead(startAt, song.videoId)
        val failure = captured.failure
        if (failure != null) {
            audio = Audio.Failed(failure)
            report()
            return
        }
        audio = Audio.Awaited(heardBefore = false)
        report()
        startOutput()
        Engine.load(captured.track, loaded.item, startAt, autoplay, loaded.gain)
        startPlayheadReports()
    }

    override fun prepareNext(song: Song?) {
        if (song == null) {
            next = null
            Engine.setNext(null)
            broker.clearUpcoming()
            return
        }
        if (next?.song?.videoId == song.videoId) return
        val captured = broker.track(song.videoId, StreamBroker.Purpose.UPCOMING)
        // A song that cannot be fetched is not joined on; the queue deals with it when its turn comes.
        if (captured.failure != null) {
            next = null
            Engine.setNext(null)
            return
        }
        captured.retain()
        val loaded = Loaded(song, captured, newItem(), songGain(captured))
        next = loaded
        watch(captured)
        Engine.setNext(captured.track, loaded.item, loaded.gain)
    }

    override fun fetchAhead(songs: List<Song>) {
        val wanted = if (network.isMetered) emptyList() else songs.map { it.videoId }
        broker.keepLater(wanted)
        for (videoId in wanted) {
            if (videoId != current?.song?.videoId && videoId != next?.song?.videoId) {
                broker.track(videoId, StreamBroker.Purpose.LATER)
            }
        }
    }

    override fun play() {
        if (current == null || isFailed) return
        wantsPlay = true
        startOutput()
        Engine.play()
        report()
    }

    override fun pause() {
        if (current == null) return
        wantsPlay = false
        Engine.pause()
        report()
        restOutputSoon()
    }

    override fun seek(seconds: Double) {
        val current = current ?: return
        if (isFailed) return
        broker.setPlayhead(seconds, current.song.videoId)
        Engine.seek(seconds)
    }

    override fun stop() {
        wantsPlay = false
        playheadJob?.cancel()
        Engine.stop()
        current = null
        next = null
        audio = Audio.None
        reported = null
        system.deactivate()
        restOutputSoon()
    }

    override val position: Double
        get() {
            if (current == null) return 0.0
            val heard = Engine.position
            val length = duration
            return if (length > 0) minOf(heard, length) else heard
        }

    override val duration: Double
        get() {
            val current = current ?: return 0.0
            return current.captured.track.info?.duration ?: current.song.duration ?: 0.0
        }

    override val bufferedFraction: Double get() = current?.captured?.track?.coveredFraction ?: 0.0

    /** The format of the song being heard, as the page's answer offered it. */
    val currentFormat: AudioOffer? get() = current?.captured?.format

    override val formatDescription: String?
        get() {
            val current = current ?: return null
            val track = current.captured.track
            val info = track.info ?: return null
            val codec = if (info.codec == io.github.boomboom.engine.TrackInfo.Codec.OPUS) "Opus" else "AAC"
            // What was really captured, measured from the bytes in hand, not what was advertised.
            val parts = mutableListOf(codec)
            val held = track.heldPackets
            val offer = current.captured.format
            if (info.packetDuration > 0 && held > 50 && info.timescale > 0) {
                val seconds = held.toDouble() * info.packetDuration / info.timescale
                parts += "${(track.heldBytes * 8 / seconds / 1000).roundToInt()} kbps"
            } else if (offer != null && offer.bitrate > 0) {
                parts += "${offer.bitrate / 1000} kbps"
            }
            parts += String.format(Locale.ROOT, "%s kHz", trimmed(info.sampleRate / 1000))
            return parts.joinToString(" · ")
        }

    /** The device the sound goes to and the rate it runs at, and whether the stream had to be converted to it. */
    override val outputDescription: String?
        get() {
            val route = system.outputDescription ?: return null
            val output = Engine.outputInfo
            if (!output.running || output.nativeRate <= 0) return route
            val rate = String.format(Locale.ROOT, "%s kHz", trimmed(output.nativeRate / 1000.0))
            return "$route · $rate · " + if (output.resampled) "resampled from 48 kHz" else "not resampled"
        }

    // MARK: State

    private val isFailed: Boolean get() = audio is Audio.Failed

    /** Works out what the screens should see from [audio] and [wantsPlay], and says so when it has changed. */
    private fun report() {
        val state: EngineState = when (val now = audio) {
            Audio.None -> return
            is Audio.Failed -> EngineState.Failed(now.message)
            Audio.Ready -> if (wantsPlay) EngineState.Playing else EngineState.Paused
            is Audio.Awaited -> when {
                !wantsPlay -> EngineState.Paused
                now.heardBefore -> EngineState.Buffering
                else -> EngineState.Loading
            }
        }
        if (state == reported) return
        reported = state
        delegate?.engineDidChange(this, state)
    }

    private fun fail(message: String) {
        audio = Audio.Failed(message)
        wantsPlay = false
        Engine.pause()
        report()
    }

    private fun handle(event: PipelineEvent) {
        BoomTrace.note(String.format(Locale.ROOT, "pipeline %s at %.1f s", describe(event), Engine.position))
        when (event) {
            is PipelineEvent.Playing -> {
                if (event.item != current?.item || isFailed) return
                audio = Audio.Ready
                report()
            }
            is PipelineEvent.Waiting -> {
                val current = current ?: return
                if (event.item != current.item || isFailed) return
                current.captured.failure?.let {
                    fail(it)
                    return
                }
                if (audio is Audio.Awaited) return
                audio = Audio.Awaited(heardBefore = audio == Audio.Ready)
                report()
            }
            is PipelineEvent.Advanced -> {
                val upcoming = next ?: return
                if (event.item != upcoming.item) return
                // The engine's own reference keeps the song playing; the hold moves from `next` to `current`.
                upcoming.captured.retain()
                current = upcoming
                next = null
                // Whether the new song is being heard or awaited follows at once as its own event.
                audio = Audio.Awaited(heardBefore = false)
                reported = null
                broker.track(upcoming.song.videoId, StreamBroker.Purpose.PLAYING)
                broker.setPlayhead(0.0, upcoming.song.videoId)
                delegate?.engineDidAdvanceToPreparedSong(this)
            }
            is PipelineEvent.Ended -> {
                if (event.item != current?.item) return
                // The song is over and nothing is sounding: say "paused" first, then that it finished, so whoever
                // listens can start it again or move on from a known state.
                wantsPlay = false
                if (!isFailed) audio = Audio.Ready
                report()
                delegate?.engineDidFinishSong(this)
            }
            is PipelineEvent.Failed -> {
                if (event.item != current?.item) return
                fail(event.reason.message)
            }
        }
    }

    private fun describe(event: PipelineEvent): String = when (event) {
        is PipelineEvent.Playing -> "playing"
        is PipelineEvent.Advanced -> "advanced to the next song"
        is PipelineEvent.Waiting -> "waiting for audio"
        is PipelineEvent.Ended -> "ended"
        is PipelineEvent.Failed -> "failed: ${event.reason.message}"
    }

    private fun watch(captured: CapturedTrack) {
        captured.onUpdate = update@{
            val failure = captured.failure
            if (failure != null && captured === current?.captured && audio is Audio.Awaited) {
                // Audio already in hand keeps playing; the failure shows once the listener is waiting on more.
                fail(failure)
            }
            if (captured === next?.captured && failure != null) {
                next = null
                Engine.setNext(null)
            }
            // The song's loudness arrives with the player's answer, a moment after the request.
            applySongGains()
        }
    }

    private fun newItem(): Long = nextItem++

    private fun startOutput() {
        system.activate()
        outputRest?.cancel()
        outputRest = null
        if (isOutputRunning) return
        isOutputRunning = Engine.startOutput()
        val info = Engine.outputInfo
        BoomTrace.note(
            if (isOutputRunning) "audio output started: ${info.nativeRate} Hz device, burst ${info.framesPerBurst}, resampled=${info.resampled}"
            else "audio output failed to start",
        )
    }

    /**
     * A paused player lets the audio device go after a while: an open output keeps the phone's audio path (and its
     * power) running for nothing. Playing again opens it in a moment.
     */
    private fun restOutputSoon() {
        outputRest?.cancel()
        outputRest = scope.launch {
            delay(OUTPUT_REST_MILLIS)
            if (wantsPlay || !isOutputRunning) return@launch
            Engine.stopOutput()
            isOutputRunning = false
            BoomTrace.note("audio output rested after a pause")
        }
    }

    private fun startPlayheadReports() {
        playheadJob?.cancel()
        playheadJob = scope.launch {
            while (isActive) {
                delay(1000)
                val current = current ?: return@launch
                broker.setPlayhead(Engine.position, current.song.videoId)
            }
        }
    }

    // MARK: SoundProcessing

    override fun apply(settings: SoundSettings) {
        val normalizationChanged = settings.isLoudnessNormalizationEnabled != this.settings.isLoudnessNormalizationEnabled
        this.settings = settings
        Engine.applySound(params(settings.profile(this)))
        if (normalizationChanged) applySongGains()
    }

    /** Head tracking needs a sensor in the headphones; Android's is not read yet. */
    override val isHeadTrackingAvailable: Boolean get() = false

    override fun takeLevels(): SoundLevels {
        val levels = Engine.takeLevels()
        return SoundLevels(levels.peakLeft, levels.peakRight, levels.rmsLeft, levels.rmsRight, levels.gainReductionDb)
    }

    override fun spectrum(bands: Int): FloatArray = Engine.spectrum(bands)

    /**
     * Turns a louder-than-reference song down by the amount YouTube Music reports. Quieter songs are left alone, as
     * YouTube Music itself does: turning them up would need the limiter.
     */
    private fun songGain(captured: CapturedTrack): Float {
        val loudness = captured.loudnessDb
        if (!settings.isLoudnessNormalizationEnabled || loudness == null || loudness <= 0) return 1f
        return 10.0.pow(-loudness / 20).toFloat()
    }

    private fun applySongGains() {
        for (loaded in listOfNotNull(current, next)) {
            val gain = songGain(loaded.captured)
            if (gain != loaded.gain) {
                loaded.gain = gain
                Engine.setItemGain(loaded.item, gain)
            }
        }
    }

    // MARK: SoundMath (the native processor's own curve)

    override fun response(settings: SoundSettings, frequencies: DoubleArray): DoubleArray {
        if (!settings.isEnabled) return DoubleArray(frequencies.size)
        return Sound.response(curve(settings), frequencies, SoundMath.SAMPLE_RATE)
    }

    override fun peakResponse(settings: SoundSettings): Double {
        if (!settings.isEnabled) return 0.0
        return maxOf(0.0, Sound.peakResponse(curve(settings), SoundMath.SAMPLE_RATE))
    }

    /** Only what shapes the curve: the bands, bass and treble. */
    private fun curve(settings: SoundSettings): SoundParams =
        SoundParams(enabled = true, bandGainDb = bands(settings.gains), bassDb = settings.bass, trebleDb = settings.treble)

    // MARK: DownloadBackend

    override fun startDownloads(videoIds: List<String>) {
        broker.startDownloads(videoIds)
    }

    override fun removeDownloads(videoIds: List<String>) {
        broker.removeDownloads(videoIds)
    }

    private fun updateDownloadHold() {
        val holding = network.isMetered && !allowsDownloadsOnMobileData
        broker.allowsDownloads = !holding
        if (holding == isHoldingDownloadsForWiFi) return
        isHoldingDownloadsForWiFi = holding
        BoomTrace.note("downloads ${if (holding) "wait for Wi-Fi" else "may run"}")
        onDownloadHoldChange?.invoke()
    }

    companion object {
        private const val OUTPUT_REST_MILLIS = 60_000L

        private fun bands(gains: List<Float>): List<Float> = List(SoundParams.BANDS) { gains.getOrElse(it) { 0f } }

        private fun params(profile: SoundProfile): SoundParams = SoundParams(
            enabled = profile.isEnabled,
            bandGainDb = bands(profile.bandGains),
            preampDb = profile.preamp,
            headroomDb = profile.headroom,
            bassDb = profile.bassGain,
            trebleDb = profile.trebleGain,
            boostDb = profile.boost,
            limiter = profile.isLimiterEnabled,
            balance = profile.balance,
            width = profile.width,
            deepBass = profile.deepBass,
            air = profile.air,
            crossfeed = profile.crossfeed,
            spatial = if (profile.isSpatial) 1f else 0f,
        )

        /** "48", "44.1". */
        private fun trimmed(value: Double): String =
            if (value == value.roundToInt().toDouble()) value.roundToInt().toString() else String.format(Locale.ROOT, "%.1f", value)
    }
}
