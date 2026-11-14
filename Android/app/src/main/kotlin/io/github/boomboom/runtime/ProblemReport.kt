package io.github.boomboom.runtime

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import androidx.webkit.WebViewCompat
import java.io.File

/**
 * "Report a problem": the app's own record of what it did, with the phone's make and Android version on top, as a
 * text file the listener sends to whoever gave them the app, with any app they choose. The record holds times,
 * song ids, formats and states; no account details, cookies or search words (see [BoomTrace]). Nothing is sent
 * anywhere unless the listener shares it.
 */
object ProblemReport {
    fun share(activity: Activity) {
        val app = activity.application as BoomApplication
        val browser = try {
            WebViewCompat.getCurrentWebViewPackage(activity)?.let { "${it.packageName} ${it.versionName}" }
        } catch (failure: RuntimeException) {
            null
        }
        val version = try {
            activity.packageManager.getPackageInfo(activity.packageName, 0).versionName
        } catch (failure: Exception) {
            null
        }
        val text = buildString {
            append("BoomBoom ${version ?: "?"} for Android\n")
            append("Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
            append("Processor: ${Build.SUPPORTED_ABIS.joinToString()}\n")
            append("Browser part: ${browser ?: "none"}\n")
            app.startFailure?.let { append("Could not start: $it\n") }
            append("\n")
            append(BoomTrace.read() ?: "(no record yet)\n")
        }
        try {
            val folder = File(activity.cacheDir, "reports").apply { mkdirs() }
            val file = File(folder, "boomboom-report.txt")
            file.writeText(text)
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.reports", file)
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, "BoomBoom problem report")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            activity.startActivity(Intent.createChooser(send, "Send the report with"))
        } catch (failure: java.io.IOException) {
            BoomTrace.note("the problem report could not be written")
        } catch (failure: IllegalArgumentException) {
            BoomTrace.note("the problem report could not be shared")
        } catch (failure: ActivityNotFoundException) {
            BoomTrace.note("no app to share the problem report with")
        }
    }
}
