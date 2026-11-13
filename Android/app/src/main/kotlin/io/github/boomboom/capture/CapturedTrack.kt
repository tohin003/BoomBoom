package io.github.boomboom.capture

import io.github.boomboom.engine.Track
import io.github.boomboom.web.AudioOffer
import org.json.JSONObject

/**
 * One song's audio on its way in: the native packet store the player reads from, and what is known about the stream.
 *
 * The store is native memory (a few megabytes), so it is let go of explicitly: each holder (the broker's memory, the
 * fetch under way, the engine's current and next song, a write to disk) takes a hold with [retain] and gives it back
 * with [release]; the last release closes the store. The audio engine keeps its own reference to a store it is
 * playing, so a close never cuts a song off.
 *
 * Everything but [retain] and [release] is used on the main thread.
 */
class CapturedTrack(
    val videoId: String,
    val track: Track = Track(),
    val isFromCache: Boolean = false,
) {
    /** How much louder than YouTube Music's reference level the song is, in decibels (negative: quieter). */
    var loudnessDb: Double? = null

    /** The audio format being captured ("Opus", 141 kbps). */
    var format: AudioOffer? = null

    /** Set when the song cannot be fetched. The text is for the listener. */
    var failure: String? = null

    /** Called when [loudnessDb], [format] or [failure] changes. */
    var onUpdate: (() -> Unit)? = null

    private val lock = Any()
    private var holds = 0
    private var closed = false

    fun retain() {
        synchronized(lock) {
            check(!closed) { "the track of $videoId was let go of" }
            holds += 1
        }
    }

    fun release() {
        val close = synchronized(lock) {
            holds -= 1
            if (holds <= 0 && !closed) {
                closed = true
                true
            } else {
                false
            }
        }
        if (close) track.close()
    }

    val isClosed: Boolean get() = synchronized(lock) { closed }
}

/** What is remembered about a kept song besides its audio. */
data class CachedTrackDetails(
    val loudnessDb: Double?,
    val format: AudioOffer?,
    val savedAtMillis: Long,
) {
    fun toJson(): String = JSONObject().apply {
        loudnessDb?.let { put("loudnessDb", it) }
        format?.let { put("format", it.toJson()) }
        put("savedAt", savedAtMillis)
    }.toString()

    companion object {
        fun fromJson(text: String): CachedTrackDetails? = try {
            val json = JSONObject(text)
            CachedTrackDetails(
                loudnessDb = if (json.isNull("loudnessDb")) null else json.optDouble("loudnessDb"),
                format = AudioOffer.fromJson(json.optJSONObject("format")),
                savedAtMillis = json.optLong("savedAt", 0),
            )
        } catch (failure: org.json.JSONException) {
            null
        }
    }
}

/** A song read back from disk. */
class KeptTrack(val track: Track, val details: CachedTrackDetails?)

/** Video ids are letters, digits, "-" and "_"; anything else never reaches a file name. */
internal fun safeFileName(videoId: String): String = videoId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
