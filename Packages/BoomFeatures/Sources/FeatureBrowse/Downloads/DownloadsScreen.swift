import BoomDesign
import BoomModels
import BoomPlayer
import SwiftUI

/// The listener's downloads: the space they take, what is still coming, the songs or the albums and playlists, and
/// the ways to remove them (swipe, select, remove all). Reached from Library and Settings; works signed out and with
/// no connection, since everything on it is on the phone.
struct DownloadsScreen: View {
    @Environment(AppServices.self) private var services
    @AppStorage("downloads.sort") private var sort = DownloadSort.recent
    @State private var view = DownloadsView.songs
    @State private var mode = DownloadsMode.browsing
    @State private var question: DownloadsQuestion?
    @State private var openedCollection: String?

    private var store: DownloadStore { services.downloads }

    /// - Parameters: the view and mode to open in (galleries stage states with them).
    init(view: DownloadsView = .songs, mode: DownloadsMode = .browsing) {
        _view = State(initialValue: view)
        _mode = State(initialValue: mode)
    }

    var body: some View {
        List {
            if !store.items.isEmpty {
                header.plainListRow()
            }
            if store.items.isEmpty {
                MessageView(
                    "No downloads yet",
                    message: "Choose Download in a song's menu, or tap the download button beside Play on an album or "
                        + "playlist. Downloads stay on this phone until you remove them, and play without a connection.",
                    systemImage: "arrow.down.circle"
                )
                .plainListRow()
            } else {
                ChipBar(DownloadsView.allCases, selection: view, title: \.title) { chosen in
                    guard chosen != view else { return }
                    // A selection belongs to one view: song ids and collection ids do not mix.
                    mode = .browsing
                    view = chosen
                }
                .padding(.vertical, 4)
                .plainListRow()
                switch view {
                case .songs: songs
                case .collections: collections
                }
                if !mode.isSelecting {
                    removeAllButton
                        .frame(maxWidth: .infinity)
                        .padding(.top, 24)
                        .plainListRow()
                }
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .environment(\.defaultMinListRowHeight, 0)
        .background(BoomColor.background.ignoresSafeArea())
        .navigationTitle("Downloads")
        .navigationBarTitleDisplayMode(.large)
        .toolbarBackground(BoomColor.background, for: .navigationBar)
        .navigationBarBackButtonHidden(mode.isSelecting)
        .toolbar { toolbar }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if mode.isSelecting { selectionBar }
        }
        .navigationDestination(item: $openedCollection) { id in
            DownloadedCollectionScreen(id: id)
                .reservesShellBars()
        }
        .onChange(of: store.items.isEmpty) { _, isEmpty in
            if isEmpty { mode = .browsing }
        }
        .alert("Remove every download?", isPresented: isAsking(.removeAllAgain)) {
            Button("Remove all", role: .destructive) { store.removeAll() }
            Button("Keep them", role: .cancel) {}
        } message: {
            Text("All \(DownloadsMath.songs(store.items.count)) leave this phone, freeing "
                + "\(DownloadSize.text(store.totalBytes)). Getting them back means downloading them again.")
        }
    }

    /// Whether the listener is being asked `kind`. Each question is attached to the control that raises it, so it
    /// opens from there (see `SettingsScreen.isAsking(_:)`).
    private func isAsking(_ kind: DownloadsQuestion) -> Binding<Bool> {
        Binding(get: { question == kind }, set: { if !$0, question == kind { question = nil } })
    }

    // MARK: Header

    private var header: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(DownloadSize.text(store.totalBytes))
                    .font(.system(size: 34, weight: .bold).monospacedDigit())
                    .foregroundStyle(BoomColor.accent)
                Text("on this phone")
                    .font(BoomFont.itemSubtitle)
                    .foregroundStyle(BoomColor.textSecondary)
            }
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("downloads.space")
            Text(countLine)
                .font(BoomFont.itemSubtitle)
                .foregroundStyle(BoomColor.textSecondary)
                .accessibilityIdentifier("downloads.count")
            if store.pendingCount > 0 {
                HStack(spacing: 8) {
                    // Held for Wi-Fi, nothing is moving: a pause sign rather than a spinner.
                    if store.isWaitingForWiFi {
                        Image(systemName: "pause.circle.fill").foregroundStyle(BoomColor.textSecondary)
                    } else {
                        ProgressView().controlSize(.mini).tint(BoomColor.accent)
                    }
                    Text("\(store.pendingCount) still to download")
                        .font(BoomFont.itemSubtitle.weight(.semibold))
                        .foregroundStyle(BoomColor.textPrimary)
                }
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("downloads.pending")
            }
            if store.failedCount > 0 {
                HStack(spacing: 8) {
                    Image(systemName: "exclamationmark.circle.fill").foregroundStyle(BoomColor.destructive)
                    Text("\(store.failedCount) couldn't be downloaded")
                        .font(BoomFont.itemSubtitle)
                        .foregroundStyle(BoomColor.textPrimary)
                    Spacer(minLength: 0)
                    Button("Retry") { store.retryFailed() }
                        .buttonStyle(OutlinePillButtonStyle(isCompact: true))
                        .accessibilityIdentifier("downloads.retry")
                }
            }
            if store.isWaitingForWiFi {
                wifiBanner
            } else if store.pendingCount > 0 {
                Text("Downloads carry on while BoomBoom is open or playing music, and pick up where they left off "
                    + "next time otherwise.")
                    .font(BoomFont.caption)
                    .foregroundStyle(BoomColor.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if !store.downloaded.isEmpty && !mode.isSelecting {
                HStack(spacing: 10) {
                    Button {
                        playAll(shuffled: false)
                    } label: {
                        Label("Play", systemImage: "play.fill")
                    }
                    .buttonStyle(FilledPillButtonStyle())
                    .accessibilityIdentifier("downloads.play")
                    Button {
                        playAll(shuffled: true)
                    } label: {
                        Label("Shuffle", systemImage: "shuffle")
                    }
                    .buttonStyle(TonalPillButtonStyle())
                    .accessibilityIdentifier("downloads.shuffle")
                }
                .padding(.top, 4)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, BoomMetrics.margin)
        .padding(.bottom, 8)
    }

    private var countLine: String {
        let songs = DownloadsMath.songs(store.downloaded.count)
        switch store.collections.count {
        case 0: return songs
        case 1: return songs + " · 1 album or playlist"
        case let count: return songs + " · \(count) albums & playlists"
        }
    }

    private var wifiBanner: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label("Waiting for Wi-Fi", systemImage: "wifi")
                .font(BoomFont.itemTitle)
                .foregroundStyle(BoomColor.textPrimary)
            Text("The phone is on mobile data, and downloads use Wi-Fi only. They start again as soon as you are on "
                + "Wi-Fi.")
                .font(BoomFont.itemSubtitle)
                .foregroundStyle(BoomColor.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            Button("Use mobile data") { question = .mobileData }
                .buttonStyle(OutlinePillButtonStyle(isCompact: true))
                .accessibilityIdentifier("downloads.mobileData")
                .confirmationDialog(
                    "Download over mobile data?", isPresented: isAsking(.mobileData), titleVisibility: .visible
                ) {
                    Button("Use mobile data") { store.isMobileDataAllowed = true }
                } message: {
                    Text("A song is about 4 to 10 MB. This stays on until you turn it off in Settings.")
                }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(BoomColor.surfaceRaised, in: RoundedRectangle(cornerRadius: BoomMetrics.cardRadius))
        .overlay(
            RoundedRectangle(cornerRadius: BoomMetrics.cardRadius).strokeBorder(BoomColor.accent.opacity(0.5)))
        .accessibilityIdentifier("downloads.wifi")
    }

    // MARK: Songs

    private var shownSongs: [DownloadItem] { sort.sorted(store.items) }

    @ViewBuilder
    private var songs: some View {
        let shown = shownSongs
        HStack {
            Menu {
                Picker("Sort by", selection: $sort) {
                    ForEach(DownloadSort.allCases, id: \.self) { Text($0.title).tag($0) }
                }
            } label: {
                HStack(spacing: 6) {
                    Image(systemName: "arrow.up.arrow.down")
                    Text(sort.title)
                }
                .font(BoomFont.button)
                .foregroundStyle(BoomColor.textPrimary)
                .frame(minHeight: BoomMetrics.minimumTarget)
                .contentShape(Rectangle())
            }
            .accessibilityLabel("Sort by")
            .accessibilityValue(sort.title)
            .accessibilityIdentifier("downloads.sort")
            Spacer()
        }
        .padding(.horizontal, BoomMetrics.margin)
        .plainListRow()
        let playable = shown.filter(\.isDownloaded).map(\.song)
        ForEach(shown) { item in
            DownloadSongRow(item: item, mode: rowMode(item.id) { play(item, from: playable) })
                .padding(.horizontal, BoomMetrics.margin)
                .plainListRow()
                .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                    Button(role: .destructive) {
                        store.remove([item.id])
                    } label: {
                        Label("Remove", systemImage: "trash")
                    }
                    .tint(BoomColor.destructive)
                }
        }
    }

    private func play(_ item: DownloadItem, from playable: [Song]) {
        if let index = playable.firstIndex(where: { $0.videoId == item.id }) {
            services.player.play(songs: playable, startAt: index, source: Self.source)
        } else {
            // Not on the phone yet: it plays like any song, over the network.
            services.player.play(item.song)
        }
    }

    private func playAll(shuffled: Bool) {
        let playable = shownSongs.filter(\.isDownloaded).map(\.song)
        guard !playable.isEmpty else { return }
        services.player.play(songs: shuffled ? playable.shuffled() : playable, startAt: 0, source: Self.source)
    }

    static let source = QueueSource(kind: .library, label: "Downloads", title: "Downloads")

    // MARK: Albums and playlists

    @ViewBuilder
    private var collections: some View {
        if store.collections.isEmpty {
            MessageView(
                "No albums or playlists",
                message: "Download a whole album or playlist with the download button beside Play on its page.",
                systemImage: "square.stack"
            )
            .padding(.top, -40)
            .plainListRow()
        }
        ForEach(store.collections) { collection in
            DownloadCollectionRow(collection: collection, mode: rowMode(collection.id) {
                openedCollection = collection.id
            })
            .padding(.horizontal, BoomMetrics.margin)
            .plainListRow()
            // Not on a full swipe: a whole album or playlist goes only on a deliberate tap.
            .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                Button(role: .destructive) {
                    store.removeCollection(collection.id)
                } label: {
                    Label("Remove", systemImage: "trash")
                }
                .tint(BoomColor.destructive)
            }
        }
    }

    // MARK: Selecting

    private func rowMode(_ id: String, onTap: @escaping () -> Void) -> DownloadRowMode {
        switch mode {
        case .browsing: .normal(onTap: onTap)
        case .selecting(let ids): .selecting(isSelected: ids.contains(id), onToggle: { mode = mode.toggling(id) })
        }
    }

    /// The ids that can be picked in the current view.
    private var selectableIDs: Set<String> {
        switch view {
        case .songs: Set(store.items.map(\.id))
        case .collections: Set(store.collections.map(\.id))
        }
    }

    /// The picked ids that still exist (a download may finish or be removed meanwhile).
    private var picked: Set<String> { mode.selection.intersection(selectableIDs) }

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        if mode.isSelecting {
            ToolbarItem(placement: .topBarLeading) {
                let all = selectableIDs
                let isAll = !all.isEmpty && picked == all
                Button(isAll ? "Deselect all" : "Select all") { mode = .selecting(isAll ? [] : all) }
                    .accessibilityIdentifier("downloads.selectAll")
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button("Done") { mode = .browsing }
                    .fontWeight(.semibold)
                    .accessibilityIdentifier("downloads.done")
            }
        } else if !store.items.isEmpty {
            ToolbarItem(placement: .topBarTrailing) {
                Button("Select") { mode = .selecting([]) }
                    .accessibilityIdentifier("downloads.select")
            }
        }
    }

    private var freedBySelection: Int64 {
        switch view {
        case .songs: DownloadsMath.freedBytes(songs: picked, in: store)
        case .collections: DownloadsMath.freedBytes(collections: picked, in: store)
        }
    }

    private var selectionBar: some View {
        let count = picked.count
        let noun = view == .songs ? (count == 1 ? "song" : "songs") : (count == 1 ? "album or playlist" : "albums and playlists")
        return HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(count == 0 ? "Pick what to remove" : "\(count) \(noun) selected")
                    .font(BoomFont.itemTitle)
                    .foregroundStyle(BoomColor.textPrimary)
                Text(count == 0 ? "Tap rows to select them" : "Frees \(DownloadSize.text(freedBySelection))")
                    .font(BoomFont.itemSubtitle.monospacedDigit())
                    .foregroundStyle(BoomColor.textSecondary)
                    .accessibilityIdentifier("downloads.frees")
            }
            Spacer(minLength: 0)
            Button(role: .destructive) {
                question = .deleteSelection
            } label: {
                Text("Delete (\(count))")
                    .font(BoomFont.button)
                    .foregroundStyle(count == 0 ? BoomColor.textTertiary : Color.white)
                    .padding(.horizontal, 18)
                    .frame(minHeight: 40)
                    .background(count == 0 ? BoomColor.surfaceRaised : BoomColor.destructive, in: Capsule())
                    .frame(minHeight: BoomMetrics.minimumTarget)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(count == 0)
            .accessibilityIdentifier("downloads.delete")
            .confirmationDialog(
                "Remove \(count) \(noun)?", isPresented: isAsking(.deleteSelection), titleVisibility: .visible
            ) {
                Button("Remove \(count)", role: .destructive) { deleteSelection() }
            } message: {
                Text("This frees \(DownloadSize.text(freedBySelection)). They can be downloaded again later.")
            }
        }
        .padding(.horizontal, BoomMetrics.margin)
        .padding(.vertical, 10)
        .background(BoomColor.surface)
        .overlay(alignment: .top) { BoomColor.separator.frame(height: 0.5) }
    }

    private func deleteSelection() {
        let ids = picked
        switch view {
        case .songs:
            store.remove(ids)
        case .collections:
            for id in ids { store.removeCollection(id) }
        }
        mode = .browsing
    }

    // MARK: Remove all

    private var removeAllButton: some View {
        Button(role: .destructive) {
            question = .removeAll
        } label: {
            Text("Remove all downloads").foregroundStyle(BoomColor.destructive)
        }
            .buttonStyle(OutlinePillButtonStyle())
            .accessibilityIdentifier("downloads.removeAll")
            .confirmationDialog(
                "Remove all downloads?", isPresented: isAsking(.removeAll), titleVisibility: .visible
            ) {
                Button("Remove all", role: .destructive) {
                    // Asked once more, in its own alert, once this one has gone.
                    Task {
                        try? await Task.sleep(for: .milliseconds(400))
                        question = .removeAllAgain
                    }
                }
            } message: {
                Text("Every downloaded song, album and playlist leaves this phone.")
            }
    }
}

extension View {
    /// A list row with no insets, separator or background of its own: the page draws its own rows.
    func plainListRow() -> some View {
        listRowInsets(EdgeInsets())
            .listRowSeparator(.hidden)
            .listRowBackground(BoomColor.background)
    }
}
