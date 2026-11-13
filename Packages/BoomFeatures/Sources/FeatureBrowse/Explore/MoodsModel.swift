import BoomModels
import Foundation
import Observation

/// The mood and genre groups.
@MainActor
@Observable
final class MoodsModel {
    private(set) var state: LoadState<[ChipGroup]> = .loading

    /// Retry or a refresh replaces the request out; leaving the page cancels it.
    @ObservationIgnored private let request = RequestSlot()

    /// The first load, and again after a failure when the page comes back. A request already out is left to finish.
    func loadIfNeeded(api: any MusicAPI) {
        guard !state.isLoaded, !request.isBusy else { return }
        load(api: api)
    }

    func retry(api: any MusicAPI) {
        load(api: api)
    }

    /// Pull to refresh: the tiles stay until the new ones arrive, and stay if it fails.
    func refresh(api: any MusicAPI) async {
        await load(api: api).value
    }

    @discardableResult
    private func load(api: any MusicAPI) -> Task<Void, Never> {
        if !state.isLoaded { state = .loading }
        return request.start({ try await api.moodsAndGenres() }) { [weak self] result in
            guard let self else { return }
            switch result {
            case .success(let groups):
                state = .loaded(groups)
            case .failure(let error):
                if !state.isLoaded { state = .failed(userMessage(for: error)) }
            }
        }
    }
}
