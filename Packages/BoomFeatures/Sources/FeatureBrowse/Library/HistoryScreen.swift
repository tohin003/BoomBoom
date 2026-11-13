import BoomDesign
import BoomModels
import BoomPlayer
import SwiftUI

/// What the listener played, by day.
@MainActor
@Observable
final class HistoryModel {
    private(set) var state: LoadState<[Shelf]> = .loading
    private(set) var continuation: String?
    private(set) var isLoadingMore = false

    @ObservationIgnored private var generation = 0
    @ObservationIgnored private var moreTask: Task<Void, Never>?
    @ObservationIgnored private var knownRevision: Int?

    /// Forgets the history when the sign-in changed since the last call.
    func sync(revision: Int) {
        guard revision != knownRevision else { return }
        if knownRevision != nil {
            generation += 1
            cancelLoadingMore()
            state = .loading
            continuation = nil
        }
        knownRevision = revision
    }

    func cancelLoadingMore() {
        moreTask?.cancel()
        moreTask = nil
        isLoadingMore = false
    }

    func loadIfNeeded(api: any MusicAPI) async {
        guard !state.isLoaded else { return }
        await load(api: api, keepContent: false)
    }

    func refresh(api: any MusicAPI) async {
        await load(api: api, keepContent: true)
    }

    private func load(api: any MusicAPI, keepContent: Bool) async {
        generation += 1
        let request = generation
        cancelLoadingMore()
        let hasContent = state.isLoaded
        if !keepContent || !hasContent { state = .loading }
        do {
            let page = try await api.history()
            guard request == generation, !Task.isCancelled else { return }
            state = .loaded(page.shelves.filter { !$0.items.isEmpty })
            continuation = page.continuation
        } catch {
            guard request == generation, !Task.isCancelled else { return }
            if !keepContent || !hasContent { state = .failed(userMessage(for: error)) }
        }
    }

    func loadMore(api: any MusicAPI) {
        guard !isLoadingMore, let token = continuation, case .loaded = state else { return }
        isLoadingMore = true
        let request = generation
        moreTask = Task {
            defer {
                if request == generation {
                    isLoadingMore = false
                    moreTask = nil
                }
            }
            guard let page = try? await api.browseContinuation(token), request == generation, !Task.isCancelled,
                  case .loaded(let days) = state else { return }
            state = .loaded(Self.merged(page.shelves, into: days))
            continuation = page.continuation
        }
    }

    /// More days after the ones shown. A day that continues ("Yesterday" again) joins the day already there.
    private static func merged(_ new: [Shelf], into days: [Shelf]) -> [Shelf] {
        var result = days
        for shelf in new where !shelf.items.isEmpty {
            if let index = result.firstIndex(where: { $0.title == shelf.title }) {
                result[index].items += shelf.items
            } else {
                result.append(shelf)
            }
        }
        return result
    }
}

/// History: the songs played, a titled list per day. A tap plays the song with its radio.
public struct HistoryScreen: View {
    @Environment(AppServices.self) private var services
    @State private var model = HistoryModel()
    @State private var isTitleShown = false
    @State private var retryCount = 0

    public init() {}

    public var body: some View {
        Group {
            if services.session.isSignedIn {
                signedIn
            } else {
                ScrollView {
                    pageTitle
                    SignedOutView(
                        title: "Sign in to see your history",
                        message: "The songs you play are kept in your own account, so there is nothing to show yet.")
                }
            }
        }
        .collapsingTitle("History", isShown: isTitleShown)
        .background(BoomColor.background.ignoresSafeArea())
        .task(id: [services.session.revision, retryCount]) {
            model.sync(revision: services.session.revision)
            guard services.session.isSignedIn else { return }
            await model.loadIfNeeded(api: services.api)
        }
        .onDisappear { model.cancelLoadingMore() }
    }

    private var pageTitle: some View {
        Text("History")
            .font(BoomFont.pageTitle)
            .foregroundStyle(BoomColor.textPrimary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, BoomMetrics.margin)
            .padding(.top, 4)
            .accessibilityAddTraits(.isHeader)
    }

    private var signedIn: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                pageTitle
                switch model.state {
                case .loading:
                    LoadingView(.list).padding(.top, 16)
                case .failed(let message):
                    ErrorView(message) { retryCount += 1 }
                case .loaded(let days):
                    if days.isEmpty {
                        MessageView(
                            "Nothing played yet", message: "Songs you play show up here, newest first.",
                            systemImage: "clock.arrow.circlepath")
                    }
                    ForEach(days) { day in
                        VStack(alignment: .leading, spacing: 14) {
                            ShelfHeader(title: day.title)
                            // The same song may be played twice in a day, so rows are keyed by position.
                            VStack(spacing: 4) {
                                ForEach(Array(day.items.enumerated()), id: \.offset) { _, item in
                                    ItemRow(item, showsKind: false)
                                }
                            }
                        }
                        .padding(.top, BoomMetrics.shelfSpacing)
                    }
                    if model.continuation != nil {
                        LoadMoreRow(token: model.continuation, isLoading: model.isLoadingMore) {
                            model.loadMore(api: services.api)
                        }
                    }
                }
            }
            .padding(.bottom, 24)
        }
        .refreshable { await model.refresh(api: services.api) }
        .onScrolledPast(40) { isTitleShown = $0 }
    }
}
