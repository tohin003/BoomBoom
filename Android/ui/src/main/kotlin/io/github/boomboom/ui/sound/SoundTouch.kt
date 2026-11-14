package io.github.boomboom.ui.sound

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlin.math.abs

// The sound screen's controls sit inside a scrolling page, where two things want the same finger: the control and
// the page's scroll. Each control reads its own finger with `soundTouch`, on the control itself (never on a clear
// layer laid over it, which on the iPhone's first version took every fader drag away from the faders), and says who
// owns the finger by consuming its moves: the page scrolls only on moves nobody consumed.

/** What a finger did on a control, in the control's own coordinates; times are the touches' own (milliseconds). */
internal sealed class FingerEvent {
    /**
     * The control owns the finger from here: at touch-down ([FingerMode.IMMEDIATE]), or once a drag turned out
     * sideways ([FingerMode.HORIZONTAL], with the point where the finger first came down).
     */
    data class Began(val position: Offset, val time: Long) : FingerEvent()
    data class Moved(val position: Offset) : FingerEvent()
    data class Ended(val position: Offset, val time: Long) : FingerEvent()

    /** The system took the touch away, or the control left the screen mid-touch. */
    data object Cancelled : FingerEvent()
}

internal enum class FingerMode {
    /** Takes every touch at once: nothing behind it scrolls while the finger is down (the faders, "Original"). */
    IMMEDIATE,

    /** Takes a drag only when it starts sideways; one that starts upward or downward is left to the page (sliders). */
    HORIZONTAL,
}

/**
 * Reads one finger for a sound control. [onDoubleTap] (horizontal controls) fires on two quick taps that do not move.
 */
@Composable
internal fun Modifier.soundTouch(
    mode: FingerMode,
    onDoubleTap: (() -> Unit)? = null,
    onEvent: (FingerEvent) -> Unit,
): Modifier {
    val latestEvent by rememberUpdatedState(onEvent)
    val latestDoubleTap by rememberUpdatedState(onDoubleTap)
    return pointerInput(mode) {
        val slop = viewConfiguration.touchSlop
        val doubleTapWindow = viewConfiguration.doubleTapTimeoutMillis
        var lastTap = Long.MIN_VALUE / 2
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val start = down.position
            var owned = false
            var finished = false
            if (mode == FingerMode.IMMEDIATE) {
                down.consume()
                owned = true
                latestEvent(FingerEvent.Began(start, down.uptimeMillis))
            }
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        if (owned) {
                            change.consume()
                            finished = true
                            latestEvent(FingerEvent.Ended(change.position, change.uptimeMillis))
                        } else if (change.uptimeMillis - down.uptimeMillis < 500) {
                            // A tap that never moved far enough to be a drag.
                            if (change.uptimeMillis - lastTap < doubleTapWindow) {
                                lastTap = Long.MIN_VALUE / 2
                                latestDoubleTap?.invoke()
                            } else {
                                lastTap = change.uptimeMillis
                            }
                        }
                        break
                    }
                    if (owned) {
                        change.consume()
                        latestEvent(FingerEvent.Moved(change.position))
                        continue
                    }
                    // Someone else (the page's scroll) took it.
                    if (change.isConsumed) break
                    val moved = change.position - start
                    if (moved.getDistance() < slop) continue
                    if (abs(moved.x) <= abs(moved.y)) break   // upward or downward: the page's
                    owned = true
                    change.consume()
                    latestEvent(FingerEvent.Began(start, down.uptimeMillis))
                    latestEvent(FingerEvent.Moved(change.position))
                }
            } finally {
                if (owned && !finished) latestEvent(FingerEvent.Cancelled)
            }
        }
    }
}

/** The screen's haptics, played at the moment a control crosses a mark (not on the next frame). */
internal class SoundHaptics(private val feedback: HapticFeedback) {
    /** A value landed on its detent (0 dB, centre, 100 %). */
    fun detent() = feedback.performHapticFeedback(HapticFeedbackType.SegmentTick)

    /** A value reached the end of its range. */
    fun limit() = feedback.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)

    /** A stroke crossed into the next band; a button was pressed. */
    fun tick() = feedback.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)

    fun toggle(isOn: Boolean) = feedback.performHapticFeedback(if (isOn) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
}

@Composable
internal fun rememberSoundHaptics(): SoundHaptics {
    val feedback = LocalHapticFeedback.current
    return remember(feedback) { SoundHaptics(feedback) }
}
