package io.github.boomboom.capture

import io.github.boomboom.engine.Track
import io.github.boomboom.logic.AudioCacheBackend
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * Songs kept on the phone after they were fetched once: they start at once and play without the network. Files live
 * in the app's cache directory (the system may clear it when the phone runs out of space); when the folder passes
 * its limit, the songs played longest ago go first. Writes, trimming and totals run on a thread of their own.
 */
class AudioCache(private val folder: File) : AudioCacheBackend {
    private val work = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "boom-cache").apply { isDaemon = true } }
    private val dispatcher = work.asCoroutineDispatcher()
    @Volatile private var limit: Long = 2_000_000_000L

    override var limitBytes: Long
        get() = limit
        set(value) {
            limit = value
            work.execute { trim() }
        }

    init {
        folder.mkdirs()
    }

    private fun audioFile(videoId: String) = File(folder, safeFileName(videoId) + ".boomtrk")
    private fun detailsFile(videoId: String) = File(folder, safeFileName(videoId) + ".json")

    fun contains(videoId: String): Boolean = audioFile(videoId).isFile

    /** Reads a kept song. A few megabytes from flash: quick enough to do on a tap. */
    fun load(videoId: String): KeptTrack? {
        val file = audioFile(videoId)
        if (!file.isFile) return null
        val track = Track.load(file)
        if (track == null) {
            // A cut-off or outdated file is of no use.
            file.delete()
            return null
        }
        val details = try {
            detailsFile(videoId).takeIf { it.isFile }?.readText()?.let(CachedTrackDetails::fromJson)
        } catch (failure: java.io.IOException) {
            null
        }
        // "Played now": the trimming goes by this date.
        file.setLastModified(System.currentTimeMillis())
        return KeptTrack(track, details)
    }

    /** Keeps a fully fetched song, in the background. The caller's hold on [captured] is taken over and given back. */
    fun store(captured: CapturedTrack, details: CachedTrackDetails) {
        captured.retain()
        work.execute {
            try {
                val audio = audioFile(captured.videoId)
                if (captured.track.writeTo(audio)) {
                    try {
                        detailsFile(captured.videoId).writeText(details.toJson())
                    } catch (failure: java.io.IOException) {
                        audio.delete()
                    }
                }
            } finally {
                captured.release()
            }
            trim()
        }
    }

    private fun entries(): List<File> = folder.listFiles { file -> file.name.endsWith(".boomtrk") }?.toList() ?: emptyList()

    private fun trim() {
        val limit = this.limit
        val all = entries().sortedBy { it.lastModified() }.toMutableList()
        var total = all.sumOf { it.length() }
        while (total > limit && all.isNotEmpty()) {
            val oldest = all.removeAt(0)
            total -= oldest.length()
            oldest.delete()
            File(folder, oldest.nameWithoutExtension + ".json").delete()
        }
    }

    // MARK: Totals (AudioCacheBackend)

    override suspend fun usedBytes(): Long = withContext(dispatcher) { entries().sumOf { it.length() } }

    override suspend fun songCount(): Int = withContext(dispatcher) { entries().size }

    override suspend fun clear() {
        withContext(dispatcher) { folder.listFiles()?.forEach { it.delete() } }
    }
}
