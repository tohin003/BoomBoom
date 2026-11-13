import BoomDesign
import BoomModels
import BoomPlayer
import SwiftUI

/// One downloaded album or playlist, from the phone alone: its songs in its own order, Play and Shuffle, swipe to
/// remove a song, and removing the whole of it. Opens with no connection.
struct DownloadedCollectionScreen: View {
    let id: String

    @Environment(AppServices.self) private var services
    @Environment(\.dismiss) private var dismiss
    @State private var isAskingRemove = false

    private var store: DownloadStore { services.downloads }
    private var collection: DownloadCollection? { store.collections.first { $0.id == id } }

    var body: some View {
        Group {
            if let collection {
                list(collection)
            } else {
                MessageView("Removed", message: "This album or playlist is no longer downloaded.",
                            systemImage: "arrow.down.circle")
                    .frame(maxHeight: .infinity, alignment: .top)
            }
        }
        .background(BoomColor.background.ignoresSafeArea())
        .navigationTitle(collection?.title ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(BoomColor.background, for: .navigationBar)
    }

    private func list(_ collection: DownloadCollection) -> some View {
        let songs = store.songs(of: id)
        let playable = songs.filter(\.isDownloaded).map(\.song)
        let source = Self.source(for: collection)
        return List {
            header(collection, songs: songs, playable: playable, source: source)
                .plainListRow()
            ForEach(songs) { item in
                DownloadSongRow(item: item, mode: .normal(onTap: {
                    if let index = playable.firstIndex(where: { $0.videoId == item.id }) {
                        services.player.play(songs: playable, startAt: index, source: source)
                    } else {
                        services.player.play(item.song)
                    }
                }))
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
            removeButton(collection)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 24)
                .plainListRow()
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .environment(\.defaultMinListRowHeight, 0)
    }

    private func header(
        _ collection: DownloadCollection, songs: [DownloadItem], playable: [Song], source: QueueSource
    ) -> some View {
        VStack(spacing: 8) {
            ArtworkView(collection.artwork, width: 180, cornerRadius: BoomMetrics.cardRadius)
                .shadow(color: .black.opacity(0.45), radius: 16, y: 8)
                .padding(.bottom, 8)
            Text(collection.title)
                .font(.system(.title2, weight: .bold))
                .foregroundStyle(BoomColor.textPrimary)
                .multilineTextAlignment(.center)
                .lineLimit(3)
                .accessibilityAddTraits(.isHeader)
            Text([collection.kind == .album ? "Album" : "Playlist", collection.subtitle ?? ""]
                .filter { !$0.isEmpty }.joined(separator: " · "))
                .font(BoomFont.itemSubtitle)
                .foregroundStyle(BoomColor.textSecondary)
            Text(summary(songs))
                .font(BoomFont.itemSubtitle.monospacedDigit())
                .foregroundStyle(BoomColor.textTertiary)
            HStack(spacing: 10) {
                Button {
                    services.player.play(songs: playable, startAt: 0, source: source)
                } label: {
                    Label("Play", systemImage: "play.fill")
                }
                .buttonStyle(FilledPillButtonStyle())
                Button {
                    services.player.play(songs: playable.shuffled(), startAt: 0, source: source)
                } label: {
                    Label("Shuffle", systemImage: "shuffle")
                }
                .buttonStyle(TonalPillButtonStyle())
            }
            .disabled(playable.isEmpty)
            .padding(.top, 6)
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, BoomMetrics.margin)
        .padding(.top, 8)
        .padding(.bottom, 12)
    }

    private func summary(_ songs: [DownloadItem]) -> String {
        let done = songs.filter(\.isDownloaded).count
        let size = DownloadSize.text(store.bytes(of: id))
        guard done < songs.count else { return "\(DownloadsMath.songs(songs.count)) · \(size)" }
        return "\(done) of \(songs.count) downloaded · \(size)"
    }

    private func removeButton(_ collection: DownloadCollection) -> some View {
        Button(role: .destructive) {
            isAskingRemove = true
        } label: {
            Text("Remove download").foregroundStyle(BoomColor.destructive)
        }
        .buttonStyle(OutlinePillButtonStyle())
        .accessibilityIdentifier("downloads.removeCollection")
        .confirmationDialog(
            "Remove “\(collection.title)” from downloads?", isPresented: $isAskingRemove, titleVisibility: .visible
        ) {
            Button("Remove download", role: .destructive) {
                store.removeCollection(collection.id)
                dismiss()
            }
        } message: {
            Text("Songs you also downloaded on their own, or that another download holds, stay.")
        }
    }

    static func source(for collection: DownloadCollection) -> QueueSource {
        switch collection.kind {
        case .album:
            QueueSource(
                kind: .album, label: "Album", title: collection.title,
                endpoint: BrowseEndpoint(browseId: collection.id, pageType: .album))
        case .playlist:
            QueueSource(
                kind: .playlist, label: "Playlist", title: collection.title,
                endpoint: BrowseEndpoint(browseId: collection.id, pageType: .playlist))
        }
    }
}
