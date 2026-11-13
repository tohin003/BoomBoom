package io.github.boomboom.innertube

import io.github.boomboom.models.MusicAPIError
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest

/** Builds requests that look like the YouTube Music web app's own (`WEB_REMIX`) calls. */
internal object InnerTubeRequest {
    const val ORIGIN = "https://music.youtube.com"

    /** `X-Youtube-Client-Name` of `WEB_REMIX`. */
    const val CLIENT_NAME_NUMBER = "67"

    private val jsonType = "application/json".toMediaType()

    fun url(endpoint: String) = "$ORIGIN/youtubei/v1/$endpoint?prettyPrint=false".toHttpUrlOrNull()

    /** The `context` every call carries: the client, the language and region, and the brand account if any. */
    fun context(session: InnerTubeSession): JsonObject {
        var client = obj(
            "clientName" to "WEB_REMIX",
            "clientVersion" to session.clientVersion,
            "hl" to session.language,
            "gl" to session.region,
        )
        session.visitorData?.let { client = client.with("visitorData", it) }
        var user = obj()
        session.onBehalfOfUser?.takeIf { it.isNotEmpty() }?.let { user = user.with("onBehalfOfUser", it) }
        return obj("client" to client, "user" to user)
    }

    /**
     * The POST for [endpoint]. Cookies are never taken from a jar: OkHttp has none unless one is given, and the
     * session's own cookies go in the header below (only when signed in). The caller sets the timeouts.
     */
    fun make(endpoint: String, body: JsonElement, session: InnerTubeSession, nowSeconds: Long = currentSeconds()): Request {
        val url = url(endpoint) ?: throw MusicAPIError.Unreadable("bad endpoint $endpoint")
        val fullBody = body.with("context", context(session))
        val builder = Request.Builder()
            .url(url)
            .post(fullBody.toJsonBytes().toRequestBody(jsonType))
        for ((name, value) in headers(session, nowSeconds)) builder.header(name, value)
        return builder.build()
    }

    fun headers(session: InnerTubeSession, nowSeconds: Long = currentSeconds()): List<Pair<String, String>> {
        val headers = mutableListOf(
            "Content-Type" to "application/json",
            "Accept" to "*/*",
            "Accept-Language" to session.language,
            "User-Agent" to session.userAgent,
            "Origin" to ORIGIN,
            "X-Origin" to ORIGIN,
            "Referer" to "$ORIGIN/",
            "X-Youtube-Client-Name" to CLIENT_NAME_NUMBER,
            "X-Youtube-Client-Version" to session.clientVersion,
        )
        session.visitorData?.let { headers += "X-Goog-Visitor-Id" to it }
        // Signed out, no cookie at all: the API answers without one, and the visitor id carries the region.
        val sapisid = session.sapisid
        if (sapisid != null) {
            headers += "Cookie" to cookieHeader(session.cookies)
            headers += "X-Goog-AuthUser" to session.authUser
            headers += "Authorization" to authorization(sapisid, nowSeconds)
        }
        return headers
    }

    /** `SAPISIDHASH <unix>_<sha1("<unix> <SAPISID> <origin>")>`. */
    fun authorization(sapisid: String, timestamp: Long, origin: String = ORIGIN): String {
        val digest = MessageDigest.getInstance("SHA-1").digest("$timestamp $sapisid $origin".toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "SAPISIDHASH ${timestamp}_$hex"
    }

    /** All cookies as one header, sorted by name so the header is stable. */
    fun cookieHeader(cookies: Map<String, String>): String =
        cookies.entries.sortedBy { it.key }.joinToString("; ") { "${it.key}=${it.value}" }

    private fun currentSeconds(): Long = System.currentTimeMillis() / 1000
}
