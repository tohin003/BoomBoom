import BoomDesign
import BoomModels
import BoomPlayer
import SwiftUI

/// The one sheet the Library can have up.
private enum LibrarySheet: Identifiable {
    case newPlaylist
    case edit(PlaylistItem)

    var id: String {
        switch self {
        case .newPlaylist: "new"
        case .edit(let playlist): "edit:" + playlist.id
        }
    }
}

/// What starts a (re)load: the section, the sign-in, and the Retry button.
private struct LibraryLoadKey: Hashable {
    var section: LibrarySection
    var revision: Int
    var isSignedIn: Bool
    var retry: Int
}

/// The Library tab: the listener's playlists, songs, albums, artists and subscriptions.
struct LibraryScreen: View {
    @Environment(AppServices.self) private var services
    @Environment(\.boomNavigator) private var navigator
    @Environment(\.tabReselect) private var reselect
    @AppStorage("library.showsGrid") private var showsGrid = false
    @State private var model = LibraryModel()
    @State private var section = LibrarySection.playlists
    @State private var sheet: LibrarySheet?
    @State private var playlistToDelete: PlaylistItem?
    @State private var retryCount = 0

    private var api: any MusicAPI { services.api }

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    Color.clear.frame(height: 0).id("top")
                    // First, and signed out too: downloads are on the phone and play without a connection.
                    DownloadsEntryRow()
                        .padding(.top, 4)
                    if services.session.isSignedIn {
                        signedInContent
                    } else {
                        SignedOutView(
                            title: "Your music, all in one place",
                            message: "Your playlists, liked songs, albums and artists come from your own account. "
                                + "Sign in to see them here.")
                    }
                }
                .padding(.bottom, 24)
            }
            .refreshable {
                guard services.session.isSignedIn else { return }
                await model.refresh(section, api: api)
            }
            .onChange(of: reselect) {
                withAnimation { proxy.scrollTo("top", anchor: .top) }
            }
        }
        .safeAreaInset(edge: .top, spacing: 0) {
            LibraryTopBar().background(BoomColor.background)
        }
        .background(BoomColor.background.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .task(id: LibraryLoadKey(
            section: section, revision: services.session.revision, isSignedIn: services.session.isSignedIn,
            retry: retryCount)
        ) {
            model.sync(revision: services.session.revision)
            guard services.session.isSignedIn else { return }
            await model.loadIfNeeded(section, api: api)
        }
        .onDisappear { model.cancelLoadingMore() }
        .sheet(item: $sheet) { sheet in
            switch sheet {
            case .newPlaylist:
                NewPlaylistSheet { id in
                    Task { await model.refresh(.playlists, api: api) }
                    navigator.open(BrowseEndpoint(browseId: id, pageType: .playlist))
                }
            case .edit(let playlist):
                PlaylistEditorLoader(playlist: playlist) {
                    Task { await model.refresh(.playlists, api: api) }
                }
            }
        }
        .confirmationDialog(
            playlistToDelete.map { "Delete “\($0.title)”?" } ?? "Delete this playlist?",
            isPresented: Binding(get: { playlistToDelete != nil }, set: { if !$0 { playlistToDelete = nil } }),
            titleVisibility: .visible, presenting: playlistToDelete
        ) { playlist in
            Button("Delete", role: .destructive) {
                Task { await model.deletePlaylist(playlist, api: api) }
            }
        } message: { _ in
            Text("The playlist and the order of its songs will be gone. The songs themselves stay on BoomBoom.")
        }
        .alert(
            model.notice?.title ?? "", isPresented: Binding(get: { model.notice != nil }, set: { if !$0 { model.notice = nil } }),
            presenting: model.notice
        ) { _ in
            Button("OK") {}
        } message: { notice in
            Text(notice.message)
        }
    }

    // MARK: Signed in

    @ViewBuilder
    private var signedInContent: some View {
        ChipBar(LibrarySection.allCases, selection: section, title: \.title) { chosen in
            guard chosen != section else { return }
            model.cancelLoadingMore()
            section = chosen
        }
        .padding(.vertical, 4)
        switch model.state(of: section) {
        case .loading:
            LoadingView(.list).padding(.top, 8)
        case .failed(let message):
            PageErrorView(message) { retryCount += 1 }
        case .loaded(let content):
            loaded(content)
        }
    }

    private var layoutToggle: some View {
        HStack {
            Spacer()
            Button {
                showsGrid.toggle()
            } label: {
                Image(systemName: showsGrid ? "list.bullet" : "square.grid.2x2")
                    .font(.system(size: 17, weight: .medium))
                    .foregroundStyle(BoomColor.textSecondary)
                    .frame(width: BoomMetrics.minimumTarget, height: 36)
                    .contentShape(Rectangle())
            }
            .accessibilityLabel(showsGrid ? "Show as list" : "Show as grid")
        }
        .padding(.trailing, 8)
    }

    @ViewBuilder
    private func loaded(_ content: LibraryContent) -> some View {
        if section != .songs && !content.items.isEmpty {
            layoutToggle
        }
        if section == .playlists {
            PinnedRow(kind: .newPlaylist) { sheet = .newPlaylist }
            PinnedRow(kind: .likedMusic) {
                navigator.open(BrowseEndpoint(browseId: "LM", pageType: .playlist))
            }
        }
        if content.items.isEmpty {
            MessageView(
                emptyTitle, message: emptyMessage, systemImage: emptyImage)
        } else if showsGrid && section != .songs {
            LibraryGrid(items: content.items, onEdit: { sheet = .edit($0) }, onDelete: { playlistToDelete = $0 })
                .padding(.top, 8)
        } else {
            let songs = content.songs
            let source = QueueSource(kind: .library, label: "Library", title: "Songs")
            ForEach(content.items) { item in
                row(item, songs: songs, source: source)
            }
        }
        if content.continuation != nil {
            LoadMoreRow(token: content.continuation, isLoading: model.loadingMore.contains(section)) {
                model.loadMore(section, api: api)
            }
        }
    }

    @ViewBuilder
    private func row(_ item: MusicItem, songs: [Song], source: QueueSource) -> some View {
        switch item {
        case .playlist(let playlist) where playlist.isOwn:
            OwnPlaylistRow(
                playlist: playlist, onEdit: { sheet = .edit(playlist) }, onDelete: { playlistToDelete = playlist })
        case .song:
            ItemRow(item, context: .list(songs, source: source), showsKind: false)
        default:
            ItemRow(item)
        }
    }

    private var emptyTitle: String {
        switch section {
        case .playlists: "No playlists yet"
        case .songs: "No songs yet"
        case .albums: "No albums yet"
        case .artists: "No artists yet"
        case .subscriptions: "No subscriptions yet"
        }
    }

    private var emptyMessage: String {
        switch section {
        case .playlists: "Playlists you make or save show up here."
        case .songs: "Songs you add to your library show up here."
        case .albums: "Albums you add to your library show up here."
        case .artists: "Artists you follow show up here."
        case .subscriptions: "Channels you subscribe to show up here."
        }
    }

    private var emptyImage: String {
        switch section {
        case .playlists: "music.note.list"
        case .songs: "music.note"
        case .albums: "square.stack"
        case .artists, .subscriptions: "person.2"
        }
    }
}

/// The items two across, with the listener's own playlists carrying Edit and Delete in their menu.
private struct LibraryGrid: View {
    let items: [MusicItem]
    let onEdit: (PlaylistItem) -> Void
    let onDelete: (PlaylistItem) -> Void
    @State private var width: CGFloat = 0

    var body: some View {
        let spacing: CGFloat = 12
        let cover = max(80, ((width - BoomMetrics.margin * 2 - spacing) / 2).rounded(.down))
        LazyVGrid(
            columns: [GridItem(.fixed(cover), spacing: spacing, alignment: .top), GridItem(.fixed(cover))],
            alignment: .leading, spacing: 20
        ) {
            ForEach(items) { item in
                if case .playlist(let playlist) = item, playlist.isOwn {
                    OwnPlaylistCard(
                        playlist: playlist, width: cover, onEdit: { onEdit(playlist) },
                        onDelete: { onDelete(playlist) })
                } else {
                    ItemCard(item, width: cover)
                }
            }
        }
        .padding(.horizontal, BoomMetrics.margin)
        .frame(maxWidth: .infinity, alignment: .leading)
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
        .opacity(width > 0 ? 1 : 0)
    }
}
