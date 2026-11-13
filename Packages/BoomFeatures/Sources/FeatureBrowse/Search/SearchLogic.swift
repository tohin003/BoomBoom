import BoomModels
import Foundation
import Observation

/// Suggestions while typing: each keystroke cancels the previous request, waits a moment, then asks. An answer is
/// shown only if the field still holds the words it was asked for, so slow or out-of-order answers never appear.
@MainActor
@Observable
final class SuggestionLoader {
    /// An answer and the words it answers.
    struct Answer: Equatable {
        var text = ""
        var suggestions: [SearchSuggestion] = []
    }

    /// The newest answer. It stays while the next is on its way, so `visible(for:)` can keep matching lines up.
    private(set) var answer = Answer()
    /// The words a request is out for (waiting out the pause, or asked); nil when none is.
    private(set) var pending: String?

    @ObservationIgnored private var task: Task<Void, Never>?
    @ObservationIgnored private let api: any MusicAPI
    @ObservationIgnored private let debounce: Duration

    init(api: any MusicAPI, debounce: Duration = .milliseconds(180)) {
        self.api = api
        self.debounce = debounce
    }

    var suggestions: [SearchSuggestion] { answer.suggestions }
    var answeredText: String { answer.text }
    /// True between a keystroke and its answer.
    var isLoading: Bool { pending != nil }

    func update(_ newText: String) {
        task?.cancel()
        let words = newText.trimmingCharacters(in: .whitespaces)
        guard !words.isEmpty else {
            task = nil
            answer = Answer()
            pending = nil
            return
        }
        pending = newText
        task = Task { [api, debounce] in
            do {
                try await Task.sleep(for: debounce)
            } catch {
                return
            }
            let result: [SearchSuggestion]?
            do {
                result = try await api.searchSuggestions(words)
            } catch {
                result = nil
            }
            guard !Task.isCancelled, pending == newText else { return }
            if let result { answer = Answer(text: newText, suggestions: result) }
            pending = nil
            task = nil
        }
    }

    func cancel() {
        task?.cancel()
        task = nil
        pending = nil
    }

    /// What to show for `current` right now: the answer for these exact words, or, while the next answer is on its
    /// way, the earlier word suggestions that still begin with what is typed (never items or unrelated lines).
    func visible(for current: String) -> [SearchSuggestion] {
        if answer.text == current { return answer.suggestions }
        let typed = current.trimmingCharacters(in: .whitespaces).lowercased()
        guard !typed.isEmpty else { return [] }
        return answer.suggestions.compactMap { suggestion in
            guard case .query(let query) = suggestion, query.text.lowercased().hasPrefix(typed),
                  query.text.count > typed.count else { return nil }
            let rest = String(query.text.dropFirst(typed.count))
            return .query(SuggestedQuery(
                text: query.text,
                runs: [.init(text: String(query.text.prefix(typed.count)), isBold: false),
                       .init(text: rest, isBold: true)],
                isFromHistory: query.isFromHistory))
        }
    }
}

/// Search results: the unfiltered shelves, or one filtered list that loads more as it scrolls. A newer search
/// replaces an older one, and answers to the older one are dropped.
@MainActor
@Observable
final class ResultsLoader {
    /// What was searched for. The filter shows as selected while its results load.
    struct Request: Equatable {
        var query = ""
        /// Nil: every kind, as shelves.
        var filter: SearchFilter?
    }

    enum Phase {
        case idle
        case loading
        case failed(String)
        /// Without a filter: the shelves.
        case loaded(SearchResults)
        /// With a filter: one list (the items of the answer's shelves) that loads more as it scrolls.
        case list(SearchResults, Paged<[MusicItem]>)
    }

    private(set) var request = Request()
    private(set) var phase: Phase = .idle

    @ObservationIgnored private let searchRequest = RequestSlot()
    /// The next part of the list on screen; dropped whenever the list is replaced.
    @ObservationIgnored private let moreRequest = RequestSlot()
    @ObservationIgnored private let api: any MusicAPI

    init(api: any MusicAPI) {
        self.api = api
    }

    var query: String { request.query }
    var filter: SearchFilter? { request.filter }

    /// The filtered list so far; empty without a filter.
    var items: [MusicItem] {
        if case .list(_, let list) = phase { return list.content }
        return []
    }

    /// The filtered list's next part; `.end` without a filter.
    var more: MoreState {
        if case .list(_, let list) = phase { return list.more }
        return .end
    }

    func search(_ newQuery: String, filter newFilter: SearchFilter?) {
        let api = api
        request = Request(query: newQuery, filter: newFilter)
        show(.loading)
        searchRequest.start({ try await api.search(newQuery, filter: newFilter) }) { [weak self] result in
            guard let self else { return }
            switch result {
            case .success(let page) where newFilter != nil:
                let items = Self.unique(page.shelves.flatMap(\.items), after: [])
                show(.list(page, Paged(content: items, more: MoreState(token: page.continuation))))
            case .success(let page):
                show(.loaded(page))
            case .failure(let error):
                show(.failed(userMessage(for: error)))
            }
        }
    }

    func retry() {
        search(request.query, filter: request.filter)
    }

    /// The words were edited away: drops the search and anything it is still loading.
    func cancel() {
        searchRequest.cancel()
        show(.idle)
    }

    /// The only place the results are replaced. A load-more for the old list goes with them.
    private func show(_ new: Phase) {
        moreRequest.cancel()
        phase = new
    }

    func loadMore() {
        guard case .list(let page, var list) = phase, let token = list.more.askable else { return }
        let api = api
        list.more = .loading(token)
        phase = .list(page, list)
        moreRequest.start({ try await api.searchContinuation(token) }) { [weak self] result in
            guard let self, case .list(let page, var list) = phase, list.more == .loading(token) else { return }
            switch result {
            case .success(let next):
                list.content += Self.unique(next.items, after: list.content)
                list.more = MoreState(token: next.continuation)
            case .failure:
                list.more = .failed(token)
            }
            phase = .list(page, list)
        }
    }

    /// `new` without the items already listed (a list keyed by id cannot hold one twice).
    private static func unique(_ new: [MusicItem], after old: [MusicItem]) -> [MusicItem] {
        var seen = Set(old.map(\.id))
        return new.filter { seen.insert($0.id).inserted }
    }
}

/// The listener's earlier searches, newest first, kept on the phone.
@MainActor
@Observable
final class RecentSearches {
    private(set) var all: [String]

    @ObservationIgnored private let defaults: UserDefaults
    private static let key = "search.recent"
    static let limit = 20

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        all = defaults.stringArray(forKey: Self.key) ?? []
    }

    func add(_ query: String) {
        let words = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !words.isEmpty else { return }
        all.removeAll { $0.caseInsensitiveCompare(words) == .orderedSame }
        all.insert(words, at: 0)
        if all.count > Self.limit { all.removeLast(all.count - Self.limit) }
        save()
    }

    func remove(_ query: String) {
        all.removeAll { $0 == query }
        save()
    }

    func clear() {
        all = []
        save()
    }

    private func save() {
        defaults.set(all, forKey: Self.key)
    }
}

/// The search screen's state: what is typed, whether suggestions or results show, and the loaders behind them.
@MainActor
@Observable
final class SearchModel {
    enum Mode: Equatable {
        /// Typing: suggestions, or recent searches while the field is empty.
        case suggestions
        /// Results for these submitted words. Editing the field away from them goes back to suggestions.
        case results(String)
    }

    var text = ""
    private(set) var mode: Mode = .suggestions
    let suggestions: SuggestionLoader
    let results: ResultsLoader
    let recents: RecentSearches

    init(api: any MusicAPI, defaults: UserDefaults = .standard, debounce: Duration = .milliseconds(180)) {
        suggestions = SuggestionLoader(api: api, debounce: debounce)
        results = ResultsLoader(api: api)
        recents = RecentSearches(defaults: defaults)
    }

    var isShowingResults: Bool {
        if case .results = mode { return true }
        return false
    }

    /// Called on every change of the field.
    func textChanged() {
        if case .results(let submitted) = mode {
            // The field echoing the submitted words changes nothing.
            if text == submitted { return }
            mode = .suggestions
            results.cancel()
        }
        suggestions.update(text)
    }

    /// The return key, or a tap on a suggested query or a recent search.
    func submit(_ query: String? = nil) {
        let words = (query ?? text).trimmingCharacters(in: .whitespacesAndNewlines)
        guard !words.isEmpty else { return }
        mode = .results(words)
        text = words
        suggestions.cancel()
        recents.add(words)
        results.search(words, filter: nil)
    }

    /// A filter pill: applies it, or clears it when it is the selected one.
    func select(_ filter: SearchFilter) {
        guard isShowingResults else { return }
        results.search(results.query, filter: results.filter == filter ? nil : filter)
    }

    /// The arrow beside a suggestion: puts its words in the field to keep typing, without searching.
    func fill(_ query: String) {
        text = query + " "
    }
}
