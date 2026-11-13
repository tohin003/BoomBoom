import BoomModels
import Foundation
import Observation

// The album, artist and playlist pages' state. Each page loads once, again on Retry or pull to refresh (which keeps
// the page on screen until the new one arrives, and keeps it if the refresh fails), and cancels what is out when it
// is popped. Save and Subscribe are `OptimisticToggle`s: shown at once, sent one request at a time.

@MainActor
@Observable
final class AlbumModel {
    let id: String
    private(set) var state: LoadState<AlbumPage> = .loading
    /// In the listener's library.
    private(set) var library = OptimisticToggle(false)

    @ObservationIgnored private let request = RequestSlot()

    init(id: String) {
        self.id = id
    }

    func loadIfNeeded(api: any MusicAPI) {
        guard !state.isLoaded, !request.isBusy else { return }
        load(api: api)
    }

    func retry(api: any MusicAPI) {
        load(api: api)
    }

    func refresh(api: any MusicAPI) async {
        await load(api: api).value
    }

    @discardableResult
    private func load(api: any MusicAPI) -> Task<Void, Never> {
        if !state.isLoaded { state = .loading }
        let id = id
        return request.start({ try await api.album(id: id) }) { [weak self] result in
            guard let self else { return }
            switch result {
            case .success(let page):
                state = .loaded(page)
                library.reload(page.libraryTokens?.isInLibrary ?? false)
            case .failure(let error):
                if !state.isLoaded { state = .failed(userMessage(for: error)) }
            }
        }
    }

    /// Save to library, or remove from it. Albums the service gives no token for that change cannot be toggled.
    func toggleLibrary(api: any MusicAPI) {
        guard token(adding: !library.isOn) != nil, let adding = library.flip() else { return }
        sendLibrary(adding, api: api)
    }

    private func token(adding: Bool) -> String? {
        let tokens = state.value?.libraryTokens
        return adding ? tokens?.add : tokens?.remove
    }

    /// Sent even if the page is closed meanwhile: the listener asked for it.
    private func sendLibrary(_ adding: Bool, api: any MusicAPI) {
        guard let token = token(adding: adding) else {
            _ = library.answered(succeeded: false)
            return
        }
        Task {
            let succeeded = (try? await api.sendLibraryFeedback(token: token)) != nil
            if let next = library.answered(succeeded: succeeded) { sendLibrary(next, api: api) }
        }
    }
}

@MainActor
@Observable
final class ArtistModel {
    let id: String
    private(set) var state: LoadState<ArtistPage> = .loading
    private(set) var subscription = OptimisticToggle(false)

    @ObservationIgnored private let request = RequestSlot()

    init(id: String) {
        self.id = id
    }

    func loadIfNeeded(api: any MusicAPI) {
        guard !state.isLoaded, !request.isBusy else { return }
        load(api: api)
    }

    func retry(api: any MusicAPI) {
        load(api: api)
    }

    func refresh(api: any MusicAPI) async {
        await load(api: api).value
    }

    @discardableResult
    private func load(api: any MusicAPI) -> Task<Void, Never> {
        if !state.isLoaded { state = .loading }
        let id = id
        return request.start({ try await api.artist(id: id) }) { [weak self] result in
            guard let self else { return }
            switch result {
            case .success(let page):
                state = .loaded(page)
                subscription.reload(page.isSubscribed)
            case .failure(let error):
                if !state.isLoaded { state = .failed(userMessage(for: error)) }
            }
        }
    }

    func toggleSubscription(api: any MusicAPI) {
        guard state.value != nil, let subscribing = subscription.flip() else { return }
        sendSubscription(subscribing, api: api)
    }

    /// Sent even if the page is closed meanwhile: the listener asked for it.
    private func sendSubscription(_ subscribing: Bool, api: any MusicAPI) {
        guard let channel = state.value?.artist.id else {
            _ = subscription.answered(succeeded: false)
            return
        }
        Task {
            let succeeded = (try? await api.subscribe(channelId: channel, subscribing)) != nil
            if let next = subscription.answered(succeeded: succeeded) { sendSubscription(next, api: api) }
        }
    }
}

@MainActor
@Observable
final class PlaylistModel {
    let id: String
    /// The page, its songs so far (`content.tracks`) and the next part of them.
    private(set) var state: LoadState<Paged<PlaylistPage>> = .loading
    /// Saved to the library (liked).
    private(set) var saved = OptimisticToggle(false)

    @ObservationIgnored private let request = RequestSlot()
    /// The next songs; dropped whenever the page is replaced.
    @ObservationIgnored private let more = RequestSlot()

    init(id: String) {
        self.id = id
    }

    func loadIfNeeded(api: any MusicAPI) {
        guard !state.isLoaded, !request.isBusy else { return }
        load(api: api)
    }

    func retry(api: any MusicAPI) {
        load(api: api)
    }

    /// Pull to refresh. Songs keep loading as the listener scrolls until the new page arrives.
    func refresh(api: any MusicAPI) async {
        await load(api: api).value
    }

    @discardableResult
    private func load(api: any MusicAPI) -> Task<Void, Never> {
        if !state.isLoaded { show(.loading) }
        let id = id
        return request.start({ try await api.playlist(id: id) }) { [weak self] result in
            guard let self else { return }
            switch result {
            case .success(let page):
                show(.loaded(Paged(content: page, more: MoreState(token: page.continuation))))
                saved.reload(page.likeStatus == .like)
            case .failure(let error):
                if !state.isLoaded { show(.failed(userMessage(for: error))) }
            }
        }
    }

    /// The only place the page is replaced. A load-more for the old songs goes with them.
    private func show(_ new: LoadState<Paged<PlaylistPage>>) {
        more.cancel()
        state = new
    }

    func loadMore(api: any MusicAPI) {
        guard case .loaded(var list) = state, let token = list.more.askable else { return }
        list.more = .loading(token)
        state = .loaded(list)
        more.start({ try await api.playlistContinuation(token) }) { [weak self] result in
            guard let self, case .loaded(var list) = state, list.more == .loading(token) else { return }
            switch result {
            case .success(let next):
                list.content.tracks += next.songs
                list.content.continuation = next.continuation
                list.more = MoreState(token: next.continuation)
            case .failure:
                list.more = .failed(token)
            }
            state = .loaded(list)
        }
    }

    func toggleSaved(api: any MusicAPI) {
        guard state.isLoaded, let saving = saved.flip() else { return }
        sendSaved(saving, api: api)
    }

    /// Sent even if the page is closed meanwhile: the listener asked for it.
    private func sendSaved(_ saving: Bool, api: any MusicAPI) {
        guard let id = state.value?.content.playlist.id else {
            _ = saved.answered(succeeded: false)
            return
        }
        Task {
            let succeeded = (try? await api.ratePlaylist(id: id, saving ? .like : .indifferent)) != nil
            if let next = saved.answered(succeeded: succeeded) { sendSaved(next, api: api) }
        }
    }
}
