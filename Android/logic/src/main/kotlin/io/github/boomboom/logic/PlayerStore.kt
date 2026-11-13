package io.github.boomboom.logic

import io.github.boomboom.models.LikeStatus
import io.github.boomboom.models.Lyrics
import io.github.boomboom.models.MusicAPI
import io.github.boomboom.models.Shelf
import io.github.boomboom.models.Song
import io.github.boomboom.models.WatchEndpoint
import io.github.boomboom.models.WatchQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.random.Random

/** Where the current song is, at one moment. */
data class ClockState(
    /** Seconds into the song. */
    val position: Double = 0.0,
    /** The song's length in seconds, 0 until known. */
    val duration: Double = 0.0,
    /** How much of the song has been fetched, 0...1 (the lighter part of the scrubber's track). */
    val bufferedFraction: Double = 0.0,
) {
    /** 0...1 for a progress bar. */
    val progress: Double get() = if (duration > 0) minOf(1.0, maxOf(0.0, position / duration)) else 0.0
    val remaining: Double get() = maxOf(0.0, duration - position)
}

/**
 * Where the current song is. Kept apart from [PlayerStore] so that only the views showing time recompose as it runs
 * (about ten times a second while a song plays): observe [state].
 */
class PlaybackClock {
    private val _state = MutableStateFlow(ClockState())
    val state: StateFlow<ClockState> = _state.asStateFlow()

    val position: Double get() = _state.value.position
    val duration: Double get() = _state.value.duration
    val bufferedFraction: Double get() = _state.value.bufferedFraction
    val progress: Double get() = _state.value.progress
    val remaining: Double get() = _state.value.remaining

    internal fun update(change: (ClockState) -> ClockState) {
        _state.value = change(_state.value)
    }
}

/**
 * Everything about the player that a screen shows apart from the time: the queue, what is playing and the controls'
 * settings. It changes when the listener or the queue does (not ten times a second), so one object is enough.
 */
data class PlayerState(
    /** Everything queued: songs already played, the current one, and what is up next. */
    val queue: List<QueueEntry> = emptyList(),
    val currentEntryId: UUID? = null,
    val status: PlaybackStatus = PlaybackStatus.Idle,
    /** What the queue was started from ("Playing from …"). */
    val source: QueueSource? = null,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    /** True while the songs up next are in shuffled order. */
    val isShuffled: Boolean = false,
    val sleepTimer: SleepTimer? = null,
    /** True while the queue for a new song or radio is being fetched. */
    val isLoadingQueue: Boolean = false,
    /** The audio format being heard: "Opus · 141 kbps · 48 kHz". Null until known. */
    val audioFormat: String? = null,
    /** Where the sound is going: "Headphones · 48 kHz · not resampled". Null until something plays. */
    val audioOutput: String? = null,
    /** When on, a radio of similar songs carries on after the last queued song. */
    val isAutoplayEnabled: Boolean = true,
    /** True while there is more of the radio to load after the last queued song. */
    val hasContinuation: Boolean = false,
) {
    val currentIndex: Int? get() = currentEntryId?.let { id -> queue.indexOfFirst { it.id == id }.takeIf { it >= 0 } }

    val current: Song? get() = currentIndex?.let { queue[it].song }

    /** The listener's rating of the current song. */
    val likeStatus: LikeStatus get() = current?.likeStatus ?: LikeStatus.INDIFFERENT

    /** The entries after the current one. */
    val upNext: List<QueueEntry>
        get() {
            val index = currentIndex ?: return emptyList()
            return if (index + 1 < queue.size) queue.subList(index + 1, queue.size) else emptyList()
        }

    /** True when the play button should show "pause": playing, or about to. */
    val isPlayingOrAboutTo: Boolean
        get() = when (status) {
            PlaybackStatus.Playing, PlaybackStatus.Loading, PlaybackStatus.Buffering -> true
            PlaybackStatus.Idle, PlaybackStatus.Paused, is PlaybackStatus.Failed -> false
        }

    val hasNext: Boolean
        get() {
            val index = currentIndex ?: return false
            return index + 1 < queue.size || repeatMode == RepeatMode.ALL || hasContinuation
        }

    val hasPrevious: Boolean get() = (currentIndex ?: 0) > 0
}

/**
 * The player as the screens see it: the queue, what is playing, and every control.
 *
 * Observe [state] (and [clock] for time); call the methods from buttons. The store lives on one thread, the one
 * [scope] runs on (the main thread in the app, a test dispatcher in tests). Use a dispatcher that does not run a new
 * coroutine inside the call that starts it (`Dispatchers.Main`, not `Main.immediate`), as the iPhone app's `Task`
 * does not either.
 *
 * Every wait (the pass-over pause, the sleep timer, the clock's ticker) goes through [scope], and "now" through
 * [time], so a test controls time.
 */
class PlayerStore(
    private val api: MusicAPI,
    private val engine: PlaybackEngine,
    private val storage: KeyValueStore,
    private val scope: CoroutineScope,
    private val time: Clock = Clock.System,
    /** What shuffles: a test passes a seeded one. */
    private val random: Random = Random.Default,
) : PlaybackEngineDelegate {
    // MARK: What the screens read

    /**
     * How the player came to the current song. It decides what happens when that song cannot be played.
     */
    private sealed class Arrival {
        /** The listener picked this very song (a tap on it): a failure is shown, with a way to try again. */
        data object Chosen : Arrival()

        /**
         * The queue moved on to it (the song before ended, or the listener skipped): an unplayable song is passed
         * over, as long as that does not happen [failuresInARow] times running (then the trouble is not the song).
         */
        data class Passing(val failuresInARow: Int) : Arrival()
    }

    /** Shuffle is either off, or on together with the order to put back when it is turned off. */
    private sealed class Shuffle {
        data object Off : Shuffle()
        data class On(val orderBefore: List<UUID>) : Shuffle()
    }

    private val _state = MutableStateFlow(
        PlayerState(
            repeatMode = storage.getString(Keys.REPEAT_MODE)?.let(RepeatMode::fromRawValue) ?: RepeatMode.OFF,
            isAutoplayEnabled = storage.getBoolean(Keys.AUTOPLAY) ?: true,
        ),
    )

    /** Everything a screen shows apart from the time. */
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    /** The time. */
    val clock = PlaybackClock()

    private fun update(change: PlayerState.() -> PlayerState) {
        _state.value = _state.value.change()
    }

    // The same values as in [state], for reading and writing inside the store (and for tests and screens that only
    // want the current one).
    val queue: List<QueueEntry> get() = _state.value.queue
    val currentEntryId: UUID? get() = _state.value.currentEntryId
    val status: PlaybackStatus get() = _state.value.status
    val source: QueueSource? get() = _state.value.source
    val repeatMode: RepeatMode get() = _state.value.repeatMode
    val isShuffled: Boolean get() = _state.value.isShuffled
    val likeStatus: LikeStatus get() = _state.value.likeStatus
    val sleepTimer: SleepTimer? get() = _state.value.sleepTimer
    val isLoadingQueue: Boolean get() = _state.value.isLoadingQueue
    val audioFormat: String? get() = _state.value.audioFormat
    val audioOutput: String? get() = _state.value.audioOutput
    val currentIndex: Int? get() = _state.value.currentIndex
    val current: Song? get() = _state.value.current
    val upNext: List<QueueEntry> get() = _state.value.upNext
    val isPlayingOrAboutTo: Boolean get() = _state.value.isPlayingOrAboutTo
    val hasNext: Boolean get() = _state.value.hasNext
    val hasPrevious: Boolean get() = _state.value.hasPrevious

    /** When on, a radio of similar songs carries on after the last queued song. */
    var isAutoplayEnabled: Boolean
        get() = _state.value.isAutoplayEnabled
        set(value) {
            update { copy(isAutoplayEnabled = value) }
            storage.putBoolean(Keys.AUTOPLAY, value)
            prepareNext()
        }

    private var shuffle: Shuffle = Shuffle.Off
        set(value) {
            field = value
            update { copy(isShuffled = value is Shuffle.On) }
        }

    private var arrival: Arrival = Arrival.Chosen
    private var skipJob: Job? = null

    private var currentEntryIdValue: UUID?
        get() = _state.value.currentEntryId
        set(value) = update { copy(currentEntryId = value) }

    private var queueValue: List<QueueEntry>
        get() = _state.value.queue
        set(value) = update { copy(queue = value) }

    private var statusValue: PlaybackStatus
        get() = _state.value.status
        set(value) = update { copy(status = value) }

    // MARK: Inside

    /** Loads more of a radio queue. */
    private var continuation: String? = null
        set(value) {
            field = value
            update { copy(hasContinuation = value != null) }
        }

    /** Goes up whenever the queue is replaced, so answers to older requests are dropped. */
    private var generation = 0
    private var ticker: Job? = null
    private var sleepJob: Job? = null
    private var isFetchingMore = false
    private val extras = HashMap<String, Extras>()

    private class Extras(val lyrics: String?, val related: String?)

    /** Called after anything a lock screen would show has changed (song, playing or paused, a jump in time). */
    var onChange: ((PlayerStore) -> Unit)? = null

    private object Keys {
        const val REPEAT_MODE = "player.repeatMode"
        const val AUTOPLAY = "player.autoplay"
    }

    init {
        engine.delegate = this
    }

    // MARK: Starting playback

    /** Plays one song now and fills the queue with a radio based on it (a tap on a song in search or on Home). */
    fun play(song: Song) {
        val entry = QueueEntry(song = song)
        replaceQueue(
            listOf(entry), entry.id,
            QueueSource(QueueSource.Kind.RADIO, "Radio", song.title),
        )
        val request = generation
        update { copy(isLoadingQueue = true) }
        scope.launch {
            try {
                val radio = attempt { api.watchQueue(WatchEndpoint(videoId = song.videoId)) }
                if (radio == null || request != generation) return@launch
                rememberExtras(song.videoId, radio)
                // The radio starts with the song itself; everything after it goes up next.
                val start = radio.songs.indexOfFirst { it.videoId == song.videoId }.let { if (it >= 0) it + 1 else 0 }
                val rest = radio.songs.drop(start).filter { it.isAvailable }
                queueValue = queue + rest.map { QueueEntry(song = it) }
                continuation = radio.continuation
                prepareNext()
            } finally {
                if (request == generation) update { copy(isLoadingQueue = false) }
            }
        }
    }

    /** Plays a list (an album, a playlist, "Top songs") from the song at [startAt]. */
    fun play(songs: List<Song>, startAt: Int = 0, source: QueueSource? = null) {
        val playable = songs.withIndex().filter { it.value.isAvailable || it.index == startAt }
        if (playable.isEmpty()) return
        val entries = playable.map { QueueEntry(song = it.value) }
        val position = playable.indexOfFirst { it.index == startAt }.let { if (it >= 0) it else 0 }
        replaceQueue(entries, entries[position].id, source)
    }

    /** Plays what a button's endpoint describes: "Shuffle", "Radio", "Play" on a playlist card. */
    fun play(endpoint: WatchEndpoint, source: QueueSource? = null) {
        generation += 1
        val request = generation
        update { copy(isLoadingQueue = true) }
        if (queue.isEmpty()) statusValue = PlaybackStatus.Loading
        scope.launch {
            try {
                val result = api.watchQueue(endpoint)
                if (request != generation) return@launch
                val songs = result.songs.filter { it.isAvailable }
                if (songs.isEmpty()) {
                    if (queue.isEmpty()) statusValue = PlaybackStatus.Failed("Nothing to play here.")
                    return@launch
                }
                val entries = songs.map { QueueEntry(song = it) }
                val wanted = if (result.currentIndex in result.songs.indices) {
                    result.songs[result.currentIndex].videoId
                } else {
                    songs[0].videoId
                }
                val start = entries.firstOrNull { it.song.videoId == wanted } ?: entries[0]
                endpoint.videoId?.let { rememberExtras(it, result) }
                // `replaceQueue` raises the generation itself; this request is still the current one afterwards.
                generation -= 1
                replaceQueue(entries, start.id, source)
                continuation = result.continuation
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (request != generation) return@launch
                if (queue.isEmpty()) statusValue = PlaybackStatus.Failed("Couldn't load this. Check your connection.")
            } finally {
                if (request == generation) update { copy(isLoadingQueue = false) }
            }
        }
    }

    /** Starts a radio of songs like [song]. */
    fun startRadio(song: Song) {
        play(song)
    }

    // MARK: Transport

    fun togglePlayPause() {
        when (status) {
            PlaybackStatus.Playing, PlaybackStatus.Buffering, PlaybackStatus.Loading -> pause()
            PlaybackStatus.Paused -> resume()
            is PlaybackStatus.Failed -> retry()
            PlaybackStatus.Idle -> Unit
        }
    }

    fun resume() {
        if (current == null) return
        // At the very end of the last song, "play" starts it again.
        if (clock.duration > 0 && clock.position >= clock.duration - 0.25) seek(0.0)
        engine.play()
    }

    fun pause() {
        engine.pause()
    }

    /** Tries the current song again after a failure. */
    fun retry() {
        val song = current ?: return
        skipJob?.cancel()
        arrival = Arrival.Chosen
        start(song, 0.0)
    }

    fun seek(seconds: Double) {
        if (current == null) return
        val target = maxOf(0.0, if (clock.duration > 0) minOf(seconds, clock.duration) else seconds)
        engine.seek(target)
        clock.update { it.copy(position = target) }
        onChange?.invoke(this)
    }

    /** The next song. At the end of the queue: wraps when repeating all, or loads more of a radio. */
    fun next() {
        val index = currentIndex ?: return
        if (index + 1 < queue.size) {
            move(queue[index + 1].id)
        } else if (repeatMode == RepeatMode.ALL && queue.isNotEmpty()) {
            move(queue.first().id)
        } else {
            fetchMore(thenAdvance = true)
        }
    }

    /** Back to the start of the song, or to the song before when already near the start. */
    fun previous() {
        val index = currentIndex ?: return
        if (clock.position > 3 || index == 0) {
            seek(0.0)
            if (status == PlaybackStatus.Paused) resume()
        } else {
            move(queue[index - 1].id)
        }
    }

    /** Plays a queued entry now (a tap in "Up next"). */
    fun jump(entryId: UUID) {
        if (queue.none { it.id == entryId }) return
        move(entryId, Arrival.Chosen)
    }

    fun stop() {
        generation += 1
        skipJob?.cancel()
        engine.stop()
        ticker?.cancel()
        queueValue = emptyList()
        currentEntryIdValue = null
        continuation = null
        update { copy(source = null) }
        statusValue = PlaybackStatus.Idle
        clock.update { ClockState() }
        setSleepTimer(null)
        onChange?.invoke(this)
    }

    // MARK: Queue

    /** Puts songs right after the current one. */
    fun playNext(songs: List<Song>) {
        val entries = songs.filter { it.isAvailable }.map { QueueEntry(song = it) }
        if (entries.isEmpty()) return
        val index = currentIndex
        if (index == null) {
            play(songs)
            return
        }
        queueValue = queue.take(index + 1) + entries + queue.drop(index + 1)
        prepareNext()
    }

    /** Puts songs at the end of the queue. */
    fun addToQueue(songs: List<Song>) {
        val entries = songs.filter { it.isAvailable }.map { QueueEntry(song = it) }
        if (entries.isEmpty()) return
        if (currentIndex == null) {
            play(songs)
            return
        }
        queueValue = queue + entries
        prepareNext()
    }

    /** Reorders the queue (drag in "Up next"). Offsets are positions in the queue; [toOffset] is counted before the move. */
    fun moveQueue(fromOffsets: Collection<Int>, toOffset: Int) {
        val moving = fromOffsets.toSortedSet().filter { it in queue.indices }.map { queue[it] }
        if (moving.isEmpty()) return
        val ids = moving.map { it.id }.toSet()
        val before = queue.take(minOf(maxOf(toOffset, 0), queue.size)).count { it.id !in ids }
        val rest = queue.filter { it.id !in ids }
        queueValue = rest.take(before) + moving + rest.drop(before)
        // The listener has put the queue in an order of their own: that is now the order to keep.
        if (isShuffled) shuffle = Shuffle.On(emptyList())
        prepareNext()
    }

    fun removeFromQueue(entryIds: Set<UUID>) {
        if (entryIds.isEmpty()) return
        val index = currentIndex
        val currentId = currentEntryId
        if (currentId != null && currentId in entryIds && index != null) {
            // The current song goes: carry on with the next that stays, or stop.
            val successor = queue.drop(index + 1).firstOrNull { it.id !in entryIds }
            if (successor != null) {
                queueValue = queue.filter { it.id !in entryIds }
                move(successor.id)
            } else {
                stop()
            }
            return
        }
        queueValue = queue.filter { it.id !in entryIds }
        prepareNext()
    }

    /** Empties "Up next", keeping what has played and the current song. */
    fun clearUpNext() {
        val index = currentIndex ?: return
        queueValue = queue.take(index + 1)
        continuation = null
        prepareNext()
    }

    fun toggleShuffle() {
        try {
            val index = currentIndex ?: -1
            val upcoming = queue.drop(index + 1)
            when (val mode = shuffle) {
                Shuffle.Off -> {
                    shuffle = Shuffle.On(upcoming.map { it.id })
                    if (upcoming.isNotEmpty()) queueValue = queue.take(index + 1) + upcoming.shuffled(random)
                }
                is Shuffle.On -> {
                    shuffle = Shuffle.Off
                    if (upcoming.isEmpty() || mode.orderBefore.isEmpty()) return
                    // Back to the order from before, for the entries that are still queued; newer ones keep their
                    // place after them.
                    val rank = HashMap<UUID, Int>()
                    mode.orderBefore.forEachIndexed { position, id -> rank.putIfAbsent(id, position) }
                    val restored = upcoming.withIndex()
                        .sortedWith(
                            compareBy<IndexedValue<QueueEntry>>({ rank[it.value.id] ?: Int.MAX_VALUE }, { it.index }),
                        )
                        .map { it.value }
                    queueValue = queue.take(index + 1) + restored
                }
            }
        } finally {
            prepareNext()
        }
    }

    fun cycleRepeatMode() {
        when (repeatMode) {
            RepeatMode.OFF -> setRepeatMode(RepeatMode.ALL)
            RepeatMode.ALL -> setRepeatMode(RepeatMode.ONE)
            RepeatMode.ONE -> setRepeatMode(RepeatMode.OFF)
        }
    }

    fun setRepeatMode(mode: RepeatMode) {
        update { copy(repeatMode = mode) }
        storage.putString(Keys.REPEAT_MODE, mode.rawValue)
        prepareNext()
    }

    // MARK: The current song's extras

    /** Rates the current song. Shows at once; put back if YouTube Music refuses. */
    fun setLike(newStatus: LikeStatus) {
        val index = currentIndex ?: return
        val entry = queue[index]
        val before = entry.song.likeStatus
        setRating(entry.id, newStatus)
        onChange?.invoke(this)
        scope.launch {
            try {
                api.rate(entry.song.videoId, newStatus)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // Refused: put the rating back on that entry, wherever in the queue it is now.
                val now = queue.firstOrNull { it.id == entry.id } ?: return@launch
                if (now.song.likeStatus != newStatus) return@launch
                setRating(entry.id, before)
                onChange?.invoke(this@PlayerStore)
            }
        }
    }

    private fun setRating(entryId: UUID, rating: LikeStatus?) {
        queueValue = queue.map { if (it.id == entryId) it.copy(song = it.song.copy(likeStatus = rating)) else it }
    }

    /** The current song's lyrics, or null when it has none. */
    suspend fun lyrics(): Lyrics? {
        val song = current ?: return null
        val browseId = extras(song).lyrics ?: return null
        return api.lyrics(browseId)
    }

    /** The shelves of the player's "Related" tab for the current song. */
    suspend fun related(): List<Shelf> {
        val song = current ?: return emptyList()
        val browseId = extras(song).related ?: return emptyList()
        return api.related(browseId)
    }

    /** Pauses after a while, or at the end of the song. Null turns the timer off. */
    fun setSleepTimer(timer: SleepTimer?) {
        sleepJob?.cancel()
        update { copy(sleepTimer = timer) }
        val at = timer as? SleepTimer.At ?: return
        sleepJob = scope.launch {
            delay(maxOf(0L, at.atMillis - time.nowMillis()))
            pause()
            update { copy(sleepTimer = null) }
        }
    }

    // MARK: PlaybackEngineDelegate

    override fun engineDidChange(engine: PlaybackEngine, state: EngineState) {
        when (state) {
            EngineState.Loading -> statusValue = PlaybackStatus.Loading
            EngineState.Playing -> statusValue = PlaybackStatus.Playing
            EngineState.Paused -> statusValue = PlaybackStatus.Paused
            EngineState.Buffering -> statusValue = PlaybackStatus.Buffering
            is EngineState.Failed -> {
                statusValue = PlaybackStatus.Failed(state.message)
                passOverIfUnplayable()
            }
        }
        if (status == PlaybackStatus.Playing && arrival is Arrival.Passing) arrival = Arrival.Passing(0)
        syncClock()
        if (status == PlaybackStatus.Playing || status == PlaybackStatus.Buffering || status == PlaybackStatus.Loading) {
            startTicker()
        } else {
            ticker?.cancel()
        }
        onChange?.invoke(this)
    }

    override fun engineDidAdvanceToPreparedSong(engine: PlaybackEngine) {
        val successor = successorForAutoAdvance() ?: return
        currentEntryIdValue = successor.id
        didChangeSong()
        if (sleepTimer == SleepTimer.EndOfSong) {
            pause()
            update { copy(sleepTimer = null) }
        }
    }

    override fun engineDidFinishSong(engine: PlaybackEngine) {
        if (sleepTimer == SleepTimer.EndOfSong) {
            update { copy(sleepTimer = null) }
            statusValue = PlaybackStatus.Paused
            syncClock()
            onChange?.invoke(this)
            return
        }
        val song = current
        val successor = successorForAutoAdvance()
        if (repeatMode == RepeatMode.ONE && song != null) {
            start(song, 0.0)
        } else if (successor != null) {
            move(successor.id)
        } else if (isAutoplayEnabled && (continuation != null || source?.kind != QueueSource.Kind.RADIO)) {
            fetchMore(thenAdvance = true)
        } else {
            statusValue = PlaybackStatus.Paused
            syncClock()
            onChange?.invoke(this)
        }
    }

    // MARK: Inside: moving through the queue

    private fun replaceQueue(entries: List<QueueEntry>, current: UUID, source: QueueSource?) {
        generation += 1
        queueValue = entries
        continuation = null
        shuffle = Shuffle.Off
        update { copy(source = source) }
        move(current, Arrival.Chosen)
    }

    /**
     * Makes an entry the current song and starts it. [next] says whether the listener picked it or the queue moved on
     * to it; passing over keeps count of failures in a row.
     */
    private fun move(entryId: UUID, next: Arrival = Arrival.Passing(0)) {
        val entry = queue.firstOrNull { it.id == entryId } ?: return
        skipJob?.cancel()
        val before = arrival
        arrival = if (before is Arrival.Passing && next is Arrival.Passing && status.isFailure) {
            Arrival.Passing(before.failuresInARow)
        } else {
            next
        }
        currentEntryIdValue = entryId
        start(entry.song, 0.0)
        didChangeSong()
    }

    /** The current song cannot be played. If the queue was passing through it, move on after a moment. */
    private fun passOverIfUnplayable() {
        val passing = arrival as? Arrival.Passing ?: return
        val index = currentIndex ?: return
        if (passing.failuresInARow + 1 >= MOST_SKIPS_IN_A_ROW || index + 1 >= queue.size) return
        arrival = Arrival.Passing(passing.failuresInARow + 1)
        val failed = currentEntryId
        skipJob?.cancel()
        skipJob = scope.launch {
            // Long enough to read what went wrong.
            delay(1200)
            val at = currentIndex ?: return@launch
            if (currentEntryId != failed || !status.isFailure || at + 1 >= queue.size) return@launch
            move(queue[at + 1].id)
        }
    }

    private fun start(song: Song, seconds: Double) {
        clock.update { ClockState(position = seconds, duration = song.duration ?: 0.0, bufferedFraction = 0.0) }
        statusValue = PlaybackStatus.Loading
        engine.load(song, autoplay = true, startAt = seconds)
    }

    private fun didChangeSong() {
        syncClock()
        prepareNext()
        val index = currentIndex
        if (index != null && index >= queue.size - 3) fetchMore(thenAdvance = false)
        onChange?.invoke(this)
    }

    /** The entry that follows by itself when the current song ends. */
    private fun successorForAutoAdvance(): QueueEntry? {
        val index = currentIndex ?: return null
        return when (repeatMode) {
            RepeatMode.ONE -> null
            RepeatMode.ALL -> if (index + 1 < queue.size) queue[index + 1] else queue.firstOrNull()
            RepeatMode.OFF -> if (index + 1 < queue.size) queue[index + 1] else null
        }
    }

    private fun prepareNext() {
        val successor = successorForAutoAdvance()
        engine.prepareNext(successor?.song)
        // And the few after that, for fetching ahead.
        val at = successor?.let { s -> queue.indexOfFirst { it.id == s.id } } ?: -1
        if (successor == null || at < 0) {
            engine.fetchAhead(emptyList())
            return
        }
        engine.fetchAhead(queue.drop(at + 1).take(3).map { it.song })
    }

    /** Loads more of the radio. At the end of a plain list, starts a radio from its last song when autoplay is on. */
    private fun fetchMore(thenAdvance: Boolean) {
        if (isFetchingMore) return
        val last = queue.lastOrNull() ?: return
        val token = continuation
        if (token == null && !(isAutoplayEnabled && thenAdvance)) return
        isFetchingMore = true
        val request = generation
        val known = queue.map { it.song.videoId }.toSet()
        scope.launch {
            try {
                var songs: List<Song> = emptyList()
                var nextToken: String? = null
                if (token != null) {
                    val more = attempt { api.watchQueueContinuation(token) }
                    songs = more?.songs ?: emptyList()
                    nextToken = more?.continuation
                } else {
                    val radio = attempt { api.watchQueue(WatchEndpoint(videoId = last.song.videoId)) }
                    if (radio != null) {
                        songs = radio.songs
                        nextToken = radio.continuation
                    }
                }
                if (request != generation) return@launch
                continuation = nextToken
                val fresh = songs.filter { it.isAvailable && it.videoId !in known }
                queueValue = queue + fresh.map { QueueEntry(song = it) }
                prepareNext()
                if (thenAdvance) {
                    val index = currentIndex
                    if (index != null && index + 1 < queue.size) {
                        move(queue[index + 1].id)
                    } else {
                        statusValue = PlaybackStatus.Paused
                        onChange?.invoke(this@PlayerStore)
                    }
                }
            } finally {
                isFetchingMore = false
            }
        }
    }

    private fun rememberExtras(videoId: String, queue: WatchQueue) {
        extras[videoId] = Extras(queue.lyricsBrowseId, queue.relatedBrowseId)
    }

    private suspend fun extras(song: Song): Extras {
        extras[song.videoId]?.let { return it }
        val result = api.watchQueue(WatchEndpoint(videoId = song.videoId))
        rememberExtras(song.videoId, result)
        return Extras(result.lyricsBrowseId, result.relatedBrowseId)
    }

    // MARK: Inside: time

    private fun syncClock() {
        val position = engine.position
        val duration = engine.duration
        val buffered = engine.bufferedFraction
        clock.update {
            it.copy(position = position, duration = if (duration > 0) duration else it.duration, bufferedFraction = buffered)
        }
        val format = engine.formatDescription
        if (format != audioFormat) update { copy(audioFormat = format) }
        val output = if (format == null) null else engine.outputDescription
        if (output != audioOutput) update { copy(audioOutput = output) }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(100)
                syncClock()
            }
        }
    }

    private companion object {
        /** After this many unplayable songs in a row the player stops passing over them. */
        const val MOST_SKIPS_IN_A_ROW = 3
    }
}

/** Runs an API call whose failure the caller meets with "nothing came back" (Swift's `try?`); cancellation still cancels. */
internal suspend fun <T> attempt(call: suspend () -> T): T? =
    try {
        call()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        null
    }
