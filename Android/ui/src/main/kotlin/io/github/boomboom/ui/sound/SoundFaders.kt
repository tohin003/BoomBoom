package io.github.boomboom.ui.sound

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.boomboom.logic.EqualizerStore
import io.github.boomboom.ui.design.BoomColor
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/** A finger on the faders. Changed only through `move`. */
internal sealed class FaderTouch {
    data object Idle : FaderTouch()

    /** Down on [band], not yet moved far enough to tell an adjustment from a stroke (or a tap). */
    data class Pressed(override val band: Int, val start: Offset, val time: Long) : FaderTouch()

    /** Moving one band relative to where it was picked up: [startGain] at [startY]. */
    data class Adjusting(override val band: Int, val startY: Float, val startGain: Float) : FaderTouch()

    /** Drawing a curve: each band the finger reaches takes the level under the finger. */
    data class Drawing(override val band: Int, val gain: Float) : FaderTouch()

    open val band: Int? get() = null
}

/** The fader area's height: 220 dp of travel plus room for the cap at either end. */
internal val FaderHeight = 236.dp
private val CapHeight = 16.dp
private val Inset = CapHeight / 2
private val RANGE = EqualizerStore.GAIN_RANGE.endInclusive
private val COUNT = EqualizerStore.bands.size

/** Steps of 0.1 dB, clamped, with a gentle pull to 0. */
internal fun snappedGain(gain: Double): Float {
    val clamped = gain.coerceIn(-RANGE.toDouble(), RANGE.toDouble())
    if (abs(clamped) < 0.4) return 0f
    return ((clamped * 10).roundToInt() / 10.0).toFloat()
}

/**
 * The ten band faders. A drag that starts upward or downward moves the band under the finger from where it is (the
 * cap does not jump to the finger); a drag that starts sideways draws a curve across the bands it passes. Double tap
 * a band to zero it. The faders take the finger at once, so the page does not scroll under them.
 *
 * @param onActiveBand The band under the finger, for the graph to light.
 */
@Composable
internal fun SoundFaders(
    gains: List<Float>,
    isEnabled: Boolean,
    equalizer: EqualizerStore,
    activeBand: Int?,
    onActiveBand: (Int?) -> Unit,
) {
    val haptics = rememberSoundHaptics()
    var touch by remember { mutableStateOf<FaderTouch>(FaderTouch.Idle) }
    var lastTap by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    var box by remember { mutableStateOf(IntSize(1, 1)) }
    val insetPx = with(LocalDensity.current) { Inset.toPx() }
    val sixDp = with(LocalDensity.current) { 6.dp.toPx() }

    fun move(next: FaderTouch) {
        if (touch == next) return
        touch = next
        if (activeBand != next.band) onActiveBand(next.band)
    }

    fun travel() = maxOf(box.height - 2 * insetPx, 1f)
    fun bandAt(x: Float) = (x / (box.width.toFloat() / COUNT)).toInt().coerceIn(0, COUNT - 1)
    fun gainAt(y: Float): Double = (RANGE - ((y - insetPx) / travel()) * 2 * RANGE).toDouble()

    fun set(gain: Float, band: Int) {
        val old = equalizer.settings.gains[band]
        if (gain == old) return
        equalizer.setGain(gain, band)
        if (gain == 0f) haptics.detent()
        if (abs(gain) == RANGE && abs(old) != RANGE) haptics.limit()
    }

    fun adjust(band: Int, startY: Float, startGain: Float, y: Float) {
        val raw = startGain + ((startY - y) / travel()) * 2 * RANGE
        set(snappedGain(raw.toDouble()), band)
    }

    fun draw(last: Int, lastGain: Float, point: Offset) {
        val band = bandAt(point.x)
        val target = snappedGain(gainAt(point.y))
        // A fast finger skips columns: fill them in so the stroke draws a continuous curve.
        if (abs(band - last) > 1) {
            val step = if (band > last) 1 else -1
            var skipped = last + step
            while (skipped != band) {
                val fraction = (skipped - last).toDouble() / (band - last)
                set(snappedGain(lastGain + (target - lastGain) * fraction), skipped)
                skipped += step
            }
        }
        if (band != last) haptics.tick()
        set(target, band)
        move(FaderTouch.Drawing(band, target))
    }

    fun tapped(band: Int, time: Long) {
        val before = lastTap
        if (before != null && before.first == band && time - before.second < 400) {
            lastTap = null
            if (equalizer.settings.gains[band] != 0f) {
                equalizer.setGain(0f, band)
                haptics.detent()
            }
        } else {
            lastTap = band to time
        }
    }

    Column(Modifier.testTag("sound.fadersBlock"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(ScaleWidth))
            EqualizerStore.bands.forEach { band -> Readout(gains[band.id], touch.band == band.id, Modifier.weight(1f)) }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .height(FaderHeight),
        ) {
            val measurer = rememberTextMeasurer()
            Canvas(
                Modifier
                    .width(ScaleWidth)
                    .fillMaxHeight(),
            ) { drawDecibelScale(measurer) { faderY(it, size.height, Inset.toPx()) } }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .onSizeChanged { box = it }
                    .soundTouch(FingerMode.IMMEDIATE) { event ->
                        when (event) {
                            is FingerEvent.Began -> move(FaderTouch.Pressed(bandAt(event.position.x), event.position, event.time))
                            is FingerEvent.Moved -> when (val now = touch) {
                                is FaderTouch.Pressed -> {
                                    val dx = event.position.x - now.start.x
                                    val dy = event.position.y - now.start.y
                                    if (hypot(dx, dy) < sixDp) return@soundTouch
                                    if (abs(dy) >= abs(dx)) {
                                        val startGain = equalizer.settings.gains[now.band]
                                        move(FaderTouch.Adjusting(now.band, now.start.y, startGain))
                                        adjust(now.band, now.start.y, startGain, event.position.y)
                                    } else {
                                        val gain = snappedGain(gainAt(now.start.y))
                                        set(gain, now.band)
                                        move(FaderTouch.Drawing(now.band, gain))
                                        draw(now.band, gain, event.position)
                                    }
                                }
                                is FaderTouch.Adjusting -> adjust(now.band, now.startY, now.startGain, event.position.y)
                                is FaderTouch.Drawing -> draw(now.band, now.gain, event.position)
                                FaderTouch.Idle -> Unit
                            }
                            is FingerEvent.Ended -> {
                                val now = touch
                                if (now is FaderTouch.Pressed && event.time - now.time < 350) tapped(now.band, event.time)
                                move(FaderTouch.Idle)
                            }
                            FingerEvent.Cancelled -> move(FaderTouch.Idle)
                        }
                    }
                    .testTag("sound.faders"),
            ) {
                FaderCanvas(gains, touch.band, isEnabled, touch == FaderTouch.Idle)
                // For TalkBack only: one adjustable element per band. Semantics take no touches, so the faders' own
                // finger reading above gets every finger.
                Row(Modifier.fillMaxSize()) {
                    EqualizerStore.bands.forEach { band ->
                        val gain = gains[band.id]
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .semantics {
                                    contentDescription = "${band.label} hertz"
                                    stateDescription = "${faderReadout(gain)} dB"
                                    progressBarRangeInfo = ProgressBarRangeInfo(gain, EqualizerStore.GAIN_RANGE, steps = 47)
                                    setProgress { target ->
                                        equalizer.setGain((target * 2).roundToInt() / 2f, band.id)
                                        true
                                    }
                                    customActions = listOf(
                                        CustomAccessibilityAction("Set to 0 dB") { equalizer.setGain(0f, band.id); true },
                                    )
                                }
                                .testTag("sound.band.${band.id}"),
                        )
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(ScaleWidth))
            EqualizerStore.bands.forEach { band ->
                Text(
                    band.label, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                    color = if (touch.band == band.id) BoomColor.accent else BoomColor.textTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun Readout(gain: Float, isActive: Boolean, modifier: Modifier) {
    Box(modifier.padding(horizontal = 1.dp), contentAlignment = Alignment.Center) {
        Text(
            faderReadout(gain),
            style = TextStyle(
                fontSize = 11.sp, fontWeight = if (isActive) FontWeight.Black else FontWeight.SemiBold,
                fontFeatureSettings = "tnum",
            ),
            color = when {
                isActive -> BoomColor.onAccent
                gain == 0f -> BoomColor.textTertiary
                else -> BoomColor.textPrimary
            },
            maxLines = 1, softWrap = false,
            modifier = Modifier
                .fillMaxWidth()
                .background(if (isActive) BoomColor.accent else Color.Transparent, RoundedCornerShape(4.dp))
                .padding(vertical = 3.dp),
            textAlign = TextAlign.Center,
        )
    }
}

/** Where a gain sits on a fader [height] pixels tall. */
internal fun faderY(gain: Double, height: Float, inset: Float): Float =
    inset + ((RANGE - gain) / (2 * RANGE)).toFloat() * (height - 2 * inset)

/** All ten faders in one drawing: grooves, the fill from 0 dB, and console-style caps. Glides when a preset is chosen. */
@Composable
private fun FaderCanvas(gains: List<Float>, activeBand: Int?, isEnabled: Boolean, glides: Boolean) {
    val shown = rememberGlide(gains.map { it.toDouble() }, glides)
    Canvas(Modifier.fillMaxSize()) {
        val inset = Inset.toPx()
        fun y(gain: Double) = faderY(gain, size.height, inset)
        val column = size.width / COUNT
        val tint = if (isEnabled) BoomColor.accent else Color.White.copy(alpha = 0.45f)
        for (decibels in listOf(-12.0, -6.0, 6.0, 12.0)) {
            drawLine(
                Color.White.copy(alpha = 0.06f), Offset(0f, y(decibels)), Offset(size.width, y(decibels)), 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx())),
            )
        }
        drawLine(Color.White.copy(alpha = 0.18f), Offset(0f, y(0.0)), Offset(size.width, y(0.0)), 1.dp.toPx())
        val values = shown()
        for (index in 0 until COUNT) {
            val gain = values.getOrElse(index) { 0.0 }
            val x = column * (index + 0.5f)
            val isActive = index == activeBand
            if (isActive) {
                drawRoundRect(
                    BoomColor.accent.copy(alpha = 0.08f), Offset(x - column / 2 + 1, 0f), Size(column - 2, size.height),
                    CornerRadius(6.dp.toPx()),
                )
            }
            val grooveTop = y(12.0)
            val grooveSize = Size(4.dp.toPx(), y(-12.0) - grooveTop)
            drawRoundRect(Color.Black.copy(alpha = 0.9f), Offset(x - 2.dp.toPx(), grooveTop), grooveSize, CornerRadius(2.dp.toPx()))
            drawRoundRect(
                Color.White.copy(alpha = 0.10f), Offset(x - 2.dp.toPx(), grooveTop), grooveSize, CornerRadius(2.dp.toPx()),
                style = Stroke(0.5.dp.toPx()),
            )
            val capY = y(gain)
            drawRoundRect(
                tint, Offset(x - 1.5.dp.toPx(), minOf(y(0.0), capY)), Size(3.dp.toPx(), abs(y(0.0) - capY)),
                CornerRadius(1.5.dp.toPx()),
            )
            val capWidth = minOf(column - 6.dp.toPx(), 28.dp.toPx())
            val capHeight = CapHeight.toPx()
            val capTop = Offset(x - capWidth / 2, capY - capHeight / 2)
            // A soft shadow under the cap.
            drawRoundRect(
                Color.Black.copy(alpha = 0.45f), capTop + Offset(0f, 2.dp.toPx()), Size(capWidth, capHeight),
                CornerRadius(4.dp.toPx()),
            )
            drawRoundRect(
                if (isActive) {
                    Brush.verticalGradient(listOf(BoomColor.accent, BoomColor.accent), capTop.y, capTop.y + capHeight)
                } else {
                    Brush.verticalGradient(
                        listOf(Color(if (isEnabled) 0xFFEBEBEB else 0xFF999999), Color(if (isEnabled) 0xFFADADAD else 0xFF6B6B6B)),
                        capTop.y, capTop.y + capHeight,
                    )
                },
                capTop, Size(capWidth, capHeight), CornerRadius(4.dp.toPx()),
            )
            drawLine(
                Color.Black.copy(alpha = 0.75f), Offset(capTop.x + 4.dp.toPx(), capY), Offset(capTop.x + capWidth - 4.dp.toPx(), capY),
                1.5.dp.toPx(),
            )
        }
    }
}
