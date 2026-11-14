package io.github.boomboom.runtime

import android.app.Application
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/** Puts the runtime together when the process starts, before any activity or the playback service. */
class BoomApplication : Application() {
    /** The app's machinery. Only there when [startFailure] is null. */
    lateinit var runtime: BoomRuntime
        private set

    /**
     * Why the app cannot run on this phone as it is, in words for the listener; null when it can. The app plays
     * through the phone's own browser component (Android System WebView), which the Play Store keeps up to date; one
     * that is missing, switched off or years old cannot run the page the music comes from.
     */
    var startFailure: String? = null
        private set

    /** The package of the browser component to update in the store, when [startFailure] is about it. */
    var browserPackage: String? = null
        private set

    override fun onCreate() {
        super.onCreate()
        BoomTrace.start(this)
        BoomTrace.note("process start")
        // A crash leaves its reason in the app's own record, so a problem report can say what happened.
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
            BoomTrace.crash(thread, failure)
            before?.uncaughtException(thread, failure)
        }
        startFailure = missingRequirement()
        if (startFailure != null) {
            BoomTrace.note("cannot start: $startFailure")
            return
        }
        try {
            runtime = BoomRuntime(this)
        } catch (failure: RuntimeException) {
            // The browser component went away between the check and its first use (the store updating it right now).
            BoomTrace.note("cannot start: ${failure.javaClass.simpleName}")
            startFailure = NO_BROWSER
        }
    }

    private fun missingRequirement(): String? {
        val browser = try {
            WebViewCompat.getCurrentWebViewPackage(this)
        } catch (failure: RuntimeException) {
            null
        } ?: return NO_BROWSER
        browserPackage = browser.packageName
        val canInject = try {
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        } catch (failure: RuntimeException) {
            return NO_BROWSER
        }
        if (canInject) return null
        return "BoomBoom plays through \"Android System WebView\", a part of Android that the Play Store keeps up to " +
            "date. The one on this phone is too old (version ${browser.versionName ?: "unknown"}). Update it in the " +
            "Play Store, then open BoomBoom again."
    }

    private companion object {
        const val NO_BROWSER = "BoomBoom plays through \"Android System WebView\", a part of Android. It is missing or " +
            "switched off on this phone. Install or enable it in the Play Store, then open BoomBoom again."
    }
}
