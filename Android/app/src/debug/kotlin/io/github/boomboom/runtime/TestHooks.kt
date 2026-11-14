package io.github.boomboom.runtime

import android.content.Intent

/**
 * The command-line test hooks (see [Diagnostics]), in debug builds. The release build has an empty stand-in of the
 * same name: the app's window can be opened by any app on the phone, and nothing in a released app may let one of
 * them play, seek or run script in the tab the listener is signed in to.
 */
object TestHooks {
    fun take(intent: Intent?, runtime: BoomRuntime) = Diagnostics.take(intent, runtime)
}
