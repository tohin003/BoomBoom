import BoomModels
import Foundation
import Synchronization

/// Checks of the search screen's timing logic and of the browse pages' request states, run against a
/// `PreviewMusicAPI` whose answers arrive out of order or fail on demand.
/// The package has no test target, so a demo app runs these (`-selfcheck 1`) and prints the outcome.
public enum BrowseSelfCheck {
    /// One line per check, starting with "PASS" or "FAIL". Takes a few seconds.
    @MainActor
    public static func run() async -> [String] {
        var report: [String] = []
        func check(_ name: String, _ condition: Bool, _ detail: @autoclosure () -> String = "") {
            report.append((condition ? "PASS " : "FAIL ") + name + (condition ? "" : " (\(detail()))"))
        }
        func sleep(_ milliseconds: Int) async {
            try? await Task.sleep(for: .milliseconds(milliseconds))
        }
        /// Waits until `condition` holds, up to `limit` milliseconds, so a busy device does not fail a check.
        func until(_ limit: Int = 3000, _ condition: () -> Bool) async {
            var waited = 0
            while !condition(), waited < limit {
                await sleep(20)
                waited += 20
            }
        }

        // Slow answers for short words, fast ones for longer words: older requests finish last.
        let api = PreviewMusicAPI(delays: false, signedIn: true, failsBrowsing: false) { call, input in
            switch call {
            case "suggestions": input.count <= 1 ? .milliseconds(600) : .milliseconds(40)
            case "search": input.hasPrefix("juniper") ? .milliseconds(500) : .milliseconds(40)
            default: .milliseconds(10)
            }
        }

        // 1. Typing quickly asks once, for the last words only.
        do {
            let loader = SuggestionLoader(api: api, debounce: .milliseconds(180))
            let before = api.log.count(prefix: "suggestions:")
            for text in ["l", "lo", "lov", "love"] {
                loader.update(text)
                await sleep(40)
            }
            await sleep(400)
            let asked = api.log.all.filter { $0.hasPrefix("suggestions:") }.dropFirst(before)
            check("debounce: one request for a burst of keystrokes", asked.count == 1, "asked \(Array(asked))")
            check("debounce: the request is for the last text", asked.last == "suggestions:love", "\(asked)")
            check("debounce: the answer is shown for the last text", loader.answeredText == "love",
                  "answered '\(loader.answeredText)'")
        }

        // 2. A slow answer for older words never replaces a newer answer.
        do {
            let loader = SuggestionLoader(api: api, debounce: .milliseconds(180))
            loader.update("l")
            await sleep(260)  // "l" is now in flight (600 ms).
            loader.update("lo")
            await sleep(330)  // "lo" answered (180 + 40 ms).
            check("order: newer answer shown", loader.answeredText == "lo", "answered '\(loader.answeredText)'")
            await sleep(500)  // The old "l" request would have finished by now.
            check("order: stale answer dropped", loader.answeredText == "lo", "answered '\(loader.answeredText)'")
            let allMatch = loader.suggestions.allSatisfy { suggestion in
                if case .query(let query) = suggestion { return query.text.contains("lo") }
                return true
            }
            check("order: shown suggestions match the text", allMatch && !loader.suggestions.isEmpty)
            let stale = loader.visible(for: "lov")
            let staleOK = stale.allSatisfy { suggestion in
                if case .query(let query) = suggestion { return query.text.lowercased().hasPrefix("lov") }
                return false
            }
            check("order: while waiting, only matching earlier lines show", staleOK, "\(stale.map(\.id))")
        }

        // 3. Clearing the field drops a request on its way.
        do {
            let loader = SuggestionLoader(api: api, debounce: .milliseconds(180))
            loader.update("lo")
            await sleep(200)
            loader.update("")
            await sleep(300)
            check("clear: no suggestions after clearing", loader.suggestions.isEmpty && !loader.isLoading)
        }

        // 4. A newer search wins over an older, slower one.
        do {
            let results = ResultsLoader(api: api)
            results.search("juniper", filter: nil)
            await sleep(50)
            results.search("neon", filter: nil)
            await until { if case .loaded = results.phase { true } else { false } }
            var query = ""
            if case .loaded(let page) = results.phase { query = page.query }
            check("results: newer search shown", query == "neon", "showing '\(query)' in \(results.phase)")
            await sleep(500)
            if case .loaded(let page) = results.phase { query = page.query }
            check("results: older answer dropped", query == "neon", "showing '\(query)'")
        }

        // 5. A filtered list loads more pages without repeating an item.
        do {
            let results = ResultsLoader(api: api)
            results.search("neon", filter: .songs)
            await sleep(150)
            let first = results.items.count
            results.loadMore()
            await sleep(150)
            results.loadMore()
            await sleep(150)
            let ids = results.items.map(\.id)
            check("continuation: more rows arrive", results.items.count > first, "\(first) → \(results.items.count)")
            check("continuation: no repeated rows", Set(ids).count == ids.count)
            results.search("neon", filter: .albums)
            results.loadMore()  // Ignored: the new search has not answered yet.
            await sleep(150)
            let albumsOnly = results.items.allSatisfy { if case .album = $0 { true } else { false } }
            check("continuation: switching filters starts a fresh list", albumsOnly && !results.items.isEmpty)
        }

        // 6. The screen's modes: submitting shows results; editing returns to suggestions.
        do {
            let defaults = UserDefaults(suiteName: "boom.selfcheck") ?? .standard
            defaults.removeObject(forKey: "search.recent")
            let model = SearchModel(api: api, defaults: defaults)
            model.text = "neon"
            model.textChanged()
            model.submit()
            model.textChanged()  // The field echoing the submitted words changes nothing.
            check("mode: submit shows results", model.isShowingResults)
            check("mode: submit records a recent search", model.recents.all.first == "neon")
            model.text = "neon h"
            model.textChanged()
            check("mode: editing returns to suggestions", model.mode == .suggestions)
            for index in 0..<25 { model.recents.add("query \(index)") }
            check("recents: kept to 20, newest first",
                  model.recents.all.count == 20 && model.recents.all.first == "query 24")
            defaults.removeObject(forKey: "search.recent")
        }

        // Answers that take a while and calls that fail on demand, for the checks below.
        let failing = FailingCalls()
        let slow = PreviewMusicAPI(delays: false, signedIn: true, failsBrowsing: false) { call, input in
            switch call {
            case "search": input.hasPrefix("juniper") ? .milliseconds(400) : .milliseconds(10)
            case "searchContinuation", "browseContinuation", "playlistContinuation": .milliseconds(250)
            case "charts": input == "US" ? .milliseconds(300) : .milliseconds(10)
            case "subscribe": .milliseconds(100)
            default: .milliseconds(10)
            }
        } failOverride: { call, _ in
            failing.contains(call)
        }

        // 7. Editing the words while their results load drops the search.
        do {
            let defaults = UserDefaults(suiteName: "boom.selfcheck") ?? .standard
            let model = SearchModel(api: slow, defaults: defaults)
            model.text = "juniper"
            model.submit()
            await sleep(50)
            model.text = "juniper v"
            model.textChanged()
            let dropped = model.mode == .suggestions && { if case .idle = model.results.phase { true } else { false } }()
            await sleep(500)
            let stillDropped = { if case .idle = model.results.phase { true } else { false } }()
            check("results: editing while results load drops them", dropped && stillDropped,
                  "mode \(model.mode), phase \(model.results.phase)")
            defaults.removeObject(forKey: "search.recent")
        }

        // 8. A filtered list whose next part fails offers it again, and a new filter drops a part on its way.
        do {
            let results = ResultsLoader(api: slow)
            results.search("neon", filter: .songs)
            await until { !results.items.isEmpty }
            let first = results.items.count
            failing.set("searchContinuation", true)
            results.loadMore()
            await until { if case .failed = results.more { true } else { false } }
            let failedKept = { if case .failed = results.more { true } else { false } }() && results.items.count == first
            failing.set("searchContinuation", false)
            results.loadMore()
            await until { results.items.count > first }
            check("continuation: a failed part can be asked again", failedKept && results.items.count > first,
                  "\(first) → \(results.items.count), \(results.more)")

            results.loadMore()  // On its way for 250 ms.
            results.search("neon", filter: .albums)
            await sleep(400)
            let albumsOnly = !results.items.isEmpty
                && results.items.allSatisfy { if case .album = $0 { true } else { false } }
            check("continuation: a part for the old filter is dropped", albumsOnly && !results.more.isLoading,
                  "\(results.items.count) items, \(results.more)")
        }

        // 9. Save and Subscribe: taps made while a request is out are sent after it, in order.
        do {
            var toggle = OptimisticToggle(false)
            let sent = toggle.flip()
            let second = toggle.flip()
            let shownOff = !toggle.isOn
            let next = toggle.answered(succeeded: true)
            check("toggle: a tap while a request is out is sent after it",
                  sent == true && second == nil && shownOff && next == false && !toggle.isOn,
                  "sent \(String(describing: sent)), then \(String(describing: next)), on \(toggle.isOn)")

            var refused = OptimisticToggle(true)
            _ = refused.flip()
            _ = refused.flip()
            _ = refused.flip()
            let shownBeforeAnswer = refused.isOn
            let after = refused.answered(succeeded: false)
            check("toggle: a refusal puts back what the service confirmed",
                  !shownBeforeAnswer && after == nil && refused.isOn && !refused.isSending)

            let artist = ArtistModel(id: "UCbbjunipervale")
            artist.loadIfNeeded(api: slow)
            await until { artist.state.isLoaded }
            let before = slow.log.count(prefix: "subscribe:")
            artist.toggleSubscription(api: slow)
            artist.toggleSubscription(api: slow)
            await until { !artist.subscription.isSending }
            let asked = slow.log.all.filter { $0.hasPrefix("subscribe:") }.dropFirst(before)
            check("subscribe: two quick taps are sent in order and end unsubscribed",
                  asked.count == 2 && asked.first?.hasSuffix("|true") == true
                      && asked.last?.hasSuffix("|false") == true && !artist.subscription.isOn,
                  "\(Array(asked)), on \(artist.subscription.isOn)")
        }

        // 10. Home: shelves asked for under the old chip never join the new chip's feed.
        do {
            let home = HomeModel()
            home.loadIfNeeded(api: slow)
            await until { home.state.isLoaded && !home.chips.isEmpty }
            home.loadMore(api: slow)  // On its way for 250 ms.
            if let chip = home.chips.first { home.select(chip, api: slow) }
            await sleep(400)
            let shelves = home.state.value?.content ?? []
            let clean = !shelves.isEmpty && !shelves.contains { $0.id.hasPrefix("fresh-") }
            check("home: a continuation for the old chip is dropped",
                  clean && home.state.value?.more.isLoading == false, "\(shelves.map(\.id))")
        }

        // 11. Playlist: a refresh that fails while more songs load leaves the list able to grow.
        do {
            let playlist = PlaylistModel(id: "PLbb_latenightdrive")
            playlist.loadIfNeeded(api: slow)
            await until { playlist.state.isLoaded }
            let first = playlist.state.value?.content.tracks.count ?? 0
            playlist.loadMore(api: slow)  // On its way for 250 ms.
            failing.set("playlist", true)
            await playlist.refresh(api: slow)
            failing.set("playlist", false)
            await until { playlist.state.value?.more.isLoading == false }
            let grown = playlist.state.value?.content.tracks.count ?? 0
            playlist.loadMore(api: slow)
            await until { (playlist.state.value?.content.tracks.count ?? 0) > grown }
            let again = playlist.state.value?.content.tracks.count ?? 0
            check("playlist: a failed refresh during a load-more keeps loading more",
                  playlist.state.isLoaded && grown > first && again > grown, "\(first) → \(grown) → \(again)")
        }

        // 12. Charts: a refresh that replaces a country switch and fails shows the error, not endless skeletons.
        do {
            let charts = BrowsePageModel(source: .charts(country: nil))
            charts.loadIfNeeded(api: slow)
            await until { charts.state.isLoaded }
            let us = charts.state.value?.chips.first { $0.endpoint?.params == "US" }
            if let us { charts.select(us, api: slow) }
            let switching = charts.state.value?.shelves == .switching
            failing.set("charts", true)
            await charts.refresh(api: slow)
            failing.set("charts", false)
            let failed = { if case .failed = charts.state { true } else { false } }()
            await sleep(400)
            let stillFailed = { if case .failed = charts.state { true } else { false } }()
            charts.retry(api: slow)
            await until { if case .shown = charts.state.value?.shelves { true } else { false } }
            let shown = { if case .shown = charts.state.value?.shelves { true } else { false } }()
            check("charts: a failed refresh during a country switch shows the error",
                  us != nil && switching && failed && stillFailed && shown, "\(charts.state)")
        }

        // 13. A page closed while its request is out goes away at once (and its request is cancelled with it).
        do {
            weak var closed: AlbumModel?
            do {
                let album = AlbumModel(id: "MPREb_afterhoursatlas")
                album.loadIfNeeded(api: slow)
                closed = album
            }
            check("pages: a closed page does not wait for its request", closed == nil)
        }

        return report
    }
}

/// Calls a self-check makes fail, switched on and off while it runs.
final class FailingCalls: Sendable {
    private let calls = Mutex<Set<String>>([])

    func set(_ call: String, _ fails: Bool) {
        calls.withLock { if fails { $0.insert(call) } else { $0.remove(call) } }
    }

    func contains(_ call: String) -> Bool {
        calls.withLock { $0.contains(call) }
    }
}
