package io.github.boomboom.innertube

// The contract between the hidden browser (which owns the Google sign-in) and the data client.

/** What a request to YouTube Music's web API needs to look like the web app's own request. */
data class InnerTubeSession(
    /** `INNERTUBE_CLIENT_VERSION` of the `WEB_REMIX` client, as the live page reports it ("1.20261105.01.00"). */
    val clientVersion: String,
    /** `VISITOR_DATA`: sent as `X-Goog-Visitor-Id` and in the request context. */
    val visitorData: String? = null,
    /** `hl`, such as "en". */
    val language: String = "en",
    /** `gl`, such as "IN". */
    val region: String = "US",
    val userAgent: String = DESKTOP_SAFARI_USER_AGENT,
    /** The `.youtube.com` cookies by name. Empty when signed out. */
    val cookies: Map<String, String> = emptyMap(),
    /** `X-Goog-AuthUser`: which of the browser's Google accounts ("0"). */
    val authUser: String = "0",
    /** A brand account's id (`DELEGATED_SESSION_ID`), sent as `context.user.onBehalfOfUser`. */
    val onBehalfOfUser: String? = null,
) {
    /** The cookie the `SAPISIDHASH` authorization is computed from. */
    val sapisid: String? get() = cookies["SAPISID"] ?: cookies["__Secure-3PAPISID"]

    val isSignedIn: Boolean get() = sapisid != null

    companion object {
        /**
         * The hidden browser and the data client present themselves as desktop Safari, so both get the same web app.
         * (The page script and the web app do not mind that the WebView is really Chromium.)
         */
        const val DESKTOP_SAFARI_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) " +
                "Version/26.0 Safari/605.1.15"
    }
}

/**
 * Supplies the session for each request. In the app this is backed by the hidden browser; in tests and before the
 * browser has loaded, by [BootstrapSessionProvider] (signed out).
 */
interface InnerTubeSessionProviding {
    suspend fun currentSession(): InnerTubeSession
}
