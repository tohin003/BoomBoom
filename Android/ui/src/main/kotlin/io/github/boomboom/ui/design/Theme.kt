package io.github.boomboom.ui.design

import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.floor

// BoomBoom's look: near-black surfaces, white text, one accent (yellow). Screens use these tokens and do not invent
// colours or sizes of their own. The same values as the iPhone app's Theme.swift.

object BoomColor {
    /** The app's background. */
    val background = Color(0xFF030303)
    /** Cards, sheets, the mini player and the tab bar. */
    val surface = Color(0xFF121212)
    /** Things raised above a surface: menus, pressed rows, text fields. */
    val surfaceRaised = Color(0xFF212121)
    /** The accent. Where a red would mark "this is the brand" or "this is active", BoomBoom is yellow. */
    val accent = Color(0xFFFFD60A)
    /** A deeper yellow for gradients and pressed accents. */
    val accentDeep = Color(0xFFFFB800)
    /** Text and glyphs on top of the accent. */
    val onAccent = Color.Black
    val textPrimary = Color.White
    val textSecondary = Color.White.copy(alpha = 0.7f)
    val textTertiary = Color.White.copy(alpha = 0.5f)
    val separator = Color.White.copy(alpha = 0.1f)
    /** An unselected filter pill. */
    val chip = Color.White.copy(alpha = 0.1f)
    /** A selected filter pill (white with black text). */
    val chipSelected = Color.White
    val destructive = Color(0xFFFF5A52)

    /** A colour from 0xRRGGBB. */
    fun hex(rgb: Int, alpha: Float = 1f): Color =
        Color(red = (rgb shr 16 and 0xFF) / 255f, green = (rgb shr 8 and 0xFF) / 255f, blue = (rgb and 0xFF) / 255f, alpha = alpha)
}

/**
 * The type scale, after the iPhone's text styles (title, title 2, footnote, body, subheadline, caption) at their
 * default sizes. Sizes are in `sp`, so they follow the phone's font size setting the way Dynamic Type does.
 */
object BoomFont {
    private val tight = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
    private fun style(size: Int, line: Int, weight: FontWeight) = TextStyle(
        fontFamily = FontFamily.Default, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight,
        lineHeightStyle = tight,
    )

    /** Page titles ("Library", an album's name). */
    val pageTitle = style(28, 34, FontWeight.Bold)
    /** Shelf headers ("Quick picks"). */
    val shelfTitle = style(22, 28, FontWeight.Bold)
    /** The big title of an album's or playlist's header, a top result's name. */
    val headline = style(20, 25, FontWeight.Bold)
    /** The small capitals line above a shelf header. */
    val strapline = style(13, 18, FontWeight.Normal)
    /** A row's or card's first line. */
    val itemTitle = style(16, 21, FontWeight.Medium)
    /** A row's or card's second line. */
    val itemSubtitle = style(14, 19, FontWeight.Normal)
    val button = style(14, 19, FontWeight.SemiBold)
    val caption = style(12, 16, FontWeight.Normal)
    /** Times next to the scrubber and in tracklists: digits of one width. */
    val time = style(12, 16, FontWeight.Normal).copy(fontFeatureSettings = "tnum")
    /** A section title inside a list ("Recent searches"). */
    val sectionTitle = style(17, 22, FontWeight.Bold)
    /** A quiet message's title ("No results"). */
    val messageTitle = style(20, 25, FontWeight.SemiBold)
}

object BoomMetrics {
    /** The gap between the screen's edge and content. */
    val margin = 16.dp
    /** Corner radius of covers. */
    val coverRadius = 4.dp
    /** Corner radius of cards, sheets' inner boxes and large covers. */
    val cardRadius = 8.dp
    /** A row's cover. */
    val rowCover = 56.dp
    /** A carousel card's cover. */
    val cardCover = 160.dp
    val rowSpacing = 12.dp
    val shelfSpacing = 28.dp
    /** The player bar's height. The frame leaves this much room above the tab bar while a song is loaded. */
    val miniPlayerHeight = 64.dp
    /** The tab bar's height above the gesture area. */
    val tabBarHeight = 56.dp
    /** The least a tappable thing may measure (Android's own guideline is 48). */
    val minimumTarget = 48.dp
}

/** Seconds as "3:45" or "1:02:03". */
fun boomTime(seconds: Double): String {
    if (!seconds.isFinite() || seconds < 0) return "0:00"
    val total = floor(seconds).toLong()
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val rest = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, rest) else "%d:%02d".format(minutes, rest)
}

private val boomColorScheme = darkColorScheme(
    primary = BoomColor.accent,
    onPrimary = BoomColor.onAccent,
    primaryContainer = BoomColor.accentDeep,
    onPrimaryContainer = BoomColor.onAccent,
    secondary = BoomColor.accent,
    onSecondary = BoomColor.onAccent,
    background = BoomColor.background,
    onBackground = BoomColor.textPrimary,
    surface = BoomColor.surface,
    onSurface = BoomColor.textPrimary,
    surfaceVariant = BoomColor.surfaceRaised,
    onSurfaceVariant = BoomColor.textSecondary,
    surfaceContainerLowest = BoomColor.background,
    surfaceContainerLow = BoomColor.surface,
    surfaceContainer = BoomColor.surface,
    surfaceContainerHigh = BoomColor.surfaceRaised,
    surfaceContainerHighest = BoomColor.surfaceRaised,
    surfaceBright = BoomColor.surfaceRaised,
    outline = Color.White.copy(alpha = 0.25f),
    outlineVariant = BoomColor.separator,
    error = BoomColor.destructive,
    onError = Color.Black,
    scrim = Color.Black.copy(alpha = 0.6f),
)

/** The app is dark only: there is no light theme to switch to. */
@Composable
fun BoomTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = boomColorScheme) {
        CompositionLocalProvider(
            LocalTextSelectionColors provides TextSelectionColors(
                handleColor = BoomColor.accent, backgroundColor = BoomColor.accent.copy(alpha = 0.35f),
            ),
            content = content,
        )
    }
}
