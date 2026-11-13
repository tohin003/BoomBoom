import BoomModels
import Foundation

/// The items of one Library section, in the order YouTube Music lists them.
struct LibraryContent: Equatable {
    var items: [MusicItem]
    /// Loads more items of the section.
    var continuation: String?

    var songs: [Song] { items.compactMap(\.song) }
}

/// Where the request for one section stands.
enum LibraryLoad: Equatable {
    case loading
    case failed(String)
    case loaded(LibraryContent)

    var content: LibraryContent? {
        if case .loaded(let content) = self { return content }
        return nil
    }
}

/// A short message about something that did not work, shown as an alert.
struct LibraryNotice: Equatable {
    var title: String
    var message: String
}

extension PlaylistItem {
    /// The listener's own playlists have ids that start with "PL"; "LM" is liked music.
    var isOwn: Bool { id.hasPrefix("PL") }
}

/// The Library tab's data: every section keeps its own state, so switching sections shows what was loaded before
/// and an answer can never land in a section it was not asked for.
@MainActor
@Observable
final class LibraryModel {
    private(set) var states: [LibrarySection: LibraryLoad] = [:]
    private(set) var loadingMore: Set<LibrarySection> = []
    var notice: LibraryNotice?

    @ObservationIgnored private var generations: [LibrarySection: Int] = [:]
    @ObservationIgnored private var moreTasks: [LibrarySection: Task<Void, Never>] = [:]
    @ObservationIgnored private var knownRevision: Int?

    func state(of section: LibrarySection) -> LibraryLoad {
        states[section] ?? .loading
    }

    /// Forgets everything when the sign-in changed since the last call.
    func sync(revision: Int) {
        guard revision != knownRevision else { return }
        if knownRevision != nil { reset() }
        knownRevision = revision
    }

    func reset() {
        cancelLoadingMore()
        for section in LibrarySection.allCases { generations[section, default: 0] += 1 }
        states = [:]
    }

    func cancelLoadingMore() {
        for task in moreTasks.values { task.cancel() }
        moreTasks = [:]
        loadingMore = []
    }

    func loadIfNeeded(_ section: LibrarySection, api: any MusicAPI) async {
        guard state(of: section).content == nil else { return }
        await load(section, api: api, keepContent: false)
    }

    /// Pull to refresh and refresh after a change: the old items stay on screen until the new ones arrive.
    func refresh(_ section: LibrarySection, api: any MusicAPI) async {
        await load(section, api: api, keepContent: true)
    }

    private func load(_ section: LibrarySection, api: any MusicAPI, keepContent: Bool) async {
        generations[section, default: 0] += 1
        let request = generations[section, default: 0]
        moreTasks[section]?.cancel()
        moreTasks[section] = nil
        loadingMore.remove(section)
        let hasContent = state(of: section).content != nil
        if !keepContent || !hasContent { states[section] = .loading }
        do {
            let page = try await api.library(section)
            guard request == generations[section], !Task.isCancelled else { return }
            states[section] = .loaded(Self.content(of: page, section: section))
        } catch {
            guard request == generations[section], !Task.isCancelled else { return }
            if !keepContent || !hasContent { states[section] = .failed(userMessage(for: error)) }
        }
    }

    func loadMore(_ section: LibrarySection, api: any MusicAPI) {
        guard !loadingMore.contains(section), let token = state(of: section).content?.continuation else { return }
        loadingMore.insert(section)
        let request = generations[section, default: 0]
        moreTasks[section] = Task {
            defer {
                if request == generations[section] {
                    loadingMore.remove(section)
                    moreTasks[section] = nil
                }
            }
            guard let page = try? await api.browseContinuation(token), request == generations[section],
                  !Task.isCancelled, var content = state(of: section).content else { return }
            let more = Self.content(of: page, section: section)
            let known = Set(content.items.map(\.id))
            content.items += more.items.filter { !known.contains($0.id) }
            content.continuation = more.continuation
            states[section] = .loaded(content)
        }
    }

    /// Takes the playlist out of the list at once, asks YouTube Music to delete it, and puts it back where it was
    /// if that fails.
    func deletePlaylist(_ playlist: PlaylistItem, api: any MusicAPI) async {
        let item = MusicItem.playlist(playlist)
        guard var content = state(of: .playlists).content, let index = content.items.firstIndex(of: item) else {
            return
        }
        content.items.remove(at: index)
        states[.playlists] = .loaded(content)
        do {
            try await api.deletePlaylist(id: playlist.id)
        } catch {
            if var current = state(of: .playlists).content, !current.items.contains(item) {
                current.items.insert(item, at: min(index, current.items.count))
                states[.playlists] = .loaded(current)
            }
            notice = LibraryNotice(
                title: "Couldn't delete “\(playlist.title)”",
                message: userMessage(for: error) + " The playlist is still in your library.")
        }
    }

    /// One flat list of the page's items. Playlists leave out "Liked music", which has its own row; repeats are
    /// dropped so that every row has an identity of its own.
    private static func content(of page: BrowsePage, section: LibrarySection) -> LibraryContent {
        var seen = Set<String>()
        let items = page.shelves.flatMap(\.items).filter { item in
            if case .playlist(let playlist) = item, playlist.id == "LM" { return false }
            return seen.insert(item.id).inserted
        }
        return LibraryContent(items: items, continuation: page.continuation)
    }
}
