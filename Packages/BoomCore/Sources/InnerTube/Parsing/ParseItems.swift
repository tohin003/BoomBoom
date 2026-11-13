import BoomModels
import Foundation

// One entry of a list or carousel as a `MusicItem`. Unknown or incomplete renderers give nil and are skipped.
extension Parse {
    /// Any item renderer wrapper (`{"musicResponsiveListItemRenderer": {...}}` and friends).
    static func item(_ wrapper: JSON) -> MusicItem? {
        guard let (key, renderer) = wrapper.soleEntry else { return nil }
        switch key {
        case "musicResponsiveListItemRenderer": return responsiveItem(renderer)
        case "musicTwoRowItemRenderer": return twoRowItem(renderer)
        case "musicMultiRowListItemRenderer": return multiRowItem(renderer).map(MusicItem.song)
        case "playlistPanelVideoRenderer": return panelSong(renderer).map(MusicItem.song)
        case "playlistPanelVideoWrapperRenderer":
            return panelSong(renderer["primaryRenderer", "playlistPanelVideoRenderer"]).map(MusicItem.song)
        default: return nil
        }
    }

    // MARK: List rows

    static func flexColumns(_ renderer: JSON) -> [JSON] {
        renderer["flexColumns"].array.map { $0["musicResponsiveListItemFlexColumnRenderer", "text"] }
    }

    /// A row with a small cover and columns of text: search results, album tracks, playlist entries, charts.
    /// `typeWordFirst` is for unfiltered search rows, whose second line always starts with the kind ("Song • …").
    static func responsiveItem(_ renderer: JSON, typeWordFirst: Bool = false) -> MusicItem? {
        let columns = flexColumns(renderer)
        guard let first = columns.first, let title = first.runsText else { return nil }
        let artwork = artwork(of: renderer)
        let detailColumns = Array(columns.dropFirst())

        if let browse = browseEndpoint(in: renderer["navigationEndpoint"]) {
            switch browse.pageType {
            case .artist, .userChannel:
                return .artist(ArtistItem(
                    id: browse.browseId, name: title, artwork: artwork,
                    detailText: detailText(detailColumns, dropTypeWord: true, anyTypeWord: typeWordFirst)
                ))
            case .album:
                let runs = joinedRuns(detailColumns)
                let info = songInfo(runs, skipTypeSpec: true, anyTypeWord: typeWordFirst)
                let play = playNavigation(of: renderer)
                return .album(AlbumItem(
                    id: browse.browseId,
                    playlistId: watchEndpoint(in: play)?.playlistId,
                    title: title,
                    artists: info.artists,
                    year: info.year,
                    typeText: info.typeText,
                    artwork: artwork,
                    isExplicit: isExplicit(renderer)
                ))
            case .playlist, .podcast:
                return .playlist(PlaylistItem(
                    id: playlistId(fromBrowseId: browse.browseId),
                    title: title,
                    detailText: detailText(detailColumns, dropTypeWord: false),
                    artwork: artwork
                ))
            case .generic:
                // Episode rows link their title to an episode page; they still play.
                if let song = responsiveSong(
                    renderer, title: title, columns: columns, artwork: artwork, typeWordFirst: typeWordFirst
                ) {
                    return .song(song)
                }
                return nil
            }
        }
        return responsiveSong(renderer, title: title, columns: columns, artwork: artwork, typeWordFirst: typeWordFirst)
            .map(MusicItem.song)
    }

    /// The text of the second and later columns, joined: "Playlist • YouTube Music • 51 songs".
    static func detailText(_ columns: [JSON], dropTypeWord: Bool, anyTypeWord: Bool = false) -> String? {
        var runs = joinedRuns(columns)
        if dropTypeWord, startsWithTypeWord(runs, anyWord: anyTypeWord) {
            runs.removeFirst(2)
        }
        let text = runs.compactMap { $0["text"].string }.joined()
        return text.isEmpty ? nil : text
    }

    /// The runs of several columns as one line, with a " • " between columns.
    static func joinedRuns(_ columns: [JSON]) -> [JSON] {
        var runs: [JSON] = []
        for column in columns {
            let columnRuns = column.runs
            guard !columnRuns.isEmpty else { continue }
            if !runs.isEmpty { runs.append(["text": .string(dotSeparator)]) }
            runs.append(contentsOf: columnRuns)
        }
        return runs
    }

    static func responsiveSong(
        _ renderer: JSON, title: String, columns: [JSON], artwork: Artwork, typeWordFirst: Bool = false
    ) -> Song? {
        let play = playNavigation(of: renderer)
        let titleNavigation = columns.first?["runs", 0, "navigationEndpoint"] ?? .null
        let menu = menuInfo(renderer)
        let videoId = play["watchEndpoint", "videoId"].text
            ?? titleNavigation["watchEndpoint", "videoId"].text
            ?? renderer["playlistItemData", "videoId"].text
            ?? menu.removedVideoId
        guard let videoId else { return nil }
        let videoType = musicVideoType(in: play) ?? musicVideoType(in: titleNavigation)

        // Each detail column is read on its own (artists, album, plays), the first one may start with a type word.
        var info = SongInfo()
        for (offset, column) in columns.dropFirst().enumerated() {
            let part = songInfo(column.runs, skipTypeSpec: offset == 0, anyTypeWord: typeWordFirst)
            info.artists.append(contentsOf: part.artists)
            info.album = info.album ?? part.album
            info.duration = info.duration ?? part.duration
            info.year = info.year ?? part.year
            info.countText = info.countText ?? part.countText
            info.typeText = info.typeText ?? part.typeText
        }
        let fixedDuration = renderer["fixedColumns"].array.lazy
            .compactMap { duration($0["musicResponsiveListItemFixedColumnRenderer", "text"].runsText) }
            .first
        var kind = songKind(videoType: videoType, artwork: artwork)
        if videoType == nil, let typeText = info.typeText?.lowercased() {
            if typeText == "video" { kind = .video } else if typeText == "episode" { kind = .episode }
        }
        if kind == .episode {
            // "Episode • Apr 12, 2024 • Show": the date is not an artist; the show is.
            info.artists = episodeShow(columns.dropFirst().flatMap(\.runs)).map { [$0] } ?? []
        }
        let policy = renderer["musicItemRendererDisplayPolicy"].string
        return Song(
            videoId: videoId,
            title: title,
            artists: info.artists,
            album: info.album,
            duration: fixedDuration ?? info.duration,
            artwork: artwork,
            isExplicit: isExplicit(renderer),
            kind: kind,
            playsText: info.countText,
            likeStatus: menu.likeStatus,
            setVideoId: renderer["playlistItemData", "playlistSetVideoId"].text
                ?? play["watchEndpoint", "playlistSetVideoId"].text ?? menu.setVideoId,
            libraryTokens: menu.libraryTokens,
            isAvailable: policy != "MUSIC_ITEM_RENDERER_DISPLAY_POLICY_GREY_OUT"
        )
    }

    // MARK: Cards

    /// A card with a big cover and two lines of text: Home and artist-page carousels.
    static func twoRowItem(_ renderer: JSON) -> MusicItem? {
        guard let title = renderer["title"].runsText else { return nil }
        let artwork = artwork(of: renderer)
        let subtitleRuns = renderer["subtitle"].runs
        let subtitle = renderer["subtitle"].runsText
        let navigation = renderer["navigationEndpoint"]
        let browse = browseEndpoint(in: renderer["title", "runs", 0, "navigationEndpoint"])
            ?? browseEndpoint(in: navigation)

        if let browse {
            switch browse.pageType {
            case .album:
                let info = songInfo(subtitleRuns, skipTypeSpec: true)
                var typeText = info.typeText
                // "Album" alone (no " • ") is read as an unlinked artist: it is the type word.
                var artists = info.artists
                if typeText == nil, subtitleRuns.count == 1 || (subtitleRuns.count == 3 && info.year != nil),
                    let only = artists.first, only.id == nil, artists.count == 1
                {
                    typeText = only.name
                    artists = []
                }
                return .album(AlbumItem(
                    id: browse.browseId,
                    playlistId: watchEndpoint(in: playNavigation(of: renderer))?.playlistId,
                    title: title,
                    artists: artists,
                    year: info.year,
                    typeText: typeText,
                    artwork: artwork,
                    isExplicit: isExplicit(renderer)
                ))
            case .artist, .userChannel:
                return .artist(ArtistItem(id: browse.browseId, name: title, artwork: artwork, detailText: subtitle))
            case .playlist, .podcast:
                return .playlist(PlaylistItem(
                    id: playlistId(fromBrowseId: browse.browseId), title: title, detailText: subtitle, artwork: artwork
                ))
            case .generic:
                break
            }
        }

        if let videoId = navigation["watchEndpoint", "videoId"].text {
            let info = songInfo(subtitleRuns, skipTypeSpec: true)
            let menu = menuInfo(renderer)
            var kind = songKind(videoType: musicVideoType(in: navigation), artwork: artwork)
            if musicVideoType(in: navigation) == nil, info.typeText?.lowercased() == "video" { kind = .video }
            return .song(Song(
                videoId: videoId,
                title: title,
                artists: info.artists,
                album: info.album,
                duration: info.duration,
                artwork: artwork,
                isExplicit: isExplicit(renderer),
                kind: kind,
                playsText: info.countText,
                likeStatus: menu.likeStatus,
                libraryTokens: menu.libraryTokens
            ))
        }
        if let playlistId = navigation["watchPlaylistEndpoint", "playlistId"].text
            ?? navigation["watchEndpoint", "playlistId"].text
        {
            // A mix that only plays ("My Supermix").
            return .playlist(PlaylistItem(id: playlistId, title: title, detailText: subtitle, artwork: artwork))
        }
        return nil
    }

    /// The show of an episode row: the run linking to the show page, or else the last run that is not a date.
    static func episodeShow(_ runs: [JSON]) -> ArtistRef? {
        if let linked = runs.first(where: { $0["navigationEndpoint", "browseEndpoint", "browseId"].string?.hasPrefix("MPSP") == true }),
            let name = linked["text"].text
        {
            return ArtistRef(name: name, id: nil)
        }
        let names = runs.dropFirst(2).compactMap { run -> String? in
            guard !isSeparator(run), let text = run["text"].text, !isDateText(text), !isCountText(text),
                !isDurationText(text)
            else { return nil }
            return text
        }
        return names.last.map { ArtistRef(name: $0, id: nil) }
    }

    /// "Apr 12, 2024", "Jan 23", "1d ago", "3 weeks ago": a release date of an episode.
    static func isDateText(_ text: String) -> Bool {
        let lower = text.lowercased()
        if lower.hasSuffix(" ago") || lower == "today" || lower == "yesterday" { return true }
        let months = ["jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"]
        let words = lower.replacingOccurrences(of: ",", with: "").split(separator: " ")
        guard words.count >= 2, words.count <= 3, months.contains(where: { words[0].hasPrefix($0) }) else { return false }
        return words.dropFirst().allSatisfy { $0.allSatisfy(\.isASCIIDigit) }
    }

    /// A podcast episode row (title, show, date, duration).
    static func multiRowItem(_ renderer: JSON) -> Song? {
        guard let title = renderer["title"].runsText else { return nil }
        let onTap = renderer["onTap"]
        let play = playNavigation(of: renderer)
        guard let videoId = onTap["watchEndpoint", "videoId"].text ?? play["watchEndpoint", "videoId"].text
        else { return nil }
        let showRun = renderer["secondTitle", "runs", 0]
        let artists = showRun["text"].text.map { [ArtistRef(name: $0, id: nil)] } ?? []
        let progress = renderer["playbackProgress", "musicPlaybackProgressRenderer"]
        let durationText = progress["durationText"].runs.compactMap { $0["text"].string }
            .first { !$0.contains("•") }
        return Song(
            videoId: videoId,
            title: title,
            artists: artists,
            duration: duration(durationText),
            artwork: artwork(of: renderer),
            isExplicit: isExplicit(renderer),
            kind: .episode,
            playsText: renderer["subtitle"].runs.first?["text"].text.flatMap { isCountText($0) ? $0 : nil }
        )
    }

    // MARK: Queue rows

    /// A row of the "Up next" queue.
    static func panelSong(_ renderer: JSON) -> Song? {
        guard let videoId = renderer["videoId"].text ?? renderer["navigationEndpoint", "watchEndpoint", "videoId"].text,
            let title = renderer["title"].runsText
        else { return nil }
        let info = songInfo(renderer["longBylineText"].runs, skipTypeSpec: false)
        let artwork = artwork(list: renderer["thumbnail"])
        let videoType = musicVideoType(in: renderer["navigationEndpoint"])
        let menu = menuInfo(renderer)
        return Song(
            videoId: videoId,
            title: title,
            artists: info.artists.isEmpty
                ? (renderer["shortBylineText"].runsText.map { [ArtistRef(name: $0, id: nil)] } ?? []) : info.artists,
            album: info.album,
            duration: duration(renderer["lengthText"].runsText) ?? info.duration,
            artwork: artwork,
            isExplicit: isExplicit(renderer),
            kind: songKind(videoType: videoType, artwork: artwork),
            // The queue's byline counts likes for videos ("49K likes"), which is not a play count.
            playsText: info.countText.flatMap { $0.lowercased().contains("like") ? nil : $0 },
            likeStatus: menu.likeStatus,
            setVideoId: renderer["playlistSetVideoId"].text
                ?? renderer["navigationEndpoint", "watchEndpoint", "playlistSetVideoId"].text,
            libraryTokens: menu.libraryTokens,
            isAvailable: !renderer["unplayableText"].exists
        )
    }
}
