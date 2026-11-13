package io.github.boomboom.engine

/**
 * The native engine's raw surface (`libboomcore.so`, built from `Android/core`). Handles are plain `Long`s owned by
 * the wrappers in this package ([Track], [Demuxer]); nothing outside the package calls these. `core/API.md` describes
 * every function and the layouts of the arrays.
 */
internal object BoomCore {
    init {
        System.loadLibrary("boomcore")
    }

    // Tracks
    @JvmStatic external fun trackCreate(): Long
    @JvmStatic external fun trackRelease(track: Long)
    @JvmStatic external fun trackInfo(track: Long): DoubleArray?
    @JvmStatic external fun trackHeldPackets(track: Long): Int
    @JvmStatic external fun trackHeldBytes(track: Long): Long
    @JvmStatic external fun trackIsComplete(track: Long): Boolean
    @JvmStatic external fun trackCoveredFraction(track: Long): Double
    @JvmStatic external fun trackFirstGap(track: Long, index: Int): Int
    @JvmStatic external fun trackFirstGapAt(track: Long, seconds: Double): Double
    @JvmStatic external fun trackContiguousEnd(track: Long, index: Int): Int
    @JvmStatic external fun trackHasReachedEnd(track: Long, index: Int): Boolean
    @JvmStatic external fun trackPacketIndexAt(track: Long, seconds: Double): Int
    @JvmStatic external fun trackTimeOfPacket(track: Long, index: Int): Double
    @JvmStatic external fun trackSeal(track: Long)
    @JvmStatic external fun trackSetDuration(track: Long, seconds: Double)
    @JvmStatic external fun trackWrite(track: Long, path: String): Boolean
    @JvmStatic external fun trackLoad(path: String): Long

    // Demuxers
    @JvmStatic external fun demuxerCreate(mimeType: String): Long
    @JvmStatic external fun demuxerRelease(demuxer: Long)
    @JvmStatic external fun demuxerReset(demuxer: Long)
    @JvmStatic external fun demuxerFeed(demuxer: Long, track: Long, bytes: ByteArray, offset: Int, length: Int): Int
    @JvmStatic external fun demuxerLastError(demuxer: Long): String

    // The engine
    @JvmStatic external fun engineStartOutput(): Boolean
    @JvmStatic external fun engineStopOutput()
    @JvmStatic external fun engineLoad(track: Long, item: Long, seconds: Double, autoplay: Boolean, gain: Float)
    @JvmStatic external fun engineSetNext(track: Long, item: Long, gain: Float)
    @JvmStatic external fun enginePlay()
    @JvmStatic external fun enginePause()
    @JvmStatic external fun engineSeek(seconds: Double)
    @JvmStatic external fun engineStop()
    @JvmStatic external fun engineSetItemGain(item: Long, gain: Float)
    @JvmStatic external fun engineSetLevel(gain: Float)
    @JvmStatic external fun enginePosition(): Double
    @JvmStatic external fun engineCurrentItem(): Long
    @JvmStatic external fun engineDuration(): Double
    @JvmStatic external fun engineBufferedFraction(): Double
    @JvmStatic external fun engineOutputInfo(): LongArray
    @JvmStatic external fun enginePollEvents(timeoutMs: Int): LongArray

    // Sound
    @JvmStatic external fun soundApply(params: FloatArray)
    @JvmStatic external fun soundSetHeadTurn(radians: Float)
    @JvmStatic external fun soundTakeLevels(): FloatArray
    @JvmStatic external fun soundSpectrum(bands: Int, lowHz: Double, highHz: Double): FloatArray
    @JvmStatic external fun soundResponse(params: FloatArray, frequencies: DoubleArray, sampleRate: Double): DoubleArray
    @JvmStatic external fun soundPeakResponse(params: FloatArray, sampleRate: Double): Double
    @JvmStatic external fun soundBandFrequencies(): DoubleArray
    @JvmStatic external fun soundLatency(): Int

    // Codes shared with core/src/jni_api.rs.
    const val FEED_CORRUPT = -1
    const val FEED_MISSING_HEADER = -2
    const val FEED_UNSUPPORTED = -3
    const val FEED_BAD_ARGUMENTS = -4

    const val EVENT_PLAYING = 1L
    const val EVENT_ADVANCED = 2L
    const val EVENT_WAITING = 3L
    const val EVENT_ENDED = 4L
    const val EVENT_FAILED_UNSUPPORTED = 5L
    const val EVENT_FAILED_UNDECODABLE = 6L
}
