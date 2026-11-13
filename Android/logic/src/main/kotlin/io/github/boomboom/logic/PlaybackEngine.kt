package io.github.boomboom.logic

import io.github.boomboom.models.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * What [PlayerStore] needs from the thing that makes sound. The real one captures the hidden tab's audio and plays it
 * through the equaliser; [SimulatedEngine] only keeps time, for previews and tests.
 *
 * Everything here is called on the one thread the stores live on.
 */
interface PlaybackEngine {
    var delegate: PlaybackEngineDelegate?

    /** Starts on a new song, dropping whatever was playing. [startAt] is seconds into the song. */
    fun load(song: Song, autoplay: Boolean, startAt: Double)

    /** Says which song comes next, so it can be fetched ahead and joined on without a gap. Null: nothing follows. */
    fun prepareNext(song: Song?)

    fun play()
    fun pause()
    fun seek(seconds: Double)

    /** Stops and lets go of everything. */
    fun stop()

    /** Seconds into the current song, as heard now. */
    val position: Double

    /** The current song's length in seconds, 0 until known. */
    val duration: Double

    /** How much of the current song is in hand, 0...1. */
    val bufferedFraction: Double

    /** The audio format being heard, for a details line: "Opus · 141 kbps · 48 kHz". Null when not known. */
    val formatDescription: String? get() = null

    /** Where the sound is going, for the listener: "Headphones · 48 kHz · not resampled". Null when unknown. */
    val outputDescription: String? get() = null

    /**
     * The songs that follow the next one, nearest first, so their audio can be fetched ahead. With the screen
     * locked the hidden tab may be slowed down or stopped; what is already in hand keeps playing regardless.
     */
    fun fetchAhead(songs: List<Song>) {}
}

sealed class EngineState {
    data object Loading : EngineState()
    data object Playing : EngineState()
    data object Paused : EngineState()
    data object Buffering : EngineState()
    data class Failed(val message: String) : EngineState()
}

interface PlaybackEngineDelegate {
    fun engineDidChange(engine: PlaybackEngine, state: EngineState)

    /** The song given to [PlaybackEngine.prepareNext] has started (joined on without a gap). */
    fun engineDidAdvanceToPreparedSong(engine: PlaybackEngine)

    /** The current song ended and nothing was prepared to follow it. */
    fun engineDidFinishSong(engine: PlaybackEngine)
}

/**
 * Keeps time without making sound: plays every song as if its audio were there. For previews and tests.
 * Waiting goes through [scope] and the time through [clock], so a test dispatcher controls both.
 */
class SimulatedEngine(
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
) : PlaybackEngine {
    override var delegate: PlaybackEngineDelegate? = null

    private var song: Song? = null
    private var prepared: Song? = null
    private var isPlaying = false
    private var anchorPosition = 0.0
    private var anchorMillis = clock.nowMillis()
    private var ticker: Job? = null
    private var loadJob: Job? = null

    /** How long a song takes to "load" before it plays. */
    var loadDelay: Duration = 250.milliseconds

    /** Songs (by video id) that fail to load, to try out how the player deals with unplayable ones. */
    var unplayable: Set<String> = emptySet()

    override val duration: Double get() = song?.duration ?: 0.0
    override val bufferedFraction: Double get() = if (song == null) 0.0 else 1.0

    override val position: Double
        get() {
            if (!isPlaying) return anchorPosition
            val now = anchorPosition + (clock.nowMillis() - anchorMillis) / 1000.0
            return if (duration > 0) minOf(now, duration) else now
        }

    override fun load(song: Song, autoplay: Boolean, startAt: Double) {
        loadJob?.cancel()
        this.song = song
        prepared = null
        isPlaying = false
        anchorPosition = maxOf(0.0, startAt)
        delegate?.engineDidChange(this, EngineState.Loading)
        val wait = loadDelay
        loadJob = scope.launch {
            delay(wait)
            if (song.videoId in unplayable) {
                delegate?.engineDidChange(this@SimulatedEngine, EngineState.Failed("This song can't be played."))
            } else if (autoplay) {
                play()
            } else {
                delegate?.engineDidChange(this@SimulatedEngine, EngineState.Paused)
            }
        }
    }

    override fun prepareNext(song: Song?) {
        prepared = song
    }

    override fun play() {
        if (song == null || isPlaying) return
        anchorMillis = clock.nowMillis()
        isPlaying = true
        delegate?.engineDidChange(this, EngineState.Playing)
        startTicker()
    }

    override fun pause() {
        if (!isPlaying) return
        anchorPosition = position
        isPlaying = false
        ticker?.cancel()
        delegate?.engineDidChange(this, EngineState.Paused)
    }

    override fun seek(seconds: Double) {
        anchorPosition = maxOf(0.0, if (duration > 0) minOf(seconds, duration) else seconds)
        anchorMillis = clock.nowMillis()
    }

    override fun stop() {
        loadJob?.cancel()
        ticker?.cancel()
        song = null
        prepared = null
        isPlaying = false
        anchorPosition = 0.0
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(100)
                if (!isPlaying) return@launch
                if (duration <= 0 || position < duration) continue
                val next = prepared
                if (next != null) {
                    song = next
                    prepared = null
                    anchorPosition = 0.0
                    anchorMillis = clock.nowMillis()
                    delegate?.engineDidAdvanceToPreparedSong(this@SimulatedEngine)
                } else {
                    anchorPosition = duration
                    isPlaying = false
                    delegate?.engineDidFinishSong(this@SimulatedEngine)
                    return@launch
                }
            }
        }
    }
}
