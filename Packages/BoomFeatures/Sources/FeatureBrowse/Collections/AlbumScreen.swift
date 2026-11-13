import BoomDesign
import BoomModels
import BoomPlayer
import SwiftUI

/// An album: the cover over its blurred backdrop, title, artist, description, actions, the tracklist, then shelves.
struct AlbumScreen: View {
    let id: String

    @Environment(AppServices.self) private var services
    @Environment(\.boomNavigator) private var navigator
    @State private var model: AlbumModel
    @State private var tracker = ScrollTracker()
    @State private var isTitleShown = false

    init(id: String) {
        self.id = id
        _model = State(initialValue: AlbumModel(id: id))
    }

    var body: some View {
        LoadStateView(state: model.state, style: .collection, retry: { model.retry(api: services.api) }) { page in
            ZStack(alignment: .top) {
                CollectionBackdrop(artwork: page.album.artwork, tracker: tracker)
                ScrollView {
                    LazyVStack(spacing: 0) {
                        header(page)
                        tracklist(page)
                        ForEach(page.shelves) { shelf in
                            ShelfView(shelf).padding(.top, BoomMetrics.shelfSpacing)
                        }
                    }
                    .padding(.bottom, 24)
                }
                .refreshable { await model.refresh(api: services.api) }
                .onScrollOffset { tracker.offset = $0 }
                .onScrolledPast(330) { isTitleShown = $0 }
            }
        }
        .collapsingTitle(model.state.value?.album.title ?? "", isShown: isTitleShown)
        .task { model.loadIfNeeded(api: services.api) }
    }

    private var actions: ItemActions { ItemActions(services: services, navigator: navigator) }

    private func header(_ page: AlbumPage) -> some View {
        let album = page.album
        let item = MusicItem.album(album)
        return CollectionHeader(
            artwork: album.artwork, title: album.title, isExplicit: album.isExplicit, description: page.description
        ) {
            if let artist = album.artists.first {
                Button {
                    if let id = artist.id { navigator.open(BrowseEndpoint(browseId: id, pageType: .artist)) }
                } label: {
                    Text(album.artists.map(\.name).joined(separator: ", "))
                        .font(.system(.subheadline, weight: .semibold))
                        .foregroundStyle(BoomColor.textPrimary)
                        .frame(minHeight: 28)
                }
                .buttonStyle(.plain)
                .disabled(artist.id == nil)
                .accessibilityHint(artist.id == nil ? "" : "Opens the artist")
                .accessibilityIdentifier("album.artist")
            }
            Text([album.typeText ?? "Album", album.year ?? ""].filter { !$0.isEmpty }.joined(separator: " • "))
        } actions: {
            let download = CollectionDownloadSource(
                kind: .album, entry: DownloadCollector.entry(for: page), songs: page.tracks, continuation: nil)
            VStack(spacing: 0) {
                CollectionActions(play: {
                    actions.playList(page.tracks, shuffled: false, source: ItemActions.source(for: album))
                }) {
                    if page.libraryTokens != nil {
                        CircleIconButton(
                            model.library.isOn ? "checkmark" : "plus",
                            label: model.library.isOn ? "Remove from library" : "Save to library",
                            isActive: model.library.isOn
                        ) {
                            if services.session.isSignedIn {
                                model.toggleLibrary(api: services.api)
                            } else {
                                navigator.signIn()
                            }
                        }
                    }
                    CollectionDownloadButton(source: download)
                } trailing: {
                    // Share lives in the menu: the row keeps two buttons a side.
                    CircleIconButton("shuffle", label: "Shuffle") {
                        actions.playList(page.tracks, shuffled: true, source: ItemActions.source(for: album))
                    }
                    CircleMenuButton(item: item)
                }
                CollectionDownloadStatus(source: download)
            }
        }
    }

    @ViewBuilder
    private func tracklist(_ page: AlbumPage) -> some View {
        let context = PlayContext.list(page.tracks, source: ItemActions.source(for: page.album))
        ForEach(Array(page.tracks.enumerated()), id: \.offset) { index, track in
            SongRow(track, leading: .trackNumber(index + 1), context: context)
        }
        if let stats = page.statsText {
            Text(stats)
                .font(BoomFont.itemSubtitle)
                .foregroundStyle(BoomColor.textSecondary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, BoomMetrics.margin)
                .padding(.top, 16)
        }
    }
}
