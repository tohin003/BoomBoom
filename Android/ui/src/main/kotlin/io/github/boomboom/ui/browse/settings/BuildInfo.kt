package io.github.boomboom.ui.browse.settings

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Facts about this installed copy of the app, read from the phone's own record of it (the screens cannot see the app's build settings). */
object BuildInfo {
    /** "1.0 (12)"; "–" for what cannot be read. */
    fun version(context: Context): String = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        info.versionName?.let { "$it ($code)" } ?: "–"
    } catch (missing: PackageManager.NameNotFoundException) {
        "–"
    }
}
