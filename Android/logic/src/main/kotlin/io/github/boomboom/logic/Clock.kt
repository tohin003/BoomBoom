package io.github.boomboom.logic

/**
 * What the stores ask for the time. Everything that waits goes through the `CoroutineScope` it was given (so a test
 * dispatcher controls it); everything that needs to know "now" goes through this, so a test can make the two agree
 * (`Clock { testScheduler.currentTime }`).
 */
fun interface Clock {
    /** Milliseconds since 1970: the only unit the stores use for moments in time. */
    fun nowMillis(): Long

    companion object {
        val System: Clock = Clock { java.lang.System.currentTimeMillis() }
    }
}
