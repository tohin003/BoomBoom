package io.github.boomboom.engine

import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/** What the decoder knows about a captured stream (the native `TrackInfo`). */
data class TrackInfo(
    val codec: Codec,
    /** Units per second of the stream's timeline. */
    val timescale: Int,
    /** The rate the container states. */
    val sampleRate: Double,
    val channels: Int,
    /** Timeline units the encoder put before the music (dropped when playing). */
    val leadIn: Long,
    /** The packet length the stream's header states, if it does. */
    val statedPacketDuration: Int?,
    /** The song's length in seconds, once known. */
    val duration: Double?,
    /** Timeline units per packet; 0 until the first packet is in. */
    val packetDuration: Int,
    /** Packets in the whole song, once known. */
    val expectedPacketCount: Int?,
) {
    enum class Codec { AAC, OPUS }
}

/**
 * One song's compressed audio, as it arrives: a native packet store. Packets come in any order; the track keeps each
 * once and knows which stretches it has. Safe to use from any thread. Close it when done: the engine keeps its own
 * hold on a track it is playing, so closing one that is still playing is fine.
 */
class Track private constructor(private var handle: Long) : AutoCloseable {
    constructor() : this(BoomCore.trackCreate())

    private val lock = ReentrantReadWriteLock()

    internal fun <T> withHandle(block: (Long) -> T): T = lock.read {
        check(handle != 0L) { "the track was closed" }
        block(handle)
    }

    /** The stream's header, once it has arrived. */
    val info: TrackInfo?
        get() = withHandle { h ->
            BoomCore.trackInfo(h)?.let { v ->
                TrackInfo(
                    codec = if (v[0] == 1.0) TrackInfo.Codec.OPUS else TrackInfo.Codec.AAC,
                    timescale = v[1].toInt(),
                    sampleRate = v[2],
                    channels = v[3].toInt(),
                    leadIn = v[4].toLong(),
                    statedPacketDuration = v[5].toInt().takeIf { it > 0 },
                    duration = v[6].takeUnless { it.isNaN() },
                    packetDuration = v[7].toInt(),
                    expectedPacketCount = v[8].toInt().takeIf { it >= 0 },
                )
            }
        }

    val heldPackets: Int get() = withHandle { BoomCore.trackHeldPackets(it) }
    val heldBytes: Long get() = withHandle { BoomCore.trackHeldBytes(it) }

    /** Every packet of the song is held. */
    val isComplete: Boolean get() = withHandle { BoomCore.trackIsComplete(it) }

    /** How much of the song is held, 0..1. */
    val coveredFraction: Double get() = withHandle { BoomCore.trackCoveredFraction(it) }

    /** The first missing packet at or after [index], wrapping round to the start; null when nothing is missing. */
    fun firstGap(index: Int): Int? = withHandle { BoomCore.trackFirstGap(it, index) }.takeIf { it >= 0 }

    /** Where the first missing stretch at or after [seconds] starts, in seconds, wrapping round; null when whole. */
    fun firstGapAt(seconds: Double): Double? = withHandle { BoomCore.trackFirstGapAt(it, seconds) }.takeIf { it >= 0 }

    /** The first missing packet at or after [index]. */
    fun contiguousEnd(index: Int): Int = withHandle { BoomCore.trackContiguousEnd(it, index) }

    /** [index] lies past the song's last packet. */
    fun hasReachedEnd(index: Int): Boolean = withHandle { BoomCore.trackHasReachedEnd(it, index) }

    fun packetIndexAt(seconds: Double): Int = withHandle { BoomCore.trackPacketIndexAt(it, seconds) }
    fun timeOfPacket(index: Int): Double = withHandle { BoomCore.trackTimeOfPacket(it, index) }

    /** The capture reached the end of the stream: the last packet held is the song's last. */
    fun seal() = withHandle { BoomCore.trackSeal(it) }

    /** What the page says the song lasts; the stream's own header wins when it states a length. */
    fun setDuration(seconds: Double) = withHandle { BoomCore.trackSetDuration(it, seconds) }

    /** Writes the whole song to [file]. False unless every packet is held and the file was written. */
    fun writeTo(file: File): Boolean = withHandle { BoomCore.trackWrite(it, file.absolutePath) }

    override fun close() = lock.write {
        if (handle != 0L) {
            BoomCore.trackRelease(handle)
            handle = 0L
        }
    }

    companion object {
        /** A song kept by [writeTo]; null when the file is not one. */
        fun load(file: File): Track? = BoomCore.trackLoad(file.absolutePath).takeIf { it != 0L }?.let(::Track)
    }
}
