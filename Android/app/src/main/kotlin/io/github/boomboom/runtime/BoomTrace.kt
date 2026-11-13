package io.github.boomboom.runtime

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * A short running record of what the engine did: logcat lines tagged `BOOM`, and the last few hundred kilobytes in
 * `boom-trace.log` in the app's files directory, so that a problem seen on a phone (a song that would not start,
 * playback that stopped in the background) can be read afterwards. It holds times, video ids, formats and states:
 * no account details, cookies or search words.
 */
object BoomTrace {
    const val TAG = "BOOM"
    private const val LIMIT = 300_000
    private val started = SystemClock.elapsedRealtime()
    private val writer = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "boom-trace").apply { isDaemon = true } }
    @Volatile private var file: File? = null
    private var stream: FileOutputStream? = null
    private var written = 0

    /** Seconds since the process started noting. */
    fun seconds(): Double = (SystemClock.elapsedRealtime() - started) / 1000.0

    /** Where the log goes; until this is called, notes go to logcat only. */
    fun start(context: Context) {
        file = File(context.filesDir, "boom-trace.log")
    }

    fun note(text: String) {
        val elapsed = seconds()
        val now = System.currentTimeMillis()
        Log.i(TAG, String.format(Locale.ROOT, "+%.1f %s", elapsed, text))
        writer.execute { write(String.format(Locale.ROOT, "%s +%.1f %s\n", stamp(now), elapsed, text)) }
    }

    /** A line for logcat only (the once-a-second diagnostics line), not kept in the file. */
    fun console(text: String) {
        Log.i(TAG, text)
    }

    private fun stamp(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(millis))

    private fun write(line: String) {
        val target = file ?: return
        try {
            if (stream == null) {
                // Each launch starts with the tail of the previous one, so a crash's last words survive.
                val previous = if (target.isFile) target.readBytes() else ByteArray(0)
                val kept = previous.copyOfRange(maxOf(0, previous.size - LIMIT / 3), previous.size)
                target.writeBytes(kept)
                stream = FileOutputStream(target, true)
                written = kept.size
                val header = "---- launch ${stamp(System.currentTimeMillis())} ----\n".toByteArray()
                stream?.write(header)
                written += header.size
            }
            val bytes = line.toByteArray()
            stream?.write(bytes)
            written += bytes.size
            if (written > LIMIT) {
                stream?.close()
                stream = null
            }
        } catch (failure: java.io.IOException) {
            stream = null
        }
    }
}
