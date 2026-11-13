package io.github.boomboom.web

import io.github.boomboom.innertube.BootstrapSessionProvider
import io.github.boomboom.innertube.InnerTubeSession
import io.github.boomboom.innertube.InnerTubeSessionProviding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * What the hidden tab's page says about how the web app introduces itself, kept per profile. Unlike the iPhone app's
 * `WebSessionSnapshot` it holds no cookies: the browser's own cookie store is read whenever they are needed, so the
 * sign-in is never copied into the app's files. [isSignedIn] is remembered so that a profile that is not active can
 * be shown as signed in without loading it.
 */
data class PageSession(
    val clientVersion: String? = null,
    val visitorData: String? = null,
    val language: String? = null,
    val region: String? = null,
    val authUser: String? = null,
    val onBehalfOfUser: String? = null,
    val isSignedIn: Boolean = false,
) {
    fun toJson(): String = JSONObject().apply {
        clientVersion?.let { put("clientVersion", it) }
        visitorData?.let { put("visitorData", it) }
        language?.let { put("language", it) }
        region?.let { put("region", it) }
        authUser?.let { put("authUser", it) }
        onBehalfOfUser?.let { put("onBehalfOfUser", it) }
        put("isSignedIn", isSignedIn)
    }.toString()

    /** Takes the values the page's `__boom.session()` answered with, keeping known ones the answer lacks. */
    fun merging(answer: JSONObject): PageSession {
        fun text(key: String): String? = if (answer.isNull(key)) null else answer.optString(key).takeIf { it.isNotEmpty() }
        return copy(
            clientVersion = text("clientVersion") ?: clientVersion,
            visitorData = text("visitorData") ?: visitorData,
            language = text("language") ?: language,
            region = text("region") ?: region,
            authUser = text("authUser"),
            onBehalfOfUser = text("onBehalfOfUser"),
        )
    }

    companion object {
        fun fromJson(text: String?): PageSession {
            if (text == null) return PageSession()
            val json = try {
                JSONObject(text)
            } catch (failure: org.json.JSONException) {
                return PageSession()
            }
            fun value(key: String): String? = if (json.isNull(key)) null else json.optString(key)
            return PageSession(
                clientVersion = value("clientVersion"),
                visitorData = value("visitorData"),
                language = value("language"),
                region = value("region"),
                authUser = value("authUser"),
                onBehalfOfUser = value("onBehalfOfUser"),
                isSignedIn = json.optBoolean("isSignedIn", false),
            )
        }

        /** The `.youtube.com` cookies as the browser's cookie store returns them ("a=1; b=2"). */
        fun parseCookies(header: String?): Map<String, String> {
            if (header.isNullOrBlank()) return emptyMap()
            val cookies = LinkedHashMap<String, String>()
            for (part in header.split(';')) {
                val equals = part.indexOf('=')
                if (equals <= 0) continue
                cookies[part.substring(0, equals).trim()] = part.substring(equals + 1).trim()
            }
            return cookies
        }

        fun isSignedIn(cookies: Map<String, String>): Boolean = cookies["SAPISID"] != null || cookies["__Secure-3PAPISID"] != null
    }
}

/**
 * The data client's session: the hidden tab's cookies and the web app's own identifiers, so the listener's sign-in
 * applies to search and the library. Until the page has said which client version it is, the identifiers come from
 * the start page read directly ([BootstrapSessionProvider]); the cookies always come from the tab.
 */
class WebSessionProvider(
    private val browser: HiddenBrowser,
    private val bootstrap: BootstrapSessionProvider = BootstrapSessionProvider(),
) : InnerTubeSessionProviding {
    override suspend fun currentSession(): InnerTubeSession {
        val fromTab = withContext(Dispatchers.Main) { browser.sessionSnapshot() }
        if (fromTab != null) return fromTab
        val start = bootstrap.currentSession()
        val cookies = withContext(Dispatchers.Main) { browser.currentCookies() }
        return start.copy(cookies = cookies, userAgent = browser.userAgent)
    }
}
