package io.github.boomboom.ui.sound

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.boomboom.logic.EqualizerStore
import io.github.boomboom.logic.SoundSettings
import io.github.boomboom.ui.design.BoomColor
import io.github.boomboom.ui.design.BoomFont
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Every horizontal control on the sound screen: its range, default, detents, words and how it reaches the store.
 * Sliders take one of these rather than lambdas, so Compose can tell an unchanged slider from a changed one and skip
 * the others while one moves.
 */
internal enum class SoundControl(val title: String, val tag: String) {
    PREAMP("Preamp", "preamp"),
    BASS("Bass", "bass"),
    TREBLE("Treble", "treble"),
    BOOST("Volume boost", "boost"),
    DEEP_BASS("Deep bass", "deepBass"),
    AIR("Air", "air"),
    WIDTH("Stereo width", "width"),
    BALANCE("Balance", "balance"),
    CROSSFEED("Headphone crossfeed", "crossfeed");

    val range: ClosedFloatingPointRange<Float>
        get() = when (this) {
            PREAMP -> EqualizerStore.GAIN_RANGE
            BASS, TREBLE -> EqualizerStore.TONE_RANGE
            BOOST -> EqualizerStore.BOOST_RANGE
            DEEP_BASS, AIR, CROSSFEED -> EqualizerStore.AMOUNT_RANGE
            WIDTH -> EqualizerStore.WIDTH_RANGE
            BALANCE -> EqualizerStore.BALANCE_RANGE
        }

    /** Where a double tap puts it, and where its coloured fill starts. */
    val origin: Float get() = if (this == WIDTH) 1f else 0f

    /** Values the slider settles on with a haptic. */
    val detents: List<Float>
        get() = when (this) {
            PREAMP, BASS, TREBLE, BALANCE -> listOf(0f)
            WIDTH -> listOf(1f)
            BOOST, DEEP_BASS, AIR, CROSSFEED -> emptyList()
        }

    /** The finest step: a tenth of a decibel, or a percent. */
    val step: Float
        get() = when (this) {
            PREAMP, BASS, TREBLE, BOOST -> 0.1f
            DEEP_BASS, AIR, CROSSFEED, WIDTH, BALANCE -> 0.01f
        }

    /** One step for TalkBack's volume keys or swipe. */
    val accessibilityStep: Float
        get() = when (this) {
            PREAMP, BASS, TREBLE, BOOST -> 0.5f
            DEEP_BASS, AIR, CROSSFEED, WIDTH, BALANCE -> 0.05f
        }

    /** The words under the track's start, middle (when it has one) and end. */
    val scale: Triple<String, String?, String>
        get() = when (this) {
            PREAMP, BASS, TREBLE -> Triple("−12", "0", "+12")
            BOOST -> Triple("0", "+6", "+12 dB")
            DEEP_BASS, AIR, CROSSFEED -> Triple("Off", null, "100%")
            WIDTH -> Triple("Mono", "As recorded", "Wide")
            BALANCE -> Triple("L", "Centre", "R")
        }

    fun value(settings: SoundSettings): Float = when (this) {
        PREAMP -> settings.preamp
        BASS -> settings.bass
        TREBLE -> settings.treble
        BOOST -> settings.boost
        DEEP_BASS -> settings.deepBass
        AIR -> settings.air
        WIDTH -> settings.width
        BALANCE -> settings.balance
        CROSSFEED -> settings.crossfeed
    }

    fun apply(value: Float, equalizer: EqualizerStore) {
        // Moving a control means wanting to hear it: with the master switch off nothing would change.
        if (!equalizer.settings.isEnabled) equalizer.setEnabled(true)
        when (this) {
            PREAMP -> equalizer.setPreamp(value)
            BASS -> equalizer.setBass(value)
            TREBLE -> equalizer.setTreble(value)
            BOOST -> equalizer.setBoost(value)
            DEEP_BASS -> equalizer.setDeepBass(value)
            AIR -> equalizer.setAir(value)
            WIDTH -> equalizer.setWidth(value)
            BALANCE -> equalizer.setBalance(value)
            CROSSFEED -> equalizer.setCrossfeed(value)
        }
    }

    fun readout(value: Float): String = when (this) {
        PREAMP, BASS, TREBLE, BOOST -> decibels(value)
        DEEP_BASS, AIR, CROSSFEED, WIDTH -> "${(value * 100).roundToInt()}%"
        BALANCE -> if (abs(value) < 0.005f) "Centre" else "${if (value < 0) "L" else "R"} ${(abs(value) * 100).roundToInt()}%"
    }

    fun accessibilityValue(value: Float): String = when (this) {
        BOOST -> "${decibels(value)}, ${percent(value)} level"
        WIDTH -> if (value == 0f) "Mono" else if (value == 1f) "100%, as recorded" else "${(value * 100).roundToInt()}%"
        else -> readout(value)
    }

    /** Clamped, stepped, and held on a detent within 2 % of the range. */
    fun snapped(raw: Float): Float {
        val clamped = raw.coerceIn(range)
        val zone = (range.endInclusive - range.start) * 0.02f
        detents.firstOrNull { abs(clamped - it) < zone }?.let { return it }
        return quantised(clamped)
    }

    fun quantised(value: Float): Float {
        val stepped = (value / step).roundToInt() * step
        // Keeps 0.1 steps from drifting to 0.30000001 in the readout and the store.
        val tidy = (stepped * 100).roundToInt() / 100f
        return tidy.coerceIn(range) + 0f
    }

    companion object {
        /** "0.0 dB", "+3.5 dB", "−2.0 dB". */
        fun decibels(value: Float): String = "${faderReadout(value)} dB"

        /** The boost as a level: +6 dB is about twice the level, 200 %. */
        fun percent(boost: Float): String = "${(10.0.pow(boost / 20.0) * 100).roundToInt()}%"
    }
}

/** "+3.5", "0.0", "−2.0": one decimal, a true minus sign. */
internal fun faderReadout(gain: Float): String {
    val rounded = (gain * 10).roundToInt() / 10f
    if (rounded == 0f) return "0.0"
    return "%+.1f".format(java.util.Locale.ROOT, rounded).replace("-", "−")
}

/** A finger on a slider. Changed only through `move`. */
private sealed class SliderTouch {
    data object Idle : SliderTouch()

    /** Moving the value relative to [startValue], which it had when the finger was at [startX]. */
    data class Dragging(val startX: Float, val startValue: Float) : SliderTouch()
}

/** Room at each end of the track for the thumb. */
private val TrackInset = 12.dp

/**
 * A horizontal slider for the sound screen. A sideways drag anywhere on it moves the value from where it is (the
 * thumb does not jump to the finger); a drag that starts upward or downward scrolls the page and leaves the value
 * alone; a double tap returns it to its default. Detents and the ends give a haptic.
 *
 * @param showsHeader Shows the title and readout above the track (the boost's large readout draws its own).
 */
@Composable
internal fun SoundSlider(
    control: SoundControl,
    value: Float,
    isEnabled: Boolean,
    equalizer: EqualizerStore,
    modifier: Modifier = Modifier,
    showsHeader: Boolean = true,
    detail: String? = null,
) {
    val haptics = rememberSoundHaptics()
    var touch by remember { mutableStateOf<SliderTouch>(SliderTouch.Idle) }
    var trackWidth by remember { mutableFloatStateOf(1f) }
    val isDragging = touch != SliderTouch.Idle
    val insetPx = with(LocalDensity.current) { TrackInset.toPx() }

    fun move(next: SliderTouch) {
        if (touch != next) touch = next
    }

    fun set(next: Float) {
        val old = control.value(equalizer.settings)
        if (next == old) return
        control.apply(next, equalizer)
        if (next in control.detents) haptics.detent()
        if (next == control.range.start || next == control.range.endInclusive) haptics.limit()
    }

    fun fraction(of: Float): Float {
        val range = control.range
        return (of.coerceIn(range) - range.start) / (range.endInclusive - range.start)
    }

    Column(
        modifier
            .fillMaxWidth()
            .soundTouch(
                FingerMode.HORIZONTAL,
                onDoubleTap = {
                    if (control.value(equalizer.settings) != control.origin) {
                        control.apply(control.origin, equalizer)
                        haptics.detent()
                    }
                },
            ) { event ->
                when (event) {
                    is FingerEvent.Began -> move(SliderTouch.Dragging(event.position.x, control.value(equalizer.settings)))
                    is FingerEvent.Moved -> {
                        val dragging = touch as? SliderTouch.Dragging ?: return@soundTouch
                        val span = control.range.endInclusive - control.range.start
                        val usable = maxOf(trackWidth - 2 * insetPx, 1f)
                        set(control.snapped(dragging.startValue + (event.position.x - dragging.startX) / usable * span))
                    }
                    is FingerEvent.Ended, FingerEvent.Cancelled -> move(SliderTouch.Idle)
                }
            }
            .semantics(mergeDescendants = true) {
                contentDescription = control.title
                stateDescription = control.accessibilityValue(value)
                progressBarRangeInfo = ProgressBarRangeInfo(value, control.range)
                setProgress { target ->
                    control.apply(control.quantised(target), equalizer)
                    true
                }
                customActions = listOf(CustomAccessibilityAction("Reset") { control.apply(control.origin, equalizer); true })
            }
            .testTag("sound.${control.tag}")
            .padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (showsHeader) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(control.title, style = BoomFont.itemTitle, color = BoomColor.textPrimary, modifier = Modifier.weight(1f))
                Text(
                    control.readout(value), style = BoomFont.time.copy(fontWeight = FontWeight.SemiBold),
                    color = if (isDragging) BoomColor.onAccent else BoomColor.textPrimary, maxLines = 1,
                    modifier = Modifier
                        .background(
                            if (isDragging) BoomColor.accent else Color.White.copy(alpha = 0.08f), RoundedCornerShape(5.dp),
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                        .testTag("sound.${control.tag}.readout"),
                )
            }
            if (detail != null) Text(detail, style = BoomFont.caption, color = BoomColor.textSecondary)
        }
        SliderTrack(
            fraction = fraction(value), origin = fraction(control.origin), marks = control.detents.map { fraction(it) },
            isDragging = isDragging, isEnabled = isEnabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .onSizeChanged { trackWidth = maxOf(it.width.toFloat(), 1f) },
        )
        ScaleLabels(control, fraction(control.detents.firstOrNull() ?: ((control.range.start + control.range.endInclusive) / 2)))
    }
}

@Composable
private fun ScaleLabels(control: SoundControl, middleFraction: Float) {
    val (start, middle, end) = control.scale
    val style = BoomFont.caption.copy(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(14.dp),
    ) {
        Text(start, style = style, color = BoomColor.textTertiary, modifier = Modifier.align(Alignment.CenterStart))
        Text(end, style = style, color = BoomColor.textTertiary, modifier = Modifier.align(Alignment.CenterEnd))
        if (middle != null) {
            val usable = maxWidth - TrackInset * 2
            val centre = TrackInset + usable * middleFraction
            var labelWidth by remember { mutableFloatStateOf(0f) }
            Text(
                middle, style = style, color = BoomColor.textTertiary, maxLines = 1,
                modifier = Modifier
                    .onSizeChanged { labelWidth = it.width.toFloat() }
                    .offset { IntOffset((centre.toPx() - labelWidth / 2).roundToInt(), 0) },
            )
        }
    }
}

/** The track: a groove, the fill from the origin to the value, the detent marks and the thumb. */
@Composable
private fun SliderTrack(
    fraction: Float,
    origin: Float,
    marks: List<Float>,
    isDragging: Boolean,
    isEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val inset = TrackInset.toPx()
        val usable = size.width - 2 * inset
        val middle = size.height / 2
        fun x(at: Float) = inset + at * usable
        val tint = if (isEnabled) BoomColor.accent else Color.White.copy(alpha = 0.5f)
        val groove = 2.5.dp.toPx()

        drawRoundRect(
            Color.White.copy(alpha = 0.12f), Offset(inset - 2.dp.toPx(), middle - groove),
            Size(usable + 4.dp.toPx(), groove * 2), CornerRadius(groove),
        )
        for (mark in marks) {
            drawRoundRect(
                Color.White.copy(alpha = 0.35f), Offset(x(mark) - 1.dp.toPx(), middle - 9.dp.toPx()),
                Size(2.dp.toPx(), 18.dp.toPx()), CornerRadius(1.dp.toPx()),
            )
        }
        val from = minOf(x(origin), x(fraction))
        val to = maxOf(x(origin), x(fraction))
        if (to - from > 0.5f) drawRoundRect(tint, Offset(from, middle - groove), Size(to - from, groove * 2), CornerRadius(groove))
        val centre = Offset(x(fraction), middle)
        if (isDragging) drawCircle(tint.copy(alpha = 0.18f), 20.dp.toPx(), centre)
        val radius = (if (isDragging) 13 else 11).dp.toPx()
        drawCircle(Color.Black.copy(alpha = 0.35f), radius + 1.5.dp.toPx(), centre + Offset(0f, 1.dp.toPx()))
        drawCircle(if (isEnabled) Color.White else Color(0xFFB3B3B3), radius, centre)
        drawCircle(tint, 3.5.dp.toPx(), centre)
    }
}
