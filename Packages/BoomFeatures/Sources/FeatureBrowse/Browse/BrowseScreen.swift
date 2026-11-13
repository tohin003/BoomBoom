import BoomDesign
import BoomModels
import BoomPlayer
import SwiftUI

/// A titled page of shelves for any endpoint: a "More" page, a mood, New releases, Charts.
struct BrowseScreen: View {
    @Environment(AppServices.self) private var services
    @State private var model: BrowsePageModel
    @State private var isTitleShown = false

    init(source: BrowsePageModel.Source) {
        _model = State(initialValue: BrowsePageModel(source: source))
    }

    var body: some View {
        LoadStateView(state: model.state, retry: { model.retry(api: services.api) }) { content in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    if let title = content.title {
                        Text(title)
                            .font(BoomFont.pageTitle)
                            .foregroundStyle(BoomColor.textPrimary)
                            .lineLimit(2)
                            .padding(.horizontal, BoomMetrics.margin)
                            .padding(.top, 4)
                            .padding(.bottom, content.chips.isEmpty ? 0 : 12)
                            .accessibilityAddTraits(.isHeader)
                    }
                    if !content.chips.isEmpty {
                        ChipBar(content.chips, selection: selectedChip(content.chips), title: \.title) { chip in
                            model.select(chip, api: services.api)
                        }
                    }
                    switch content.shelves {
                    case .switching:
                        LoadingView(.shelves).padding(.top, 20)
                    case .shown(let list):
                        if list.content.isEmpty {
                            MessageView("Nothing here yet")
                        }
                        ForEach(list.content) { shelf in
                            ShelfView(shelf).padding(.top, BoomMetrics.shelfSpacing)
                        }
                        if list.more != .end {
                            LoadMoreRow(more: list.more) {
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
        .collapsingTitle(model.state.value?.title ?? "", isShown: isTitleShown)
        .task { model.loadIfNeeded(api: services.api) }
    }

    private func selectedChip(_ chips: [Chip]) -> Chip? {
        if case .charts(let country) = model.source {
            return chips.first { $0.endpoint?.params == (country ?? "ZZ") } ?? chips.first { $0.isSelected }
        }
        if case .endpoint(let endpoint) = model.source {
            return chips.first { $0.endpoint == endpoint } ?? chips.first { $0.isSelected }
        }
        return chips.first { $0.isSelected }
    }
}
