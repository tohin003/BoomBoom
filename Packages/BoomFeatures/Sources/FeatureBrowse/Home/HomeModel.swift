import BoomModels
import Foundation
import Observation

/// Home's content: the feed for the selected chip, and more shelves as the listener scrolls.
@MainActor
@Observable
final class HomeModel {
    private(set) var chips: [Chip] = []
    /// Nil: the default feed.
    private(set) var selectedChip: Chip?
    /// The shelves on screen and the next part of the feed.
    private(set) var state: LoadState<Paged<[Shelf]>> = .loading

    /// The feed itself: a chip tap, Retry or a refresh replaces the request out.
    @ObservationIgnored private let feed = RequestSlot()
    /// The next part of the shelves on screen; dropped whenever they are replaced.
    @ObservationIgnored private let more = RequestSlot()
    /// The session's revision the feed was loaded for.
    @ObservationIgnored private var knownRevision: Int?

    /// Called each time Home appears and whenever the active account changes: the feed is that account's, so a new
    /// account (or none) gets its own from the start, chips included.
    func sync(revision: Int, api: any MusicAPI) {
        defer { knownRevision = revision }
        guard let knownRevision, knownRevision != revision else {
            loadIfNeeded(api: api)
            return
        }
        selectedChip = nil
        load(api: api, keepContent: false)
    }

    /// The first load, and again after a failure when Home comes back. A request already out is left to finish.
    func loadIfNeeded(api: any MusicAPI) {
        guard !state.isLoaded, !feed.isBusy else { return }
        load(api: api, keepContent: false)
    }

    /// Pull to refresh: the shelves stay (and keep loading more) until the new ones arrive, and stay if it fails.
    func refresh(api: any MusicAPI) async {
        await load(api: api, keepContent: true).value
    }

    /// A chip tap: loads its feed; the selected chip again goes back to the default feed.
    func select(_ chip: Chip, api: any MusicAPI) {
        selectedChip = chip.title == selectedChip?.title ? nil : chip
        load(api: api, keepContent: false)
    }

    func retry(api: any MusicAPI) {
        load(api: api, keepContent: false)
    }

    @discardableResult
    private func load(api: any MusicAPI, keepContent: Bool) -> Task<Void, Never> {
        let chip = selectedChip
        if !keepContent || !state.isLoaded { show(.loading) }
        return feed.start({ try await api.home(chip: chip) }) { [weak self] result in
            guard let self else { return }
            switch result {
            case .success(let page):
                if !page.chips.isEmpty { chips = page.chips }
                show(.loaded(Paged(content: page.shelves, more: MoreState(token: page.continuation))))
            case .failure(let error):
                if !state.isLoaded { show(.failed(userMessage(for: error))) }
            }
        }
    }

    /// The only place the shelves are replaced. A load-more for the old shelves goes with them.
    private func show(_ new: LoadState<Paged<[Shelf]>>) {
        more.cancel()
        state = new
    }

    func loadMore(api: any MusicAPI) {
        guard case .loaded(var feed) = state, let token = feed.more.askable else { return }
        feed.more = .loading(token)
        state = .loaded(feed)
        more.start({ try await api.browseContinuation(token) }) { [weak self] result in
            guard let self, case .loaded(var feed) = state, feed.more == .loading(token) else { return }
            switch result {
            case .success(let next):
                feed.content = appending(next.shelves, to: feed.content)
                feed.more = MoreState(token: next.continuation)
            case .failure:
                feed.more = .failed(token)
            }
            state = .loaded(feed)
        }
    }
}
