package io.github.boomboom

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/**
 * Runs the on-device tests in a process that has a plain application object in place of the app's own. The tests
 * drive the audio engine directly; with the app's machinery alive beside them, its player would take the engine's
 * events before a test could see them (and open a hidden browser tab nobody asked for).
 */
class BareRunner : AndroidJUnitRunner() {
    override fun newApplication(loader: ClassLoader, className: String, context: Context): Application =
        super.newApplication(loader, Application::class.java.name, context)
}
