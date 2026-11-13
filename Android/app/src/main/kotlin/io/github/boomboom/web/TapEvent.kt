package io.github.boomboom.web

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/** One audio format the player's answer offers for a song. */
data class AudioOffer(
    val itag: Int,
    /** `audio/webm; codecs="opus"`. */
    val mimeType: String,
    /** Bits per second. */
    val bitrate: Int,
) {
    /** "Opus", "AAC". */
    val codecName: String
        get() {
            val lower = mimeType.lowercase()
            return when {
                "opus" in lower -> "Opus"
                "mp4a" in lower -> "AAC"
                else -> "Audio"
            }
        }

    fun toJson(): JSONObject = JSONObject().put("itag", itag).put("mime", mimeType).put("bitrate", bitrate)

    companion object {
        fun fromJson(json: JSONObject?): AudioOffer? {
            if (json == null || !json.has("itag") || !json.has("mime")) return null
            return AudioOffer(json.optInt("itag"), json.optString("mime"), json.optInt("bitrate", 0))
        }
    }
}

/** What the script in the hidden tab tells the app (see `boom-tap.js`, the iPhone app's own script). */
sealed class TapEvent {
    /** The script is in place in a freshly loaded page ([href]). */
    data class Hello(val mediaSource: String, val href: String?) : TapEvent()

    /** The player of the page at [href] is there to take commands. */
    data class Ready(val href: String?) : TapEvent()

    /**
     * The player's answer about a song. [token] is set when it is the song the app asked for; [chosen] is the one
     * audio format the script left for the player to use (the best on offer).
     */
    data class Player(
        val token: String?,
        val videoId: String?,
        val status: String?,
        val reason: String?,
        val seconds: Double?,
        val isLive: Boolean,
        val loudnessDb: Double?,
        val audio: List<AudioOffer>,
        val chosen: AudioOffer?,
    ) : TapEvent()

    /** The player opened an audio buffer for the app's song. */
    data class Buffer(val token: String, val id: Int, val mimeType: String) : TapEvent()

    /** Bytes the player appended to that buffer. */
    class Append(val token: String, val id: Int, val data: ByteArray) : TapEvent()

    /** The player dropped a partly appended segment and starts a new one (it jumped). */
    data class Restart(val token: String, val id: Int) : TapEvent()

    /** The player reached the end of the stream. */
    data class End(val token: String, val id: Int, val seconds: Double?) : TapEvent()

    data class Unsupported(val reason: String) : TapEvent()
    data class ScriptError(val where: String, val message: String) : TapEvent()

    companion object {
        /**
         * The audio pieces are nearly all of the traffic (a song is 3-8 MB, as base64 in JSON). `JSON.stringify` writes
         * them as `{"t":"append","token":"…","id":N,"d":"…"}`, and base64 never needs escaping, so they are read
         * without a JSON parser; anything else goes through one.
         */
        private const val APPEND_PREFIX = "{\"t\":\"append\",\"token\":\""

        fun parse(text: String): TapEvent? {
            if (text.startsWith(APPEND_PREFIX)) parseAppend(text)?.let { return it }
            val body = try {
                JSONObject(text)
            } catch (failure: org.json.JSONException) {
                return null
            }
            return parse(body)
        }

        private fun parseAppend(text: String): Append? {
            val tokenStart = APPEND_PREFIX.length
            val tokenEnd = text.indexOf('"', tokenStart).takeIf { it > tokenStart } ?: return null
            val idKey = text.indexOf("\"id\":", tokenEnd).takeIf { it > 0 } ?: return null
            val idEnd = text.indexOf(',', idKey).takeIf { it > 0 } ?: return null
            val id = text.substring(idKey + 5, idEnd).trim().toIntOrNull() ?: return null
            val dataKey = text.indexOf("\"d\":\"", idEnd).takeIf { it > 0 } ?: return null
            val dataStart = dataKey + 5
            val dataEnd = text.indexOf('"', dataStart).takeIf { it >= dataStart } ?: return null
            val data = try {
                Base64.getDecoder().decode(text.substring(dataStart, dataEnd))
            } catch (failure: IllegalArgumentException) {
                return null
            }
            return Append(text.substring(tokenStart, tokenEnd), id, data)
        }

        fun parse(body: JSONObject): TapEvent? {
            // `isNull` is also true for a key that is not there.
            fun string(key: String): String? = if (body.isNull(key)) null else body.optString(key)
            fun double(key: String): Double? =
                if (body.isNull(key)) null else body.optDouble(key, Double.NaN).takeUnless { it.isNaN() || it.isInfinite() }
            fun int(key: String): Int? = if (body.has(key) && !body.isNull(key)) body.optInt(key) else null
            return when (body.optString("t")) {
                "hello" -> Hello(string("mediaSource") ?: "", string("href"))
                "ready" -> Ready(string("href"))
                "player" -> Player(
                    token = string("token"),
                    videoId = string("videoId"),
                    status = string("status"),
                    reason = string("reason"),
                    seconds = double("seconds"),
                    isLive = body.optBoolean("isLive", false),
                    loudnessDb = double("loudnessDb"),
                    audio = offers(body.optJSONArray("audio")),
                    chosen = AudioOffer.fromJson(body.optJSONObject("chosen")),
                )
                "buffer" -> {
                    val token = string("token") ?: return null
                    val id = int("id") ?: return null
                    Buffer(token, id, string("mime") ?: return null)
                }
                "append" -> {
                    val token = string("token") ?: return null
                    val id = int("id") ?: return null
                    val data = try {
                        Base64.getDecoder().decode(string("d") ?: return null)
                    } catch (failure: IllegalArgumentException) {
                        return null
                    }
                    Append(token, id, data)
                }
                "restart" -> Restart(string("token") ?: return null, int("id") ?: return null)
                "end" -> End(string("token") ?: return null, int("id") ?: return null, double("seconds"))
                "unsupported" -> Unsupported(string("reason") ?: "")
                "error" -> ScriptError(string("where") ?: "", string("message") ?: "")
                else -> null
            }
        }

        private fun offers(array: JSONArray?): List<AudioOffer> {
            if (array == null) return emptyList()
            return (0 until array.length()).mapNotNull { AudioOffer.fromJson(array.optJSONObject(it)) }
        }
    }
}
