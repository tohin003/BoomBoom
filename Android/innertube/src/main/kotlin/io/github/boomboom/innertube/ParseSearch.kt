package io.github.boomboom.innertube

import io.github.boomboom.models.AlbumItem
import io.github.boomboom.models.ArtistItem
import io.github.boomboom.models.ArtistRef
import io.github.boomboom.models.BrowseEndpoint
import io.github.boomboom.models.MusicItem
import io.github.boomboom.models.PlaylistItem
import io.github.boomboom.models.SearchFilter
import io.github.boomboom.models.SearchResults
import io.github.boomboom.models.SearchSuggestion
import io.github.boomboom.models.Shelf
import io.github.boomboom.models.Song
import io.github.boomboom.models.SongsOrItemsContinuation
import io.github.boomboom.models.SuggestedQuery
import kotlinx.serialization.json.JsonElement
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

// Search answers: suggestions while typing, the unfiltered results (top result card, then rows by kind) and the
// filtered results (one list that scrolls on).

// Filters

/** The `params` that select a filter (ytmusicapi's `get_search_params`, spelling correction on). */
internal fun Parse.searchParams(filter: SearchFilter): String {
    val tail = "AWoMEA4QChADEAQQCRAF"
    return when (filter) {
        SearchFilter.SONGS -> "EgWKAQII$tail"
        SearchFilter.VIDEOS -> "EgWKAQIQ$tail"
        SearchFilter.ALBUMS -> "EgWKAQIY$tail"
        SearchFilter.ARTISTS -> "EgWKAQIg$tail"
        SearchFilter.PROFILES -> "EgWKAQJY$tail"
        SearchFilter.PODCASTS -> "EgWKAQJQ$tail"
        SearchFilter.EPISODES -> "EgWKAQJI$tail"
        SearchFilter.FEATURED_PLAYLISTS -> "EgeKAQQoADgBagwQDhAKEAMQBBAJEAU%3D"
        SearchFilter.COMMUNITY_PLAYLISTS -> "EgeKAQQoAEABagwQDhAKEAMQBBAJEAU%3D"
    }
}

/** Reads the filter back out of a search `params` (a base64 protobuf: field 2 → field 17 → the kind's field). */
internal fun Parse.searchFilter(params: String): SearchFilter? {
    var text = removingPercentEncoding(params) ?: params
    text = text.replace('-', '+').replace('_', '/')
    while (text.length % 4 != 0) text += "="
    val bytes = try {
        Base64.getDecoder().decode(text)
    } catch (e: IllegalArgumentException) {
        return null
    }
    fun byteAt(index: Int) = bytes[index].toInt() and 0xFF
    // The pair 0x8A 0x01 is field 17 (a length-delimited field); its length byte follows.
    val start = (0 until bytes.size - 2).firstOrNull { byteAt(it) == 0x8A && byteAt(it + 1) == 0x01 } ?: return null
    val length = byteAt(start + 2)
    val innerEnd = minOf(bytes.size, start + 3 + length)
    val inner = (start + 3 until innerEnd).map { byteAt(it) }
    val tag = inner.firstOrNull() ?: return null
    return when (tag) {
        0x08 -> SearchFilter.SONGS
        0x10 -> SearchFilter.VIDEOS
        0x18 -> SearchFilter.ALBUMS
        0x20 -> SearchFilter.ARTISTS
        0x28 -> if (0x38 in inner) SearchFilter.FEATURED_PLAYLISTS else SearchFilter.COMMUNITY_PLAYLISTS
        0x48 -> SearchFilter.EPISODES
        0x50 -> SearchFilter.PODCASTS
        0x58 -> SearchFilter.PROFILES
        else -> null
    }
}

/**
 * Percent-decoding as Foundation's `removingPercentEncoding` does it: null for a bad `%` sequence or bytes that are
 * not UTF-8, and `+` stays a plus (`URLDecoder` would turn it into a space, which base64 cannot afford).
 */
internal fun removingPercentEncoding(text: String): String? {
    if (!text.contains('%')) return text
    val bytes = java.io.ByteArrayOutputStream()
    var index = 0
    while (index < text.length) {
        val character = text[index]
        if (character == '%') {
            if (index + 2 >= text.length) return null
            val high = Character.digit(text[index + 1], 16)
            val low = Character.digit(text[index + 2], 16)
            if (high < 0 || low < 0) return null
            bytes.write(high * 16 + low)
            index += 3
        } else {
            // A surrogate pair is encoded as one character, not as two broken halves.
            val width = if (Character.isHighSurrogate(character) && index + 1 < text.length) 2 else 1
            bytes.write(text.substring(index, index + width).toByteArray(Charsets.UTF_8))
            index += width
        }
    }
    return try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes.toByteArray()))
            .toString()
    } catch (e: java.nio.charset.CharacterCodingException) {
        null
    }
}

// Results

internal fun Parse.searchSectionList(response: JsonElement): JsonElement {
    val tabbed = response["contents", "tabbedSearchResultsRenderer", "tabs"].array
    val tab = tabbed.firstOrNull { it["tabRenderer", "selected"].bool == true } ?: tabbed.firstOrNull()
    if (tab != null) return tab["tabRenderer", "content", "sectionListRenderer"]
    return response["contents", "sectionListRenderer"]
}

internal fun Parse.searchResults(response: JsonElement, query: String, filter: SearchFilter?): SearchResults {
    val list = searchSectionList(response)
    var corrected: String? = null
    val resultShelves = mutableListOf<Shelf>()
    val flat = mutableListOf<Pair<SearchFilter, MusicItem>>()
    var token: String? = null

    for ((index, section) in list["contents"].array.withIndex()) {
        val (key, body) = section.soleEntry ?: continue
        when (key) {
            "musicCardShelfRenderer" -> topResultShelf(body, index)?.let { resultShelves += it }
            "musicShelfRenderer" -> {
                var shelf = listShelf(body, index) ?: continue
                shelf = shelf.copy(moreFilter = shelf.moreFilter ?: filter)
                if (filter != null) {
                    shelf = shelf.copy(title = if (shelf.title.isEmpty()) filter.title else shelf.title)
                    token = token ?: shelf.continuation
                } else {
                    shelf = shelf.copy(continuation = null)
                }
                resultShelves += shelf
            }
            "itemSectionRenderer" -> {
                for (inner in body["contents"].array) {
                    val text = inner["showingResultsForRenderer", "correctedQueryEndpoint", "searchEndpoint", "query"].text
                        ?: inner["showingResultsForRenderer", "correctedQuery"].runsText
                        ?: inner["didYouMeanRenderer", "correctedQueryEndpoint", "searchEndpoint", "query"].text
                        ?: inner["didYouMeanRenderer", "correctedQuery"].runsText
                    if (text != null) corrected = text
                    val row = inner["musicResponsiveListItemRenderer"]
                    if (row.exists) {
                        val item = responsiveItem(row, typeWordFirst = filter == null)
                        if (item != null) flat += (filter ?: searchGroup(item, row)) to item
                    }
                    continuationItemToken(inner)?.let { token = token ?: it }
                }
            }
            else -> continue
        }
    }

    // The flat layout (one row per section, no headers): one shelf per kind, in order of first appearance.
    val groups = LinkedHashMap<SearchFilter, MutableList<MusicItem>>()
    for ((group, item) in flat) groups.getOrPut(group) { mutableListOf() } += item
    for ((group, groupItems) in groups) {
        resultShelves += Shelf(
            id = "search:${group.rawValue}",
            title = group.title,
            layout = Shelf.Layout.LIST,
            items = groupItems,
            moreFilter = group,
        )
    }
    if (filter != null) token = token ?: continuation(list)
    return SearchResults(
        query = query,
        correctedQuery = corrected,
        filter = filter,
        shelves = resultShelves,
        continuation = if (filter == null) null else token,
    )
}

/** Which "Show all" filter a result row belongs to. */
internal fun Parse.searchGroup(item: MusicItem, row: JsonElement): SearchFilter = when (item) {
    is MusicItem.OfSong -> when (item.song.kind) {
        Song.Kind.SONG -> SearchFilter.SONGS
        Song.Kind.VIDEO -> SearchFilter.VIDEOS
        Song.Kind.EPISODE -> SearchFilter.EPISODES
    }
    is MusicItem.OfAlbum -> SearchFilter.ALBUMS
    is MusicItem.OfArtist -> {
        val pageType = row["navigationEndpoint", "browseEndpoint", "browseEndpointContextSupportedConfigs",
            "browseEndpointContextMusicConfig", "pageType"].string
        if (pageType == "MUSIC_PAGE_TYPE_USER_CHANNEL") SearchFilter.PROFILES else SearchFilter.ARTISTS
    }
    is MusicItem.OfPlaylist -> {
        val browseId = row["navigationEndpoint", "browseEndpoint", "browseId"].string ?: ""
        if (browseId.startsWith("MPSP")) {
            SearchFilter.PODCASTS
        } else if (item.playlist.id.startsWith("RDCLAK")) {
            SearchFilter.FEATURED_PLAYLISTS
        } else {
            SearchFilter.COMMUNITY_PLAYLISTS
        }
    }
}

/** The big "Top result" card: its item first, then the rows under it. */
internal fun Parse.topResultShelf(card: JsonElement, index: Int): Shelf? {
    val cardItems = mutableListOf<MusicItem>()
    val top = topResultItem(card)
    top?.let { cardItems += it }
    // Under an artist's card the songs' own lines leave the artist out ("Song • 5:55"): it is the card's.
    val cardArtist = (top as? MusicItem.OfArtist)?.artist?.let { ArtistRef(name = it.name, id = it.id) }
    for (wrapper in card["contents"].array) {
        var found = item(wrapper) ?: continue
        if (cardArtist != null && found is MusicItem.OfSong && found.song.artists.isEmpty()) {
            found = MusicItem.OfSong(found.song.copy(artists = listOf(cardArtist)))
        }
        cardItems += found
    }
    if (cardItems.isEmpty()) return null
    val title = card["header", "musicCardShelfHeaderBasicRenderer", "title"].runsText ?: "Top result"
    return Shelf(id = "$index:top", title = title, layout = Shelf.Layout.TOP_RESULT, items = cardItems)
}

internal fun Parse.topResultItem(card: JsonElement): MusicItem? {
    val title = card["title"].runsText ?: return null
    val titleNavigation = card["title", "runs", 0, "navigationEndpoint"]
    val subtitleRuns = card["subtitle"].runs
    val artwork = artworkOf(card)
    // The card's line always starts with the kind ("Video • Coldplay • 1.3B views").
    val info = songInfo(subtitleRuns, skipTypeSpec = true, anyTypeWord = true)

    val browse = browseEndpointIn(titleNavigation) ?: browseEndpointIn(card["onTap"])
    if (browse != null) {
        when (browse.pageType) {
            BrowseEndpoint.PageType.ARTIST, BrowseEndpoint.PageType.USER_CHANNEL -> {
                var runs = subtitleRuns
                if (runs.size > 2 && runs[1]["text"].string == Parse.DOT_SEPARATOR) runs = runs.drop(2)
                val detail = runs.mapNotNull { it["text"].string }.joinToString("")
                return MusicItem.OfArtist(
                    ArtistItem(browse.browseId, title, artwork, detail.ifEmpty { null }),
                )
            }
            BrowseEndpoint.PageType.ALBUM -> {
                var albumPlaylistId: String? = null
                for (button in card["buttons"].array) {
                    val id = watchEndpointIn(button["buttonRenderer", "command"])?.playlistId
                    if (id != null) {
                        albumPlaylistId = id
                        break
                    }
                }
                return MusicItem.OfAlbum(
                    AlbumItem(
                        id = browse.browseId,
                        playlistId = albumPlaylistId,
                        title = title,
                        artists = info.artists,
                        year = info.year,
                        typeText = info.typeText,
                        artwork = artwork,
                        isExplicit = isExplicit(card),
                    ),
                )
            }
            BrowseEndpoint.PageType.PLAYLIST, BrowseEndpoint.PageType.PODCAST ->
                return MusicItem.OfPlaylist(
                    PlaylistItem(playlistId(browse.browseId), title, card["subtitle"].runsText, artwork),
                )
            BrowseEndpoint.PageType.GENERIC -> {}
        }
    }
    val watch = if (titleNavigation["watchEndpoint"].exists) titleNavigation else card["onTap"]
    val videoId = watch["watchEndpoint", "videoId"].text
        ?: playNavigation(card)["watchEndpoint", "videoId"].text
        ?: return null
    var kind = songKind(musicVideoType(watch), artwork)
    if (musicVideoType(watch) == null && info.typeText?.lowercase() == "video") kind = Song.Kind.VIDEO
    val menu = menuInfo(card)
    return MusicItem.OfSong(
        Song(
            videoId = videoId,
            title = title,
            artists = info.artists,
            album = info.album,
            duration = info.duration,
            artwork = artwork,
            isExplicit = isExplicit(card),
            kind = kind,
            playsText = info.countText,
            likeStatus = menu.likeStatus,
            libraryTokens = menu.libraryTokens,
        ),
    )
}

internal fun Parse.searchContinuation(response: JsonElement): SongsOrItemsContinuation? {
    val page = continuationPage(response) ?: return null
    val rows = mutableListOf<MusicItem>()
    for (wrapper in page.items) {
        val parsed = item(wrapper)
        if (parsed != null) {
            rows += parsed
        } else {
            // Newer answers wrap each row in its own item section.
            rows += wrapper["itemSectionRenderer", "contents"].array.mapNotNull { item(it) }
        }
    }
    var token = page.token
    if (token == null) {
        val last = page.items.lastOrNull()
        if (last != null) token = last["itemSectionRenderer", "contents"].array.lastOrNull()?.let { continuationItemToken(it) }
    }
    return SongsOrItemsContinuation(rows, token)
}

// Suggestions

internal fun Parse.searchSuggestions(response: JsonElement): List<SearchSuggestion> {
    val queries = mutableListOf<SearchSuggestion>()
    val items = mutableListOf<SearchSuggestion>()
    for (section in response["contents"].array) {
        for (entry in section["searchSuggestionsSectionRenderer", "contents"].array) {
            val history = entry["historySuggestionRenderer"]
            val plain = entry["searchSuggestionRenderer"]
            if (history.exists || plain.exists) {
                val renderer = if (history.exists) history else plain
                val runs = renderer["suggestion"].runs.mapNotNull { run ->
                    val text = run["text"].text ?: return@mapNotNull null
                    SuggestedQuery.Run(text, run["bold"].bool ?: false)
                }
                val text = renderer["navigationEndpoint", "searchEndpoint", "query"].text
                    ?: renderer["suggestion"].runsText
                    ?: continue
                queries += SearchSuggestion.Query(SuggestedQuery.of(text, runs, isFromHistory = history.exists))
            } else {
                item(entry)?.let { items += SearchSuggestion.Item(it) }
            }
        }
    }
    return queries + items
}
