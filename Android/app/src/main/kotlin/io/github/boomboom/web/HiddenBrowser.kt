package io.github.boomboom.web

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.github.boomboom.innertube.InnerTubeSession
import io.github.boomboom.logic.BackendState
import io.github.boomboom.logic.KeyValueStore
import io.github.boomboom.logic.SessionBackend
import io.github.boomboom.logic.SessionProfile
import io.github.boomboom.runtime.BoomTrace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/**
 * The hidden browser tab: one WebView on music.youtube.com, with ads stripped, kept silent, and with the iPhone
 * app's page script inside (`boom-tap.js`, run at document start). It holds the listener's Google sign-in (the
 * WebView profile's own cookies), and its player fetches the audio that the app then plays itself.
 *
 * The WebView belongs to the process, not to an activity: it is made with the application context and is never put
 * in a window. Unseen, the page counts as hidden and its own timers are slowed, which is why the capture broker
 * drives it from the app's side on a timer of its own. Its renderer is bound as important even when the app is in
 * the background, so the system does not reclaim it while music plays.
 *
 * Up to three Google accounts are kept apart in WebView profiles (`androidx.webkit` `ProfileStore`), each with its
 * own cookies and storage. Only the active profile has a WebView; switching takes that one down and builds a new one
 * on the other profile. A WebView without profiles keeps one account.
 *
 * Everything here runs on the main thread.
 */
class HiddenBrowser(
    private val context: Context,
    private val storage: KeyValueStore,
    private val scope: CoroutineScope,
) : SessionBackend {
    /** Where the tab stands. Every change goes through [move]. */
    sealed class TabPhase {
        /**
         * The start page is loading: at launch, after a failure, after the sign-in changed, or in a new WebView after
         * an account switch.
         */
        data object Loading : TabPhase()

        /**
         * A page is in. [hasPlayer] turns true once its player takes commands (the site makes the player when a page
         * is opened onto a song, and keeps it from then on).
         */
        data class Loaded(val atMillis: Long, val hasPlayer: Boolean) : TabPhase()

        /** The capture broker is opening a song's own page in the tab. To the rest of the app the tab stays usable. */
        data object SwitchingPage : TabPhase()

        data class Failed(val message: String) : TabPhase()

        /** For the trace and the debug screen. */
        val label: String
            get() = when (this) {
                Loading -> "loading"
                is Loaded -> if (hasPlayer) "ready" else "loaded"
                SwitchingPage -> "opening"
                is Failed -> "failed"
            }
    }

    var phase: TabPhase = TabPhase.Loading
        private set

    override val state: BackendState
        get() = when (val now = phase) {
            TabPhase.Loading -> BackendState.Starting
            is TabPhase.Loaded, TabPhase.SwitchingPage -> BackendState.Ready
            is TabPhase.Failed -> BackendState.Failed(now.message)
        }

    override var isSignedIn: Boolean = false
        private set
    override var onChange: (() -> Unit)? = null

    /** Called for everything the script reports. */
    var onEvent: ((TapEvent) -> Unit)? = null

    /** Called on every change of [phase], and when the WebView is replaced (the capture broker listens). */
    var onPhaseChange: (() -> Unit)? = null

    /** True once the page's player takes commands. */
    val isPlayerReady: Boolean get() = (phase as? TabPhase.Loaded)?.hasPlayer == true

    /** True when the page in the tab is a song's own page, which makes its player a moment after it loads. */
    val isOnSongPage: Boolean get() = Uri.parse(webView.url ?: "").path == "/watch"

    /** When the page in the tab finished loading (elapsed-realtime milliseconds); null while one is on its way. */
    val pageLoadedAt: Long? get() = (phase as? TabPhase.Loaded)?.atMillis

    /** Whether this WebView keeps several profiles apart. Without it there is one account. */
    val supportsProfiles: Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    /** The tab presents itself as desktop Chrome, so it gets the full web app and its media-source player. */
    val userAgent: String = desktopUserAgent(context)

    /** The tab. It is replaced when the listener switches accounts or its renderer dies, so it is read afresh. */
    var webView: WebView
        private set

    /** Every profile, the default one first, then in the order they were added. */
    var profileIds: List<String>
        private set
    var activeProfileId: String
        private set

    /** The active profile's page values. */
    private var page: PageSession
    private val adRules = AdRules.load(context)
    private val script: String
    private var hasStarted = false
    private var mainFrameFailed = false

    /**
     * The page being loaded, while one is on its way: the page being left can still report for a moment, and its
     * player must not be taken for the new page's.
     */
    private var expectedPage: String? = null
    private var cookieCheck: Job? = null
    private val main = Handler(Looper.getMainLooper())

    /** The script's messages are read off the main thread, in order, then handed back to it. */
    private val reader = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "boom-bridge").apply { isDaemon = true } }
    private val bridgeNanos = AtomicLong()
    private val bridgeBytes = AtomicLong()
    private val bridgeMessages = AtomicLong()

    init {
        val tap = context.assets.open("boom-tap.js").bufferedReader().use { it.readText() }
        // The script looks for the iPhone's message handler; here the same name leads to this object.
        script = "window.webkit = window.webkit || {}; window.webkit.messageHandlers = { boom: { postMessage: " +
            "function (m) { BoomNative.postMessage(JSON.stringify(m)); } } };\n" + tap

        val ids = mutableListOf(DEFAULT_PROFILE)
        if (supportsProfiles) {
            for (id in savedProfileIds()) {
                if (isValidId(id) && id !in ids && ids.size < MAXIMUM_PROFILES) ids += id
            }
        }
        val wanted = storage.getString(ACTIVE_PROFILE_KEY) ?: DEFAULT_PROFILE
        val active = if (wanted in ids) wanted else DEFAULT_PROFILE
        // A profile that is neither active nor signed in was left by an "add account" the app was closed during.
        ids.removeAll { it != DEFAULT_PROFILE && it != active && !savedPage(it).isSignedIn }
        profileIds = ids
        activeProfileId = active
        page = savedPage(active)
        // The cookies are read fresh from the profile's store; the remembered flag only bridges the first moments.
        isSignedIn = page.isSignedIn
        if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) WebView.setWebContentsDebuggingEnabled(true)
        webView = makeWebView(active)
        saveProfiles()
        BoomTrace.note(
            "browser: WebView ${WebViewCompat.getCurrentWebViewPackage(context)?.versionName ?: "?"}, " +
                "profiles=${if (supportsProfiles) "yes" else "no (one account)"}, " +
                "document-start=${WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)}, " +
                "listener=${WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)}",
        )
        scope.launch {
            refreshCookies()
            deleteForgottenProfiles()
        }
    }

    // MARK: Loading

    /** Loads YouTube Music in the tab. */
    fun start() {
        hasStarted = true
        if (webView.url != null) return
        reload()
    }

    override fun reload() {
        expectedPage = pageKey(HOME)
        move(TabPhase.Loading)
        webView.loadUrl(HOME)
    }

    /** Opens a page of the site in the tab (a song's own page, when the player is not there yet). */
    fun open(url: String) {
        expectedPage = pageKey(url)
        move(TabPhase.SwitchingPage)
        webView.loadUrl(url)
    }

    /** A message from a page other than the one being loaded, while one is on its way, comes from the page being left. */
    private fun isFromPageBeingLeft(href: String?): Boolean {
        val expected = expectedPage ?: return false
        if (phase != TabPhase.Loading && phase != TabPhase.SwitchingPage) return false
        return href != null && pageKey(href) != expected
    }

    /**
     * The one place the tab's phase changes. [always] announces it even when the phase stays the same: a new WebView
     * starts in `Loading` too, and the capture broker must hear that the page under its fetch is gone.
     */
    private fun move(next: TabPhase, always: Boolean = false) {
        if (next == phase && !always) return
        val before = state
        phase = next
        when (next) {
            TabPhase.Loading -> BoomTrace.note("tab loading")
            is TabPhase.Loaded -> BoomTrace.note("tab loaded ${Uri.parse(webView.url ?: "-").path ?: "-"} player=${next.hasPlayer} signedIn=$isSignedIn")
            TabPhase.SwitchingPage -> BoomTrace.note("tab opening a song's page")
            is TabPhase.Failed -> BoomTrace.note("tab failed: ${next.message}")
        }
        if (state != before || always) onChange?.invoke()
        onPhaseChange?.invoke()
    }

    /** A page finished loading, or its player appeared. */
    private fun pageArrived(hasPlayer: Boolean) {
        when (val now = phase) {
            is TabPhase.Loaded -> if (hasPlayer && !now.hasPlayer) move(TabPhase.Loaded(now.atMillis, true))
            TabPhase.Loading, TabPhase.SwitchingPage, is TabPhase.Failed ->
                move(TabPhase.Loaded(SystemClock.elapsedRealtime(), hasPlayer))
        }
    }

    // MARK: Talking to the page

    /**
     * Runs one of the script's commands and returns its answer: a [JSONObject], [JSONArray], [String], [Number],
     * [Boolean], or null (also when it threw).
     */
    suspend fun call(expression: String): Any? {
        val text = evaluate("(function(){ try { return $expression; } catch (e) { return null; } })()") ?: return null
        return try {
            JSONTokener(text).nextValue().takeUnless { it == JSONObject.NULL }
        } catch (failure: org.json.JSONException) {
            null
        }
    }

    /** For diagnostics: evaluates an expression in the page and gives its answer as text (JSON for objects). */
    suspend fun inspect(expression: String): String {
        val script = "(function(){ try { var v = ($expression); return typeof v === 'string' ? v : JSON.stringify(v); } " +
            "catch (e) { return 'threw: ' + e; } })()"
        val text = evaluate(script) ?: return "no answer"
        return try {
            (JSONTokener(text).nextValue() as? String) ?: text
        } catch (failure: org.json.JSONException) {
            text
        }
    }

    private suspend fun evaluate(script: String): String? = suspendCancellableCoroutine { continuation ->
        try {
            webView.evaluateJavascript(script) { answer -> if (continuation.isActive) continuation.resume(answer) }
        } catch (failure: IllegalStateException) {
            continuation.resume(null)
        }
    }

    private fun receive(view: WebView, message: String) {
        reader.execute {
            val start = System.nanoTime()
            val event = TapEvent.parse(message)
            bridgeNanos.addAndGet(System.nanoTime() - start)
            bridgeBytes.addAndGet(message.length.toLong())
            bridgeMessages.incrementAndGet()
            if (event != null) main.post { if (view === webView) handle(event) }
        }
    }

    private fun handle(event: TapEvent) {
        val href = when (event) {
            is TapEvent.Hello -> event.href
            is TapEvent.Ready -> event.href
            else -> null
        }
        if (href != null && isFromPageBeingLeft(href)) {
            BoomTrace.note("a message from the page being left: ignored")
            return
        }
        if (event is TapEvent.Ready) {
            pageArrived(hasPlayer = true)
            refreshPageSession()
        }
        onEvent?.invoke(event)
    }

    /** The bridge's cost so far: messages, characters, and milliseconds spent reading them (off the main thread). */
    fun bridgeSummary(): String =
        String.format(java.util.Locale.ROOT, "%d messages, %.1f MB, %.0f ms reading", bridgeMessages.get(),
            bridgeBytes.get() / 1e6, bridgeNanos.get() / 1e6)

    /** Ad and tracking requests refused since launch. */
    val refusedRequests: Long get() = adRules.refused

    // MARK: The session

    /** What the data client needs to act as this tab; null until the page has said which client version it is. */
    fun sessionSnapshot(): InnerTubeSession? {
        val cookies = currentCookies()
        noteCookies(cookies)
        val version = page.clientVersion ?: return null
        return InnerTubeSession(
            clientVersion = version,
            visitorData = page.visitorData,
            language = page.language ?: "en",
            region = page.region ?: "US",
            userAgent = userAgent,
            cookies = cookies,
            authUser = page.authUser ?: "0",
            onBehalfOfUser = page.onBehalfOfUser,
        )
    }

    /** The active profile's `.youtube.com` cookies, read from its store now. */
    fun currentCookies(): Map<String, String> = PageSession.parseCookies(cookieManager(activeProfileId).getCookie(HOME))

    private fun refreshCookies() {
        noteCookies(currentCookies())
    }

    private fun noteCookies(cookies: Map<String, String>) {
        val signedIn = PageSession.isSignedIn(cookies)
        if (signedIn != page.isSignedIn) {
            page = page.copy(isSignedIn = signedIn)
            save()
        }
        if (signedIn == isSignedIn) return
        isSignedIn = signedIn
        BoomTrace.note("sign-in changed: signedIn=$signedIn")
        onChange?.invoke()
        // The page in the tab still shows the old account state.
        if (hasStarted && webView.url != null) reload()
    }

    /** There is no cookie observer on Android: the cookies are looked at after each page and while signing in. */
    private fun scheduleCookieCheck(delayMillis: Long = 400) {
        cookieCheck?.cancel()
        cookieCheck = scope.launch {
            delay(delayMillis)
            refreshCookies()
        }
    }

    private fun refreshPageSession() {
        val view = webView
        scope.launch {
            val values = call("window.__boom.session()") as? JSONObject ?: return@launch
            if (view !== webView) return@launch
            page = page.merging(values)
            save()
        }
    }

    private fun save() {
        storage.putString(sessionKey(activeProfileId), page.toJson())
    }

    override suspend fun signOut() {
        removeProfile(activeProfileId)
    }

    // MARK: Profiles

    override val profiles: List<SessionProfile>
        get() = profileIds.map { SessionProfile(it, isSignedIn(it), it == activeProfileId) }

    override val profileLimit: Int get() = if (supportsProfiles) MAXIMUM_PROFILES else 1

    /** Whether a profile is signed in, as its saved session last said (only the active one is read live). */
    fun isSignedIn(profile: String): Boolean = if (profile == activeProfileId) isSignedIn else savedPage(profile).isSignedIn

    /**
     * A profile to sign another account in to, without switching to it: an empty one if there is one (the default
     * profile, once signed out, is used again), otherwise a new one. Null when all are taken.
     */
    override fun addProfile(): String? {
        profileIds.firstOrNull { !isSignedIn(it) }?.let { return it }
        if (!supportsProfiles || profileIds.size >= MAXIMUM_PROFILES) return null
        val id = UUID.randomUUID().toString()
        profileIds = profileIds + id
        saveProfiles()
        BoomTrace.note("profile ${profileIds.size} of $MAXIMUM_PROFILES added")
        onChange?.invoke()
        return id
    }

    /**
     * Takes the tab off the active profile and puts a new WebView, signed in as the other account, in its place.
     * Whatever plays keeps playing: the capture broker hears that the page is new and asks it again.
     */
    override fun switchProfile(id: String) {
        if (id !in profileIds || id == activeProfileId) return
        val previous = activeProfileId
        cookieCheck?.cancel()
        takeDown(webView)
        activeProfileId = id
        storage.putString(ACTIVE_PROFILE_KEY, id)
        page = savedPage(id)
        isSignedIn = page.isSignedIn
        webView = makeWebView(id)
        BoomTrace.note("profile ${position(id)} of ${profileIds.size} is active, signed in: $isSignedIn")
        // A profile whose Google session ended while it was active holds nothing worth keeping.
        if (previous != DEFAULT_PROFILE && !isSignedIn(previous)) forget(previous)
        move(TabPhase.Loading, always = true)
        if (hasStarted) {
            expectedPage = pageKey(HOME)
            webView.loadUrl(HOME)
        }
        refreshCookies()
    }

    /**
     * Signs a profile out. Another profile is deleted; the default one is emptied and stays. When the active profile
     * goes, the tab moves to the first other signed-in one, or else to the default one.
     */
    override suspend fun removeProfile(id: String) {
        if (id !in profileIds) return
        BoomTrace.note("signing out profile ${position(id)} of ${profileIds.size}")
        if (id == activeProfileId) {
            val next = profileIds.firstOrNull { it != id && isSignedIn(it) } ?: DEFAULT_PROFILE
            // The tab leaves the profile before its data goes, so no page of it is left signed in.
            if (next != id) switchProfile(next)
        }
        eraseGoogleData(id)
        if (id == DEFAULT_PROFILE) {
            if (id == activeProfileId) {
                page = page.copy(isSignedIn = false, authUser = null, onBehalfOfUser = null)
                save()
                isSignedIn = false
                onChange?.invoke()
                reload()
            } else {
                storage.putString(sessionKey(id), savedPage(id).copy(isSignedIn = false, authUser = null, onBehalfOfUser = null).toJson())
                onChange?.invoke()
            }
        } else if (id != activeProfileId) {
            forget(id)
            onChange?.invoke()
        }
    }

    /**
     * Called when the sign-in screen closes, whether Google sent the listener back or they gave up. A profile that is
     * signed in now becomes the active one; a new profile left empty is removed.
     */
    override suspend fun didFinishSignIn(profile: String) {
        if (profile !in profileIds) return
        cookieManager(profile).flush()
        if (profile == activeProfileId) {
            refreshCookies()
            return
        }
        var signedIn = PageSession.isSignedIn(PageSession.parseCookies(cookieManager(profile).getCookie(HOME)))
        if (!signedIn) {
            // Google's last answer may still be settling into the store.
            delay(800)
            signedIn = PageSession.isSignedIn(PageSession.parseCookies(cookieManager(profile).getCookie(HOME)))
        }
        if (profile !in profileIds || profile == activeProfileId) return
        storage.putString(sessionKey(profile), savedPage(profile).copy(isSignedIn = signedIn).toJson())
        if (signedIn) {
            switchProfile(profile)
        } else if (profile != DEFAULT_PROFILE) {
            forget(profile)
            onChange?.invoke()
        }
    }

    /**
     * Prepares a visible WebView (the sign-in screen) to work on a profile's cookies and storage. Must be called
     * before the view loads anything.
     */
    fun useProfile(view: WebView, profile: String) {
        if (supportsProfiles) WebViewCompat.setProfile(view, profileName(profile))
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
    }

    /** The sign-in screen may still be settling cookies: look again shortly. */
    fun signInPageChanged() {
        scheduleCookieCheck()
    }

    /** Drops a profile that is neither active nor the default one, and deletes its data. */
    private fun forget(id: String) {
        if (id == activeProfileId || id == DEFAULT_PROFILE) return
        profileIds = profileIds - id
        saveProfiles()
        storage.remove(sessionKey(id))
        deleteProfileData(id)
    }

    private fun deleteProfileData(id: String) {
        if (!supportsProfiles) return
        scope.launch {
            // A WebView only just taken down may still hold the profile: try again a little later; what stays is
            // deleted at the next launch.
            repeat(3) { attempt ->
                val deleted = try {
                    ProfileStore.getInstance().deleteProfile(profileName(id))
                } catch (failure: IllegalStateException) {
                    false
                } catch (failure: IllegalArgumentException) {
                    // There was no such profile: nothing to delete.
                    true
                }
                if (deleted) return@launch
                BoomTrace.note("a profile's data could not be deleted yet (try ${attempt + 1})")
                delay(2000)
            }
        }
    }

    /** Deletes profiles no account owns any more: ones whose deletion failed earlier. */
    private fun deleteForgottenProfiles() {
        if (!supportsProfiles) return
        val owned = profileIds.map(::profileName).toSet()
        for (name in ProfileStore.getInstance().allProfileNames) {
            if (!name.startsWith(PROFILE_PREFIX) || name in owned) continue
            try {
                ProfileStore.getInstance().deleteProfile(name)
            } catch (failure: RuntimeException) {
                // In use or already gone: the next launch tries again.
            }
        }
    }

    /** The profile's place in the list, counted from 1: the trace says which profile, never whose. */
    private fun position(id: String): Int = profileIds.indexOf(id).let { if (it < 0) profileIds.size else it } + 1

    private fun saveProfiles() {
        storage.putString(PROFILES_KEY, JSONArray(profileIds).toString())
        storage.putString(ACTIVE_PROFILE_KEY, activeProfileId)
    }

    private fun savedProfileIds(): List<String> {
        val text = storage.getString(PROFILES_KEY) ?: return emptyList()
        return try {
            val array = JSONArray(text)
            (0 until array.length()).map { array.optString(it) }
        } catch (failure: org.json.JSONException) {
            emptyList()
        }
    }

    private fun savedPage(id: String): PageSession = PageSession.fromJson(storage.getString(sessionKey(id)))

    private fun profileName(id: String): String = if (id == DEFAULT_PROFILE) Profile.DEFAULT_PROFILE_NAME else PROFILE_PREFIX + id

    private fun profile(id: String): Profile? =
        if (supportsProfiles) ProfileStore.getInstance().getOrCreateProfile(profileName(id)) else null

    private fun cookieManager(id: String): CookieManager = profile(id)?.cookieManager ?: CookieManager.getInstance()

    private fun webStorage(id: String): WebStorage = profile(id)?.webStorage ?: WebStorage.getInstance()

    /** Removes the Google and YouTube sign-in and storage from a profile (a profile only ever holds those sites). */
    private suspend fun eraseGoogleData(id: String) {
        val cookies = cookieManager(id)
        suspendCancellableCoroutine { continuation ->
            cookies.removeAllCookies { if (continuation.isActive) continuation.resume(Unit) }
        }
        cookies.flush()
        webStorage(id).deleteAllData()
    }

    // MARK: The WebView

    /** The tab's WebView on a profile. Every WebView the tab ever has is made here, so a switch gives the same tab. */
    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun makeWebView(profile: String): WebView {
        val view = WebView(context)
        if (supportsProfiles) WebViewCompat.setProfile(view, profileName(profile))
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            userAgentString = userAgent
            // Nobody sees the tab: covers and pictures would only cost memory and data. (The iPhone tab loads them.)
            blockNetworkImage = true
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(view.settings, emptySet())
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        // The renderer is kept at the app's own importance even unseen: the page fetches while the screen is off.
        view.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        // Never in a window, but given a size, as a page laid out at nothing by nothing may not make a player.
        view.measure(
            View.MeasureSpec.makeMeasureSpec(TAB_WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(TAB_HEIGHT, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, TAB_WIDTH, TAB_HEIGHT)
        view.webViewClient = Client()
        view.webChromeClient = WebChromeClient()

        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            // Messages are taken only from the main frame of the music site.
            WebViewCompat.addWebMessageListener(view, BRIDGE_NAME, setOf(ORIGIN)) {
                    source: WebView, message: WebMessageCompat, _: Uri, isMainFrame: Boolean, _: JavaScriptReplyProxy ->
                val text = message.data
                if (isMainFrame && text != null) receive(source, text)
            }
        } else {
            view.addJavascriptInterface(LegacyBridge(view), BRIDGE_NAME)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(view, script, setOf(ORIGIN))
        } else {
            BoomTrace.note("this WebView cannot run a script at document start: capture will not work")
        }
        view.onResume()
        view.resumeTimers()
        return view
    }

    /** Takes a WebView out of service for good: nothing of it reaches this object again, and its page stops. */
    private fun takeDown(view: WebView) {
        view.stopLoading()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.removeWebMessageListener(view, BRIDGE_NAME)
        } else {
            view.removeJavascriptInterface(BRIDGE_NAME)
        }
        view.webViewClient = WebViewClient()
        view.loadUrl("about:blank")
        view.destroy()
    }

    /** The renderer was killed (memory pressure, or it crashed): a new WebView on the same profile, loaded again. */
    private fun replaceAfterRendererLoss(view: WebView) {
        // A WebView whose renderer is gone can only be destroyed.
        view.webViewClient = WebViewClient()
        view.destroy()
        webView = makeWebView(activeProfileId)
        move(TabPhase.Loading, always = true)
        if (hasStarted) {
            expectedPage = pageKey(HOME)
            webView.loadUrl(HOME)
        }
    }

    private inner class Client : WebViewClient() {
        // Each callback checks that it is about the current WebView: one taken down says nothing now.

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            if (view === webView) mainFrameFailed = false
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (view !== webView || url == null || url.startsWith("about:")) return
            if (mainFrameFailed) return
            pageArrived(hasPlayer = false)
            refreshPageSession()
            scheduleCookieCheck()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (view !== webView || !request.isForMainFrame) return
            mainFrameFailed = true
            BoomTrace.note("tab failed to load: error ${error.errorCode}")
            move(TabPhase.Failed("Couldn't reach YouTube Music. Check your connection."))
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // One taken down earlier is destroyed already.
            if (view !== webView) return true
            BoomTrace.note("tab renderer gone (crashed=${detail.didCrash()}): making the tab again")
            replaceAfterRendererLoss(view)
            return true
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            // The tab stays on YouTube Music and Google's own pages; anything else (an app link, a store page) is refused.
            if (!request.isForMainFrame) return false
            val scheme = request.url.scheme?.lowercase() ?: return false
            return !(scheme == "https" || scheme == "about" || scheme == "blob")
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url.toString()
            return if (adRules.refuses(url)) adRules.refusal() else null
        }
    }

    /** For a WebView without message listeners: the same name, called the same way (`BoomNative.postMessage`). */
    private inner class LegacyBridge(private val view: WebView) {
        @JavascriptInterface
        fun postMessage(message: String) {
            receive(view, message)
        }
    }

    companion object {
        const val HOME = "https://music.youtube.com/"
        const val ORIGIN = "https://music.youtube.com"

        /** How many Google accounts the app keeps at once, each in its own profile. */
        const val MAXIMUM_PROFILES = 3

        /** The profile on the WebView's default profile, where the one sign-in of a WebView without profiles lives. */
        const val DEFAULT_PROFILE = "default"

        private const val PROFILE_PREFIX = "boom-"
        private const val BRIDGE_NAME = "BoomNative"
        private const val PROFILES_KEY = "web.profiles"
        private const val ACTIVE_PROFILE_KEY = "web.activeProfile"
        private const val SESSION_KEY = "web.session"
        private const val TAB_WIDTH = 480
        private const val TAB_HEIGHT = 320

        /** A page's identity for [isFromPageBeingLeft]: its path and the song it is about. */
        private fun pageKey(url: String): String {
            val uri = Uri.parse(url)
            return (uri.path ?: "").trimEnd('/') + "?v=" + (uri.getQueryParameter("v") ?: "")
        }

        private fun sessionKey(id: String): String = if (id == DEFAULT_PROFILE) SESSION_KEY else "$SESSION_KEY.$id"

        private fun isValidId(id: String): Boolean =
            try {
                UUID.fromString(id)
                true
            } catch (failure: IllegalArgumentException) {
                false
            }

        /** The WebView's own Chrome version, from its default user agent ("134"). */
        fun chromeMajorVersion(context: Context): String {
            val default = try {
                WebSettings.getDefaultUserAgent(context)
            } catch (failure: RuntimeException) {
                ""
            }
            return Regex("Chrome/(\\d+)").find(default)?.groupValues?.get(1) ?: "134"
        }

        /** Desktop Chrome on Linux, with the version of the Chromium inside this WebView. */
        fun desktopUserAgent(context: Context): String =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/${chromeMajorVersion(context)}.0.0.0 Safari/537.36"

        /**
         * Mobile Chrome as it introduces itself (the reduced form), without the WebView marker ("; wv"): Google refuses
         * to sign in inside browsers it recognises as embedded.
         */
        fun mobileUserAgent(context: Context): String =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/${chromeMajorVersion(context)}.0.0.0 Mobile Safari/537.36"
    }
}
