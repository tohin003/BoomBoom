package io.github.boomboom.web

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import io.github.boomboom.runtime.BoomApplication

/**
 * Google's own sign-in page in a visible WebView on one profile, the one the hidden tab uses for that account, so
 * signing in here signs that profile in. The app never sees the password: it is typed into Google's page, and the
 * app only notices, from the address, when Google sends the listener back to YouTube Music.
 *
 * The page is shown as mobile Chrome shows it, without the WebView marker in the user agent and without the header
 * that names the app: Google refuses to sign in inside browsers it recognises as embedded.
 *
 * Not tested against a real sign-in (that needs a real account and must never be typed by a machine).
 */
class SignInActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var profile: String
    private var didSignIn = false
    private var reported = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        profile = intent.getStringExtra(EXTRA_PROFILE) ?: run {
            finish()
            return
        }
        val runtime = (application as BoomApplication).runtime
        title = "Sign in with Google"
        web = WebView(this)
        runtime.browser.useProfile(web, profile)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = HiddenBrowser.mobileUserAgent(this@SignInActivity)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(web.settings, emptySet())
        }
        val spinner = ProgressBar(this).apply { isIndeterminate = true }
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                spinner.visibility = android.view.View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String?) {
                spinner.visibility = android.view.View.GONE
                runtime.browser.signInPageChanged()
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Back on YouTube Music: Google is done and the cookies are set.
                if (request.isForMainFrame && request.url.host == "music.youtube.com" && !didSignIn) {
                    didSignIn = true
                    finish()
                    return true
                }
                return false
            }
        }
        val root = FrameLayout(this)
        root.addView(web, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(spinner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER))
        setContentView(root)
        web.loadUrl(START)
    }

    override fun finish() {
        report()
        super.finish()
    }

    override fun onDestroy() {
        report()
        if (::web.isInitialized) {
            web.stopLoading()
            web.destroy()
        }
        super.onDestroy()
    }

    /** Back, a finished sign-in, or the screen going away: the session store hears once which it was. */
    private fun report() {
        if (reported || !::profile.isInitialized) return
        reported = true
        val session = (application as BoomApplication).runtime.services.session
        if (didSignIn) session.finishAddingAccount(profile) else session.cancelAddingAccount(profile)
    }

    companion object {
        private const val EXTRA_PROFILE = "profile"

        /** Google's page for signing in to YouTube Music; it returns to music.youtube.com when done. */
        private const val START = "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube&uilel=3&passive=true" +
            "&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26app%3Ddesktop" +
            "%26next%3Dhttps%253A%252F%252Fmusic.youtube.com%252F"

        fun intent(context: Context, profile: String): Intent =
            Intent(context, SignInActivity::class.java).putExtra(EXTRA_PROFILE, profile)
    }
}
