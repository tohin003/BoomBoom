package io.github.boomboom.ui.design

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import io.github.boomboom.models.Artwork
import io.github.boomboom.models.Thumbnail
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * A cover, a video still or an artist's picture, loaded at the size it is drawn. Until the picture is in (or when
 * there is none, or it fails) a grey tile with a note, or a person for artists, stands in its place.
 *
 * @param width The picture is fetched at this width in pixels on this screen.
 * @param height The same as [width] when left out.
 * @param isCircle Artists are round.
 */
@Composable
fun ArtworkView(
    artwork: Artwork,
    width: Dp,
    modifier: Modifier = Modifier,
    height: Dp = width,
    cornerRadius: Dp = BoomMetrics.coverRadius,
    isCircle: Boolean = false,
) {
    val density = LocalDensity.current
    val pixels = with(density) { ceil(max(width.toPx(), height.toPx() * artwork.aspectRatio.toFloat())).toInt() }
    val url = remember(artwork, pixels) { artwork.url(pixels) }
    val shape: Shape = if (isCircle) CircleShape else RoundedCornerShape(cornerRadius)
    var isLoaded by remember(url) { mutableStateOf(false) }
    Box(
        modifier
            .size(width, height)
            .clip(shape)
            .background(BoomColor.surfaceRaised),
        contentAlignment = Alignment.Center,
    ) {
        val preview = url?.let { PreviewArt.bitmap(it) }
        if (preview != null) {
            // Invented preview art: a few pixels drawn large, so they blur into a soft gradient.
            Image(
                preview, contentDescription = null, modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop, filterQuality = FilterQuality.High,
            )
            return@Box
        }
        if (!isLoaded) {
            Icon(
                if (isCircle) Icons.Filled.Person else Icons.Filled.MusicNote,
                contentDescription = null,
                tint = BoomColor.textTertiary,
                modifier = Modifier.size(min(width.value, height.value).dp * 0.36f),
            )
        }
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onState = { isLoaded = it is AsyncImagePainter.State.Success },
            )
        }
    }
}

/**
 * Invented cover art that needs no network, for previews and tests: a tiny two-colour picture with a soft disc,
 * described by a `boom-preview:` address and drawn here (the same pictures as the iPhone app's preview data).
 */
object PreviewArt {
    private const val SCHEME = "boom-preview:art/"

    fun artwork(seed: String, pixels: Pair<Int, Int>, points: Pair<Int, Int>): Artwork =
        Artwork(listOf(Thumbnail("$SCHEME${pixels.first}x${pixels.second}/$seed", points.first, points.second)))

    private val cache = HashMap<String, ImageBitmap>()

    /** The picture for a preview address, or null for any other address. */
    fun bitmap(url: String): ImageBitmap? {
        if (!url.startsWith(SCHEME)) return null
        cache[url]?.let { return it }
        val rest = url.removePrefix(SCHEME)
        val size = rest.substringBefore('/')
        val seed = rest.substringAfter('/')
        val width = size.substringBefore('x').toIntOrNull() ?: return null
        val height = size.substringAfter('x').toIntOrNull() ?: return null
        val image = draw(seed, width, height)
        cache[url] = image
        return image
    }

    private fun draw(seed: String, width: Int, height: Int): ImageBitmap {
        val hash = stableHash(seed)
        val hue = (hash % 360) / 360.0
        val first = hsb(hue, 0.62, 0.92)
        val second = hsb((hue + 0.12 + (hash % 7) / 30.0) % 1.0, 0.75, 0.5)
        val disc = hsb((hue + 0.5) % 1.0, 0.4, 1.0)
        val image = ImageBitmap(width, height)
        val canvas = Canvas(image)
        val paint = Paint()
        val brush = Brush.linearGradient(listOf(first, second), Offset.Zero, Offset(width.toFloat(), height.toFloat()))
        brush.applyTo(androidx.compose.ui.geometry.Size(width.toFloat(), height.toFloat()), paint, 1f)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        val discSize = min(width, height) * (0.35 + (hash % 5) / 20.0)
        val x = (hash % 97) / 97.0 * (width - discSize)
        val y = ((hash / 97) % 89) / 89.0 * (height - discSize)
        val discPaint = Paint().apply { color = disc.copy(alpha = 0.75f) }
        canvas.drawOval(x.toFloat(), y.toFloat(), (x + discSize).toFloat(), (y + discSize).toFloat(), discPaint)
        return image
    }

    /** A hash that stays the same between launches (the iPhone preview data's own, so both apps draw alike). */
    fun stableHash(text: String): Int {
        var value = 5381L
        var index = 0
        while (index < text.length) {
            val scalar = text.codePointAt(index)
            value = (value * 33 + scalar) and 0x7FFF_FFFF
            index += Character.charCount(scalar)
        }
        return value.toInt()
    }

    private fun hsb(hue: Double, saturation: Double, brightness: Double): Color {
        val sector = hue * 6
        val index = sector.toInt() % 6
        val fraction = sector - sector.toInt()
        val p = brightness * (1 - saturation)
        val q = brightness * (1 - saturation * fraction)
        val t = brightness * (1 - saturation * (1 - fraction))
        val (red, green, blue) = when (index) {
            0 -> Triple(brightness, t, p)
            1 -> Triple(q, brightness, p)
            2 -> Triple(p, brightness, t)
            3 -> Triple(p, q, brightness)
            4 -> Triple(t, p, brightness)
            else -> Triple(brightness, p, q)
        }
        return Color(red.toFloat(), green.toFloat(), blue.toFloat())
    }
}

/**
 * A cover drawn large and soft behind a page's header: fetched tiny and stretched, so it blurs into colour on every
 * phone (and blurred further where the system can).
 */
@Composable
fun ArtworkBackdrop(artwork: Artwork, modifier: Modifier = Modifier) {
    val url = remember(artwork) { artwork.url(32) }
    val preview = url?.let { PreviewArt.bitmap(it) }
    val soft = modifier.blur(26.dp, BlurredEdgeTreatment.Rectangle)
    when {
        preview != null -> Image(preview, null, soft, contentScale = ContentScale.Crop, filterQuality = FilterQuality.High)
        url != null -> AsyncImage(url, null, soft, contentScale = ContentScale.Crop, filterQuality = FilterQuality.High)
        else -> Box(modifier.background(BoomColor.surface))
    }
}
