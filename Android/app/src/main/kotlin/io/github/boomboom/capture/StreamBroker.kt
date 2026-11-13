package io.github.boomboom.capture

import android.os.SystemClock
import io.github.boomboom.engine.DemuxException
import io.github.boomboom.engine.Demuxer
import io.github.boomboom.engine.Track
import io.github.boomboom.logic.DownloadEvent
import io.github.boomboom.logic.KeyValueStore
import io.github.boomboom.runtime.BoomTrace
import io.github.boomboom.web.AudioOffer
import io.github.boomboom.web.HiddenBrowser
import io.github.boomboom.web.HiddenBrowser.TabPhase
import io.github.boomboom.web.TapEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Gets songs' audio out of the hidden tab: a port of the iPhone app's `StreamBroker`.
 *
 * For each song it tells the page's player to load it, takes the audio the player appends to the browser's media
 * pipeline (passed on by `boom-tap.js`), demuxes it into the song's [Track], and keeps moving the player's paused
 * playhead forward so the whole song arrives in seconds rather than at listening speed. The page fetches one song at
 * a time; which one is decided afresh whenever something changes (see [mostUrgent]). Finished songs go to the
 * on-disk cache, or to the downloads when the listener asked to keep them.
 *
 * Everything here runs on the main thread.
 */
class StreamBroker(
    private val browser: HiddenBrowser,
    private val cache: AudioCache,
    private val downloads: DownloadLibrary,
    private val storage: KeyValueStore,
    private val scope: CoroutineScope,
) {
    /** How soon a song is needed: being played, next in line, or further up the queue. */
    enum class Purpose { PLAYING, UPCOMING, LATER }

    /** Where one song's fetch stands. A job is in exactly one of these; [FetchJob.move] is the only way to change. */
    private sealed class Phase {
        /** Not asked of the page yet: the tab is loading, or its player has not appeared. */
        data class WaitingForTab(val since: Long) : Phase()

        /** The page's player was told to load the song; no audio buffer has opened yet. */
        data class Requested(val at: Long) : Phase()

        /**
         * The tab had no player, so the song's own page is being opened in it. The song is claimed in the new page as
         * soon as the script is there.
         */
        data class OpeningPage(val at: Long) : Phase()

        /** Audio is arriving. */
        class Receiving(val state: ReceivingState) : Phase()
    }

    /** What has been tried for a fetch whose audio stopped arriving. Each step is tried once, in this order. */
    private enum class Recovery {
        NONE,

        /** The page's player was told to load the song again. */
        ASKED_AGAIN,

        /** The song's own page was opened afresh in the tab. */
        REOPENED_PAGE,
    }

    /**
     * A new buffer's stream is read into a scratch store until its header is in, to check that it is the song asked
     * for (an advert or another video has a different length) before anything of it reaches the song's own store.
     */
    private class Probe(val demuxer: Demuxer, val track: Track = Track()) {
        val pieces = ArrayList<ByteArray>()

        fun close() {
            demuxer.close()
            track.close()
        }
    }

    private class ReceivingState(val bufferId: Int, val mimeType: String, var held: Int, now: Long) {
        /** Feeds the song's own store, once the stream is known to be the song. */
        var demuxer: Demuxer? = null
        var probe: Probe? = null

        /** When the number of packets in hand last went up. */
        var grewAt = now

        /** The last move of the page's playhead: not repeated for the same place within half a second. */
        var lastNudgeAt = 0L
        var lastNudgeTarget = Double.NaN

        /** How many pieces the page has passed on for this buffer, and their bytes: for the trace. */
        var pieces = 0
        var bytes = 0L

        fun close() {
            demuxer?.close()
            demuxer = null
            probe?.close()
            probe = null
        }
    }

    private class FetchJob(val captured: CapturedTrack, var playhead: Double) {
        val token: String = UUID.randomUUID().toString()
        val startedAt: Long = now()
        var phase: Phase = Phase.WaitingForTab(now())
            private set

        /** The song's length as the player's answer states it. */
        var expectedSeconds: Double? = null

        /** Buffers whose stream turned out not to be the song (wrong length): their bytes are ignored. */
        val rejected = HashSet<Int>()

        /** The listener jumped: move the page's playhead at the next look, without waiting for a lull. */
        var needsNudge: Boolean = playhead > 5

        /** The song's own page has been opened once for this job; it is not tried twice. */
        var hasOpenedPage = false

        /** How far the job has gone in getting a stalled fetch moving again. */
        var recovery = Recovery.NONE

        /** The job was let go of; its hold on the song is given back once. */
        var isReleased = false

        fun move(next: Phase) {
            val before = phase
            if (before is Phase.Receiving && (next !is Phase.Receiving || next.state !== before.state)) before.state.close()
            phase = next
        }

        val receiving: ReceivingState? get() = (phase as? Phase.Receiving)?.state
    }

    /**
     * The songs the listener asked to keep that are not kept yet, in the order to fetch them. They are fetched only
     * when nothing the listener is hearing or about to hear needs the page.
     */
    private val downloadIds = ArrayList<String>()

    /** A short rest after each finished download, so a long playlist does not hammer the service. */
    private var downloadsRestUntil = 0L
    private var lastDownloadProgress: Pair<String, Double> = "" to -1.0

    /** False while downloads must wait (mobile data, and the listener has not allowed it). */
    var allowsDownloads: Boolean = true
        set(value) {
            if (value == field) return
            field = value
            schedule()
        }

    /** What happens to the songs being kept. */
    var onDownload: ((DownloadEvent) -> Unit)? = null

    /** Called when a fetch starts or the last one ends (the service and the wake locks follow it). */
    var onFetchingChange: (() -> Unit)? = null

    /** Songs in memory, most recently asked for last. */
    private val tracks = HashMap<String, CapturedTrack>()
    private val recency = ArrayList<String>()

    // What the engine has said it needs.
    private var playingId: String? = null
    private var upcomingId: String? = null
    private var laterIds: List<String> = emptyList()

    /** Where the listener is in the playing song. */
    private var playhead: Pair<String, Double>? = null
    private var active: FetchJob? = null
        set(value) {
            val changed = (field == null) != (value == null)
            field = value
            if (changed) onFetchingChange?.invoke()
        }
    private var timer: Job? = null
    private var didWarmUp = false

    /** True while the page is fetching a song. */
    val isFetching: Boolean get() = active != null

    /** The song being fetched, for the debug screen and the trace. */
    val fetchingVideoId: String? get() = active?.captured?.videoId

    /**
     * The highest bitrate this account has been given so far, in bits per second (0: not known yet). A Premium account
     * gets about twice what a free one does.
     */
    private var bestBitrate: Int
        get() = storage.getInt(BEST_BITRATE_KEY) ?: 0
        set(value) = storage.putInt(BEST_BITRATE_KEY, value)

    /** The sign-in the last stream was fetched under: whether the tab was signed in, and as which profile. */
    private var lastSignIn: Pair<Boolean, String>? = null

    init {
        browser.onEvent = { event -> handle(event) }
        browser.onPhaseChange = { tabDidChange() }
    }

    /**
     * True when a kept copy of a song is clearly poorer than what the account gets now (it was kept under a free
     * account and this one has Premium): the song is then fetched afresh, and the new copy replaces the old. Only
     * while the tab is up, though: without a connection the kept copy is the one that plays.
     */
    private fun isOutclassed(format: AudioOffer?): Boolean {
        if (format == null || format.bitrate <= 0 || bestBitrate <= 0 || browser.pageLoadedAt == null) return false
        return format.bitrate < 0.75 * bestBitrate
    }

    // MARK: What the engine asks for

    /** The song's audio: from memory, from the downloads or the cache, or freshly queued for fetching. */
    fun track(videoId: String, purpose: Purpose): CapturedTrack {
        val known = tracks[videoId]
        val captured: CapturedTrack = if (known != null && known.failure == null) {
            known
        } else {
            val kept = downloads.load(videoId)
            if (kept != null) {
                // The listener's own copy: always used, with or without a connection.
                fromDisk(videoId, kept)
            } else {
                val cached = cache.load(videoId)
                if (cached != null && !isOutclassed(cached.details?.format)) {
                    fromDisk(videoId, cached)
                } else {
                    cached?.track?.close()
                    CapturedTrack(videoId)
                }
            }
        }
        remember(captured)

        when (purpose) {
            Purpose.PLAYING -> {
                playingId = videoId
                if (upcomingId == videoId) upcomingId = null
                laterIds = laterIds - videoId
                if (playhead?.first != videoId) playhead = videoId to 0.0
            }
            Purpose.UPCOMING -> {
                upcomingId = videoId
                laterIds = laterIds - videoId
            }
            Purpose.LATER -> {
                if (videoId != playingId && videoId != upcomingId && videoId !in laterIds) laterIds = laterIds + videoId
            }
        }
        forgetOldSongs()
        schedule()
        return captured
    }

    private fun fromDisk(videoId: String, kept: KeptTrack): CapturedTrack =
        CapturedTrack(videoId, kept.track, isFromCache = true).apply {
            loudnessDb = kept.details?.loudnessDb
            format = kept.details?.format
        }

    /** Nothing follows the playing song any more. */
    fun clearUpcoming() {
        upcomingId = null
        schedule()
    }

    /** The songs further up the queue, nearest first; any others queued for fetching ahead are dropped. */
    fun keepLater(videoIds: List<String>) {
        laterIds = videoIds.filter { it in laterIds }
        schedule()
    }

    /** Where the listener is in the playing song, so that fetching serves that place first. */
    fun setPlayhead(seconds: Double, videoId: String) {
        val jumped = playhead?.let { it.first != videoId || abs(it.second - seconds) > 5 } ?: true
        playhead = videoId to seconds
        val job = active
        if (job != null && job.captured.videoId == videoId) {
            job.playhead = seconds
            if (jumped) job.needsNudge = true
        }
        // A jump can change which song matters most (into, or out of, a part that is not in hand).
        if (jumped) schedule()
    }

    /** Puts a song in the memory (taking a hold on it), replacing any other copy, most recent last. */
    private fun remember(captured: CapturedTrack) {
        val before = tracks[captured.videoId]
        if (before !== captured) {
            captured.retain()
            tracks[captured.videoId] = captured
            before?.release()
        }
        recency.remove(captured.videoId)
        recency.add(captured.videoId)
    }

    private fun forget(videoId: String) {
        recency.remove(videoId)
        tracks.remove(videoId)?.release()
    }

    private fun forgetOldSongs() {
        val needed = (listOfNotNull(playingId, upcomingId, active?.captured?.videoId, downloadIds.firstOrNull()) + laterIds).toSet()
        while (recency.size > SONGS_KEPT_IN_MEMORY) {
            val oldest = recency.firstOrNull { it !in needed } ?: break
            forget(oldest)
        }
    }

    // MARK: Choosing what to fetch

    private fun needsFetching(videoId: String): Boolean {
        val captured = tracks[videoId] ?: return false
        return captured.failure == null && !captured.track.isComplete
    }

    /** True when audio is missing at or after the listener's place in the song (rather than only behind it). */
    private fun isMissingAhead(videoId: String): Boolean {
        val track = tracks[videoId]?.track ?: return false
        if (track.info == null) return true
        val seconds = playhead?.takeIf { it.first == videoId }?.second ?: 0.0
        val from = track.packetIndexAt(seconds)
        val gap = track.firstGap(from) ?: return false
        return gap >= from
    }

    /**
     * The song the page should be fetching now:
     *   1. the playing song, while audio from the listener's place onwards is missing;
     *   2. the next song (its opening is needed before the parts of the playing song the listener skipped);
     *   3. the rest of the playing song;
     *   4. songs further up the queue, nearest first;
     *   5. songs the listener asked to keep.
     */
    private fun mostUrgent(): String? {
        playingId?.let { if (needsFetching(it) && isMissingAhead(it)) return it }
        upcomingId?.let { if (needsFetching(it)) return it }
        playingId?.let { if (needsFetching(it)) return it }
        laterIds.firstOrNull(::needsFetching)?.let { return it }
        return downloadToFetch()
    }

    // MARK: Downloads

    /** Queues songs to be kept, in this order. Ones already kept are reported as finished at once. */
    fun startDownloads(videoIds: List<String>) {
        for (videoId in videoIds) {
            if (videoId in downloadIds) continue
            if (downloads.contains(videoId)) reportKept(videoId) else downloadIds += videoId
        }
        schedule()
    }

    /** Stops fetching these for keeping. What is already kept stays. */
    fun cancelDownloads(videoIds: List<String>) {
        val dropped = videoIds.toSet()
        downloadIds.removeAll { it in dropped }
        // A fetch that only the download wanted is given up; one the listener is hearing carries on.
        val job = active
        if (job != null && job.captured.videoId in dropped) schedule()
    }

    /** Removes kept songs from the phone (and stops fetching them). */
    fun removeDownloads(videoIds: List<String>) {
        cancelDownloads(videoIds)
        downloads.remove(videoIds)
    }

    /** True while songs are waiting to be fetched for keeping. */
    val hasDownloadsWaiting: Boolean get() = downloadIds.isNotEmpty()

    /**
     * The next song to fetch for keeping, once it is that song's turn; null when none is due. On the way, songs at the
     * head of the queue that need no fetching are dealt with: ones in hand are written out, failed ones reported.
     */
    private fun downloadToFetch(): String? {
        if (!allowsDownloads) return null
        while (downloadIds.isNotEmpty()) {
            val head = downloadIds.first()
            if (downloads.contains(head)) {
                downloadIds.removeAt(0)
                reportKept(head)
                continue
            }
            val captured = tracks[head] ?: run {
                val cached = cache.load(head)
                val made = if (cached != null && !isOutclassed(cached.details?.format)) {
                    // Already fetched for listening, at the quality the account gets: keep that copy.
                    fromDisk(head, cached)
                } else {
                    cached?.track?.close()
                    CapturedTrack(head)
                }
                remember(made)
                made
            }
            val failure = captured.failure
            if (failure != null) {
                downloadIds.removeAt(0)
                forget(head)
                onDownload?.invoke(DownloadEvent.Failed(head, failure))
                continue
            }
            if (captured.track.isComplete) {
                downloadIds.removeAt(0)
                keep(captured)
                continue
            }
            return if (now() >= downloadsRestUntil) head else null
        }
        return null
    }

    /** Writes a complete song to the listener's downloads and reports it. */
    private fun keep(captured: CapturedTrack) {
        val videoId = captured.videoId
        val format = captured.format
        val details = CachedTrackDetails(captured.loudnessDb, format, System.currentTimeMillis())
        downloads.store(captured, details) { bytes ->
            if (bytes != null) {
                onDownload?.invoke(DownloadEvent.Finished(videoId, bytes, quality(format)))
            } else {
                onDownload?.invoke(DownloadEvent.Failed(videoId, "There is not enough room on this phone."))
            }
        }
        downloadsRestUntil = now() + 1500
    }

    private fun reportKept(videoId: String) {
        onDownload?.invoke(DownloadEvent.Finished(videoId, downloads.size(videoId), quality(downloads.details(videoId)?.format)))
    }

    private fun schedule() {
        // A fetch that has just brought in its song's last missing packet is finished (and kept on disk) before
        // anything else is decided: after that its song no longer counts as wanted.
        active?.let { job ->
            if (job.captured.track.isComplete) {
                finish(job)
                return
            }
        }
        val wanted = mostUrgent()
        active?.let { job ->
            if (job.captured.videoId == wanted) {
                advance(job)
                return
            }
            BoomTrace.note("capture of ${job.captured.videoId} set aside for ${wanted ?: "nothing"}")
            release(job)
        }
        val captured = wanted?.let { tracks[it] }
        if (captured == null) {
            // Between two downloads there is a short rest: the timer stays on to start the next one.
            if (downloadIds.isEmpty() || !allowsDownloads) stopTimer() else startTimer()
            return
        }
        captured.retain()
        val job = FetchJob(captured, playhead?.takeIf { it.first == wanted }?.second ?: 0.0)
        active = job
        BoomTrace.note("capture start $wanted tab=${browser.phase.label}")
        startTimer()
        advance(job)
    }

    // MARK: A job's steps

    /** Takes the job's next step if its phase allows one now. Safe to call at any time. */
    private fun advance(job: FetchJob) {
        val now = now()
        when (val phase = job.phase) {
            is Phase.WaitingForTab -> when (val tab = browser.phase) {
                is TabPhase.Failed -> fail(job, tab.message)
                is TabPhase.Loaded ->
                    if (tab.hasPlayer) {
                        request(job)
                    } else if (now - tab.atMillis > if (browser.isOnSongPage) 10_000 else 1200) {
                        // A page without a player: the site makes one when a page is opened onto a song. A song's
                        // page (the warm-up's) makes its own a moment after it loads, slower on a slow phone: it is
                        // given longer before the job's own page replaces it.
                        openPage(job)
                    }
                TabPhase.Loading, TabPhase.SwitchingPage -> Unit
            }
            is Phase.Requested -> {
                // No audio buffer has opened. Try the song's own page once, then give up.
                val waited = now - phase.at
                if (waited > 14_000 && !job.hasOpenedPage) {
                    openPage(job)
                } else if (waited > 30_000) {
                    fail(job, "Couldn't load this song. Check your connection.")
                }
            }
            is Phase.OpeningPage -> if (now - phase.at > 30_000) fail(job, "Couldn't load this song. Check your connection.")
            is Phase.Receiving -> pull(job, now)
        }
    }

    /** Tells the page's player to load the song. */
    private fun request(job: FetchJob) {
        job.move(Phase.Requested(now()))
        val videoId = job.captured.videoId
        storage.putString(LAST_SONG_KEY, videoId)
        scope.launch {
            val answer = browser.call("window.__boom.load(${quoted(job.token)}, ${quoted(videoId)})")
            if (active !== job || job.phase !is Phase.Requested) return@launch
            if (answer != "ok" && !job.hasOpenedPage) openPage(job)
        }
    }

    /** Opens the song's own page in the tab: the way in when the page has no player. */
    private fun openPage(job: FetchJob) {
        if (job.hasOpenedPage) return
        job.hasOpenedPage = true
        job.move(Phase.OpeningPage(now()))
        storage.putString(LAST_SONG_KEY, job.captured.videoId)
        BoomTrace.note("capture opens the song's page ${job.captured.videoId}")
        browser.open("https://music.youtube.com/watch?v=${job.captured.videoId}")
    }

    /** Claims the job's song in a page that was opened onto it. */
    private fun claim(job: FetchJob) {
        scope.launch { browser.call("window.__boom.adopt(${quoted(job.token)}, ${quoted(job.captured.videoId)})") }
    }

    /** Stops the page fetching for a job and lets the job go. What it captured stays in the song's store. */
    private fun release(job: FetchJob) {
        if (job.isReleased) return
        job.isReleased = true
        val token = job.token
        scope.launch { browser.call("window.__boom.cancel(${quoted(token)})") }
        job.move(Phase.WaitingForTab(now()))
        job.captured.release()
        if (active === job) active = null
    }

    private fun finish(job: FetchJob) {
        val captured = job.captured
        val seconds = (now() - job.startedAt) / 1000.0
        val format = captured.format?.let { "${it.codecName} itag ${it.itag} ${it.bitrate / 1000}k" } ?: "-"
        BoomTrace.note(
            String.format(Locale.ROOT, "capture done %s bytes=%d packets=%d in %.1f s format=%s bridge: %s",
                captured.videoId, captured.track.heldBytes, captured.track.heldPackets, seconds, format, browser.bridgeSummary()),
        )
        val index = downloadIds.indexOf(captured.videoId)
        if (index >= 0) {
            downloadIds.removeAt(index)
            keep(captured)
        } else {
            cache.store(captured, CachedTrackDetails(captured.loudnessDb, captured.format, System.currentTimeMillis()))
        }
        release(job)
        schedule()
    }

    private fun fail(job: FetchJob, message: String) {
        BoomTrace.note("capture FAILED ${job.captured.videoId}: $message")
        job.captured.failure = message
        job.captured.onUpdate?.invoke()
        val index = downloadIds.indexOf(job.captured.videoId)
        if (index >= 0) {
            downloadIds.removeAt(index)
            onDownload?.invoke(DownloadEvent.Failed(job.captured.videoId, message))
        }
        release(job)
        schedule()
    }

    /** The tab started loading again, finished loading, got its player, or failed. */
    private fun tabDidChange() {
        // Another account (or none) may be given a different quality: what the last one got says nothing now.
        val signIn = browser.isSignedIn to browser.activeProfileId
        if (lastSignIn != signIn) {
            if (lastSignIn != null) bestBitrate = 0
            lastSignIn = signIn
        }
        active?.let { job ->
            val page = job.phase
            if (browser.phase == TabPhase.Loading &&
                (page is Phase.Requested || page is Phase.Receiving || page is Phase.OpeningPage)
            ) {
                // The page under the fetch went away (a reload, or a new WebView after an account switch or a lost
                // renderer). Ask again when the tab is back.
                job.hasOpenedPage = false
                job.move(Phase.WaitingForTab(now()))
            }
        }
        schedule()
        val tab = browser.phase
        if (active == null && tab is TabPhase.Loaded && !tab.hasPlayer && !didWarmUp) {
            scope.launch {
                delay(2000)
                warmUp()
            }
        }
    }

    /**
     * If the start page came without a player, the first song would pay for a page load. Opening the page of the song
     * played last (without playing it: the script refuses that while nothing is asked for) gets a player ready while
     * the listener is still choosing.
     */
    private fun warmUp() {
        val tab = browser.phase
        if (didWarmUp || active != null || tab !is TabPhase.Loaded || tab.hasPlayer) return
        val last = storage.getString(LAST_SONG_KEY) ?: return
        didWarmUp = true
        BoomTrace.note("tab warms up on the last song's page")
        browser.open("https://music.youtube.com/watch?v=${safeFileName(last)}")
    }

    // MARK: What the page reports

    private fun handle(event: TapEvent) {
        when (event) {
            is TapEvent.Hello, is TapEvent.Ready -> {
                // A fresh page, or its player. If it was opened for the job's song, claim that song in it.
                active?.let { if (it.phase is Phase.OpeningPage) claim(it) }
                if (event is TapEvent.Ready) schedule()
            }
            is TapEvent.Player -> {
                val job = active ?: return
                if (!isAbout(job, event.token, event.videoId)) {
                    // The page's player has turned to another song by itself (a signed-in page brings back the last
                    // song of its own session a few seconds after it loads). Nothing more will come for the job's
                    // song: ask for it again at once instead of waiting for the stall to show. Once only; if the page
                    // insists, the slower recovery takes over.
                    val other = event.videoId
                    if (job.phase is Phase.Receiving && job.recovery == Recovery.NONE && other != null &&
                        other != job.captured.videoId && !job.captured.track.isComplete
                    ) {
                        BoomTrace.note("the page turned to $other during ${job.captured.videoId}: asking the player again")
                        job.recovery = Recovery.ASKED_AGAIN
                        job.needsNudge = true
                        request(job)
                    }
                    return
                }
                if (event.status != "OK") {
                    fail(job, listenerText(event.status, event.reason))
                } else if (event.isLive) {
                    fail(job, "Live streams can't be played here.")
                } else {
                    job.expectedSeconds = event.seconds
                    event.seconds?.let { if (it > 0) job.captured.track.setDuration(it) }
                    job.captured.loudnessDb = event.loudnessDb
                    job.captured.format = event.chosen
                    event.chosen?.let { if (it.bitrate > bestBitrate) bestBitrate = it.bitrate }
                    job.captured.onUpdate?.invoke()
                }
            }
            is TapEvent.Buffer -> {
                val job = active ?: return
                if (event.token != job.token) return
                val demuxer = Demuxer.forMimeType(event.mimeType)
                if (demuxer == null) {
                    fail(job, "This song's audio format isn't supported.")
                    return
                }
                BoomTrace.note(
                    String.format(Locale.ROOT, "capture buffer %s %s after %.2f s", job.captured.videoId, event.mimeType,
                        (now() - job.startedAt) / 1000.0),
                )
                // A second buffer for the same song (the player started over) replaces the first.
                val state = ReceivingState(event.id, event.mimeType, job.captured.track.heldPackets, now())
                state.probe = Probe(demuxer)
                job.move(Phase.Receiving(state))
            }
            is TapEvent.Append -> {
                val job = active ?: return
                val state = job.receiving ?: return
                if (event.token != job.token || event.id != state.bufferId || event.id in job.rejected) return
                if (state.pieces == 0) {
                    val head = event.data.take(24).joinToString("") { "%02x".format(it) }
                    BoomTrace.note("capture first piece ${job.captured.videoId}: ${event.data.size} bytes, starts $head")
                }
                state.pieces += 1
                state.bytes += event.data.size
                take(job, state, event.data)
            }
            is TapEvent.Restart -> {
                val job = active ?: return
                val state = job.receiving ?: return
                if (event.token != job.token || event.id != state.bufferId) return
                state.demuxer?.reset()
                state.probe?.let {
                    // Without a complete header, what was held is a cut-off start.
                    it.demuxer.reset()
                    it.pieces.clear()
                }
            }
            is TapEvent.End -> {
                val job = active ?: return
                val state = job.receiving ?: return
                if (event.token != job.token || event.id != state.bufferId || state.demuxer == null) return
                // The player appended the stream's last segment: the last packet in hand is the song's last.
                job.captured.track.seal()
            }
            is TapEvent.Unsupported -> {
                BoomTrace.note("page cannot capture: ${event.reason}")
                active?.let { fail(it, "This phone's browser engine can't play this.") }
            }
            is TapEvent.ScriptError -> BoomTrace.note("script error in ${event.where}: ${event.message}")
        }
    }

    /**
     * True when a player answer belongs to the job: it carries the job's token, or it is about the job's song in a page
     * that was opened onto that song and has not been claimed yet.
     */
    private fun isAbout(job: FetchJob, token: String?, videoId: String?): Boolean {
        if (token == job.token) return true
        if (job.phase is Phase.OpeningPage) return videoId == job.captured.videoId
        return false
    }

    /** One piece of the stream: into the probe while the stream is unknown, into the song's store after. */
    private fun take(job: FetchJob, state: ReceivingState, data: ByteArray) {
        state.demuxer?.let {
            feed(it, job.captured.track, data)
            return
        }
        val probe = state.probe ?: return
        probe.pieces += data
        feed(probe.demuxer, probe.track, data)
        val info = probe.track.info ?: return
        val stated = info.duration
        val expected = job.expectedSeconds
        if (stated != null && expected != null && abs(stated - expected) > 5) {
            BoomTrace.note("buffer ${state.bufferId} is $stated s long, the song $expected s: ignored")
            job.rejected += state.bufferId
            state.close()
            return
        }
        BoomTrace.note("capture track ${job.captured.videoId}: ${info.codec} ${info.sampleRate.roundToInt()} Hz ${info.channels} ch, ${stated ?: "?"} s")
        // The stream is the song: the same bytes go into the song's own store.
        val demuxer = Demuxer.forMimeType(state.mimeType) ?: return
        val pieces = ArrayList(probe.pieces)
        probe.close()
        state.probe = null
        state.demuxer = demuxer
        for (piece in pieces) feed(demuxer, job.captured.track, piece)
        if (expected != null && expected > 0) job.captured.track.setDuration(expected)
    }

    private fun feed(demuxer: Demuxer, track: Track, data: ByteArray) {
        try {
            demuxer.feed(data, track)
        } catch (failure: DemuxException) {
            // The pieces after a jump may not line up; the next segment start puts the reader right again.
            BoomTrace.note("demux error ${failure.kind}: ${failure.message}")
            demuxer.reset()
        }
    }

    // MARK: Pulling the whole song in

    private fun startTimer() {
        if (timer != null) return
        timer = scope.launch {
            while (isActive) {
                delay(250)
                // Looking again may show that another song has become more urgent, or take the job's next step.
                schedule()
            }
        }
    }

    private fun stopTimer() {
        timer?.cancel()
        timer = null
    }

    /**
     * While receiving: notes growth, finishes when the song is whole, gives up on a long stall, and otherwise keeps the
     * page fetching by moving its playhead.
     */
    private fun pull(job: FetchJob, now: Long) {
        val state = job.receiving ?: return
        val track = job.captured.track
        if (track.info == null) {
            if (now - state.grewAt > 30_000) {
                BoomTrace.note("capture of ${job.captured.videoId} has no stream header after ${state.pieces} pieces, ${state.bytes} bytes")
                fail(job, "Couldn't load this song. Check your connection.")
            }
            return
        }
        if (track.isComplete) {
            finish(job)
            return
        }
        val held = track.heldPackets
        val grew = held != state.held
        if (grew) {
            state.held = held
            state.grewAt = now
            if (job.captured.videoId in downloadIds) {
                val fraction = (track.coveredFraction * 50).roundToInt() / 50.0
                if (lastDownloadProgress != job.captured.videoId to fraction) {
                    lastDownloadProgress = job.captured.videoId to fraction
                    onDownload?.invoke(DownloadEvent.Progress(job.captured.videoId, fraction))
                }
            }
        }
        val idle = now - state.grewAt
        if (grew) job.recovery = Recovery.NONE
        // Nothing has arrived for a while although the page was nudged: its player is stuck. Tell it to load the song
        // again; if that does not help, open the song's page afresh; only then give up. What is already in hand is
        // kept throughout, and fetching resumes at the first missing audio.
        if (idle > 8_000) {
            when {
                job.recovery == Recovery.NONE -> {
                    BoomTrace.note("capture of ${job.captured.videoId} stalled at $held packets: asking the player again")
                    job.recovery = Recovery.ASKED_AGAIN
                    job.needsNudge = true
                    request(job)
                    return
                }
                job.recovery == Recovery.ASKED_AGAIN && !job.hasOpenedPage -> {
                    BoomTrace.note("capture of ${job.captured.videoId} still stalled: reopening its page")
                    job.recovery = Recovery.REOPENED_PAGE
                    job.needsNudge = true
                    openPage(job)
                    return
                }
                idle > 25_000 -> {
                    fail(job, "This song stopped loading. Check your connection.")
                    return
                }
            }
        }
        // The player fetches only so far ahead of its playhead. When it has stopped (a lull), or the listener has
        // jumped, move its playhead to the first audio missing from the listener's place on (wrapping round to
        // earlier gaps), and it fetches on from there.
        if (!(job.needsNudge || (!grew && idle > 600))) return
        val gap = track.firstGap(track.packetIndexAt(job.playhead)) ?: return
        job.needsNudge = false
        val target = maxOf(0.0, track.timeOfPacket(gap) - 0.4)
        if (state.lastNudgeTarget == target && now - state.lastNudgeAt < 500) return
        state.lastNudgeAt = now
        state.lastNudgeTarget = target
        val videoId = job.captured.videoId
        val isQuiet = idle > 3_000
        scope.launch {
            val answer = browser.call("window.__boom.pump(${quoted(job.token)}, ${String.format(Locale.ROOT, "%.3f", target)})")
            // While audio flows the answer is of no interest; when it has stopped, it says what the page's player is
            // doing.
            if (isQuiet && answer is JSONObject) {
                val text = answer.keys().asSequence().sorted().joinToString(" ") { "$it=${answer.opt(it)}" }
                BoomTrace.note("page while $videoId is quiet: $text")
            }
        }
    }

    companion object {
        private const val SONGS_KEPT_IN_MEMORY = 9
        private const val LAST_SONG_KEY = "capture.lastVideoId"
        private const val BEST_BITRATE_KEY = "capture.bestBitrate"

        private fun now(): Long = SystemClock.elapsedRealtime()

        /** A string as a JavaScript literal. */
        private fun quoted(text: String): String = JSONObject.quote(text)

        /** "Opus 270 kbps", for the downloads list. */
        private fun quality(format: AudioOffer?): String? = format?.let { "${it.codecName} ${it.bitrate / 1000} kbps" }

        private fun listenerText(status: String?, reason: String?): String = when (status) {
            "LOGIN_REQUIRED" -> "Sign in to play this song."
            "UNPLAYABLE", "ERROR" -> reason?.let { "This song can't be played: $it" } ?: "This song can't be played."
            "AGE_VERIFICATION_REQUIRED", "CONTENT_CHECK_REQUIRED" -> "Sign in to confirm your age to play this."
            else -> reason ?: "This song can't be played right now."
        }
    }
}
