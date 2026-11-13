package io.github.boomboom.logic

import kotlin.math.PI
import kotlin.math.exp

/**
 * The turn of the listener's head, from headphones that report it, for the spatial stage: the two virtual
 * loudspeakers stay where they are while the head turns. The part of the iPhone app's `HeadTracker` that is not the
 * sensor: the engine (or whatever reads the sensor) calls [take] with each reading.
 *
 * "Where they are" follows the head slowly: someone who turns a corner, or settles into a chair facing another way,
 * has the loudspeakers in front again after a few seconds, as with any head-tracked sound.
 *
 * [take] may be called from the sensor's own thread; [onTurn] is called on that thread with the head's turn to the
 * right of the loudspeakers, in radians.
 */
class HeadTracker(private val onTurn: (Float) -> Unit) {
    private val lock = Any()

    /** The direction the loudspeakers face from, as a yaw in radians (the headphones' frame). */
    private var centre: Double? = null
    private var lastTime = 0.0

    /** A new run of readings: the head's direction now is straight ahead. */
    fun start() {
        synchronized(lock) {
            centre = null
            lastTime = 0.0
        }
    }

    fun stop() {
        onTurn(0f)
    }

    /** One reading. [yaw] grows as the head turns to the left (seen from above, anticlockwise). */
    fun take(yaw: Double, atSeconds: Double) {
        val turn: Double = synchronized(lock) {
            val middle = centre
            if (middle == null) {
                centre = yaw
                lastTime = atSeconds
                0.0
            } else {
                // The shortest way round from the centre to where the head points now.
                var offset = (yaw - middle) % (2 * PI)
                if (offset > PI) offset -= 2 * PI
                if (offset <= -PI) offset += 2 * PI
                // The centre drifts after the head.
                val elapsed = minOf(maxOf(atSeconds - lastTime, 0.0), 0.5)
                val share = 1 - exp(-elapsed / FOLLOW_SECONDS)
                centre = middle + offset * share
                lastTime = atSeconds
                offset * (1 - share)
            }
        }
        // To the right is the other way round from yaw.
        onTurn((-turn).toFloat())
    }

    private companion object {
        /** Seconds for the loudspeakers to come round to where the head now points. */
        const val FOLLOW_SECONDS = 8.0
    }
}
