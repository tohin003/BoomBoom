import BoomDesign
import BoomModels
import BoomPlayer
import SwiftUI

/// Explore: three big entries (New releases, Charts, Moods & genres), then Explore's own shelves.
struct ExploreScreen: View {
    @Environment(AppServices.self) private var services
    @Environment(\.tabReselect) private var reselect
    @State private var model = BrowsePageModel(source: .explore)

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    VStack(spacing: 10) {
                        ExploreTile(
                            title: "New releases", systemImage: "sparkles",
                            route: .page(BrowseEndpoint(browseId: "FEmusic_new_releases")))
                        ExploreTile(title: "Charts", systemImage: "chart.line.uptrend.xyaxis", route: .charts)
                        ExploreTile(title: "Moods & genres", systemImage: "face.smiling", route: .moods)
                    }
                    .padding(.horizontal, BoomMetrics.margin)
                    .padding(.top, 4)
                    .id("top")
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
            TopBar(title: "Explore").background(BoomColor.background)
        }
        .background(BoomColor.background.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .task { model.loadIfNeeded(api: services.api) }
    }

    @ViewBuilder
    private var content: some View {
        switch model.state {
        case .loading:
            LoadingView(.shelves).padding(.top, BoomMetrics.shelfSpacing)
        case .failed(let message):
            PageErrorView(message) { model.retry(api: services.api) }
        case .loaded(let content):
            switch content.shelves {
            case .switching:
                LoadingView(.shelves).padding(.top, BoomMetrics.shelfSpacing)
            case .shown(let list):
                ForEach(list.content) { shelf in
                    ShelfView(shelf).padding(.top, BoomMetrics.shelfSpacing)
                }
            }
        }
    }
}

/// One of Explore's three big entries.
private struct ExploreTile: View {
    let title: String
    let systemImage: String
    let route: BrowseRoute

    var body: some View {
        NavigationLink(value: route) {
            HStack(spacing: 14) {
                Image(systemName: systemImage)
                    .font(.system(size: 20, weight: .semibold))
                    .foregroundStyle(BoomColor.accent)
                    .frame(width: 28)
                Text(title)
                    .font(.system(.headline, weight: .semibold))
                    .foregroundStyle(BoomColor.textPrimary)
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(BoomColor.textTertiary)
            }
            .padding(.horizontal, 16)
            .frame(minHeight: 56)
            .background(BoomColor.surfaceRaised, in: RoundedRectangle(cornerRadius: BoomMetrics.cardRadius))
            .contentShape(Rectangle())
        }
        .buttonStyle(CardPressStyle())
    }
}

/// A light press effect for tiles.
struct CardPressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .opacity(configuration.isPressed ? 0.7 : 1)
            .scaleEffect(configuration.isPressed ? 0.98 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

/// The mood and genre tiles, in titled groups, with their colour on the left edge.
struct MoodsScreen: View {
    @Environment(AppServices.self) private var services
    @State private var model = MoodsModel()
    @State private var isTitleShown = false

    private let columns = [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)]

    var body: some View {
        LoadStateView(state: model.state, retry: { model.retry(api: services.api) }) { groups in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    Text("Moods & genres")
                        .font(BoomFont.pageTitle)
                        .foregroundStyle(BoomColor.textPrimary)
                        .padding(.horizontal, BoomMetrics.margin)
                        .padding(.top, 4)
                        .accessibilityAddTraits(.isHeader)
                    ForEach(groups) { group in
                        ShelfHeader(title: group.title)
                            .padding(.top, BoomMetrics.shelfSpacing)
                            .padding(.bottom, 12)
                        LazyVGrid(columns: columns, spacing: 10) {
                            ForEach(group.chips) { chip in
                                MoodTile(chip: chip)
                            }
                        }
                        .padding(.horizontal, BoomMetrics.margin)
                    }
                }
                .padding(.bottom, 24)
            }
            .refreshable { await model.refresh(api: services.api) }
            .onScrolledPast(40) { isTitleShown = $0 }
        }
        .collapsingTitle("Moods & genres", isShown: isTitleShown)
        .task { model.loadIfNeeded(api: services.api) }
    }
}

private struct MoodTile: View {
    let chip: Chip

    var body: some View {
        let label = HStack(spacing: 0) {
            Color(hex: chip.colorHex ?? 0x777777).frame(width: 6)
            Text(chip.title)
                .font(.system(.subheadline, weight: .semibold))
                .foregroundStyle(BoomColor.textPrimary)
                .lineLimit(2)
                .minimumScaleFactor(0.85)
                .padding(.horizontal, 12)
            Spacer(minLength: 0)
        }
        .frame(minHeight: 52)
        .background(BoomColor.surfaceRaised)
        .clipShape(RoundedRectangle(cornerRadius: 6))
        .contentShape(Rectangle())

        if let endpoint = chip.endpoint {
            NavigationLink(value: BrowseRoute.page(endpoint)) { label }
                .buttonStyle(CardPressStyle())
                .accessibilityIdentifier("mood.tile")
        } else {
            label
        }
    }
}
