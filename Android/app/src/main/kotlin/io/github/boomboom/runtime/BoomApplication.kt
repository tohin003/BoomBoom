package io.github.boomboom.runtime

import android.app.Application

/** Puts the runtime together when the process starts, before any activity or the playback service. */
class BoomApplication : Application() {
    lateinit var runtime: BoomRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        BoomTrace.start(this)
        BoomTrace.note("process start")
        runtime = BoomRuntime(this)
    }
}
