package io.github.boomboom.innertube

import io.github.boomboom.models.Account
import io.github.boomboom.models.AlbumItem
import io.github.boomboom.models.AlbumPage
import io.github.boomboom.models.AlbumRef
import io.github.boomboom.models.ArtistItem
import io.github.boomboom.models.ArtistPage
import io.github.boomboom.models.ArtistRef
import io.github.boomboom.models.Artwork
import io.github.boomboom.models.ChipGroup
import io.github.boomboom.models.Lyrics
import io.github.boomboom.models.MusicItem
import io.github.boomboom.models.PlaylistItem
import io.github.boomboom.models.PlaylistPage
import io.github.boomboom.models.Shelf
import io.github.boomboom.models.Song
import io.github.boomboom.models.SongsContinuation
import io.github.boomboom.models.Thumbnail
import io.github.boomboom.models.WatchEndpoint
import io.github.boomboom.models.WatchQueue
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import java.net.URLDecoder

// Whole pages with a header: albums, artists, playlists; and the player's queue, lyrics and the account menu.

// Album

internal fun Parse.albumPage(response: JsonElement, browseId: String): AlbumPage? {
    // Two-column layout (2024 on): header in the first tab, tracks in `secondaryContents`.
    var header = twoColumnHeader(response)["musicResponsiveHeaderRenderer"]
    var buttons = header["buttons"].array
    var artistRuns = header["straplineTextOne"].runs
    if (!header.exists) {
        // Single-column layout: `header.musicDetailHeaderRenderer`, buttons in its menu.
        header = response["header", "musicDetailHeaderRenderer"]
        buttons = header["menu", "menuRenderer", "topLevelButtons"].array
        artistRuns = emptyList()
    }
    val title = header["title"].runsText ?: return null

    val subtitleRuns = header["subtitle"].runs
    val info = songInfo(subtitleRuns, skipTypeSpec = true)
    var artists = songInfo(artistRuns, skipTypeSpec = false).artists
    if (artists.isEmpty()) artists = info.artists
    val typeText = info.typeText ?: (if (subtitleRuns.size == 1) subtitleRuns.firstOrNull()?.get("text")?.text else null)

    var albumPlaylistId: String? = null
    for (button in buttons) {
        val play = if (button["musicPlayButtonRenderer", "playNavigationEndpoint"].exists) {
            button["musicPlayButtonRenderer", "playNavigationEndpoint"]
        } else {
            button["buttonRenderer", "navigationEndpoint"]
        }
        val id = watchEndpointIn(play)?.playlistId
        if (id != null) {
            albumPlaylistId = id
            break
        }
    }

    val list = sectionList(response)
    var tracks: List<Song> = emptyList()
    val shelfSections = mutableListOf<JsonElement>()
    for (section in list["contents"].array) {
        val shelf = section["musicShelfRenderer"]
        if (tracks.isEmpty() && shelf.exists) {
            tracks = shelf["contents"].array.mapNotNull { wrapper -> item(wrapper)?.asSong }
        } else {
            shelfSections += section
        }
    }
    if (albumPlaylistId == null) {
        albumPlaylistId = response["microformat", "microformatDataRenderer", "urlCanonical"].string
            ?.let { queryItem(it, "list") }
    }

    val artwork = artworkOf(header)
    val album = AlbumItem(
        id = browseId,
        playlistId = albumPlaylistId,
        title = title,
        artists = artists,
        year = info.year,
        typeText = typeText,
        artwork = artwork,
        isExplicit = isExplicit(header),
    )
    // Album rows leave out what the header says: fill it in.
    val albumRef = AlbumRef(title, browseId)
    tracks = tracks.map { track ->
        track.copy(
            artists = track.artists.ifEmpty { artists },
            album = track.album ?: albumRef,
            artwork = if (track.artwork.isEmpty) artwork else track.artwork,
        )
    }
    val description = header["description", "musicDescriptionShelfRenderer", "description"].runsText
        ?: header["description"].runsText
    return AlbumPage(
        album = album,
        description = description,
        statsText = header["secondSubtitle"].runsText,
        tracks = tracks,
        shelves = shelves(shelfSections),
        likeStatus = toggleLikeStatus(buttons),
        libraryTokens = null,
    )
}

/** The value of the first query item called [name] in a URL, percent-decoded (what `URLComponents` gives). */
internal fun queryItem(url: String, name: String): String? {
    val query = url.substringBefore('#').substringAfter('?', "")
    if (query.isEmpty()) return null
    for (pair in query.split('&')) {
        val key = pair.substringBefore('=')
        if (key == name) {
            val value = pair.substringAfter('=', "")
            // `+` is a plus here, not a space.
            return try {
                URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
            } catch (e: IllegalArgumentException) {
                value
            }
        }
    }
    return null
}

// Artist

/** An artist page, and the channel its "Subscribe" button acts on (which can differ from the page's own id). */
internal data class ParsedArtistPage(val page: ArtistPage, val subscribeChannelId: String?)

internal fun Parse.artistPage(response: JsonElement, browseId: String): ParsedArtistPage? {
    val (_, header) = response["header"].soleEntry ?: return null
    val name = header["title"].runsText ?: return null
    val subscribe = header["subscriptionButton", "subscribeButtonRenderer"]
    val banner = artworkOf(header)
    val foreground = artwork(header["foregroundThumbnail", "musicThumbnailRenderer", "thumbnail"])
    var subscribers = subscribe["longSubscriberCountText"].runsText ?: subscribe["subscriberCountText"].runsText
    // The button shows a bare count ("4.68M").
    subscribers?.let { count ->
        if (count.all { !it.isLetter() || it in "KMBkmb" }) subscribers = "$count subscribers"
    }
    val monthly = header["monthlyListenerCount"].runsText

    val list = sectionList(response)
    // Album and single cards on an artist page name only the year: they are this artist's.
    val pageArtist = ArtistRef(name, browseId)
    val parsedShelves = shelves(list["contents"].array).map { shelf ->
        shelf.copy(
            items = shelf.items.map { item ->
                if (item is MusicItem.OfAlbum && item.album.artists.isEmpty()) {
                    MusicItem.OfAlbum(item.album.copy(artists = listOf(pageArtist)))
                } else {
                    item
                }
            },
        )
    }
    val description = header["description"].runsText ?: parsedShelves.firstOrNull { it.layout == Shelf.Layout.TEXT }?.text

    val artist = ArtistItem(
        id = browseId,
        name = name,
        artwork = if (foreground.isEmpty) banner else foreground,
        detailText = monthly ?: subscribers,
    )
    val page = ArtistPage(
        artist = artist,
        banner = banner,
        description = description,
        subscribersText = subscribers,
        monthlyListenersText = monthly,
        isSubscribed = subscribe["subscribed"].bool ?: false,
        shuffle = watchEndpointIn(header["playButton", "buttonRenderer", "navigationEndpoint"]),
        radio = watchEndpointIn(header["startRadioButton", "buttonRenderer", "navigationEndpoint"]),
        shelves = parsedShelves,
    )
    return ParsedArtistPage(page, subscribe["channelId"].text)
}

// Playlist

internal fun Parse.playlistPage(response: JsonElement, playlistId: String): PlaylistPage? {
    val headerSection = twoColumnHeader(response)
    var isEditable = false
    var header = headerSection["musicResponsiveHeaderRenderer"]
    if (!header.exists) {
        val editable = headerSection["musicEditablePlaylistDetailHeaderRenderer"]
        if (editable.exists) {
            isEditable = true
            header = editable["header", "musicResponsiveHeaderRenderer"]
        }
    }
    if (!header.exists) {
        // Single-column layout.
        val editable = response["header", "musicEditablePlaylistDetailHeaderRenderer"]
        isEditable = editable.exists
        header = if (editable.exists) editable["header", "musicDetailHeaderRenderer"]
        else response["header", "musicDetailHeaderRenderer"]
    }

    val list = sectionList(response)
    var trackShelf: JsonElement = JsonNull
    val shelfSections = mutableListOf<JsonElement>()
    for (section in list["contents"].array) {
        val shelf = if (section["musicPlaylistShelfRenderer"].exists) section["musicPlaylistShelfRenderer"]
        else section["musicShelfRenderer"]
        if (!trackShelf.exists && shelf.exists) {
            trackShelf = shelf
        } else {
            shelfSections += section
        }
    }
    val rows = trackShelf["contents"].array
    val tracks = songs(rows)
    val title = header["title"].runsText ?: (if (tracks.isEmpty()) null else pageTitle(response)) ?: return null

    val facepile = header["facepile", "avatarStackViewModel"]
    val authorName = header["straplineTextOne"].runsText ?: facepile["text", "content"].text
        ?: header["subtitle"].runs.firstOrNull { it["navigationEndpoint", "browseEndpoint"].exists }?.get("text")?.text
    var authorArtwork = artworkOf(header["straplineThumbnail"])
    if (authorArtwork.isEmpty) {
        authorArtwork = Artwork(
            facepile["avatars"].array.flatMap { avatar ->
                avatar["avatarViewModel", "image", "sources"].array.mapNotNull { source ->
                    val url = source["url"].text ?: return@mapNotNull null
                    Thumbnail(url, source["width"].int ?: 0, source["height"].int ?: 0)
                }
            },
        )
    }
    val id = trackShelf["playlistId"].text ?: playlistId
    return PlaylistPage(
        playlist = PlaylistItem(id, title, header["subtitle"].runsText, artworkOf(header)),
        description = header["description", "musicDescriptionShelfRenderer", "description"].runsText
            ?: header["description"].runsText,
        authorName = authorName,
        authorArtwork = if (authorArtwork.isEmpty) null else authorArtwork,
        statsText = header["secondSubtitle"].runsText,
        tracks = tracks,
        continuation = continuation(trackShelf, rows),
        isEditable = isEditable,
        likeStatus = toggleLikeStatus(header["buttons"].array),
        shelves = shelves(shelfSections),
    )
}

internal fun Parse.songs(wrappers: List<JsonElement>): List<Song> = wrappers.mapNotNull { item(it)?.asSong }

/** The next token of a playlist page's section list (related playlists, suggestions), if any. */
internal fun Parse.sectionContinuation(response: JsonElement): String? {
    val list = sectionList(response)
    for (entry in list["continuations"].array) {
        entry["nextContinuationData", "continuation"].text?.let { return it }
    }
    return null
}

internal fun Parse.songsContinuation(response: JsonElement): SongsContinuation? {
    val page = continuationPage(response) ?: return null
    return SongsContinuation(songs(page.items), page.token)
}

// Watch queue

internal fun Parse.watchQueue(response: JsonElement, requested: WatchEndpoint): WatchQueue? {
    val tabs = response["contents", "singleColumnMusicWatchNextResultsRenderer", "tabbedRenderer",
        "watchNextTabbedResultsRenderer", "tabs"].array
    if (tabs.isEmpty()) return null
    var lyrics: String? = null
    var related: String? = null
    for (tab in tabs) {
        val renderer = tab["tabRenderer"]
        if (renderer["unselectable"].bool == true) continue
        val browse = browseEndpointIn(renderer["endpoint"]) ?: continue
        val rawType = renderer["endpoint", "browseEndpoint", "browseEndpointContextSupportedConfigs",
            "browseEndpointContextMusicConfig", "pageType"].string
        if (rawType == "MUSIC_PAGE_TYPE_TRACK_LYRICS" || browse.browseId.startsWith("MPLY")) {
            lyrics = browse.browseId
        } else if (rawType == "MUSIC_PAGE_TYPE_TRACK_RELATED" || browse.browseId.startsWith("MPTR")) {
            related = browse.browseId
        }
    }
    val panel = tabs[0]["tabRenderer", "content", "musicQueueRenderer", "content", "playlistPanelRenderer"]
    val rows = panel["contents"].array
    // The songs and the place of the selected one are gathered together: a row that does not make a song (no
    // video id, say) must not shift the selected song's place among those that do.
    val queueSongs = mutableListOf<Song>()
    var selectedIndex: Int? = null
    for (row in rows) {
        val song = item(row)?.asSong ?: continue
        val renderer = if (row["playlistPanelVideoRenderer"].exists) row["playlistPanelVideoRenderer"]
        else row["playlistPanelVideoWrapperRenderer", "primaryRenderer", "playlistPanelVideoRenderer"]
        if (selectedIndex == null && renderer["selected"].bool == true) selectedIndex = queueSongs.size
        queueSongs += song
    }
    val currentVideoId = response["currentVideoEndpoint", "watchEndpoint", "videoId"].text ?: requested.videoId
    val currentIndex = selectedIndex
        ?: currentVideoId?.let { id -> queueSongs.indexOfFirst { it.videoId == id }.takeIf { it >= 0 } }
        ?: 0
    val queuePlaylistId = panel["playlistId"].text
        ?: response["currentVideoEndpoint", "watchEndpoint", "playlistId"].text
        ?: requested.playlistId
    return WatchQueue(
        songs = queueSongs,
        currentIndex = minOf(currentIndex, maxOf(queueSongs.size - 1, 0)),
        playlistId = queuePlaylistId,
        continuation = continuation(panel, rows),
        lyricsBrowseId = lyrics,
        relatedBrowseId = related,
    )
}

// Lyrics

internal fun Parse.lyrics(response: JsonElement): Lyrics? {
    // Timed lyrics, when an answer carries them (the mobile clients do; the web client has so far not).
    val timed = response["contents", "elementRenderer", "newElement", "type", "componentType", "model",
        "timedLyricsModel", "lyricsData"]
    val timedLines = timed["timedLyricsData"].array.mapNotNull { line ->
        val text = line["lyricLine"].string ?: return@mapNotNull null
        val start = line["cueRange", "startTimeMilliseconds"].double?.let { it / 1000 }
        Lyrics.Line(text, start)
    }
    if (timedLines.isNotEmpty()) return Lyrics(timedLines, timed["sourceMessage"].text)

    for (section in sectionList(response)["contents"].array) {
        val shelf = section["musicDescriptionShelfRenderer"]
        val text = shelf["description"].runsText
        if (text != null) {
            val lines = text.replace("\r\n", "\n").split("\n").map { Lyrics.Line(it) }
            return Lyrics(lines, shelf["footer"].runsText)
        }
        if (section["messageRenderer"].exists || section["itemSectionRenderer", "contents", 0, "messageRenderer"].exists) {
            return Lyrics(emptyList())
        }
    }
    return null
}

// Moods and genres

internal fun Parse.chipGroups(response: JsonElement): List<ChipGroup> =
    sectionList(response)["contents"].array.mapNotNull { section ->
        val (_, body) = section.soleEntry ?: return@mapNotNull null
        val tiles = navigationChips(section)
        if (tiles.isEmpty()) return@mapNotNull null
        val title = body["header", "gridHeaderRenderer", "title"].runsText
            ?: body["header", "musicCarouselShelfBasicHeaderRenderer", "title"].runsText ?: ""
        ChipGroup(title, tiles)
    }

// Account

internal fun Parse.account(response: JsonElement): Account? {
    val popup = response["actions", 0, "openPopupAction", "popup", "multiPageMenuRenderer"]
    val header = popup["header", "activeAccountHeaderRenderer"]
    val name = header["accountName"].runsText ?: return null
    return Account(
        name = name,
        handle = header["channelHandle"].runsText,
        artwork = artwork(header["accountPhoto"]),
        // The account menu does not say whether the listener has Premium in a way this client can rely on.
        isPremium = false,
    )
}
