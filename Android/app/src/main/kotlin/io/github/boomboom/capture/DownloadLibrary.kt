package io.github.boomboom.capture

import android.os.Handler
import android.os.Looper
import io.github.boomboom.engine.Track
import java.io.File
import java.util.concurrent.Executors

/**
 * The songs the listener chose to keep. Unlike the automatic cache, nothing here is ever removed except by the
 * listener: the files live in the app's files directory, which the system does not clear when space runs low.
 */
class DownloadLibrary(private val folder: File) {
    private val work = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "boom-downloads").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    init {
        folder.mkdirs()
    }

    /** Where the list of downloads (titles, covers, what belongs to which playlist) is kept. */
    val indexFile: File get() = File(folder, "index.json")

    private fun audioFile(videoId: String) = File(folder, safeFileName(videoId) + ".boomtrk")
    private fun detailsFile(videoId: String) = File(folder, safeFileName(videoId) + ".json")

    fun contains(videoId: String): Boolean = audioFile(videoId).isFile

    /** The file's size in bytes, 0 when the song is not kept. */
    fun size(videoId: String): Long = audioFile(videoId).length()

    fun details(videoId: String): CachedTrackDetails? = try {
        detailsFile(videoId).takeIf { it.isFile }?.readText()?.let(CachedTrackDetails::fromJson)
    } catch (failure: java.io.IOException) {
        null
    }

    /** Reads a kept song. A few megabytes from flash: quick enough to do on a tap. */
    fun load(videoId: String): KeptTrack? {
        val file = audioFile(videoId)
        if (!file.isFile) return null
        val track = Track.load(file) ?: return null
        if (!track.isComplete) {
            track.close()
            return null
        }
        return KeptTrack(track, details(videoId))
    }

    /**
     * Keeps a fully fetched song, in the background, and says on the main thread how many bytes it took (null when it
     * could not be written).
     */
    fun store(captured: CapturedTrack, details: CachedTrackDetails, then: (Long?) -> Unit) {
        captured.retain()
        work.execute {
            val bytes = try {
                val audio = audioFile(captured.videoId)
                if (captured.track.isComplete && captured.track.writeTo(audio)) {
                    try {
                        detailsFile(captured.videoId).writeText(details.toJson())
                        audio.length()
                    } catch (failure: java.io.IOException) {
                        audio.delete()
                        null
                    }
                } else {
                    null
                }
            } finally {
                captured.release()
            }
            main.post { then(bytes) }
        }
    }

    fun remove(videoIds: List<String>) {
        work.execute {
            for (videoId in videoIds) {
                audioFile(videoId).delete()
                detailsFile(videoId).delete()
            }
        }
    }
}
