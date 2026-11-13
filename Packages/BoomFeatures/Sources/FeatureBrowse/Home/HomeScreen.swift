import BoomDesign
import BoomModels
import BoomPlayer
import SwiftUI

/// Home: the brand bar, the mood chips, then the shelves.
struct HomeScreen: View {
    @Environment(AppServices.self) private var services
    @Environment(\.tabReselect) private var reselect
    @State private var model = HomeModel()

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    Color.clear.frame(height: 0).id("top")
                    if !model.chips.isEmpty {
                        ChipBar(model.chips, selection: model.selectedChip.flatMap { selected in
                            model.chips.first { $0.title == selected.title }
                        }, title: \.title) { chip in
                            model.select(chip, api: services.api)
                            withAnimation { proxy.scrollTo("top", anchor: .top) }
                        }
                        .padding(.vertical, 4)
                    }
                    content
                }
                .padding(.bottom, 24)
            }
            .refreshable { await model.refresh(api: services.api) }
            .onChange(of: reselect) {
                withAnimation { proxy.scrollTo("top", anchor: .top) }
            }
        }
        .safeAreaInset(edge: .top, spacing: 0) {
            TopBar().background(BoomColor.background)
        }
        .background(BoomColor.background.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .task(id: services.session.revision) {
            model.sync(revision: services.session.revision, api: services.api)
        }
    }

    @ViewBuilder
    private var content: some View {
        switch model.state {
        case .loading:
            LoadingView(.shelves).padding(.top, 16)
        case .failed(let message):
            PageErrorView(message) { model.retry(api: services.api) }
        case .loaded(let feed):
            let shelves = feed.content
            ForEach(shelves) { shelf in
                ShelfView(shelf)
                    .padding(.top, BoomMetrics.shelfSpacing - (shelf.id == shelves.first?.id ? 12 : 0))
            }
            if feed.more != .end {
                LoadMoreRow(more: feed.more) {
                    model.loadMore(api: services.api)
                }
            }
        }
    }
}
