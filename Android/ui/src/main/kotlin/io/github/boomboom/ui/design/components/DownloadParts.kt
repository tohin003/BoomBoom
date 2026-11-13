package io.github.boomboom.ui.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.outlined.Downloading
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.boomboom.logic.DownloadCollection
import io.github.boomboom.logic.DownloadItem
import io.github.boomboom.logic.DownloadStore
import io.github.boomboom.logic.DownloadsState
import io.github.boomboom.models.AlbumPage
import io.github.boomboom.models.MusicAPI
import io.github.boomboom.models.MusicAPIError
import io.github.boomboom.models.PlaylistPage
import io.github.boomboom.models.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

// The small pieces every screen uses for downloads: the sign of a song's state, the sizes in words, and the collector
// that loads a whole album or playlist before handing it to DownloadStore.

/** Sizes as the listener reads them: "6.4 MB", "1.24 GB" (decimal units, as phones show file sizes). */
object DownloadSize {
    fun text(bytes: Long): String {
        if (bytes < 1000) return if (bytes == 1L) "1 byte" else "$bytes bytes"
        val units = listOf("KB" to 0, "MB" to 1, "GB" to 2, "TB" to 2)
        var value = bytes / 1000.0
        var unit = 0
        while (value >= 1000 && unit < units.size - 1) {
            value /= 1000
            unit += 1
        }
        val (name, decimals) = units[unit]
        val number = String.format(Locale.UK, "%.${decimals}f", value).let {
            if (it.contains('.')) it.trimEnd('0').trimEnd('.') else it
        }
        return "$number $name"
    }
}

/** "40 songs", "1 song". */
fun songsText(count: Int): String = if (count == 1) "1 song" else "$count songs"

/**
 * An album's or playlist's download as one state: arriving while any song is still to come, failed when some could
 * not be fetched, downloaded when all are in. Null when it was not downloaded as a whole.
 */
fun DownloadsState.collectionState(id: String): DownloadItem.State? {
    val key = DownloadCollector.key(id)
    if (collections.none { it.id == key }) return null
    val songs = songsOf(key)
    if (songs.isEmpty()) return null
    var done = 0
    var failed = 0
    for (song in songs) {
        when (song.state) {
            DownloadItem.State.Downloaded -> done += 1
            is DownloadItem.State.Failed -> failed += 1
            else -> Unit
        }
    }
    if (done == songs.size) return DownloadItem.State.Downloaded
    if (done + failed == songs.size) return DownloadItem.State.Failed("$failed couldn't be downloaded")
    return DownloadItem.State.Downloading(done.toDouble() / songs.size)
}

/** A ring that fills as a download arrives. */
@Composable
fun DownloadRing(fraction: Double, modifier: Modifier = Modifier, lineWidth: Dp = 2.dp) {
    val shown by animateFloatAsState(max(0.03f, min(1f, fraction.toFloat())), tween(250), label = "ring")
    Canvas(modifier) {
        val stroke = lineWidth.toPx()
        val inset = stroke / 2
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawArc(
            BoomColor.textTertiary.copy(alpha = 0.3f), 0f, 360f, false, Offset(inset, inset), arcSize,
            style = Stroke(stroke),
        )
        drawArc(
            BoomColor.accent, -90f, 360f * shown, false, Offset(inset, inset), arcSize,
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
    }
}

/**
 * The small sign of a song's download beside its title: arriving (a ring), waiting, downloaded (a filled mark in the
 * accent) or failed. Nothing when the song was never asked for.
 */
@Composable
fun DownloadBadge(state: DownloadItem.State?, modifier: Modifier = Modifier, size: Dp = 14.dp) {
    if (state == null) return
    Box(
        modifier
            .size(size + 1.dp)
            .testTag("download.badge")
            .semantics { contentDescription = downloadWords(state) },
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            DownloadItem.State.Waiting -> Icon(
                Icons.Outlined.Downloading, null, tint = BoomColor.textTertiary, modifier = Modifier.size(size),
            )
            is DownloadItem.State.Downloading -> DownloadRing(
                state.fraction, Modifier.size(size - 1.dp), lineWidth = max(1.5f, size.value / 8).dp,
            )
            DownloadItem.State.Downloaded -> Icon(
                Icons.Filled.DownloadForOffline, null, tint = BoomColor.accent, modifier = Modifier.size(size),
            )
            is DownloadItem.State.Failed -> Icon(
                Icons.Filled.Error, null, tint = BoomColor.destructive, modifier = Modifier.size(size),
            )
        }
    }
}

/** What the sign says, for TalkBack and for lines of text. */
fun downloadWords(state: DownloadItem.State): String = when (state) {
    DownloadItem.State.Waiting -> "Waiting to download"
    is DownloadItem.State.Downloading -> "Downloading, ${Math.round(state.fraction * 100)} percent"
    DownloadItem.State.Downloaded -> "Downloaded"
    is DownloadItem.State.Failed -> "Couldn't download"
}

/**
 * Loads every song of an album or a playlist (a long playlist comes in parts) and then downloads them as one
 * collection. One per frame, shared by the album and playlist pages and the menus elsewhere, so a page shows
 * "collecting" even when the download was started from a shelf's menu. Lives on the main thread.
 *
 * @param scope The frame's scope: gathering carries on when the page that started it is closed.
 */
class DownloadCollector(private val scope: CoroutineScope) {
    /** A message for the listener about a collection: the cap was hit, or the list could not be loaded. */
    data class Notice(val title: String, val message: String)

    /** Collections whose songs are being gathered, with how many have been found so far. */
    val collecting = mutableStateMapOf<String, Int>()

    /** Collections cut at [SONG_LIMIT] in this session. */
    val capped = mutableStateMapOf<String, Boolean>()

    /** The newest message; the frame shows it and clears it. */
    var notice by mutableStateOf<Notice?>(null)

    private val jobs = HashMap<String, Job>()

    /** Which run of each collection is the current one: a run cancelled and started again must not clear the new one. */
    private val runs = HashMap<String, Any>()

    fun isCollecting(id: String): Boolean = collecting.containsKey(key(id))

    /** What a page already holds, so it is not asked for again: its entry, its songs, the token of the next part. */
    data class Seed(val entry: DownloadCollection, val songs: List<Song>, val continuation: String?)

    /** Downloads a whole album or playlist. */
    fun download(kind: DownloadCollection.Kind, id: String, api: MusicAPI, store: DownloadStore, seed: Seed? = null) {
        val key = key(id)
        if (jobs.containsKey(key)) return
        val run = Any()
        runs[key] = run
        collecting[key] = seed?.songs?.size ?: 0
        jobs[key] = scope.launch {
            try {
                var entry: DownloadCollection
                var songs: List<Song>
                var token: String?
                if (seed != null) {
                    entry = seed.entry
                    songs = seed.songs
                    token = seed.continuation
                } else if (kind == DownloadCollection.Kind.ALBUM) {
                    val page = api.album(key)
                    entry = entry(page)
                    songs = page.tracks
                    token = null
                } else {
                    val page = api.playlist(key)
                    entry = entry(page)
                    songs = page.tracks
                    token = page.continuation
                }
                update(key, run, songs.size)
                while (token != null && songs.size < SONG_LIMIT) {
                    ensureActive()
                    val part = api.playlistContinuation(token)
                    songs = songs + part.songs
                    token = if (part.songs.isEmpty()) null else part.continuation
                    update(key, run, songs.size)
                }
                ensureActive()
                // A playlist may hold a song twice; the download keeps it once, in its first place.
                val seen = HashSet<String>()
                val unique = songs.filter { seen.add(it.videoId) }
                val isCapped = unique.size > SONG_LIMIT || token != null
                if (runs[key] !== run) return@launch
                finish(key)
                entry = entry.copy(id = key)
                if (unique.none { it.isAvailable }) {
                    notice = Notice("Nothing to download", "None of the songs of “${entry.title}” can be played.")
                    return@launch
                }
                store.download(unique.take(SONG_LIMIT), entry)
                if (isCapped) {
                    capped[key] = true
                    notice = Notice(
                        "Only the first $SONG_LIMIT songs",
                        "“${entry.title}” is longer than $SONG_LIMIT songs. Its first $SONG_LIMIT are being downloaded.",
                    )
                }
            } catch (cancelled: CancellationException) {
                if (runs[key] === run) finish(key)
                throw cancelled
            } catch (failure: Exception) {
                if (runs[key] !== run) return@launch
                finish(key)
                val what = if (kind == DownloadCollection.Kind.ALBUM) "album" else "playlist"
                notice = Notice("Couldn't download this $what", message(failure))
            }
        }
    }

    /** Stops gathering a collection's songs; nothing is downloaded. */
    fun cancel(id: String) {
        val key = key(id)
        jobs[key]?.cancel()
        finish(key)
    }

    private fun update(id: String, run: Any, found: Int) {
        if (runs[id] === run) collecting[id] = found
    }

    private fun finish(id: String) {
        jobs.remove(id)
        runs.remove(id)
        collecting.remove(id)
    }

    companion object {
        /** The most songs one album or playlist downloads: beyond it, a playlist would fill the phone by surprise. */
        const val SONG_LIMIT = 1000

        /**
         * The one form of an album's or playlist's id that downloads are kept under: a playlist page reached through a
         * link carries "VLPL…" where shelves carry "PL…".
         */
        fun key(id: String): String = when {
            id.startsWith("VL") -> id.drop(2)
            id.startsWith("MPSP") -> id.drop(4)
            else -> id
        }

        fun entry(page: AlbumPage) = DownloadCollection(
            id = key(page.album.id), kind = DownloadCollection.Kind.ALBUM, title = page.album.title,
            subtitle = page.album.artists.joinToString(", ") { it.name }, artwork = page.album.artwork,
        )

        fun entry(page: PlaylistPage) = DownloadCollection(
            id = key(page.playlist.id), kind = DownloadCollection.Kind.PLAYLIST, title = page.playlist.title,
            subtitle = page.authorName ?: page.playlist.detailText, artwork = page.playlist.artwork,
        )

        private fun message(error: Exception): String = when (error) {
            is MusicAPIError.Network -> "Couldn't connect. Check your connection and try again."
            MusicAPIError.SignedOut -> "Sign in to download this."
            MusicAPIError.Unauthorized -> "Your sign-in has run out. Sign in again to continue."
            else -> "Couldn't load its songs right now. Try again."
        }
    }
}

val LocalDownloadCollector = staticCompositionLocalOf<DownloadCollector> { error("BoomApp provides the collector") }
