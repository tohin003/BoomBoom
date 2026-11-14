package io.github.boomboom.ui.sound

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.boomboom.logic.EqualizerStore
import io.github.boomboom.logic.LevelMeter
import io.github.boomboom.ui.design.BoomColor
import kotlin.math.ln
import kotlin.math.pow

/** Where frequencies and decibels land on the graph. The curve's scale is ±12 dB; the spectrum's is −90…0 dB. */
internal class GraphScale(private val width: Float, private val height: Float, private val padding: Float) {
    fun x(frequency: Double): Float = (ln(frequency / MIN_FREQUENCY) / ln(MAX_FREQUENCY / MIN_FREQUENCY)).toFloat() * width

    fun y(decibels: Double): Float {
        val clamped = decibels.coerceIn(-RANGE - 0.8, RANGE + 0.8)
        return height / 2 - (clamped / RANGE).toFloat() * (height / 2 - padding)
    }

    /** The spectrum fills the whole height: its floor at the bottom, full scale at the top. */
    fun spectrumY(decibels: Float): Float {
        val floor = LevelMeter.SPECTRUM_FLOOR
        val fraction = (decibels.coerceIn(floor, 0f) - floor) / -floor
        return height - fraction * height
    }

    companion object {
        val MIN_FREQUENCY = LevelMeter.SPECTRUM_RANGE.start
        val MAX_FREQUENCY = LevelMeter.SPECTRUM_RANGE.endInclusive
        const val RANGE = 12.0

        /** 160 points evenly spaced on the log scale: smooth enough for the steepest shelf. */
        val frequencies: DoubleArray = DoubleArray(160) { MIN_FREQUENCY * (MAX_FREQUENCY / MIN_FREQUENCY).pow(it / 159.0) }

        /** The centre of each of the spectrum's bars. */
        val spectrumFrequencies: DoubleArray = DoubleArray(LevelMeter.SPECTRUM_BANDS) {
            MIN_FREQUENCY * (MAX_FREQUENCY / MIN_FREQUENCY).pow((it + 0.5) / LevelMeter.SPECTRUM_BANDS)
        }
        val bandFrequencies: DoubleArray = EqualizerStore.bands.map { it.frequency }.toDoubleArray()
        val labelledFrequencies = listOf(
            50.0 to "50", 100.0 to "100", 200.0 to "200", 500.0 to "500", 1000.0 to "1K", 2000.0 to "2K", 5000.0 to "5K",
            10_000.0 to "10K",
        )
        val minorFrequencies = listOf(
            30.0, 40.0, 60.0, 70.0, 80.0, 90.0, 300.0, 400.0, 600.0, 700.0, 800.0, 900.0, 3000.0, 4000.0, 6000.0, 7000.0,
            8000.0, 9000.0, 20_000.0,
        )
        val decibelMarks = listOf(12.0 to "+12", 6.0 to "+6", 0.0 to "0", -6.0 to "−6", -12.0 to "−12")
    }
}

/** The width the dB labels take on the graph and beside the faders, so the two line up. */
internal val ScaleWidth: Dp = 28.dp
internal val GraphHeight: Dp = 176.dp

internal val LabelStyle = TextStyle(fontSize = 9.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")

/**
 * A list of numbers that glides to a new target (a preset chosen, Reset), or jumps there while a finger moves them.
 * Returns a reader for the drawing phase, so gliding redraws without recomposing.
 */
@Composable
internal fun rememberGlide(target: List<Double>, glides: Boolean): () -> List<Double> {
    val progress = remember { Animatable(1f) }
    var from by remember { mutableStateOf(target) }
    var to by remember { mutableStateOf(target) }
    LaunchedEffect(target, glides) {
        if (target == to) return@LaunchedEffect
        val shown = mix(from, to, progress.value)
        from = shown
        to = target
        if (glides) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(320))
        } else {
            progress.snapTo(1f)
        }
    }
    return { if (!glides && target != to) target else mix(from, to, progress.value) }
}

private fun mix(from: List<Double>, to: List<Double>, amount: Float): List<Double> {
    if (amount >= 1f || from.size != to.size) return to
    return List(to.size) { from[it] + (to[it] - from[it]) * amount }
}

/**
 * The analyser: the live spectrum of what is playing, with the equaliser's response curve over it and the ten band
 * points on the curve.
 *
 * @param curve The response at [GraphScale.frequencies]; all zero while the equaliser is off.
 * @param points The response at each band's centre.
 * @param activeBand The band a finger is on, highlighted on the curve.
 */
@Composable
internal fun SoundGraph(
    curve: List<Double>,
    points: List<Double>,
    isEnabled: Boolean,
    isComparing: Boolean,
    glides: Boolean,
    meter: LevelMeter,
    activeBand: Int?,
) {
    val measurer = rememberTextMeasurer()
    val shownCurve = rememberGlide(curve, glides)
    val shownPoints = rememberGlide(points, glides)
    Column(
        Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "Frequency response and spectrum"
                stateDescription = if (isComparing) "Original" else if (isEnabled) "Equaliser on" else "Equaliser off"
            }
            .testTag("sound.graph"),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(GraphHeight),
        ) {
            Canvas(
                Modifier
                    .width(ScaleWidth)
                    .fillMaxSize(),
            ) { drawDecibelScale(measurer) { GraphScale(size.width, size.height, 10.dp.toPx()).y(it) } }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clip(RoundedCornerShape(6.dp)),
            ) {
                Canvas(Modifier.fillMaxSize()) { drawGrid() }
                SpectrumLayer(meter)
                Canvas(Modifier.fillMaxSize()) {
                    drawCurve(shownCurve(), shownPoints(), isLive = isEnabled && !isComparing, activeBand = activeBand)
                }
                GraphBadge(isEnabled, isComparing, Modifier.align(Alignment.TopEnd))
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .height(12.dp),
        ) {
            Spacer(Modifier.width(ScaleWidth))
            Canvas(
                Modifier
                    .weight(1f)
                    .fillMaxSize(),
            ) {
                val scale = GraphScale(size.width, size.height, 0f)
                for ((frequency, label) in GraphScale.labelledFrequencies) {
                    drawLabel(measurer, label, Offset(scale.x(frequency), size.height / 2), BoomColor.textTertiary, anchor = 0.5f)
                }
                drawLabel(measurer, "Hz", Offset(size.width - 1f, size.height / 2), BoomColor.textTertiary, anchor = 1f)
            }
        }
    }
}

@Composable
private fun GraphBadge(isEnabled: Boolean, isComparing: Boolean, modifier: Modifier) {
    val style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 0.4.sp)
    when {
        isComparing -> Row(
            modifier
                .padding(8.dp)
                .background(BoomColor.accent, CircleShape)
                .padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Hearing, null, tint = BoomColor.onAccent, modifier = Modifier.padding(end = 4.dp).height(12.dp))
            Text("ORIGINAL", style = style, color = BoomColor.onAccent)
        }
        !isEnabled -> Text(
            "OFF", style = style, color = BoomColor.textSecondary,
            modifier = modifier
                .padding(8.dp)
                .background(BoomColor.surfaceRaised, CircleShape)
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/** The dB labels, right-aligned, at the heights [y] gives. */
internal fun DrawScope.drawDecibelScale(measurer: TextMeasurer, y: (Double) -> Float) {
    for ((decibels, label) in GraphScale.decibelMarks) {
        drawLabel(
            measurer, label, Offset(size.width - 5.dp.toPx(), y(decibels)),
            if (decibels == 0.0) BoomColor.textSecondary else BoomColor.textTertiary, anchor = 1f,
        )
    }
}

/** A small label centred vertically on [at], with [anchor] 0 (left), 0.5 (centre) or 1 (right) on it. */
internal fun DrawScope.drawLabel(
    measurer: TextMeasurer,
    text: String,
    at: Offset,
    color: Color,
    anchor: Float,
    style: TextStyle = LabelStyle,
) {
    val layout = measurer.measure(text, style)
    drawText(
        layout, color,
        Offset(at.x - layout.size.width * anchor, at.y - layout.size.height / 2f),
    )
}

private fun DrawScope.drawGrid() {
    val scale = GraphScale(size.width, size.height, 10.dp.toPx())
    drawRect(Color.White.copy(alpha = 0.035f))
    for (frequency in GraphScale.minorFrequencies) {
        val x = scale.x(frequency)
        drawLine(Color.White.copy(alpha = 0.04f), Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
    }
    for ((frequency, _) in GraphScale.labelledFrequencies) {
        val x = scale.x(frequency)
        drawLine(Color.White.copy(alpha = 0.08f), Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
    }
    for (decibels in listOf(-12.0, -6.0, 6.0, 12.0)) {
        val y = scale.y(decibels)
        drawLine(Color.White.copy(alpha = 0.08f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
    }
    val zero = scale.y(0.0)
    drawLine(Color.White.copy(alpha = 0.22f), Offset(0f, zero), Offset(size.width, zero), 1.dp.toPx())
}

/** The live spectrum. The only part of the graph that reads the meter, and only while drawing. */
@Composable
private fun SpectrumLayer(meter: LevelMeter) {
    val reading = meter.state.collectAsState()
    Canvas(
        Modifier
            .fillMaxSize()
            .testTag("sound.spectrum"),
    ) {
        val bars = reading.value.spectrum
        if (bars.size != GraphScale.spectrumFrequencies.size || bars.none { it > LevelMeter.SPECTRUM_FLOOR + 1 }) return@Canvas
        val scale = GraphScale(size.width, size.height, 10.dp.toPx())
        val points = bars.mapIndexed { index, value -> Offset(scale.x(GraphScale.spectrumFrequencies[index]), scale.spectrumY(value)) }
        val outline = Path().apply {
            moveTo(0f, points[0].y)
            lineTo(points[0].x, points[0].y)
            // Through the midpoints between bars, with each bar as the control point: a smooth line, no overshoot.
            for (index in 1 until points.size) {
                val middle = Offset((points[index - 1].x + points[index].x) / 2, (points[index - 1].y + points[index].y) / 2)
                quadraticTo(points[index - 1].x, points[index - 1].y, middle.x, middle.y)
            }
            lineTo(points.last().x, points.last().y)
            lineTo(size.width, points.last().y)
        }
        val fill = Path().apply {
            addPath(outline)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.20f), Color.White.copy(alpha = 0.03f))))
        drawPath(outline, Color.White.copy(alpha = 0.32f), style = Stroke(1.dp.toPx()))
    }
}

private fun DrawScope.drawCurve(curve: List<Double>, points: List<Double>, isLive: Boolean, activeBand: Int?) {
    if (curve.size != GraphScale.frequencies.size) return
    val scale = GraphScale(size.width, size.height, 10.dp.toPx())
    val tint = if (isLive) BoomColor.accent else Color.White.copy(alpha = 0.4f)
    val line = Path()
    GraphScale.frequencies.forEachIndexed { index, frequency ->
        val x = scale.x(frequency)
        val y = scale.y(curve[index])
        if (index == 0) line.moveTo(x, y) else line.lineTo(x, y)
    }
    val fill = Path().apply {
        addPath(line)
        lineTo(size.width, scale.y(0.0))
        lineTo(0f, scale.y(0.0))
        close()
    }
    drawPath(
        fill,
        Brush.verticalGradient(
            0f to tint.copy(alpha = 0.32f), 0.5f to tint.copy(alpha = 0.03f), 1f to tint.copy(alpha = 0.32f),
            startY = scale.y(12.0), endY = scale.y(-12.0),
        ),
    )
    if (isLive) {
        // A soft glow under the line.
        drawPath(line, tint.copy(alpha = 0.18f), style = Stroke(7.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(line, tint.copy(alpha = 0.3f), style = Stroke(4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
    drawPath(line, tint, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

    GraphScale.bandFrequencies.forEachIndexed { index, frequency ->
        if (index >= points.size) return@forEachIndexed
        val centre = Offset(scale.x(frequency), scale.y(points[index]))
        val isActive = index == activeBand
        if (isActive) drawCircle(tint.copy(alpha = 0.22f), 13.dp.toPx(), centre)
        val radius = (if (isActive) 6 else 4).dp.toPx()
        drawCircle(if (isActive) tint else BoomColor.background, radius, centre)
        drawCircle(tint, radius, centre, style = Stroke(2.dp.toPx()))
    }
}
