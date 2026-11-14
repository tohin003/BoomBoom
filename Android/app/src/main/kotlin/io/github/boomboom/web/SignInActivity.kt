package io.github.boomboom.web

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import io.github.boomboom.runtime.BoomApplication
import io.github.boomboom.runtime.BoomTrace

/**
 * Google's own sign-in page in a visible WebView on one profile, the one the hidden tab uses for that account, so
 * signing in here signs that profile in. The app never sees the password: it is typed into Google's page, and the
 * app only notices, from the address, when Google sends the listener back to YouTube Music.
 *
 * Google decides for itself which browsers it lets sign in, and has not said how. So the page can be shown two ways
 * ([Identity]), and the listener can switch with "Trouble signing in?" if Google turns the first one away.
 *
 * Not tested against a real sign-in (that needs a real account and must never be typed by a machine).
 */
class SignInActivity : Activity() {
    /** How the sign-in page's browser introduces itself. */
    private enum class Identity {
        /** As what it is: the phone's own WebView, nothing changed. The way most apps show this page. */
        WEBVIEW,

        /** As mobile Chrome, without the marks of a browser inside an app. */
        CHROME,
    }

    private lateinit var profile: String
    private lateinit var holder: FrameLayout
    private lateinit var spinner: ProgressBar
    private lateinit var trouble: TextView
    private var web: WebView? = null
    private var identity = Identity.WEBVIEW
    private var didSignIn = false
    private var reported = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        profile = intent.getStringExtra(EXTRA_PROFILE) ?: run {
            finish()
            return
        }
        identity = if (savedInstanceState?.getString(STATE_IDENTITY) == Identity.CHROME.name) Identity.CHROME else Identity.WEBVIEW
        window.statusBarColor = BACKGROUND
        window.navigationBarColor = BACKGROUND

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(BACKGROUND)
            setPadding(dp(4), 0, dp(4), 0)
        }
        bar.addView(
            barButton("✕", "Close", 20f) { finish() },
            LinearLayout.LayoutParams(dp(48), dp(48)),
        )
        bar.addView(
            TextView(this).apply {
                text = "Sign in"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(8), 0, dp(8), 0)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        trouble = barButton("Trouble signing in?", "Trouble signing in? Shows the page another way", 14f) { switchIdentity() }
        trouble.setTextColor(ACCENT)
        trouble.setPadding(dp(12), 0, dp(12), 0)
        bar.addView(trouble, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))

        holder = FrameLayout(this)
        spinner = ProgressBar(this).apply { isIndeterminate = true }
        holder.addView(
            spinner,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BACKGROUND)
            // Clear of the status bar, the gesture bar and the keyboard on every Android version.
            fitsSystemWindows = true
        }
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        root.addView(holder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_IDENTITY, identity.name)
    }

    /** Puts a fresh WebView with the current [identity] on screen and loads Google's page in it. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun show() {
        val runtime = (application as BoomApplication).runtime
        web?.let { old ->
            holder.removeView(old)
            old.stopLoading()
            old.destroy()
        }
        val view = WebView(this)
        // Before the view loads anything: its cookies and storage are the profile's.
        runtime.browser.useProfile(view, profile)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
        }
        if (identity == Identity.CHROME) describeAsChrome(view)
        view.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                spinner.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String?) {
                spinner.visibility = View.GONE
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
        holder.addView(view, 0, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        web = view
        trouble.text = if (identity == Identity.WEBVIEW) "Trouble signing in?" else "Try the first way"
        BoomTrace.note("sign-in page shown as ${if (identity == Identity.WEBVIEW) "the WebView" else "Chrome"}")
        view.loadUrl(START)
    }

    private fun switchIdentity() {
        identity = if (identity == Identity.WEBVIEW) Identity.CHROME else Identity.WEBVIEW
        show()
    }

    /**
     * Mobile Chrome's own words in the user agent (no "; wv"), in the client hints, and no header naming this app.
     */
    private fun describeAsChrome(view: WebView) {
        view.settings.userAgentString = HiddenBrowser.mobileUserAgent(this)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(view.settings, emptySet())
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
            val own = WebSettingsCompat.getUserAgentMetadata(view.settings)
            val brands = own.brandVersionList.map { brand ->
                if (brand.brand != "Android WebView") {
                    brand
                } else {
                    UserAgentMetadata.BrandVersion.Builder()
                        .setBrand("Google Chrome")
                        .setMajorVersion(brand.majorVersion)
                        .setFullVersion(brand.fullVersion)
                        .build()
                }
            }
            WebSettingsCompat.setUserAgentMetadata(
                view.settings,
                UserAgentMetadata.Builder()
                    .setBrandVersionList(brands)
                    .setFullVersion(own.fullVersion)
                    .setPlatform("Android")
                    .setPlatformVersion(Build.VERSION.RELEASE ?: "")
                    .setArchitecture("")
                    .setBitness(0)
                    .setModel("")
                    .setMobile(true)
                    .setWow64(false)
                    .build(),
            )
        }
    }

    override fun onBackPressed() {
        val view = web
        // Back goes a step back inside Google's pages first (a wrong address typed, "use another account").
        if (view != null && view.canGoBack()) view.goBack() else super.onBackPressed()
    }

    override fun finish() {
        report()
        super.finish()
    }

    override fun onDestroy() {
        report()
        web?.let {
            it.stopLoading()
            it.destroy()
        }
        web = null
        super.onDestroy()
    }

    /** Back, a finished sign-in, or the screen going away: the session store hears once which it was. */
    private fun report() {
        if (reported || !::profile.isInitialized) return
        // Turning the phone makes the screen again; that is not the listener leaving.
        if (isChangingConfigurations) return
        reported = true
        val session = (application as BoomApplication).runtime.services.session
        if (didSignIn) session.finishAddingAccount(profile) else session.cancelAddingAccount(profile)
    }

    private fun barButton(label: String, description: String, size: Float, action: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            contentDescription = description
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            isClickable = true
            isFocusable = true
            val ripple = TypedValue()
            if (theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)) {
                setBackgroundResource(ripple.resourceId)
            }
            setOnClickListener { action() }
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val EXTRA_PROFILE = "profile"
        private const val STATE_IDENTITY = "identity"
        private const val BACKGROUND = 0xFF0B0B0C.toInt()
        private const val ACCENT = 0xFFFFD60A.toInt()

        /** Google's page for signing in to YouTube Music; it returns to music.youtube.com when done. */
        private const val START = "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube&uilel=3&passive=true" +
            "&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26app%3Ddesktop" +
            "%26next%3Dhttps%253A%252F%252Fmusic.youtube.com%252F"

        fun intent(context: Context, profile: String): Intent =
            Intent(context, SignInActivity::class.java).putExtra(EXTRA_PROFILE, profile)
    }
}
