import BoomModels
import Foundation

// Sections of a page (carousels, lists, grids, text blocks) as `Shelf`, and whole browse pages as `BrowsePage`.
extension Parse {
    // MARK: Finding the section list

    /// The section list of a browse answer, in whichever layout it came: single column, two columns, or a bare
    /// `sectionListRenderer` (related, lyrics).
    ///
    /// A two-column page has a list in its tab and another in `secondaryContents`. Albums and playlists keep their
    /// header in the first and their tracks in the second; some artist pages keep everything in the first. Both are
    /// read, tab first, as one list (header sections parse to no shelf).
    static func sectionList(of response: JSON) -> JSON {
        let contents = response["contents"]
        var lists: [JSON] = []
        for root in [contents["singleColumnBrowseResultsRenderer"], contents["twoColumnBrowseResultsRenderer"]]
        where root.exists {
            let tabs = root["tabs"].array
            let selected = tabs.first { $0["tabRenderer", "selected"].bool == true } ?? tabs.first ?? .null
            let list = selected["tabRenderer", "content", "sectionListRenderer"]
            if list.exists { lists.append(list) }
        }
        let secondary = contents["twoColumnBrowseResultsRenderer", "secondaryContents", "sectionListRenderer"]
        if secondary.exists { lists.append(secondary) }
        let bare = contents["sectionListRenderer"]
        if bare.exists { lists.append(bare) }

        guard lists.count > 1 else { return lists.first ?? .null }
        var merged: JSON = ["contents": .array(lists.flatMap { $0["contents"].array })]
        // The list that scrolls on and the chips over it belong to the main list: the secondary one when present.
        for key in ["continuations", "header"] {
            if let value = lists.reversed().first(where: { $0[.key(key)].exists })?[.key(key)] { merged.set(key, value) }
        }
        return merged
    }

    /// The header section of a two-column page (`musicResponsiveHeaderRenderer` or the editable variant).
    static func twoColumnHeader(of response: JSON) -> JSON {
        response["contents", "twoColumnBrowseResultsRenderer", "tabs", 0, "tabRenderer", "content",
            "sectionListRenderer", "contents", 0]
    }

    // MARK: Pages

    static func browsePage(_ response: JSON) -> BrowsePage {
        let list = sectionList(of: response)
        var shelves = shelves(list["contents"].array)
        var chips = chips(list["header"])
        // Navigation-button grids (Explore's top row, mood tiles) become chips.
        for section in list["contents"].array {
            chips.append(contentsOf: navigationChips(section))
        }
        if shelves.isEmpty, chips.isEmpty {
            // Some pages put one grid or shelf straight in the tab content. That content is a single object (the
            // grid or shelf itself), not a list of sections.
            let content = response["contents", "singleColumnBrowseResultsRenderer", "tabs", 0, "tabRenderer", "content"]
            if content.exists { shelves = self.shelves([content]) }
        }
        // A page of one list that scrolls on (a library tab) keeps its token on the shelf; screens load more of a
        // page through the page's token, so it is offered there too.
        let shelfToken = shelves.count == 1 ? shelves[0].continuation : nil
        return BrowsePage(
            title: pageTitle(response),
            chips: chips,
            shelves: shelves,
            continuation: continuation(of: list) ?? shelfToken
        )
    }

    static func pageTitle(_ response: JSON) -> String? {
        let header = response["header"]
        for key in ["musicHeaderRenderer", "musicImmersiveHeaderRenderer", "musicDetailHeaderRenderer",
            "musicVisualHeaderRenderer", "musicEditablePlaylistDetailHeaderRenderer"]
        {
            if let title = header[.key(key), "title"].runsText { return title }
        }
        if let title = header["musicEditablePlaylistDetailHeaderRenderer", "header", "musicDetailHeaderRenderer",
            "title"].runsText
        {
            return title
        }
        return twoColumnHeader(of: response)["musicResponsiveHeaderRenderer", "title"].runsText
    }

    /// The filter pills over Home (`chipCloudRenderer`).
    static func chips(_ header: JSON) -> [Chip] {
        header["chipCloudRenderer", "chips"].array.compactMap { wrapper in
            let chip = wrapper["chipCloudChipRenderer"]
            guard let title = chip["text"].runsText else { return nil }
            return Chip(
                title: title,
                endpoint: browseEndpoint(in: chip["navigationEndpoint"]),
                isSelected: chip["isSelected"].bool ?? false
            )
        }
    }

    /// Tiles of a grid or carousel of `musicNavigationButtonRenderer` (moods, genres, Explore's buttons).
    static func navigationChips(_ section: JSON) -> [Chip] {
        guard let (_, body) = section.soleEntry else { return [] }
        let items = body["items"].array.isEmpty ? body["contents"].array : body["items"].array
        return items.compactMap { navigationChip($0["musicNavigationButtonRenderer"]) }
    }

    static func navigationChip(_ button: JSON) -> Chip? {
        guard button.exists, let title = button["buttonText"].runsText else { return nil }
        // An ARGB number such as 4294940672 (0xFFFF9800); only the RGB part is kept.
        let color = button["solid", "leftStripeColor"].double.flatMap { UInt64(exactly: $0.rounded()) }
            .map { UInt32($0 & 0xFFFFFF) }
        return Chip(title: title, endpoint: browseEndpoint(in: button["clickCommand"]), colorHex: color)
    }

    // MARK: Shelves

    static func shelves(_ sections: [JSON]) -> [Shelf] {
        var result: [Shelf] = []
        for (index, section) in sections.enumerated() {
            result.append(contentsOf: shelves(section: section, index: index))
        }
        // Keep ids unique even when two shelves share a title.
        var seen: [String: Int] = [:]
        return result.map { shelf in
            var shelf = shelf
            let count = seen[shelf.id, default: 0]
            seen[shelf.id] = count + 1
            if count > 0 { shelf.id += "#\(count)" }
            return shelf
        }
    }

    static func shelves(section: JSON, index: Int) -> [Shelf] {
        guard let (key, body) = section.soleEntry else { return [] }
        switch key {
        case "musicCarouselShelfRenderer", "musicImmersiveCarouselShelfRenderer":
            return carouselShelf(body, index: index).map { [$0] } ?? []
        case "musicShelfRenderer", "musicPlaylistShelfRenderer":
            return listShelf(body, index: index).map { [$0] } ?? []
        case "gridRenderer":
            return gridShelf(body, index: index).map { [$0] } ?? []
        case "musicDescriptionShelfRenderer":
            return descriptionShelf(body, index: index).map { [$0] } ?? []
        case "itemSectionRenderer":
            return body["contents"].array.enumerated().flatMap { offset, inner in
                shelves(section: inner, index: index * 100 + offset)
            }
        case "musicCardShelfRenderer":
            return topResultShelf(body, index: index).map { [$0] } ?? []
        default:
            return []
        }
    }

    static func items(_ wrappers: [JSON]) -> [MusicItem] {
        wrappers.compactMap(item)
    }

    static func carouselShelf(_ body: JSON, index: Int) -> Shelf? {
        let contents = body["contents"].array
        let items = items(contents)
        guard !items.isEmpty else { return nil }
        let header = body["header", "musicCarouselShelfBasicHeaderRenderer"].exists
            ? body["header", "musicCarouselShelfBasicHeaderRenderer"]
            : body["header", "musicImmersiveCarouselShelfBasicHeaderRenderer"]
        let title = header["title"].runsText ?? header["strapline"].runsText ?? ""
        let strapline = header["title"].runsText == nil ? nil : header["strapline"].runsText
        let more = moreEndpoint(in: header["moreContentButton", "buttonRenderer", "navigationEndpoint"])
            ?? moreEndpoint(in: header["title", "runs", 0, "navigationEndpoint"])
        let headerArtwork = artwork(of: header)
        let isRanked = contents.contains { $0["musicResponsiveListItemRenderer", "customIndexColumn"].exists }
        let isRows = contents.contains { $0["musicResponsiveListItemRenderer"].exists }
        let layout: Shelf.Layout = isRanked ? .ranked : isRows ? .columns : .carousel
        return Shelf(
            id: body["shelfId"].text ?? "\(index):\(title)",
            title: title,
            strapline: strapline,
            headerArtwork: headerArtwork.isEmpty ? nil : headerArtwork,
            layout: layout,
            items: items,
            more: more,
            continuation: continuation(of: body)
        )
    }

    static func listShelf(_ body: JSON, index: Int) -> Shelf? {
        let contents = body["contents"].array
        let items = items(contents)
        guard !items.isEmpty else { return nil }
        let title = body["title"].runsText ?? body["header", "musicShelfHeaderRenderer", "title"].runsText ?? ""
        let isRanked = contents.contains { $0["musicResponsiveListItemRenderer", "customIndexColumn"].exists }
        let more = moreEndpoint(in: body["bottomEndpoint"])
            ?? moreEndpoint(in: body["title", "runs", 0, "navigationEndpoint"])
        return Shelf(
            id: "\(index):\(title)",
            title: title,
            layout: isRanked ? .ranked : .list,
            items: items,
            more: more,
            moreFilter: body["bottomEndpoint", "searchEndpoint", "params"].text.flatMap(searchFilter(params:)),
            continuation: continuation(of: body, items: contents)
        )
    }

    static func gridShelf(_ body: JSON, index: Int) -> Shelf? {
        let items = items(body["items"].array)
        guard !items.isEmpty else { return nil }
        let title = body["header", "gridHeaderRenderer", "title"].runsText ?? ""
        return Shelf(
            id: "\(index):\(title)",
            title: title,
            layout: .grid,
            items: items,
            continuation: continuation(of: body, items: body["items"].array)
        )
    }

    static func descriptionShelf(_ body: JSON, index: Int) -> Shelf? {
        guard let text = body["description"].runsText else { return nil }
        let title = body["header"].runsText ?? ""
        return Shelf(id: "\(index):\(title)", title: title, layout: .text, text: text)
    }

    // MARK: Continuations of pages and lists

    /// The rows and next token of a continuation answer, from whichever container it came in.
    struct ContinuationPage {
        /// `musicShelfContinuation`, `gridContinuation`, `sectionListContinuation`…, or nil for the newer form.
        var kind: String?
        var container: JSON
        var items: [JSON]
        var token: String?
    }

    static func continuationPage(_ response: JSON) -> ContinuationPage? {
        let contents = response["continuationContents"]
        if let (kind, container) = contents.soleEntry {
            let items = container["contents"].array.isEmpty ? container["items"].array : container["contents"].array
            return ContinuationPage(
                kind: kind, container: container, items: items, token: continuation(of: container, items: items)
            )
        }
        let actions = response["onResponseReceivedActions"].array + response["onResponseReceivedCommands"].array
        for action in actions {
            for key in ["appendContinuationItemsAction", "reloadContinuationItemsCommand"] {
                let items = action[.key(key), "continuationItems"].array
                if !items.isEmpty {
                    return ContinuationPage(
                        kind: nil, container: .null, items: items, token: items.last.flatMap(continuationItemToken)
                    )
                }
            }
        }
        return nil
    }

    /// More shelves of a page (Home scrolling on), or more rows of a one-list page as one untitled shelf.
    static func browseContinuation(_ response: JSON) -> BrowsePage? {
        guard let page = continuationPage(response) else { return nil }
        let sections = shelves(page.items)
        if !sections.isEmpty || page.kind == "sectionListContinuation" {
            return BrowsePage(shelves: sections, continuation: page.token)
        }
        let rows = items(page.items)
        guard !rows.isEmpty else { return BrowsePage(continuation: page.token) }
        let isGrid = page.kind == "gridContinuation" || page.items.contains { $0["musicTwoRowItemRenderer"].exists }
        let shelf = Shelf(
            // Unique per answer, so a screen appending shelves by id keeps every batch.
            id: "more:\(page.token?.suffix(24) ?? Substring(String(rows.count)))",
            title: "",
            layout: isGrid ? .grid : .list,
            items: rows,
            continuation: page.token
        )
        return BrowsePage(shelves: [shelf], continuation: page.token)
    }
}
