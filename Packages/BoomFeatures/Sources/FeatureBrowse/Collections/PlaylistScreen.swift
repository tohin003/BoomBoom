import BoomDesign
import BoomModels
import BoomPlayer
import SwiftUI

/// A playlist: cover, title, author, description, Play and Shuffle, the songs (more as you scroll), then shelves.
struct PlaylistScreen: View {
    let id: String

    @Environment(AppServices.self) private var services
    @Environment(\.boomNavigator) private var navigator
    @State private var model: PlaylistModel
    @State private var tracker = ScrollTracker()
    @State private var isTitleShown = false
    /// The editor is up (the listener's own playlists only).
    @State private var isEditing = false
    @Environment(\.dismiss) private var dismiss

    init(id: String) {
        self.id = id
        _model = State(initialValue: PlaylistModel(id: id))
    }

    var body: some View {
        LoadStateView(state: model.state, style: .collection, retry: { model.retry(api: services.api) }) { list in
            let page = list.content
            ZStack(alignment: .top) {
                CollectionBackdrop(artwork: page.playlist.artwork, tracker: tracker)
                ScrollView {
                    LazyVStack(spacing: 0) {
                        header(page, continuation: list.more.token)
                        let source = ItemActions.source(for: page.playlist)
                        let context = PlayContext.list(page.tracks, source: source)
                        // Append-only, and songs may repeat: rows are keyed by position.
                        ForEach(Array(page.tracks.enumerated()), id: \.offset) { _, track in
                            SongRow(track, context: context, showsKind: false)
                        }
                        if list.more != .end {
                            LoadMoreRow(more: list.more) {
                                model.loadMore(api: services.api)
                            }
                        } else if page.tracks.isEmpty {
                            MessageView("This playlist is empty")
                        }
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
        .collapsingTitle(model.state.value?.content.playlist.title ?? "", isShown: isTitleShown)
        .task { model.loadIfNeeded(api: services.api) }
        .sheet(isPresented: $isEditing) {
            if let page = model.state.value?.content {
                PlaylistEditorSheet(
                    page: page,
                    onChanged: { Task { await model.refresh(api: services.api) } },
                    // The playlist is gone: leave its page.
                    onDeleted: { dismiss() })
            }
        }
    }

    private var actions: ItemActions { ItemActions(services: services, navigator: navigator) }

    private func header(_ page: PlaylistPage, continuation: String?) -> some View {
        let playlist = page.playlist
        let item = MusicItem.playlist(playlist)
        let source = ItemActions.source(for: playlist)
        return CollectionHeader(artwork: playlist.artwork, title: playlist.title, description: page.description) {
            if let author = page.authorName {
                HStack(spacing: 6) {
                    if let artwork = page.authorArtwork {
                        ArtworkView(artwork, width: 22, isCircle: true)
                    }
                    Text(author)
                        .font(.system(.subheadline, weight: .semibold))
                        .foregroundStyle(BoomColor.textPrimary)
                }
                .frame(minHeight: 28)
            }
            if let stats = page.statsText {
                Text(stats)
            } else if let detail = playlist.detailText {
                Text(detail)
            }
        } actions: {
            let download = CollectionDownloadSource(
                kind: .playlist, entry: DownloadCollector.entry(for: page), songs: page.tracks,
                continuation: continuation)
            VStack(spacing: 0) {
                CollectionActions(play: {
                    actions.playList(page.tracks, shuffled: false, source: source)
                }) {
                    if page.isEditable {
                        CircleIconButton("pencil", label: "Edit playlist") { isEditing = true }
                    } else {
                        CircleIconButton(
                            model.saved.isOn ? "checkmark" : "plus",
                            label: model.saved.isOn ? "Remove from library" : "Save to library",
                            isActive: model.saved.isOn
                        ) {
                            if services.session.isSignedIn {
                                model.toggleSaved(api: services.api)
                            } else {
                                navigator.signIn()
                            }
                        }
                    }
                    CollectionDownloadButton(source: download)
                } trailing: {
                    // Share lives in the menu: the row keeps two buttons a side.
                    CircleIconButton("shuffle", label: "Shuffle") {
                        actions.playList(page.tracks, shuffled: true, source: source)
                    }
                    CircleMenuButton(item: item)
                }
                CollectionDownloadStatus(source: download)
            }
        }
    }
}
