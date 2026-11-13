package io.github.boomboom.innertube

import io.github.boomboom.models.AlbumItem
import io.github.boomboom.models.ArtistItem
import io.github.boomboom.models.ArtistRef
import io.github.boomboom.models.Artwork
import io.github.boomboom.models.BrowseEndpoint
import io.github.boomboom.models.MusicItem
import io.github.boomboom.models.PlaylistItem
import io.github.boomboom.models.Song
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

// One entry of a list or carousel as a `MusicItem`. Unknown or incomplete renderers give null and are skipped.

/** Any item renderer wrapper (`{"musicResponsiveListItemRenderer": {...}}` and friends). */
internal fun Parse.item(wrapper: JsonElement): MusicItem? {
    val (key, renderer) = wrapper.soleEntry ?: return null
    return when (key) {
        "musicResponsiveListItemRenderer" -> responsiveItem(renderer)
        "musicTwoRowItemRenderer" -> twoRowItem(renderer)
        "musicMultiRowListItemRenderer" -> multiRowItem(renderer)?.let { MusicItem.OfSong(it) }
        "playlistPanelVideoRenderer" -> panelSong(renderer)?.let { MusicItem.OfSong(it) }
        "playlistPanelVideoWrapperRenderer" ->
            panelSong(renderer["primaryRenderer", "playlistPanelVideoRenderer"])?.let { MusicItem.OfSong(it) }
        else -> null
    }
}

// List rows

internal fun Parse.flexColumns(renderer: JsonElement): List<JsonElement> =
    renderer["flexColumns"].array.map { it["musicResponsiveListItemFlexColumnRenderer", "text"] }

/**
 * A row with a small cover and columns of text: search results, album tracks, playlist entries, charts.
 * [typeWordFirst] is for unfiltered search rows, whose second line always starts with the kind ("Song • …").
 */
internal fun Parse.responsiveItem(renderer: JsonElement, typeWordFirst: Boolean = false): MusicItem? {
    val columns = flexColumns(renderer)
    val first = columns.firstOrNull() ?: return null
    val title = first.runsText ?: return null
    val artwork = artworkOf(renderer)
    val detailColumns = columns.drop(1)

    val browse = browseEndpointIn(renderer["navigationEndpoint"])
    if (browse != null) {
        when (browse.pageType) {
            BrowseEndpoint.PageType.ARTIST, BrowseEndpoint.PageType.USER_CHANNEL ->
                return MusicItem.OfArtist(
                    ArtistItem(
                        id = browse.browseId,
                        name = title,
                        artwork = artwork,
                        detailText = detailText(detailColumns, dropTypeWord = true, anyTypeWord = typeWordFirst),
                    ),
                )
            BrowseEndpoint.PageType.ALBUM -> {
                val runs = joinedRuns(detailColumns)
                val info = songInfo(runs, skipTypeSpec = true, anyTypeWord = typeWordFirst)
                val play = playNavigation(renderer)
                return MusicItem.OfAlbum(
                    AlbumItem(
                        id = browse.browseId,
                        playlistId = watchEndpointIn(play)?.playlistId,
                        title = title,
                        artists = info.artists,
                        year = info.year,
                        typeText = info.typeText,
                        artwork = artwork,
                        isExplicit = isExplicit(renderer),
                    ),
                )
            }
            BrowseEndpoint.PageType.PLAYLIST, BrowseEndpoint.PageType.PODCAST ->
                return MusicItem.OfPlaylist(
                    PlaylistItem(
                        id = playlistId(browse.browseId),
                        title = title,
                        detailText = detailText(detailColumns, dropTypeWord = false),
                        artwork = artwork,
                    ),
                )
            BrowseEndpoint.PageType.GENERIC -> {
                // Episode rows link their title to an episode page; they still play.
                val song = responsiveSong(renderer, title, columns, artwork, typeWordFirst)
                return song?.let { MusicItem.OfSong(it) }
            }
        }
    }
    return responsiveSong(renderer, title, columns, artwork, typeWordFirst)?.let { MusicItem.OfSong(it) }
}

/** The text of the second and later columns, joined: "Playlist • YouTube Music • 51 songs". */
internal fun Parse.detailText(columns: List<JsonElement>, dropTypeWord: Boolean, anyTypeWord: Boolean = false): String? {
    var runs = joinedRuns(columns)
    if (dropTypeWord && startsWithTypeWord(runs, anyTypeWord)) runs = runs.drop(2)
    val text = runs.mapNotNull { it["text"].string }.joinToString("")
    return text.ifEmpty { null }
}

/** The runs of several columns as one line, with a " • " between columns. */
internal fun Parse.joinedRuns(columns: List<JsonElement>): List<JsonElement> {
    val runs = mutableListOf<JsonElement>()
    for (column in columns) {
        val columnRuns = column.runs
        if (columnRuns.isEmpty()) continue
        if (runs.isNotEmpty()) runs += obj("text" to DOT_SEPARATOR)
        runs += columnRuns
    }
    return runs
}

internal fun Parse.responsiveSong(
    renderer: JsonElement,
    title: String,
    columns: List<JsonElement>,
    artwork: Artwork,
    typeWordFirst: Boolean = false,
): Song? {
    val play = playNavigation(renderer)
    val titleNavigation = columns.firstOrNull()?.get("runs", 0, "navigationEndpoint") ?: JsonNull
    val menu = menuInfo(renderer)
    val videoId = play["watchEndpoint", "videoId"].text
        ?: titleNavigation["watchEndpoint", "videoId"].text
        ?: renderer["playlistItemData", "videoId"].text
        ?: menu.removedVideoId
        ?: return null
    val videoType = musicVideoType(play) ?: musicVideoType(titleNavigation)

    // Each detail column is read on its own (artists, album, plays), the first one may start with a type word.
    val artists = mutableListOf<ArtistRef>()
    var album: io.github.boomboom.models.AlbumRef? = null
    var duration: Double? = null
    var countText: String? = null
    var typeText: String? = null
    for ((offset, column) in columns.drop(1).withIndex()) {
        val part = songInfo(column.runs, skipTypeSpec = offset == 0, anyTypeWord = typeWordFirst)
        artists += part.artists
        album = album ?: part.album
        duration = duration ?: part.duration
        countText = countText ?: part.countText
        typeText = typeText ?: part.typeText
    }
    val fixedDuration = renderer["fixedColumns"].array.asSequence()
        .mapNotNull { duration(it["musicResponsiveListItemFixedColumnRenderer", "text"].runsText) }
        .firstOrNull()
    var kind = songKind(videoType, artwork)
    val lowerType = typeText?.lowercase()
    if (videoType == null && lowerType != null) {
        if (lowerType == "video") kind = Song.Kind.VIDEO else if (lowerType == "episode") kind = Song.Kind.EPISODE
    }
    var songArtists: List<ArtistRef> = artists
    if (kind == Song.Kind.EPISODE) {
        // "Episode • Apr 12, 2024 • Show": the date is not an artist; the show is.
        songArtists = episodeShow(columns.drop(1).flatMap { it.runs })?.let { listOf(it) } ?: emptyList()
    }
    val policy = renderer["musicItemRendererDisplayPolicy"].string
    return Song(
        videoId = videoId,
        title = title,
        artists = songArtists,
        album = album,
        duration = fixedDuration ?: duration,
        artwork = artwork,
        isExplicit = isExplicit(renderer),
        kind = kind,
        playsText = countText,
        likeStatus = menu.likeStatus,
        setVideoId = renderer["playlistItemData", "playlistSetVideoId"].text
            ?: play["watchEndpoint", "playlistSetVideoId"].text ?: menu.setVideoId,
        libraryTokens = menu.libraryTokens,
        isAvailable = policy != "MUSIC_ITEM_RENDERER_DISPLAY_POLICY_GREY_OUT",
    )
}

// Cards

/** A card with a big cover and two lines of text: Home and artist-page carousels. */
internal fun Parse.twoRowItem(renderer: JsonElement): MusicItem? {
    val title = renderer["title"].runsText ?: return null
    val artwork = artworkOf(renderer)
    val subtitleRuns = renderer["subtitle"].runs
    val subtitle = renderer["subtitle"].runsText
    val navigation = renderer["navigationEndpoint"]
    val browse = browseEndpointIn(renderer["title", "runs", 0, "navigationEndpoint"]) ?: browseEndpointIn(navigation)

    if (browse != null) {
        when (browse.pageType) {
            BrowseEndpoint.PageType.ALBUM -> {
                val info = songInfo(subtitleRuns, skipTypeSpec = true)
                var typeText = info.typeText
                // "Album" alone (no " • ") is read as an unlinked artist: it is the type word.
                var artists = info.artists
                if (typeText == null && (subtitleRuns.size == 1 || (subtitleRuns.size == 3 && info.year != null))) {
                    val only = artists.firstOrNull()
                    if (only != null && only.id == null && artists.size == 1) {
                        typeText = only.name
                        artists = emptyList()
                    }
                }
                return MusicItem.OfAlbum(
                    AlbumItem(
                        id = browse.browseId,
                        playlistId = watchEndpointIn(playNavigation(renderer))?.playlistId,
                        title = title,
                        artists = artists,
                        year = info.year,
                        typeText = typeText,
                        artwork = artwork,
                        isExplicit = isExplicit(renderer),
                    ),
                )
            }
            BrowseEndpoint.PageType.ARTIST, BrowseEndpoint.PageType.USER_CHANNEL ->
                return MusicItem.OfArtist(ArtistItem(browse.browseId, title, artwork, subtitle))
            BrowseEndpoint.PageType.PLAYLIST, BrowseEndpoint.PageType.PODCAST ->
                return MusicItem.OfPlaylist(PlaylistItem(playlistId(browse.browseId), title, subtitle, artwork))
            BrowseEndpoint.PageType.GENERIC -> {}
        }
    }

    val videoId = navigation["watchEndpoint", "videoId"].text
    if (videoId != null) {
        val info = songInfo(subtitleRuns, skipTypeSpec = true)
        val menu = menuInfo(renderer)
        var kind = songKind(musicVideoType(navigation), artwork)
        if (musicVideoType(navigation) == null && info.typeText?.lowercase() == "video") kind = Song.Kind.VIDEO
        return MusicItem.OfSong(
            Song(
                videoId = videoId,
                title = title,
                artists = info.artists,
                album = info.album,
                duration = info.duration,
                artwork = artwork,
                isExplicit = isExplicit(renderer),
                kind = kind,
                playsText = info.countText,
                likeStatus = menu.likeStatus,
                libraryTokens = menu.libraryTokens,
            ),
        )
    }
    val mixId = navigation["watchPlaylistEndpoint", "playlistId"].text ?: navigation["watchEndpoint", "playlistId"].text
    if (mixId != null) {
        // A mix that only plays ("My Supermix").
        return MusicItem.OfPlaylist(PlaylistItem(mixId, title, subtitle, artwork))
    }
    return null
}

/** The show of an episode row: the run linking to the show page, or else the last run that is not a date. */
internal fun Parse.episodeShow(runs: List<JsonElement>): ArtistRef? {
    val linked = runs.firstOrNull { it["navigationEndpoint", "browseEndpoint", "browseId"].string?.startsWith("MPSP") == true }
    if (linked != null) {
        val name = linked["text"].text
        if (name != null) return ArtistRef(name, null)
    }
    val names = runs.drop(2).mapNotNull { run ->
        if (isSeparator(run)) return@mapNotNull null
        val text = run["text"].text ?: return@mapNotNull null
        if (isDateText(text) || isCountText(text) || isDurationText(text)) return@mapNotNull null
        text
    }
    return names.lastOrNull()?.let { ArtistRef(it, null) }
}

/** "Apr 12, 2024", "Jan 23", "1d ago", "3 weeks ago": a release date of an episode. */
internal fun Parse.isDateText(text: String): Boolean {
    val lower = text.lowercase()
    if (lower.endsWith(" ago") || lower == "today" || lower == "yesterday") return true
    val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    val words = lower.replace(",", "").split(" ").filter { it.isNotEmpty() }
    if (words.size !in 2..3 || months.none { words[0].startsWith(it) }) return false
    return words.drop(1).all { word -> word.all { c -> isAsciiDigit(c) } }
}

/** A podcast episode row (title, show, date, duration). */
internal fun Parse.multiRowItem(renderer: JsonElement): Song? {
    val title = renderer["title"].runsText ?: return null
    val onTap = renderer["onTap"]
    val play = playNavigation(renderer)
    val videoId = onTap["watchEndpoint", "videoId"].text ?: play["watchEndpoint", "videoId"].text ?: return null
    val showRun = renderer["secondTitle", "runs", 0]
    val artists = showRun["text"].text?.let { listOf(ArtistRef(it, null)) } ?: emptyList()
    val progress = renderer["playbackProgress", "musicPlaybackProgressRenderer"]
    val durationText = progress["durationText"].runs.mapNotNull { it["text"].string }.firstOrNull { !it.contains("•") }
    return Song(
        videoId = videoId,
        title = title,
        artists = artists,
        duration = duration(durationText),
        artwork = artworkOf(renderer),
        isExplicit = isExplicit(renderer),
        kind = Song.Kind.EPISODE,
        playsText = renderer["subtitle"].runs.firstOrNull()?.get("text")?.text?.let { if (isCountText(it)) it else null },
    )
}

// Queue rows

/** A row of the "Up next" queue. */
internal fun Parse.panelSong(renderer: JsonElement): Song? {
    val videoId = renderer["videoId"].text ?: renderer["navigationEndpoint", "watchEndpoint", "videoId"].text ?: return null
    val title = renderer["title"].runsText ?: return null
    val info = songInfo(renderer["longBylineText"].runs, skipTypeSpec = false)
    val artwork = artwork(renderer["thumbnail"])
    val videoType = musicVideoType(renderer["navigationEndpoint"])
    val menu = menuInfo(renderer)
    return Song(
        videoId = videoId,
        title = title,
        artists = info.artists.ifEmpty {
            renderer["shortBylineText"].runsText?.let { listOf(ArtistRef(it, null)) } ?: emptyList()
        },
        album = info.album,
        duration = duration(renderer["lengthText"].runsText) ?: info.duration,
        artwork = artwork,
        isExplicit = isExplicit(renderer),
        kind = songKind(videoType, artwork),
        // The queue's byline counts likes for videos ("49K likes"), which is not a play count.
        playsText = info.countText?.let { if (it.lowercase().contains("like")) null else it },
        likeStatus = menu.likeStatus,
        setVideoId = renderer["playlistSetVideoId"].text
            ?: renderer["navigationEndpoint", "watchEndpoint", "playlistSetVideoId"].text,
        libraryTokens = menu.libraryTokens,
        isAvailable = !renderer["unplayableText"].exists,
    )
}
