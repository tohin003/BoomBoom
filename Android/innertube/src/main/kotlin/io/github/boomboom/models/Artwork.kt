package io.github.boomboom.models

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.math.floor

/** One rendition of a picture, as YouTube Music lists it. [url] is an absolute URL. */
@Serializable
data class Thumbnail(val url: String, val width: Int, val height: Int)

/**
 * A picture in several sizes. [thumbnails] is sorted smallest first (by construction, whatever order it is given
 * in), which is why this is a class with its own equality and serializer rather than a plain data class.
 */
@Serializable(with = ArtworkSerializer::class)
class Artwork(thumbnails: List<Thumbnail> = emptyList()) {
    val thumbnails: List<Thumbnail> = thumbnails.sortedBy { it.width }

    val isEmpty: Boolean get() = thumbnails.isEmpty()

    /** Width divided by height of the largest rendition (1 for covers, about 1.78 for video stills). */
    val aspectRatio: Double
        get() {
            val last = thumbnails.lastOrNull()
            if (last == null || last.height <= 0) return 1.0
            return last.width.toDouble() / last.height.toDouble()
        }

    /**
     * The best URL for drawing at [pixelWidth] pixels wide.
     *
     * Google's image servers (`*.googleusercontent.com`, `yt3.ggpht.com`) resize on request, so for those the size in
     * the URL is rewritten and any size can be had. For other hosts (video stills on `i.ytimg.com`) the smallest
     * listed rendition that is wide enough is returned, or the largest one.
     */
    fun url(pixelWidth: Int): String? {
        val largest = thumbnails.lastOrNull() ?: return null
        val wanted = maxOf(pixelWidth, 1)
        resizedGoogleImageUrl(largest, wanted)?.let { return it }
        return (thumbnails.firstOrNull { it.width >= wanted } ?: largest).url
    }

    /** The largest rendition as listed. */
    val largestUrl: String? get() = thumbnails.lastOrNull()?.url

    override fun equals(other: Any?): Boolean = other is Artwork && other.thumbnails == thumbnails
    override fun hashCode(): Int = thumbnails.hashCode()
    override fun toString(): String = "Artwork(thumbnails=$thumbnails)"

    companion object {
        val EMPTY = Artwork()

        private val host = Regex("""^[A-Za-z][A-Za-z0-9+.\-]*://(?:[^/?#@]*@)?([^/?#:]+)""")

        /** Rewrites `...=w120-h120-l90-rj` (or `=s120...`) to the wanted size. Returns null for hosts that do not resize. */
        internal fun resizedGoogleImageUrl(thumbnail: Thumbnail, pixelWidth: Int): String? {
            val hostName = host.find(thumbnail.url)?.groupValues?.get(1)?.lowercase() ?: return null
            if (!(hostName.endsWith("googleusercontent.com") || hostName.endsWith("ggpht.com"))) return null
            val text = thumbnail.url
            val equals = text.lastIndexOf('=')
            if (equals < 0 || text.length - equals >= 64) return null
            val base = text.substring(0, equals)
            val options = text.substring(equals + 1)
            val ratio = if (thumbnail.width > 0) thumbnail.height.toDouble() / thumbnail.width.toDouble() else 1.0
            val height = maxOf(roundHalfAwayFromZero(pixelWidth * ratio), 1)
            // `split` drops empty pieces, as Swift's does.
            val pieces = options.split('-').filter { it.isNotEmpty() }
            if (options.startsWith("w")) {
                // Keep the flags after the size ("-l90-rj", "-p-l90-rj").
                val flags = pieces.drop(2).joinToString("-")
                val tail = if (flags.isEmpty()) "" else "-$flags"
                return "$base=w$pixelWidth-h$height$tail"
            }
            if (options.startsWith("s")) {
                val flags = pieces.drop(1).joinToString("-")
                val tail = if (flags.isEmpty()) "" else "-$flags"
                return "$base=s$pixelWidth$tail"
            }
            return null
        }

        private fun roundHalfAwayFromZero(value: Double): Int =
            (if (value < 0) -floor(-value + 0.5) else floor(value + 0.5)).toInt()
    }
}

@Serializable
private class ArtworkSurrogate(val thumbnails: List<Thumbnail>)

/** Saves an [Artwork] as `{"thumbnails": [...]}` and sorts it again when it is read. */
internal object ArtworkSerializer : KSerializer<Artwork> {
    override val descriptor: SerialDescriptor = ArtworkSurrogate.serializer().descriptor

    override fun serialize(encoder: Encoder, value: Artwork) =
        encoder.encodeSerializableValue(ArtworkSurrogate.serializer(), ArtworkSurrogate(value.thumbnails))

    override fun deserialize(decoder: Decoder): Artwork =
        Artwork(decoder.decodeSerializableValue(ArtworkSurrogate.serializer()).thumbnails)
}
