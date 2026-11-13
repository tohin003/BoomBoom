import BoomModels
import Foundation
import Observation

/// A page of shelves: what to load, and the page once loaded.
@MainActor
@Observable
final class BrowsePageModel {
    enum Source: Hashable, Sendable {
        case endpoint(BrowseEndpoint)
        case charts(country: String?)
        case explore
    }

    /// The loaded page: its title and chips, then the shelves.
    struct Content: Equatable {
        var title: String?
        var chips: [Chip]
        var shelves: Shelves
    }

    enum Shelves: Equatable {
        /// A chip was tapped: its shelves are on their way, under the title and chips that stay.
        case switching
        case shown(Paged<[Shelf]>)
    }

    /// How the page looks while a load is out.
    private enum Reload {
        /// Skeletons for the whole page (the first load, Retry).
        case whole
        /// The content stays until the new one arrives, and stays if the load fails (pull to refresh).
        case keepingContent
        /// The title and chips stay; the shelves turn to skeletons (a chip tap).
        case shelvesOnly
    }

    private(set) var source: Source
    private(set) var state: LoadState<Content> = .loading

    /// The page itself: a chip tap, Retry or a refresh replaces the request out.
    @ObservationIgnored private let page = RequestSlot()
    /// The next part of the shelves on screen; dropped whenever they are replaced.
    @ObservationIgnored private let more = RequestSlot()

    init(source: Source) {
        self.source = source
    }

    /// The first load, and again after a failure when the page comes back. A request already out is left to finish.
    func loadIfNeeded(api: any MusicAPI) {
        guard !state.isLoaded, !page.isBusy else { return }
        load(api: api, .whole)
    }

    func refresh(api: any MusicAPI) async {
        await load(api: api, .keepingContent).value
    }

    func retry(api: any MusicAPI) {
        load(api: api, .whole)
    }

    /// A chip on the page (a country on Charts, a filter elsewhere).
    func select(_ chip: Chip, api: any MusicAPI) {
        switch source {
        case .charts:
            source = .charts(country: chip.endpoint?.params)
        case .endpoint, .explore:
            guard let endpoint = chip.endpoint else { return }
            source = .endpoint(endpoint)
        }
        load(api: api, .shelvesOnly)
    }

    private static func fetch(_ source: Source, api: any MusicAPI) async throws -> BrowsePage {
        switch source {
        case .endpoint(let endpoint): try await api.browse(endpoint)
        case .charts(let country): try await api.charts(country: country)
        case .explore: try await api.explore()
        }
    }

    @discardableResult
    private func load(api: any MusicAPI, _ reload: Reload) -> Task<Void, Never> {
        let source = source
        let previous = state.value
        switch reload {
        case .whole:
            show(.loading)
        case .keepingContent:
            if previous == nil { show(.loading) }
        case .shelvesOnly:
            if var content = previous {
                content.shelves = .switching
                show(.loaded(content))
            } else {
                show(.loading)
            }
        }
        return page.start({ try await Self.fetch(source, api: api) }) { [weak self] result in
            guard let self else { return }
            switch result {
            case .success(let page):
                var chips = page.chips
                if chips.isEmpty, case .shelvesOnly = reload { chips = previous?.chips ?? [] }
                show(.loaded(Content(
                    title: page.title ?? previous?.title, chips: chips,
                    shelves: .shown(Paged(content: page.shelves, more: MoreState(token: page.continuation))))))
            case .failure(let error):
                // A refresh that fails keeps shelves on screen, but not skeletons left by a chip tap it replaced.
                if case .keepingContent = reload, case .loaded(let content) = state, case .shown = content.shelves {
                    return
                }
                show(.failed(userMessage(for: error)))
            }
        }
    }

    /// The only place the shelves are replaced. A load-more for the old shelves goes with them.
    private func show(_ new: LoadState<Content>) {
        more.cancel()
        state = new
    }

    func loadMore(api: any MusicAPI) {
        guard case .loaded(var content) = state, case .shown(var list) = content.shelves,
              let token = list.more.askable else { return }
        list.more = .loading(token)
        content.shelves = .shown(list)
        state = .loaded(content)
        more.start({ try await api.browseContinuation(token) }) { [weak self] result in
            guard let self, case .loaded(var content) = state, case .shown(var list) = content.shelves,
                  list.more == .loading(token) else { return }
            switch result {
            case .success(let next):
                list.content = appending(next.shelves, to: list.content)
                list.more = MoreState(token: next.continuation)
            case .failure:
                list.more = .failed(token)
            }
            content.shelves = .shown(list)
            state = .loaded(content)
        }
    }
}
