package io.github.boomboom.runtime

import android.content.Intent

/**
 * The released app has no command-line test hooks: this stands in for the debug build's object of the same name and
 * does nothing, whatever an intent carries.
 */
object TestHooks {
    @Suppress("UNUSED_PARAMETER")
    fun take(intent: Intent?, runtime: BoomRuntime) = Unit
}
