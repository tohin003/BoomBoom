import BoomDesign
import BoomModels
import BoomPlayer
import SwiftUI

/// Search: a field with suggestions while typing, recent searches when empty, and results with filters.
struct SearchScreen: View {
    let initialQuery: String?
    var submitsInitialQuery = true

    @Environment(AppServices.self) private var services
    @State private var model: SearchModel?

    var body: some View {
        Group {
            if let model {
                SearchContent(model: model, initialQuery: initialQuery, submitsInitialQuery: submitsInitialQuery)
            } else {
                Color.clear
            }
        }
        .background(BoomColor.background.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .onAppear {
            if model == nil { model = SearchModel(api: services.api) }
        }
    }
}

private struct SearchContent: View {
    @Bindable var model: SearchModel
    let initialQuery: String?
    let submitsInitialQuery: Bool

    @Environment(\.dismiss) private var dismiss
    @FocusState private var isFieldFocused: Bool
    @State private var didStart = false

    var body: some View {
        VStack(spacing: 0) {
            searchBar
            switch model.mode {
            case .suggestions:
                if model.text.trimmingCharacters(in: .whitespaces).isEmpty {
                    RecentsList(model: model)
                } else {
                    SuggestionsList(model: model)
                }
            case .results:
                ResultsView(model: model)
            }
        }
        .onChange(of: model.text) { model.textChanged() }
        .onAppear {
            guard !didStart else { return }
            didStart = true
            if let initialQuery, !initialQuery.isEmpty {
                model.text = initialQuery
                if submitsInitialQuery { model.submit() } else { isFieldFocused = true }
            } else {
                isFieldFocused = true
            }
        }
    }

    private var searchBar: some View {
        HStack(spacing: 4) {
            Button {
                dismiss()
            } label: {
                Image(systemName: "chevron.left")
                    .font(.system(size: 19, weight: .semibold))
                    .frame(width: BoomMetrics.minimumTarget, height: BoomMetrics.minimumTarget)
                    .contentShape(Rectangle())
            }
            .accessibilityLabel("Back")
            HStack(spacing: 8) {
                TextField("Search songs, albums, artists", text: $model.text)
                    .font(.body)
                    .foregroundStyle(BoomColor.textPrimary)
                    .tint(BoomColor.accent)
                    .focused($isFieldFocused)
                    .submitLabel(.search)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .onSubmit {
                        model.submit()
                    }
                if !model.text.isEmpty {
                    Button {
                        model.text = ""
                        isFieldFocused = true
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .font(.system(size: 17))
                            .foregroundStyle(BoomColor.textTertiary)
                            .frame(width: 36, height: BoomMetrics.minimumTarget)
                            .contentShape(Rectangle())
                    }
                    .accessibilityLabel("Clear search")
                }
            }
            .padding(.leading, 14)
            .padding(.trailing, 4)
            .frame(height: 40)
            .background(BoomColor.surfaceRaised, in: Capsule())
            .onTapGesture { isFieldFocused = true }
        }
        .foregroundStyle(BoomColor.textPrimary)
        .padding(.leading, 4)
        .padding(.trailing, BoomMetrics.margin)
        .padding(.vertical, 6)
        .background(BoomColor.background)
    }
}

/// Earlier searches, newest first, each removable.
private struct RecentsList: View {
    let model: SearchModel

    var body: some View {
        let recents = model.recents.all
        if recents.isEmpty {
            ScrollView {
                MessageView("Find your music", message: "Search for songs, albums, artists and playlists.",
                            systemImage: "magnifyingglass")
            }
            .scrollDismissesKeyboard(.immediately)
        } else {
            List {
                Section {
                    ForEach(recents, id: \.self) { query in
                        HStack(spacing: 14) {
                            Button {
                                model.submit(query)
                            } label: {
                                HStack(spacing: 14) {
                                    Image(systemName: "clock.arrow.circlepath")
                                        .font(.system(size: 17))
                                        .foregroundStyle(BoomColor.textSecondary)
                                        .frame(width: 24)
                                    Text(query)
                                        .font(BoomFont.itemTitle)
                                        .foregroundStyle(BoomColor.textPrimary)
                                        .lineLimit(1)
                                    Spacer(minLength: 0)
                                }
                                .frame(minHeight: 48)
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            Button {
                                withAnimation { model.recents.remove(query) }
                            } label: {
                                Image(systemName: "xmark")
                                    .font(.system(size: 14, weight: .semibold))
                                    .foregroundStyle(BoomColor.textSecondary)
                                    .frame(width: BoomMetrics.minimumTarget, height: BoomMetrics.minimumTarget)
                                    .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel("Remove \(query) from recent searches")
                        }
                        .listRowInsets(EdgeInsets(top: 0, leading: BoomMetrics.margin, bottom: 0, trailing: 4))
                        .listRowBackground(BoomColor.background)
                        .listRowSeparator(.hidden)
                    }
                } header: {
                    HStack {
                        Text("Recent searches")
                            .font(.system(.headline, weight: .bold))
                            .foregroundStyle(BoomColor.textPrimary)
                        Spacer()
                        Button("Clear") { withAnimation { model.recents.clear() } }
                            .font(BoomFont.button)
                            .foregroundStyle(BoomColor.textSecondary)
                            .frame(minHeight: BoomMetrics.minimumTarget)
                    }
                    .textCase(nil)
                    .padding(.horizontal, BoomMetrics.margin)
                    .listRowInsets(EdgeInsets())
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .scrollDismissesKeyboard(.immediately)
        }
    }
}

/// The lines under the field while typing.
private struct SuggestionsList: View {
    let model: SearchModel

    var body: some View {
        let shown = model.suggestions.visible(for: model.text)
        ScrollView {
            LazyVStack(spacing: 0) {
                ForEach(shown) { suggestion in
                    switch suggestion {
                    case .query(let query):
                        QuerySuggestionRow(query: query) {
                            model.submit(query.text)
                        } fill: {
                            model.fill(query.text)
                        }
                    case .item(let item):
                        ItemRow(item)
                            .padding(.vertical, 2)
                    }
                }
            }
            .padding(.vertical, 4)
        }
        .scrollDismissesKeyboard(.immediately)
        .overlay(alignment: .top) {
            if model.suggestions.isLoading, shown.isEmpty {
                ProgressView()
                    .tint(BoomColor.textSecondary)
                    .padding(.top, 24)
            }
        }
    }
}

private struct QuerySuggestionRow: View {
    let query: SuggestedQuery
    let submit: () -> Void
    let fill: () -> Void

    var body: some View {
        HStack(spacing: 0) {
            Button(action: submit) {
                HStack(spacing: 14) {
                    Image(systemName: query.isFromHistory ? "clock.arrow.circlepath" : "magnifyingglass")
                        .font(.system(size: 17))
                        .foregroundStyle(BoomColor.textSecondary)
                        .frame(width: 24)
                    Text(styled)
                        .foregroundStyle(BoomColor.textPrimary)
                        .lineLimit(1)
                    Spacer(minLength: 0)
                }
                .padding(.leading, BoomMetrics.margin)
                .frame(minHeight: 48)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(query.text)
            .accessibilityHint("Searches")
            Button(action: fill) {
                Image(systemName: "arrow.up.left")
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(BoomColor.textSecondary)
                    .frame(width: 48, height: 48)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .padding(.trailing, 4)
            .accessibilityLabel("Use \(query.text)")
        }
    }

    /// The typed part plain, the completion bold.
    private var styled: AttributedString {
        var result = AttributedString()
        for run in query.runs {
            var part = AttributedString(run.text)
            part.font = .system(.body, weight: run.isBold ? .bold : .regular)
            result += part
        }
        return result
    }
}

/// Results: the filter pills, "Showing results for …", then the shelves or the filtered list.
private struct ResultsView: View {
    let model: SearchModel

    private static let filters: [SearchFilter] = [
        .songs, .videos, .albums, .artists, .communityPlaylists, .featuredPlaylists, .podcasts, .episodes, .profiles,
    ]

    var body: some View {
        let results = model.results
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    ChipBar(Self.filters, selection: results.filter, title: \.title) { filter in
                        model.select(filter)
                        proxy.scrollTo("results-top", anchor: .top)
                    }
                    .padding(.vertical, 4)
                    .id("results-top")
                    switch results.phase {
                    case .idle, .loading:
                        LoadingView(results.filter == nil ? .shelves : .list).padding(.top, 12)
                    case .failed(let message):
                        PageErrorView(message) { results.retry() }
                    case .loaded(let page):
                        if let corrected = page.correctedQuery {
                            CorrectionLine(corrected: corrected)
                        }
                        shelves(page)
                    case .list(let page, _):
                        if let corrected = page.correctedQuery {
                            CorrectionLine(corrected: corrected)
                        }
                        filtered
                    }
                }
                .padding(.bottom, 24)
            }
            .scrollDismissesKeyboard(.immediately)
        }
    }

    @ViewBuilder
    private func shelves(_ page: SearchResults) -> some View {
        let shelves = page.shelves.filter { !$0.items.isEmpty || $0.text?.isEmpty == false }
        if shelves.isEmpty {
            MessageView("No results", message: "Try different words.", systemImage: "magnifyingglass")
        }
        ForEach(shelves) { shelf in
            ShelfView(shelf) { filter in model.select(filter) }
                .padding(.top, shelf.id == shelves.first?.id ? 12 : BoomMetrics.shelfSpacing)
        }
    }

    @ViewBuilder
    private var filtered: some View {
        let results = model.results
        if results.items.isEmpty {
            MessageView("No results", message: "Try a different filter.", systemImage: "magnifyingglass")
        }
        ForEach(results.items) { item in
            ItemRow(item).padding(.vertical, 2)
        }
        if results.more != .end {
            LoadMoreRow(more: results.more) {
                results.loadMore()
            }
        }
    }
}

/// "Showing results for …" when the words were corrected.
private struct CorrectionLine: View {
    let corrected: String

    var body: some View {
        Text("Showing results for \(Text(corrected).bold().italic())")
            .font(BoomFont.itemSubtitle)
            .foregroundStyle(BoomColor.textSecondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, BoomMetrics.margin)
            .padding(.top, 10)
    }
}
