package io.github.boomboom.spike

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.util.UUID

/**
 * The proof that the capture works in Android's browser engine: a WebView on YouTube Music with the same script
 * the iPhone app uses, asked for one song, with everything the script reports written to the log ("BOOM ...").
 *
 *   adb shell am start -n io.github.boomboom.android/io.github.boomboom.spike.SpikeActivity --es song <videoId>
 */
class SpikeActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var status: TextView
    private val main = Handler(Looper.getMainLooper())
    private val token = UUID.randomUUID().toString()
    private var song = "dQw4w9WgXcQ"
    private val started = System.nanoTime()

    // What has arrived, counted on the bridge's thread.
    @Volatile private var pieces = 0
    @Volatile private var bytes = 0L
    @Volatile private var buffers = 0
    @Volatile private var asked = false
    @Volatile private var ended = false
    private var pumpTarget = 0.0

    private fun seconds() = (System.nanoTime() - started) / 1e9
    private fun log(text: String) {
        Log.i("BOOM", String.format("%.1f %s", seconds(), text))
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra("song")?.let { song = it }
        WebView.setWebContentsDebuggingEnabled(true)
        web = WebView(this)
        status = TextView(this).apply { textSize = 14f; setPadding(24, 48, 24, 24) }
        val root = FrameLayout(this)
        root.addView(web, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(status)
        setContentView(root)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            // The desktop web app, as on the iPhone: it has the media-source player.
            userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                log("page finished $url")
            }
        }
        web.webChromeClient = WebChromeClient()
        web.addJavascriptInterface(Bridge(), "BoomNative")

        val tap = assets.open("boom-tap.js").bufferedReader().use { it.readText() }
        // The script looks for the iPhone's message handler; here the same name leads to this app.
        val shim = "window.webkit = window.webkit || {}; window.webkit.messageHandlers = { boom: { postMessage: " +
            "function (m) { BoomNative.post(JSON.stringify(m)); } } };"
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, shim + "\n" + tap, setOf("https://music.youtube.com"))
            log("script set for document start; WebView ${WebViewCompat.getCurrentWebViewPackage(this)?.versionName}")
        } else {
            log("this WebView cannot run a script at document start")
        }
        web.loadUrl("https://music.youtube.com/")
        main.postDelayed(::tick, 500)
    }

    /** Twice a second: ask for the song once the player is there, then keep the page fetching, and show the count. */
    private fun tick() {
        status.text = String.format("%.0f s  buffers %d  pieces %d  %.1f MB%s", seconds(), buffers, pieces, bytes / 1e6, if (ended) "  END" else "")
        if (asked && !ended) {
            web.evaluateJavascript("JSON.stringify(window.__boom ? window.__boom.pump('$token', $pumpTarget) : null)") { answer ->
                val text = answer?.trim('"')?.replace("\\\"", "\"") ?: return@evaluateJavascript
                runCatching {
                    val reply = JSONObject(text)
                    // Move the paused playhead to the end of what the page has, so that it fetches on from there.
                    val end = reply.optDouble("audioEnd", Double.NaN)
                    if (!end.isNaN() && end - 0.4 > pumpTarget) pumpTarget = end - 0.4
                    if ((seconds() * 2).toInt() % 6 == 0) log("pump $text")
                }
            }
        }
        main.postDelayed(::tick, 500)
    }

    private inner class Bridge {
        @JavascriptInterface
        fun post(json: String) {
            val message = runCatching { JSONObject(json) }.getOrNull() ?: return
            when (message.optString("t")) {
                "append" -> {
                    val data = Base64.decode(message.optString("d"), Base64.DEFAULT)
                    if (pieces == 0) log("first piece: ${data.size} bytes, starts ${data.take(16).joinToString("") { "%02x".format(it) }}")
                    pieces += 1
                    bytes += data.size
                }
                "buffer" -> {
                    buffers += 1
                    log("buffer ${message.optString("mime")} for ${message.optString("videoId")}")
                }
                "ready" -> {
                    log("player ready")
                    if (!asked) {
                        asked = true
                        main.post {
                            web.evaluateJavascript("window.__boom.load('$token', '$song')") { log("load answered $it") }
                        }
                    }
                }
                "end" -> {
                    ended = true
                    log("END of stream after $pieces pieces, $bytes bytes")
                }
                "player" -> log("player answer: ${message.optString("videoId")} ${message.optString("status")} chosen=${message.optJSONObject("chosen")} seconds=${message.opt("seconds")}")
                else -> log("event ${json.take(300)}")
            }
        }
    }
}
