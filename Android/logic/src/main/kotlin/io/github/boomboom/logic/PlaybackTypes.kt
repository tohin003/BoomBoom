package io.github.boomboom.logic

import io.github.boomboom.models.BrowseEndpoint
import io.github.boomboom.models.Song
import java.util.UUID

/** What the player is doing, in the terms a screen shows. */
sealed class PlaybackStatus {
    /** Nothing is loaded. */
    data object Idle : PlaybackStatus()

    /** A song was asked for and its first audio has not arrived yet. */
    data object Loading : PlaybackStatus()

    data object Playing : PlaybackStatus()

    data object Paused : PlaybackStatus()

    /** Playing, but waiting for more audio (a slow connection). */
    data object Buffering : PlaybackStatus()

    /** The song could not be played. The text is for the listener. */
    data class Failed(val message: String) : PlaybackStatus()

    val isFailure: Boolean get() = this is Failed
}

enum class RepeatMode(val rawValue: String) {
    OFF("off"), ALL("all"), ONE("one");

    companion object {
        fun fromRawValue(raw: String): RepeatMode? = entries.firstOrNull { it.rawValue == raw }
    }
}

/** One place in the queue. The same song can be queued twice, so entries have their own identity. */
data class QueueEntry(
    val id: UUID = UUID.randomUUID(),
    val song: Song,
)

/** Where the queue came from, for the "Playing from" line of the player. */
data class QueueSource(
    val kind: Kind,
    /** "Album", "Playlist", "Radio". */
    val label: String,
    /** The album's or playlist's name, or the song a radio grew from. */
    val title: String,
    /** Opens the album or playlist from the player. */
    val endpoint: BrowseEndpoint? = null,
) {
    enum class Kind { ALBUM, PLAYLIST, ARTIST, RADIO, SEARCH, LIBRARY, OTHER }
}

sealed class SleepTimer {
    /** Playback pauses at this moment (milliseconds since 1970, as [Clock] tells them). */
    data class At(val atMillis: Long) : SleepTimer()

    /** Playback pauses when the current song ends. */
    data object EndOfSong : SleepTimer()
}
