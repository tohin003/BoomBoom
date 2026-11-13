import BoomDesign
import BoomModels
import BoomPlayer
import SwiftUI

/// An artist: a wide banner with the name over it, the audience, Shuffle / Radio / Subscribe, the shelves, About.
struct ArtistScreen: View {
    let id: String

    @Environment(AppServices.self) private var services
    @Environment(\.boomNavigator) private var navigator
    @State private var model: ArtistModel
    @State private var tracker = ScrollTracker()
    @State private var isTitleShown = false

    private static let bannerHeight: CGFloat = 340

    init(id: String) {
        self.id = id
        _model = State(initialValue: ArtistModel(id: id))
    }

    var body: some View {
        LoadStateView(state: model.state, style: .artist, retry: { model.retry(api: services.api) }) { page in
            ZStack(alignment: .top) {
                ArtistBanner(artwork: page.banner.isEmpty ? page.artist.artwork : page.banner,
                             height: Self.bannerHeight, tracker: tracker)
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        header(page)
                        shelves(page)
                        about(page)
                    }
                    .padding(.bottom, 24)
                }
                .refreshable { await model.refresh(api: services.api) }
                .onScrollOffset { tracker.offset = $0 }
                .onScrolledPast(Self.bannerHeight - 120) { isTitleShown = $0 }
            }
        }
        .collapsingTitle(model.state.value?.artist.name ?? "", isShown: isTitleShown)
        .task { model.loadIfNeeded(api: services.api) }
    }

    private var source: QueueSource? {
        guard let artist = model.state.value?.artist else { return nil }
        return QueueSource(
            kind: .artist, label: "Artist", title: artist.name,
            endpoint: BrowseEndpoint(browseId: artist.id, pageType: .artist))
    }

    private func header(_ page: ArtistPage) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(page.artist.name)
                .font(.system(size: 40, weight: .heavy))
                .tracking(-0.5)
                .foregroundStyle(BoomColor.textPrimary)
                .lineLimit(2)
                .minimumScaleFactor(0.6)
                .shadow(color: .black.opacity(0.5), radius: 12)
                .accessibilityAddTraits(.isHeader)
            let audience = [page.monthlyListenersText, page.subscribersText].compactMap { $0 }
            if !audience.isEmpty {
                Text(audience.joined(separator: " • "))
                    .font(BoomFont.itemSubtitle)
                    .foregroundStyle(BoomColor.textSecondary)
            }
            HStack(spacing: 10) {
                Button {
                    if services.session.isSignedIn {
                        model.toggleSubscription(api: services.api)
                    } else {
                        navigator.signIn()
                    }
                } label: {
                    Text(model.subscription.isOn ? "Subscribed" : "Subscribe")
                }
                .buttonStyle(OutlinePillButtonStyle())
                .accessibilityAddTraits(model.subscription.isOn ? [.isSelected] : [])
                Button {
                    if let radio = page.radio { services.player.play(radio, source: source) }
                } label: {
                    Label("Radio", systemImage: "dot.radiowaves.left.and.right")
                }
                .buttonStyle(TonalPillButtonStyle())
                .disabled(page.radio == nil)
                Spacer(minLength: 0)
                CircleIconButton("shuffle", label: "Shuffle") {
                    if let shuffle = page.shuffle { services.player.play(shuffle, source: source) }
                }
                .disabled(page.shuffle == nil)
                AccentPlayButton(size: 56) { playTopSongs(page) }
            }
            .padding(.top, 6)
        }
        .padding(.horizontal, BoomMetrics.margin)
        .padding(.top, Self.bannerHeight - 190)
        .padding(.bottom, 4)
    }

    private func playTopSongs(_ page: ArtistPage) {
        let top = page.shelves.first { $0.layout == .list }?.items.compactMap(\.song) ?? []
        if !top.isEmpty {
            ItemActions(services: services, navigator: navigator).playList(top, shuffled: false, source: source)
        } else if let endpoint = page.shuffle ?? page.radio {
            services.player.play(endpoint, source: source)
        }
    }

    @ViewBuilder
    private func shelves(_ page: ArtistPage) -> some View {
        let firstList = page.shelves.first { $0.layout == .list }?.id
        ForEach(page.shelves) { shelf in
            if shelf.id == firstList {
                ShelfView(shelf, songMode: .tracklist(source), rowLimit: 5)
                    .padding(.top, BoomMetrics.shelfSpacing)
            } else {
                ShelfView(shelf).padding(.top, BoomMetrics.shelfSpacing)
            }
        }
    }

    @ViewBuilder
    private func about(_ page: ArtistPage) -> some View {
        if let description = page.description, !description.isEmpty {
            VStack(alignment: .leading, spacing: 12) {
                ShelfHeader(title: "About")
                VStack(alignment: .leading, spacing: 10) {
                    ExpandableText(description, collapsedLines: 4)
                    if let subscribers = page.subscribersText {
                        Text(subscribers)
                            .font(.system(.subheadline, weight: .semibold))
                            .foregroundStyle(BoomColor.textPrimary)
                    }
                }
                .padding(16)
                .background(BoomColor.surface, in: RoundedRectangle(cornerRadius: BoomMetrics.cardRadius))
                .padding(.horizontal, BoomMetrics.margin)
            }
            .padding(.top, BoomMetrics.shelfSpacing)
        }
    }
}

/// The artist's wide picture across the top, fading into the background and sliding up with the page.
private struct ArtistBanner: View {
    let artwork: Artwork
    let height: CGFloat
    let tracker: ScrollTracker

    var body: some View {
        GeometryReader { proxy in
            let top = proxy.safeAreaInsets.top
            let full = height + top
            let pull = max(0, -tracker.offset)
            // A fixed size, so the picture is fetched once; pulling down stretches it instead.
            ArtworkView(artwork, width: proxy.size.width, height: full, cornerRadius: 0)
                .overlay {
                    LinearGradient(
                        stops: [
                            .init(color: .clear, location: 0.35),
                            .init(color: BoomColor.background.opacity(0.7), location: 0.75),
                            .init(color: BoomColor.background, location: 1),
                        ],
                        startPoint: .top, endPoint: .bottom)
                }
                .scaleEffect(1 + pull / full, anchor: .top)
                .offset(y: -top - max(0, tracker.offset) * 0.6)
                .opacity(1 - min(1, max(0, tracker.offset) / height))
        }
        .frame(height: height)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}
