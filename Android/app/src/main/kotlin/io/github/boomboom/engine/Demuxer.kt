package io.github.boomboom.engine

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/** The bytes could not be read as audio. The demuxer has started clean; the next segment may still work. */
class DemuxException(val kind: Kind, message: String) : Exception(message) {
    enum class Kind {
        /** A box or element claims more bytes than it has, or a size that makes no sense. */
        CORRUPT,
        /** Media arrived before the stream's header. */
        MISSING_HEADER,
        /** Encrypted, or a codec this app does not play. */
        UNSUPPORTED,
    }
}

/**
 * Turns the bytes the page's player appends to one media source buffer into packets for a [Track]. Bytes may come in
 * pieces of any size. One per source buffer; [reset] mirrors the buffer's `abort()`.
 */
class Demuxer private constructor(private var handle: Long) : AutoCloseable {
    private val lock = ReentrantReadWriteLock()

    /**
     * Feeds [length] bytes of [bytes] from [offset], adding what comes out to [track]. The bytes are copied once, into
     * the native side. Returns how many new packets the track took.
     */
    fun feed(bytes: ByteArray, track: Track, offset: Int = 0, length: Int = bytes.size - offset): Int = lock.read {
        check(handle != 0L) { "the demuxer was closed" }
        val result = track.withHandle { t -> BoomCore.demuxerFeed(handle, t, bytes, offset, length) }
        if (result >= 0) return result
        val kind = when (result) {
            BoomCore.FEED_CORRUPT -> DemuxException.Kind.CORRUPT
            BoomCore.FEED_MISSING_HEADER -> DemuxException.Kind.MISSING_HEADER
            BoomCore.FEED_UNSUPPORTED -> DemuxException.Kind.UNSUPPORTED
            else -> throw IllegalArgumentException("offset $offset and length $length do not fit ${bytes.size} bytes")
        }
        throw DemuxException(kind, BoomCore.demuxerLastError(handle))
    }

    /** Forgets partly received data (the player jumped). The header read so far stays valid. */
    fun reset() = lock.read {
        check(handle != 0L) { "the demuxer was closed" }
        BoomCore.demuxerReset(handle)
    }

    override fun close() = lock.write {
        if (handle != 0L) {
            BoomCore.demuxerRelease(handle)
            handle = 0L
        }
    }

    companion object {
        /** The demuxer for a source buffer's MIME type (`audio/webm; codecs="opus"`, `audio/mp4; codecs="mp4a.40.2"`). */
        fun forMimeType(mimeType: String): Demuxer? = BoomCore.demuxerCreate(mimeType).takeIf { it != 0L }?.let(::Demuxer)
    }
}
