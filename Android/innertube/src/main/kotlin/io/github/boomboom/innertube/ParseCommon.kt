package io.github.boomboom.innertube

import io.github.boomboom.models.AlbumRef
import io.github.boomboom.models.ArtistRef
import io.github.boomboom.models.Artwork
import io.github.boomboom.models.BrowseEndpoint
import io.github.boomboom.models.LibraryTokens
import io.github.boomboom.models.LikeStatus
import io.github.boomboom.models.Song
import io.github.boomboom.models.Thumbnail
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * Readers for the small pieces every InnerTube renderer is built from: pictures, links, text runs, menus,
 * continuation tokens. Everything here is forgiving: a missing piece gives null, never an error.
 *
 * The other `Parse…` files add the rest of the readers as extensions of this object.
 */
internal object Parse {
    const val DOT_SEPARATOR = " • "

    private val urlScheme = Regex("""^[A-Za-z][A-Za-z0-9+.\-]*:""")

    // Pictures

    /** A `{"thumbnails": [...]}` list as [Artwork]. */
    fun artwork(list: JsonElement): Artwork {
        val thumbnails = list["thumbnails"].array.mapNotNull { entry ->
            var text = entry["url"].text ?: return@mapNotNull null
            if (text.startsWith("//")) text = "https:$text"
            if (!urlScheme.containsMatchIn(text)) return@mapNotNull null
            Thumbnail(text, entry["width"].int ?: 0, entry["height"].int ?: 0)
        }
        return Artwork(thumbnails)
    }

    /** The picture of a renderer, wherever this kind of renderer keeps it. */
    fun artworkOf(renderer: JsonElement): Artwork {
        val candidates = listOf(
            renderer["thumbnail", "musicThumbnailRenderer", "thumbnail"],
            renderer["thumbnailRenderer", "musicThumbnailRenderer", "thumbnail"],
            renderer["thumbnail", "croppedSquareThumbnailRenderer", "thumbnail"],
            renderer["thumbnailRenderer", "croppedSquareThumbnailRenderer", "thumbnail"],
            renderer["foregroundThumbnail", "musicThumbnailRenderer", "thumbnail"],
            renderer["thumbnail"],
        )
        for (candidate in candidates) {
            val artwork = artwork(candidate)
            if (!artwork.isEmpty) return artwork
        }
        return Artwork.EMPTY
    }

    // Links

    fun pageType(raw: String?, browseId: String): BrowseEndpoint.PageType = when (raw) {
        "MUSIC_PAGE_TYPE_ALBUM", "MUSIC_PAGE_TYPE_AUDIOBOOK" -> BrowseEndpoint.PageType.ALBUM
        "MUSIC_PAGE_TYPE_ARTIST", "MUSIC_PAGE_TYPE_LIBRARY_ARTIST" -> BrowseEndpoint.PageType.ARTIST
        "MUSIC_PAGE_TYPE_USER_CHANNEL" -> BrowseEndpoint.PageType.USER_CHANNEL
        "MUSIC_PAGE_TYPE_PLAYLIST" -> BrowseEndpoint.PageType.PLAYLIST
        "MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE" -> BrowseEndpoint.PageType.PODCAST
        null -> when {
            browseId.startsWith("MPRE") -> BrowseEndpoint.PageType.ALBUM
            browseId.startsWith("MPSP") -> BrowseEndpoint.PageType.PODCAST
            browseId.startsWith("VL") -> BrowseEndpoint.PageType.PLAYLIST
            browseId.startsWith("UC") -> BrowseEndpoint.PageType.ARTIST
            else -> BrowseEndpoint.PageType.GENERIC
        }
        else -> BrowseEndpoint.PageType.GENERIC
    }

    /**
     * A `browseEndpoint` node as [BrowseEndpoint]. `browseId` stays exactly as YouTube Music links it: `VL…` for
     * playlists, `MPREb_…` for albums, `UC…` for artists.
     */
    fun browseEndpoint(node: JsonElement): BrowseEndpoint? {
        val browseId = node["browseId"].text ?: return null
        val rawType = node["browseEndpointContextSupportedConfigs", "browseEndpointContextMusicConfig", "pageType"].text
        return BrowseEndpoint(browseId, node["params"].text, pageType(rawType, browseId))
    }

    /**
     * Where a shelf's "More" button leads. A channel link with `params` opens one section of the channel ("Live
     * performances", "Playlists by …"), not the artist page, so it is typed `GENERIC` and opened with `browse`.
     */
    fun moreEndpoint(navigation: JsonElement): BrowseEndpoint? {
        val endpoint = browseEndpointIn(navigation) ?: return null
        if (endpoint.params != null &&
            (endpoint.pageType == BrowseEndpoint.PageType.ARTIST || endpoint.pageType == BrowseEndpoint.PageType.USER_CHANNEL)
        ) {
            return endpoint.copy(pageType = BrowseEndpoint.PageType.GENERIC)
        }
        return endpoint
    }

    /** The `browseEndpoint` inside a navigation endpoint or command. */
    fun browseEndpointIn(navigation: JsonElement): BrowseEndpoint? = browseEndpoint(navigation["browseEndpoint"])

    /** A `watchEndpoint` or `watchPlaylistEndpoint` node as [io.github.boomboom.models.WatchEndpoint]. */
    fun watchEndpoint(node: JsonElement): io.github.boomboom.models.WatchEndpoint? {
        val videoId = node["videoId"].text
        val playlistId = node["playlistId"].text
        if (videoId == null && playlistId == null) return null
        return io.github.boomboom.models.WatchEndpoint(videoId, playlistId, node["params"].text, node["index"].int)
    }

    /** The watch endpoint inside a navigation endpoint or command (either kind). */
    fun watchEndpointIn(navigation: JsonElement): io.github.boomboom.models.WatchEndpoint? =
        watchEndpoint(navigation["watchEndpoint"]) ?: watchEndpoint(navigation["watchPlaylistEndpoint"])

    fun musicVideoType(navigation: JsonElement): String? =
        navigation["watchEndpoint", "watchEndpointMusicSupportedConfigs", "watchEndpointMusicConfig", "musicVideoType"].text

    /** The play button's endpoint on a list row or card (`overlay` / `thumbnailOverlay`). */
    fun playNavigation(renderer: JsonElement): JsonElement {
        for (key in listOf("overlay", "thumbnailOverlay")) {
            val endpoint = renderer[key, "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer",
                "playNavigationEndpoint"]
            if (endpoint.exists) return endpoint
        }
        return JsonNull
    }

    fun songKind(videoType: String?, artwork: Artwork): Song.Kind = when (videoType) {
        "MUSIC_VIDEO_TYPE_ATV", "MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK" -> Song.Kind.SONG
        "MUSIC_VIDEO_TYPE_PODCAST_EPISODE" -> Song.Kind.EPISODE
        null -> if (artwork.aspectRatio > 1.2) Song.Kind.VIDEO else Song.Kind.SONG
        else -> Song.Kind.VIDEO
    }

    /** The playlist id behind a playlist browse id (`VLPL…` → `PL…`, `MPSPPL…` → `PL…`). */
    fun playlistId(fromBrowseId: String): String {
        if (fromBrowseId.startsWith("VL")) return fromBrowseId.drop(2)
        if (fromBrowseId.startsWith("MPSP")) return fromBrowseId.drop(4)
        return fromBrowseId
    }

    /**
     * The browse id of a playlist page from either form callers hold: the browse id an endpoint carries
     * (`VLPL…`), a bare playlist id (`PL…`, `LM`), or a podcast's (`MPSPPL…`).
     */
    fun playlistBrowseId(id: String): String = "VL" + playlistId(id)

    /** The browse id of an artist page: the channel (`UC…`), also when given a library artist's `MPLAUC…`. */
    fun artistBrowseId(id: String): String = if (id.startsWith("MPLAUC")) id.drop(4) else id

    // Badges and text

    fun isExplicit(renderer: JsonElement): Boolean {
        for (key in listOf("badges", "subtitleBadges", "subtitleBadge")) {
            for (badge in renderer[key].array) {
                if (badge["musicInlineBadgeRenderer", "icon", "iconType"].string == "MUSIC_EXPLICIT_BADGE") return true
            }
        }
        return false
    }

    /** Seconds from "3:45", "1:02:03", or the podcast forms "1 hr 40 min", "52 min", "45 sec". */
    fun duration(text: String?): Double? {
        val trimmed = text?.trim() ?: return null
        if (trimmed.isEmpty()) return null
        val parts = trimmed.split(":")
        if (parts.size in 2..3 && parts.all { it.isNotEmpty() && it.all { c -> isAsciiDigit(c) } }) {
            return parts.fold(0.0) { total, part -> total * 60 + (parseDecimal(part) ?: 0.0) }
        }
        // "1 hr 40 min", "52 min", "30 sec"
        val words = trimmed.lowercase().split(" ").filter { it.isNotEmpty() }
        var total = 0.0
        var matched = false
        var index = 0
        while (index + 1 < words.size) {
            val value = parseDecimal(words[index])
            if (value != null) {
                val unit = words[index + 1]
                if (unit.startsWith("hr") || unit.startsWith("hour")) {
                    total += value * 3600
                    matched = true
                } else if (unit.startsWith("min")) {
                    total += value * 60
                    matched = true
                } else if (unit.startsWith("sec")) {
                    total += value
                    matched = true
                }
                index += 2
            } else {
                index += 1
            }
        }
        return if (matched) total else null
    }

    fun isDurationText(text: String): Boolean {
        val parts = text.split(":")
        return parts.size in 2..3 && parts.all { it.isNotEmpty() && it.all { c -> isAsciiDigit(c) } }
    }

    fun isYearText(text: String): Boolean = text.length == 4 && text.all { c -> isAsciiDigit(c) }

    /** "1.7B plays", "716M views", "58 views", "3406万回視聴": a count followed by a word. */
    fun isCountText(text: String): Boolean {
        val first = text.firstOrNull() ?: return false
        if (!isAsciiDigit(first)) return false
        if (isDurationText(text) || isYearText(text)) return false
        // A bare token like "2Pac" is a name, not a count.
        return text.contains(" ") || text.contains(" ") || !text.all { it.code < 128 }
    }

    fun isSeparator(run: JsonElement): Boolean {
        if (run["navigationEndpoint"].exists) return false
        val text = run["text"].string ?: return false
        val trimmed = trimInline(text)
        return trimmed == "•" || trimmed == "&" || trimmed == "," || trimmed == "·" || text == ", " ||
            text == " & " || text == " and " || text == " x "
    }

    // The "Artist • Album • 3:45" line

    data class SongInfo(
        val artists: List<ArtistRef> = emptyList(),
        val album: AlbumRef? = null,
        val duration: Double? = null,
        val year: String? = null,
        val countText: String? = null,
        /** The leading type word that was skipped ("Song", "Video", "Album", "Single"). */
        val typeText: String? = null,
    )

    /** The words YouTube Music (in English) puts first on a result line: "Song • Oasis", "Single • Oasis • 1995". */
    val typeWords: Set<String> = setOf(
        "song", "video", "album", "single", "ep", "artist", "playlist", "station", "profile", "podcast", "episode",
        "audiobook",
    )

    /**
     * Whether [runs] start with a type word and its " • ": an unlinked first run that is not a duration, year or
     * count. With [anyWord] (unfiltered search rows, where every line starts with one, in any language) any such
     * run counts; otherwise only a known English type word, so an artist without a link ("Oasis • 3:45") stays.
     */
    fun startsWithTypeWord(runs: List<JsonElement>, anyWord: Boolean): Boolean {
        if (runs.size <= 2 || runs[0]["navigationEndpoint"].exists || runs[1]["text"].string != DOT_SEPARATOR) return false
        val first = runs[0]["text"].text ?: return false
        if (isDurationText(first) || isYearText(first) || isCountText(first)) return false
        return anyWord || first.lowercase() in typeWords
    }

    /**
     * Reads a subtitle line of runs. Linked runs are artists (`UC…`) or albums (`MPRE…`); unlinked runs are a
     * duration, a year, a play count, or an artist without a page.
     *
     * With [skipTypeSpec], a leading type word ("Song • Oasis") is skipped instead of read as an artist, the way
     * ytmusicapi does it (see [startsWithTypeWord]).
     */
    fun songInfo(runs: List<JsonElement>, skipTypeSpec: Boolean, anyTypeWord: Boolean = false): SongInfo {
        var typeText: String? = null
        val artists = mutableListOf<ArtistRef>()
        var album: AlbumRef? = null
        var duration: Double? = null
        var year: String? = null
        var countText: String? = null
        var remaining = runs
        if (skipTypeSpec && startsWithTypeWord(remaining, anyTypeWord)) {
            typeText = remaining[0]["text"].text
            remaining = remaining.drop(2)
        }
        for (run in remaining) {
            val text = run["text"].string ?: continue
            if (isSeparator(run) || trimInline(text).isEmpty()) continue
            val navigation = run["navigationEndpoint"]
            if (navigation.exists) {
                val browseId = navigation["browseEndpoint", "browseId"].text
                if (browseId != null && (browseId.startsWith("MPRE") || browseId.contains("release_detail"))) {
                    album = AlbumRef(text, browseId)
                } else if (browseId != null && (browseId.startsWith("MPSP") || browseId.startsWith("FEmusic"))) {
                    // A podcast show or a chart link: shown as the "artist" line without a channel.
                    artists += ArtistRef(text, null)
                } else {
                    artists += ArtistRef(text, browseId)
                }
            } else if (isDurationText(text)) {
                duration = duration(text)
            } else if (isYearText(text)) {
                year = text
            } else if (isCountText(text)) {
                // "1.3B views • 6.9M likes": the first count is the plays.
                countText = countText ?: text
            } else {
                artists += ArtistRef(text, null)
            }
        }
        return SongInfo(artists, album, duration, year, countText, typeText)
    }

    // Menus

    data class MenuInfo(
        val likeStatus: LikeStatus? = null,
        val libraryTokens: LibraryTokens? = null,
        val setVideoId: String? = null,
        val removedVideoId: String? = null,
    )

    /** Like status, "Save to library" tokens and the playlist entry id from a row's `menu`. */
    fun menuInfo(renderer: JsonElement): MenuInfo {
        var likeStatus: LikeStatus? = null
        var libraryTokens: LibraryTokens? = null
        var setVideoId: String? = null
        var removedVideoId: String? = null
        val menu = renderer["menu", "menuRenderer"]
        for (button in menu["topLevelButtons"].array) {
            button["likeButtonRenderer", "likeStatus"].text?.let { likeStatus = LikeStatus.fromRawValue(it) }
        }
        for (item in menu["items"].array) {
            val toggle = item["toggleMenuServiceItemRenderer"]
            if (toggle.exists) {
                val icon = toggle["defaultIcon", "iconType"].string ?: ""
                val isToggled = toggle["isToggled"].bool ?: false
                val defaultToken = toggle["defaultServiceEndpoint", "feedbackEndpoint", "feedbackToken"].text
                val toggledToken = toggle["toggledServiceEndpoint", "feedbackEndpoint", "feedbackToken"].text
                when (icon) {
                    "BOOKMARK_BORDER", "LIBRARY_ADD" -> libraryTokens = LibraryTokens(defaultToken, toggledToken, isToggled)
                    "BOOKMARK", "LIBRARY_SAVED", "LIBRARY_REMOVE" -> libraryTokens = LibraryTokens(toggledToken, defaultToken, true)
                    else -> {}
                }
                if (likeStatus == null) {
                    toggle["defaultServiceEndpoint", "likeEndpoint", "status"].text?.let { status ->
                        // The default action is the opposite of the current state.
                        likeStatus = if (status == "LIKE") LikeStatus.INDIFFERENT else LikeStatus.LIKE
                    }
                }
            }
            val service = item["menuServiceItemRenderer", "serviceEndpoint", "playlistEditEndpoint", "actions", 0]
            service["setVideoId"].text?.let {
                setVideoId = it
                removedVideoId = service["removedVideoId"].text
            }
        }
        return MenuInfo(likeStatus, libraryTokens, setVideoId, removedVideoId)
    }

    /** The like state of a header's toggle button (albums, playlists). */
    fun toggleLikeStatus(buttons: List<JsonElement>): LikeStatus? {
        for (button in buttons) {
            val toggle = button["toggleButtonRenderer"]
            if (!toggle.exists) continue
            val status = toggle["defaultServiceEndpoint", "likeEndpoint", "status"].text ?: continue
            val isToggled = toggle["isToggled"].bool
            if (isToggled != null && toggle["toggledServiceEndpoint"].exists) {
                return if (isToggled) LikeStatus.LIKE else LikeStatus.INDIFFERENT
            }
            return if (status == "LIKE") LikeStatus.INDIFFERENT else LikeStatus.LIKE
        }
        return null
    }

    // Continuations

    /**
     * The token of a list that scrolls on, in either form YouTube Music uses: the legacy
     * `continuations[0].nextContinuationData.continuation` on the container, or a trailing
     * `continuationItemRenderer` in its items.
     */
    fun continuation(container: JsonElement, items: List<JsonElement>? = null): String? {
        for (entry in container["continuations"].array) {
            for (key in listOf("nextContinuationData", "nextRadioContinuationData", "reloadContinuationData")) {
                entry[key, "continuation"].text?.let { return it }
            }
        }
        val list = items ?: container["contents"].array.ifEmpty { container["items"].array }
        return list.lastOrNull()?.let { continuationItemToken(it) }
    }

    fun continuationItemToken(item: JsonElement): String? {
        val renderer = item["continuationItemRenderer"]
        if (!renderer.exists) return null
        val endpoint = renderer["continuationEndpoint"]
        endpoint["continuationCommand", "token"].text?.let { return it }
        for (command in endpoint["commandExecutorCommand", "commands"].array) {
            command["continuationCommand", "token"].text?.let { return it }
        }
        return renderer["button", "buttonRenderer", "command", "continuationCommand", "token"].text
    }

    /** Trims spaces and tabs (Foundation's `.whitespaces`: no line breaks). */
    private fun trimInline(text: String): String =
        text.trim { it == '\t' || Character.getType(it) == Character.SPACE_SEPARATOR.toInt() }

    internal fun isAsciiDigit(character: Char): Boolean = character in '0'..'9'
}
