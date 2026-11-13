package io.github.boomboom.innertube

import io.github.boomboom.models.BrowsePage
import io.github.boomboom.models.Chip
import io.github.boomboom.models.MusicItem
import io.github.boomboom.models.Shelf
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlin.math.floor

// Sections of a page (carousels, lists, grids, text blocks) as `Shelf`, and whole browse pages as `BrowsePage`.

// Finding the section list

/**
 * The section list of a browse answer, in whichever layout it came: single column, two columns, or a bare
 * `sectionListRenderer` (related, lyrics).
 *
 * A two-column page has a list in its tab and another in `secondaryContents`. Albums and playlists keep their
 * header in the first and their tracks in the second; some artist pages keep everything in the first. Both are
 * read, tab first, as one list (header sections parse to no shelf).
 */
internal fun Parse.sectionList(response: JsonElement): JsonElement {
    val contents = response["contents"]
    val lists = mutableListOf<JsonElement>()
    for (root in listOf(contents["singleColumnBrowseResultsRenderer"], contents["twoColumnBrowseResultsRenderer"])) {
        if (!root.exists) continue
        val tabs = root["tabs"].array
        val selected = tabs.firstOrNull { it["tabRenderer", "selected"].bool == true } ?: tabs.firstOrNull() ?: JsonNull
        val list = selected["tabRenderer", "content", "sectionListRenderer"]
        if (list.exists) lists += list
    }
    val secondary = contents["twoColumnBrowseResultsRenderer", "secondaryContents", "sectionListRenderer"]
    if (secondary.exists) lists += secondary
    val bare = contents["sectionListRenderer"]
    if (bare.exists) lists += bare

    if (lists.size <= 1) return lists.firstOrNull() ?: JsonNull
    var merged: JsonElement = obj("contents" to lists.flatMap { it["contents"].array })
    // The list that scrolls on and the chips over it belong to the main list: the secondary one when present.
    for (key in listOf("continuations", "header")) {
        val value = lists.reversed().firstOrNull { it[key].exists }?.get(key)
        if (value != null) merged = merged.with(key, value)
    }
    return merged
}

/** The header section of a two-column page (`musicResponsiveHeaderRenderer` or the editable variant). */
internal fun Parse.twoColumnHeader(response: JsonElement): JsonElement =
    response["contents", "twoColumnBrowseResultsRenderer", "tabs", 0, "tabRenderer", "content",
        "sectionListRenderer", "contents", 0]

// Pages

internal fun Parse.browsePage(response: JsonElement): BrowsePage {
    val list = sectionList(response)
    var parsedShelves = shelves(list["contents"].array)
    val parsedChips = chips(list["header"]).toMutableList()
    // Navigation-button grids (Explore's top row, mood tiles) become chips.
    for (section in list["contents"].array) parsedChips += navigationChips(section)
    if (parsedShelves.isEmpty() && parsedChips.isEmpty()) {
        // Some pages put one grid or shelf straight in the tab content. That content is a single object (the
        // grid or shelf itself), not a list of sections.
        val content = response["contents", "singleColumnBrowseResultsRenderer", "tabs", 0, "tabRenderer", "content"]
        if (content.exists) parsedShelves = shelves(listOf(content))
    }
    // A page of one list that scrolls on (a library tab) keeps its token on the shelf; screens load more of a
    // page through the page's token, so it is offered there too.
    val shelfToken = if (parsedShelves.size == 1) parsedShelves[0].continuation else null
    return BrowsePage(
        title = pageTitle(response),
        chips = parsedChips,
        shelves = parsedShelves,
        continuation = continuation(list) ?: shelfToken,
    )
}

internal fun Parse.pageTitle(response: JsonElement): String? {
    val header = response["header"]
    for (key in listOf(
        "musicHeaderRenderer", "musicImmersiveHeaderRenderer", "musicDetailHeaderRenderer",
        "musicVisualHeaderRenderer", "musicEditablePlaylistDetailHeaderRenderer",
    )) {
        header[key, "title"].runsText?.let { return it }
    }
    header["musicEditablePlaylistDetailHeaderRenderer", "header", "musicDetailHeaderRenderer", "title"].runsText
        ?.let { return it }
    return twoColumnHeader(response)["musicResponsiveHeaderRenderer", "title"].runsText
}

/** The filter pills over Home (`chipCloudRenderer`). */
internal fun Parse.chips(header: JsonElement): List<Chip> =
    header["chipCloudRenderer", "chips"].array.mapNotNull { wrapper ->
        val chip = wrapper["chipCloudChipRenderer"]
        val title = chip["text"].runsText ?: return@mapNotNull null
        Chip(
            title = title,
            endpoint = browseEndpointIn(chip["navigationEndpoint"]),
            isSelected = chip["isSelected"].bool ?: false,
        )
    }

/** Tiles of a grid or carousel of `musicNavigationButtonRenderer` (moods, genres, Explore's buttons). */
internal fun Parse.navigationChips(section: JsonElement): List<Chip> {
    val (_, body) = section.soleEntry ?: return emptyList()
    val items = body["items"].array.ifEmpty { body["contents"].array }
    return items.mapNotNull { navigationChip(it["musicNavigationButtonRenderer"]) }
}

internal fun Parse.navigationChip(button: JsonElement): Chip? {
    if (!button.exists) return null
    val title = button["buttonText"].runsText ?: return null
    // An ARGB number such as 4294940672 (0xFFFF9800); only the RGB part is kept. Anything that is not a whole
    // number from 0 to 2^64 (a negative, an absurdly big one) is no colour.
    val color = button["solid", "leftStripeColor"].double?.let { value ->
        val rounded = if (value < 0) -floor(-value + 0.5) else floor(value + 0.5)
        if (rounded >= 0 && rounded < 18446744073709551616.0) (rounded.toULong() and 0xFFFFFFu).toInt() else null
    }
    return Chip(title = title, endpoint = browseEndpointIn(button["clickCommand"]), colorHex = color)
}

// Shelves

internal fun Parse.shelves(sections: List<JsonElement>): List<Shelf> {
    val result = mutableListOf<Shelf>()
    for ((index, section) in sections.withIndex()) result += shelves(section, index)
    // Keep ids unique even when two shelves share a title.
    val seen = HashMap<String, Int>()
    return result.map { shelf ->
        val count = seen[shelf.id] ?: 0
        seen[shelf.id] = count + 1
        if (count > 0) shelf.copy(id = shelf.id + "#$count") else shelf
    }
}

internal fun Parse.shelves(section: JsonElement, index: Int): List<Shelf> {
    val (key, body) = section.soleEntry ?: return emptyList()
    return when (key) {
        "musicCarouselShelfRenderer", "musicImmersiveCarouselShelfRenderer" ->
            listOfNotNull(carouselShelf(body, index))
        "musicShelfRenderer", "musicPlaylistShelfRenderer" -> listOfNotNull(listShelf(body, index))
        "gridRenderer" -> listOfNotNull(gridShelf(body, index))
        "musicDescriptionShelfRenderer" -> listOfNotNull(descriptionShelf(body, index))
        "itemSectionRenderer" ->
            body["contents"].array.withIndex().flatMap { (offset, inner) -> shelves(inner, index * 100 + offset) }
        "musicCardShelfRenderer" -> listOfNotNull(topResultShelf(body, index))
        else -> emptyList()
    }
}

internal fun Parse.items(wrappers: List<JsonElement>): List<MusicItem> = wrappers.mapNotNull { item(it) }

internal fun Parse.carouselShelf(body: JsonElement, index: Int): Shelf? {
    val contents = body["contents"].array
    val parsed = items(contents)
    if (parsed.isEmpty()) return null
    val header = if (body["header", "musicCarouselShelfBasicHeaderRenderer"].exists) {
        body["header", "musicCarouselShelfBasicHeaderRenderer"]
    } else {
        body["header", "musicImmersiveCarouselShelfBasicHeaderRenderer"]
    }
    val title = header["title"].runsText ?: header["strapline"].runsText ?: ""
    val strapline = if (header["title"].runsText == null) null else header["strapline"].runsText
    val more = moreEndpoint(header["moreContentButton", "buttonRenderer", "navigationEndpoint"])
        ?: moreEndpoint(header["title", "runs", 0, "navigationEndpoint"])
    val headerArtwork = artworkOf(header)
    val isRanked = contents.any { it["musicResponsiveListItemRenderer", "customIndexColumn"].exists }
    val isRows = contents.any { it["musicResponsiveListItemRenderer"].exists }
    val layout = if (isRanked) Shelf.Layout.RANKED else if (isRows) Shelf.Layout.COLUMNS else Shelf.Layout.CAROUSEL
    return Shelf(
        id = body["shelfId"].text ?: "$index:$title",
        title = title,
        strapline = strapline,
        headerArtwork = if (headerArtwork.isEmpty) null else headerArtwork,
        layout = layout,
        items = parsed,
        more = more,
        continuation = continuation(body),
    )
}

internal fun Parse.listShelf(body: JsonElement, index: Int): Shelf? {
    val contents = body["contents"].array
    val parsed = items(contents)
    if (parsed.isEmpty()) return null
    val title = body["title"].runsText ?: body["header", "musicShelfHeaderRenderer", "title"].runsText ?: ""
    val isRanked = contents.any { it["musicResponsiveListItemRenderer", "customIndexColumn"].exists }
    val more = moreEndpoint(body["bottomEndpoint"]) ?: moreEndpoint(body["title", "runs", 0, "navigationEndpoint"])
    return Shelf(
        id = "$index:$title",
        title = title,
        layout = if (isRanked) Shelf.Layout.RANKED else Shelf.Layout.LIST,
        items = parsed,
        more = more,
        moreFilter = body["bottomEndpoint", "searchEndpoint", "params"].text?.let { searchFilter(it) },
        continuation = continuation(body, contents),
    )
}

internal fun Parse.gridShelf(body: JsonElement, index: Int): Shelf? {
    val parsed = items(body["items"].array)
    if (parsed.isEmpty()) return null
    val title = body["header", "gridHeaderRenderer", "title"].runsText ?: ""
    return Shelf(
        id = "$index:$title",
        title = title,
        layout = Shelf.Layout.GRID,
        items = parsed,
        continuation = continuation(body, body["items"].array),
    )
}

internal fun Parse.descriptionShelf(body: JsonElement, index: Int): Shelf? {
    val text = body["description"].runsText ?: return null
    val title = body["header"].runsText ?: ""
    return Shelf(id = "$index:$title", title = title, layout = Shelf.Layout.TEXT, text = text)
}

// Continuations of pages and lists

/** The rows and next token of a continuation answer, from whichever container it came in. */
internal class ContinuationPage(
    /** `musicShelfContinuation`, `gridContinuation`, `sectionListContinuation`…, or null for the newer form. */
    val kind: String?,
    val container: JsonElement,
    val items: List<JsonElement>,
    val token: String?,
)

internal fun Parse.continuationPage(response: JsonElement): ContinuationPage? {
    val contents = response["continuationContents"]
    val sole = contents.soleEntry
    if (sole != null) {
        val (kind, container) = sole
        val rows = container["contents"].array.ifEmpty { container["items"].array }
        return ContinuationPage(kind, container, rows, continuation(container, rows))
    }
    val actions = response["onResponseReceivedActions"].array + response["onResponseReceivedCommands"].array
    for (action in actions) {
        for (key in listOf("appendContinuationItemsAction", "reloadContinuationItemsCommand")) {
            val rows = action[key, "continuationItems"].array
            if (rows.isNotEmpty()) {
                return ContinuationPage(null, JsonNull, rows, rows.lastOrNull()?.let { continuationItemToken(it) })
            }
        }
    }
    return null
}

/** More shelves of a page (Home scrolling on), or more rows of a one-list page as one untitled shelf. */
internal fun Parse.browseContinuation(response: JsonElement): BrowsePage? {
    val page = continuationPage(response) ?: return null
    val sections = shelves(page.items)
    if (sections.isNotEmpty() || page.kind == "sectionListContinuation") {
        return BrowsePage(shelves = sections, continuation = page.token)
    }
    val rows = items(page.items)
    if (rows.isEmpty()) return BrowsePage(continuation = page.token)
    val isGrid = page.kind == "gridContinuation" || page.items.any { it["musicTwoRowItemRenderer"].exists }
    val shelf = Shelf(
        // Unique per answer, so a screen appending shelves by id keeps every batch.
        id = "more:" + (page.token?.takeLast(24) ?: rows.size.toString()),
        title = "",
        layout = if (isGrid) Shelf.Layout.GRID else Shelf.Layout.LIST,
        items = rows,
        continuation = page.token,
    )
    return BrowsePage(shelves = listOf(shelf), continuation = page.token)
}
