package io.github.boomboom.innertube

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * A signed-out session read from the YouTube Music start page, for tests and for app start before the hidden
 * browser is ready.
 *
 * Fetches `https://music.youtube.com/` as desktop Safari, reads `INNERTUBE_CLIENT_VERSION` and `VISITOR_DATA` from
 * the page's `ytcfg.set({...})`, and keeps them for about 12 hours. When the page cannot be read it falls back to a
 * recent known client version (without a visitor id) and tries the page again a few minutes later.
 */
class BootstrapSessionProvider(
    private val language: String = "en",
    private val region: String = "US",
    private val httpClient: OkHttpClient = InnerTubeClient.defaultHttpClient(),
    private val refreshIntervalMillis: Long = 12 * 3600 * 1000L,
    private val clock: () -> Long = System::currentTimeMillis,
) : InnerTubeSessionProviding {
    private val retryIntervalMillis = 5 * 60 * 1000L

    /** The start page's answer: the session, and whether the page could be read. */
    private class Fetched(val session: InnerTubeSession, val fromPage: Boolean)

    /** What the provider has in hand. One of these at a time, so "cached" and "being fetched" cannot disagree. */
    private sealed class State {
        data object Empty : State()

        /** The start page is being read; everyone who asks meanwhile waits for the same answer. */
        class Fetching(val running: Deferred<Fetched>) : State()

        class Ready(val session: InnerTubeSession, val expiresAtMillis: Long) : State()
    }

    /** The page is read on this scope, not in the caller's job: one caller giving up must not cancel the others'. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var state: State = State.Empty

    override suspend fun currentSession(): InnerTubeSession {
        val task: Deferred<Fetched> = synchronized(lock) {
            when (val current = state) {
                is State.Ready ->
                    if (current.expiresAtMillis > clock()) return current.session else newFetch()
                is State.Fetching -> current.running
                State.Empty -> newFetch()
            }
        }
        val fetched = task.await()
        // Others may have asked, or invalidated, while this call waited: only the fetch still in progress lands.
        synchronized(lock) {
            val current = state
            if (current is State.Fetching && current.running === task) {
                val keepFor = if (fetched.fromPage) refreshIntervalMillis else retryIntervalMillis
                state = State.Ready(fetched.session, clock() + keepFor)
            }
        }
        currentCoroutineContext().ensureActive()
        return fetched.session
    }

    /** Called with [lock] held: the one place a fetch is started. */
    private fun newFetch(): Deferred<Fetched> {
        val running = scope.async { fetchSession(language, region, httpClient) }
        state = State.Fetching(running)
        return running
    }

    /**
     * Forgets the cached session, so the next call reads the page again. A read already under way is left to
     * finish: its answer is as fresh as a new one would be.
     */
    fun invalidate() {
        synchronized(lock) {
            if (state is State.Ready) state = State.Empty
        }
    }

    companion object {
        /** A recent `WEB_REMIX` version, used when the page cannot be read. */
        const val FALLBACK_CLIENT_VERSION = "1.20261110.04.00"

        /** The session from the start page, and whether the page could be read. */
        private suspend fun fetchSession(language: String, region: String, httpClient: OkHttpClient): Fetched {
            val fallback = Fetched(InnerTubeSession(FALLBACK_CLIENT_VERSION, language = language, region = region), false)
            val request = Request.Builder()
                .url("https://music.youtube.com/?hl=$language&gl=$region")
                .header("User-Agent", InnerTubeSession.DESKTOP_SAFARI_USER_AGENT)
                .header("Accept-Language", language)
                .header("Accept", "text/html,application/xhtml+xml")
                // "Reject all" consent: without it, EU visitors are redirected to consent.youtube.com.
                .header("Cookie", "SOCS=CAI")
                .build()
            val html = try {
                withContext(Dispatchers.IO) {
                    val client = httpClient.newBuilder()
                        .connectTimeout(15, TimeUnit.SECONDS)
                        .readTimeout(15, TimeUnit.SECONDS)
                        .writeTimeout(15, TimeUnit.SECONDS)
                        .build()
                    client.newCall(request).execute().use { response ->
                        // The page the request ended on, after any redirect, must still be the music site.
                        if (!response.isSuccessful || response.request.url.host != "music.youtube.com") null
                        else response.body?.string()
                    }
                }
            } catch (e: IOException) {
                null
            } ?: return fallback
            val config = parseConfig(html)
            val version = config.clientVersion ?: return fallback
            return Fetched(
                InnerTubeSession(version, visitorData = config.visitorData, language = language, region = region),
                true,
            )
        }

        internal class Config(val clientVersion: String?, val visitorData: String?)

        /** `INNERTUBE_CLIENT_VERSION` and `VISITOR_DATA` from the `ytcfg.set({...})` calls of the page. */
        internal fun parseConfig(html: String): Config {
            var clientVersion: String? = null
            var visitorData: String? = null
            for (objectText in ytcfgObjects(html)) {
                val json: JsonElement = try {
                    parseJson(objectText)
                } catch (e: IllegalArgumentException) {
                    continue
                }
                clientVersion = clientVersion ?: json["INNERTUBE_CLIENT_VERSION"].text
                visitorData = visitorData ?: json["VISITOR_DATA"].text
                    ?: json["INNERTUBE_CONTEXT", "client", "visitorData"].text
            }
            // A page that changed how it calls ytcfg: look for the keys anywhere.
            clientVersion = clientVersion ?: firstStringValue("INNERTUBE_CLIENT_VERSION", html)
            visitorData = visitorData ?: firstStringValue("VISITOR_DATA", html)
            return Config(clientVersion, visitorData)
        }

        /** The object literals passed to `ytcfg.set(`, found by matching braces outside of strings. */
        internal fun ytcfgObjects(html: String): List<String> {
            val objects = mutableListOf<String>()
            var searchStart = 0
            while (true) {
                val call = html.indexOf("ytcfg.set(", searchStart)
                if (call < 0) break
                val afterCall = call + "ytcfg.set(".length
                searchStart = afterCall
                val open = (afterCall until html.length).firstOrNull { !html[it].isWhitespace() }
                if (open == null || html[open] != '{') continue
                var depth = 0
                var inString = false
                var escaped = false
                var index = open
                while (index < html.length) {
                    val character = html[index]
                    if (inString) {
                        if (escaped) escaped = false
                        else if (character == '\\') escaped = true
                        else if (character == '"') inString = false
                    } else if (character == '"') {
                        inString = true
                    } else if (character == '{') {
                        depth += 1
                    } else if (character == '}') {
                        depth -= 1
                        if (depth == 0) {
                            objects += html.substring(open, index + 1)
                            searchStart = index + 1
                            break
                        }
                    }
                    index += 1
                }
            }
            return objects
        }

        internal fun firstStringValue(key: String, html: String): String? {
            val marker = "\"$key\":\""
            val keyStart = html.indexOf(marker)
            if (keyStart < 0) return null
            val valueStart = keyStart + marker.length
            val end = html.indexOf('"', valueStart)
            if (end < 0) return null
            val value = html.substring(valueStart, end)
            return value.ifEmpty { null }
        }
    }
}
