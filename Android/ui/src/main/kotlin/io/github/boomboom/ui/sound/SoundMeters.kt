package io.github.boomboom.ui.sound

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.boomboom.logic.LevelMeter
import io.github.boomboom.logic.MeterState
import io.github.boomboom.ui.design.BoomColor
import io.github.boomboom.ui.design.BoomFont
import io.github.boomboom.ui.design.BoomMetrics
import kotlin.math.pow
import kotlin.math.roundToInt

// The only views that read `LevelMeter`. It changes thirty times a second; anything that reads it in composition
// recomposes that often, so each meter reads it only while drawing, or through a derived value that changes rarely,
// and the screen around them never touches it.

/** Green-free on purpose: the app's yellow up to −6 dB, deepening to amber, red at full scale. */
private val meterColours = arrayOf(
    0f to BoomColor.accent.copy(alpha = 0.75f), 0.72f to BoomColor.accent, 0.9f to BoomColor.accentDeep, 1f to BoomColor.destructive,
)

private val meterMarks = listOf(-60f, -40f, -30f, -20f, -12f, -6f, -3f, 0f)
private val LabelWidth = 16.dp
private val LightWidth = 30.dp

/** 0…1 along the bar. A gentle curve gives the loud end, where it matters, more room. */
internal fun meterPosition(decibels: Float): Float {
    val linear = (decibels.coerceIn(LevelMeter.FLOOR, 0f) - LevelMeter.FLOOR) / -LevelMeter.FLOOR
    return linear.toDouble().pow(1.6).toFloat()
}

/**
 * Left and right output, −60…0 dBFS: the average (RMS) level solid, the peak as the bar's lighter tip, a peak-hold
 * mark, and a clip light.
 */
@Composable
internal fun SoundOutputMeters(meter: LevelMeter, modifier: Modifier = Modifier) {
    val reading = meter.state.collectAsState()
    val measurer = rememberTextMeasurer()
    // What TalkBack reads changes in whole decibels, not thirty times a second.
    val spoken by remember {
        derivedStateOf {
            val now = reading.value
            "Left ${now.left.roundToInt()} dB, right ${now.right.roundToInt()} dB peak" + if (now.isClipping) ", clipping" else ""
        }
    }
    Column(
        modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "Output level"
                stateDescription = spoken
            }
            .testTag("sound.output"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(38.dp),
        ) { drawMeters(reading, measurer) }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(10.dp),
        ) {
            val width = size.width - LabelWidth.toPx() - LightWidth.toPx()
            for (mark in meterMarks) {
                val label = if (mark == 0f) "0" else "${mark.toInt()}".replace("-", "−")
                drawLabel(measurer, label, Offset(LabelWidth.toPx() + meterPosition(mark) * width, size.height / 2), BoomColor.textTertiary, 0.5f)
            }
            drawLabel(
                measurer, "CLIP", Offset(size.width - LightWidth.toPx() / 2 + 3.dp.toPx(), size.height / 2), BoomColor.textTertiary,
                0.5f, TextStyle(fontSize = 8.sp, fontWeight = FontWeight.Black),
            )
        }
    }
}

private fun DrawScope.drawMeters(reading: State<MeterState>, measurer: androidx.compose.ui.text.TextMeasurer) {
    val now = reading.value
    val barHeight = 14.dp.toPx()
    val labelWidth = LabelWidth.toPx()
    val lightWidth = LightWidth.toPx()
    val width = size.width - labelWidth - lightWidth
    val channels = listOf(
        Triple("L", now.left, now.rmsLeft) to now.holdLeft,
        Triple("R", now.right, now.rmsRight) to now.holdRight,
    )
    channels.forEachIndexed { row, (levels, hold) ->
        val (name, peak, rms) = levels
        val top = row * (size.height - barHeight)
        drawLabel(
            measurer, name, Offset(5.dp.toPx(), top + barHeight / 2), BoomColor.textTertiary, 0.5f,
            TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Black),
        )
        val trackTopLeft = Offset(labelWidth, top)
        val trackSize = Size(width, barHeight)
        val radius = CornerRadius(3.dp.toPx())
        drawRoundRect(Color.White.copy(alpha = 0.07f), trackTopLeft, trackSize, radius)
        val brush = Brush.horizontalGradient(*meterColours, startX = labelWidth, endX = labelWidth + width)
        val clip = androidx.compose.ui.graphics.Path().apply {
            addRoundRect(androidx.compose.ui.geometry.RoundRect(trackTopLeft.x, top, trackTopLeft.x + width, top + barHeight, radius))
        }
        clipPath(clip) {
            val peakX = meterPosition(peak) * width
            if (peakX > 0.5f) drawRect(brush, trackTopLeft, Size(peakX, barHeight), alpha = 0.42f)
            val rmsX = meterPosition(rms) * width
            if (rmsX > 0.5f) drawRect(brush, trackTopLeft, Size(rmsX, barHeight))
            // Segment gaps give the bar its hardware look.
            var x = labelWidth + 5.dp.toPx()
            while (x < labelWidth + width) {
                drawRect(BoomColor.surface.copy(alpha = 0.75f), Offset(x, top), Size(1.5.dp.toPx(), barHeight))
                x += 5.dp.toPx()
            }
        }
        if (hold > LevelMeter.FLOOR + 1) {
            val holdX = labelWidth + meterPosition(hold) * width
            drawRect(
                if (hold >= -0.5f) BoomColor.destructive else BoomColor.textPrimary,
                Offset(minOf(holdX, labelWidth + width - 2.dp.toPx()) - 1.dp.toPx(), top - 1.dp.toPx()),
                Size(2.dp.toPx(), barHeight + 2.dp.toPx()),
            )
        }
        drawRoundRect(
            if (now.isClipping) BoomColor.destructive else Color.White.copy(alpha = 0.08f),
            Offset(size.width - lightWidth + 8.dp.toPx(), top), Size(lightWidth - 10.dp.toPx(), barHeight), radius,
        )
    }
}

/**
 * How hard the limiter is working: a bar that grows downward from 0 to −12 dB, and the figure. Beside the boost so
 * the listener sees the boost being caught.
 */
@Composable
internal fun LimiterMeter(meter: LevelMeter, isLimiterOn: Boolean, isEnabled: Boolean, modifier: Modifier = Modifier) {
    val reading = meter.state.collectAsState()
    val measurer = rememberTextMeasurer()
    val isWorking = isEnabled && isLimiterOn
    // The figure under the bar, in tenths of a decibel: recomposes when it changes, not with every reading.
    val tenths by remember(isWorking) {
        derivedStateOf { if (isWorking) (reading.value.gainReduction * 10).roundToInt() else 0 }
    }
    val isActive = tenths >= 1
    val figure = when {
        !isLimiterOn -> "Off"
        isActive -> "−%.1f".format(java.util.Locale.ROOT, tenths / 10f)
        else -> "Idle"
    }
    Column(
        modifier
            .width(46.dp)
            .fillMaxHeight()
            .semantics {
                contentDescription = "Limiter"
                stateDescription = if (!isLimiterOn) "Off" else if (isActive) "Holding back ${"%.1f".format(java.util.Locale.ROOT, tenths / 10f)} dB" else "Idle"
            }
            .testTag("sound.limiterMeter"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("LIMITER", fontSize = 9.sp, fontWeight = FontWeight.Black, color = BoomColor.textTertiary, maxLines = 1, softWrap = false)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Canvas(
                Modifier
                    .width(14.dp)
                    .fillMaxHeight(),
            ) {
                val reduction = if (isWorking) reading.value.gainReduction else 0f
                drawRoundRect(Color.White.copy(alpha = 0.07f), cornerRadius = CornerRadius(4.dp.toPx()))
                val depth = (reduction.coerceAtMost(12f) / 12f) * size.height
                if (depth > 0.5f) {
                    drawRoundRect(
                        Brush.verticalGradient(listOf(BoomColor.accentDeep, BoomColor.destructive), 0f, size.height),
                        size = Size(size.width, depth), cornerRadius = CornerRadius(4.dp.toPx()),
                    )
                }
                for (decibels in listOf(3, 6, 9)) {
                    drawRect(BoomColor.surface.copy(alpha = 0.8f), Offset(0f, decibels / 12f * size.height), Size(size.width, 1.dp.toPx()))
                }
            }
            Canvas(
                Modifier
                    .width(18.dp)
                    .fillMaxHeight(),
            ) {
                for (decibels in listOf(0, 3, 6, 9, 12)) {
                    val y = (decibels / 12f * size.height).coerceIn(5.dp.toPx(), size.height - 5.dp.toPx())
                    drawLabel(
                        measurer, if (decibels == 0) "0" else "−$decibels", Offset(0f, y), BoomColor.textTertiary, 0f,
                        TextStyle(fontSize = 8.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
                    )
                }
            }
        }
        Text(
            figure, style = BoomFont.time.copy(fontWeight = FontWeight.Bold), maxLines = 1, softWrap = false,
            color = if (isActive) BoomColor.accent else BoomColor.textTertiary,
            modifier = Modifier.testTag("sound.limiterMeter.figure"),
        )
    }
}

/** A red warning while clip protection is off; solid red while the output goes past full scale. */
@Composable
internal fun ClipWarning(meter: LevelMeter, isLimiterOn: Boolean) {
    if (isLimiterOn) return
    val reading = meter.state.collectAsState()
    val isClipping by remember { derivedStateOf { reading.value.isClipping } }
    val background by animateColorAsState(
        if (isClipping) BoomColor.destructive else BoomColor.destructive.copy(alpha = 0.14f), tween(120), label = "clip",
    )
    val foreground = if (isClipping) BoomColor.onAccent else BoomColor.textPrimary
    Row(
        Modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(BoomMetrics.cardRadius + 2.dp))
            .padding(12.dp)
            .semantics(mergeDescendants = true) {}
            .testTag(if (isClipping) "sound.clipWarning.clipping" else "sound.clipWarning"),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            if (isClipping) Icons.Filled.Warning else Icons.Outlined.WarningAmber, contentDescription = null,
            tint = if (isClipping) BoomColor.onAccent else BoomColor.destructive, modifier = Modifier.size(18.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(if (isClipping) "Clipping: the sound is distorting" else "Clip protection is off", style = BoomFont.button, color = foreground)
            Text(
                "Loud passages can distort. Turn clip protection on, or lower the boost or preamp.",
                style = BoomFont.caption, color = foreground,
            )
        }
    }
}
