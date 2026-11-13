package io.github.boomboom.web

import android.content.Context
import android.webkit.WebResourceResponse
import io.github.boomboom.runtime.BoomTrace
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * Requests to ad and tracking hosts are refused outright: the iPhone app's `ad-rules.json` (WebKit content rules,
 * packaged as an asset straight from there), read here as regular expressions over the URL. The ad *instructions*
 * inside the player's answers are removed by the page script; this is the second line.
 *
 * No rule may ever match the media requests themselves (`googlevideo.com/videoplayback`): one that refused requests
 * marked `ctier=A` refused every stream of a Premium account. So media requests are let through before any rule is
 * looked at, and a rule that would match one is dropped when the rules are read.
 */
class AdRules private constructor(private val filters: List<Regex>) {
    private val refusals = AtomicLong()

    /** How many requests were refused since launch, for the trace. */
    val refused: Long get() = refusals.get()

    /** True when [url] is an ad or tracking request. Called on the browser's network threads. */
    fun refuses(url: String): Boolean {
        if (isMedia(url)) return false
        if (filters.none { it.containsMatchIn(url) }) return false
        refusals.incrementAndGet()
        return true
    }

    /** What a refused request gets: an empty answer with an error status, so the page sees it fail. */
    fun refusal(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", 403, "Refused", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    companion object {
        /** URLs that stand for the media requests in the check made on the rules when they are read. */
        private val MEDIA_SAMPLES = listOf(
            "https://rr3---sn-abcdefgh.googlevideo.com/videoplayback?expire=1&ctier=A&itag=251&mime=audio%2Fwebm",
            "https://rr1---sn-n8v7znze.googlevideo.com/videoplayback?ctier=SH&source=youtube&itag=140",
        )

        fun isMedia(url: String): Boolean {
            val schemeEnd = url.indexOf("://")
            if (schemeEnd < 0) return false
            val hostStart = schemeEnd + 3
            val pathStart = url.indexOf('/', hostStart).let { if (it < 0) url.length else it }
            val host = url.substring(hostStart, pathStart).substringBefore(':').lowercase()
            return (host == "googlevideo.com" || host.endsWith(".googlevideo.com")) &&
                url.startsWith("/videoplayback", pathStart)
        }

        fun load(context: Context): AdRules {
            val text = try {
                context.assets.open("ad-rules.json").bufferedReader().use { it.readText() }
            } catch (failure: java.io.IOException) {
                BoomTrace.note("ad rules could not be read: ${failure.javaClass.simpleName}")
                return AdRules(emptyList())
            }
            return parse(text)
        }

        fun parse(text: String): AdRules {
            val rules = try {
                JSONArray(text)
            } catch (failure: org.json.JSONException) {
                BoomTrace.note("ad rules are not readable JSON")
                return AdRules(emptyList())
            }
            val filters = ArrayList<Regex>()
            for (index in 0 until rules.length()) {
                val rule = rules.optJSONObject(index) ?: continue
                if (rule.optJSONObject("action")?.optString("type") != "block") continue
                val trigger = rule.optJSONObject("trigger") ?: continue
                val pattern = trigger.optString("url-filter").takeIf { it.isNotEmpty() } ?: continue
                val caseSensitive = trigger.optBoolean("url-filter-is-case-sensitive", false)
                val regex = try {
                    if (caseSensitive) Regex(pattern) else Regex(pattern, RegexOption.IGNORE_CASE)
                } catch (failure: IllegalArgumentException) {
                    continue
                }
                if (MEDIA_SAMPLES.any { regex.containsMatchIn(it) }) {
                    BoomTrace.note("ad rule $index would refuse the media requests: left out")
                    continue
                }
                filters += regex
            }
            return AdRules(filters)
        }
    }
}
