import BoomDesign
import BoomModels
import BoomPlayer
import SwiftUI

/// The "Related" tab: YouTube Music's shelves for the song, drawn simply.
struct RelatedPanel: View {
    @Environment(AppServices.self) private var services
    @Environment(PlayerExtras.self) private var extras

    var body: some View {
        let player = services.player
        let song = player.current?.videoId
        Group {
            switch extras.related(for: song) {
            case .loading:
                ProgressView().tint(BoomColor.textSecondary).frame(maxWidth: .infinity, maxHeight: .infinity)
            case .failed:
                PanelMessage(systemImage: "wifi.exclamationmark", text: "Couldn't load related music") {
                    Task { await extras.loadRelated(player) }
                }
            case .loaded(let shelves):
                if shelves.isEmpty {
                    PanelMessage(systemImage: "square.stack", text: "Nothing related to show")
                } else {
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: BoomMetrics.shelfSpacing) {
                            ForEach(shelves) { shelf in
                                RelatedShelf(shelf: shelf)
                            }
                        }
                        .padding(.vertical, 20)
                        .padding(.bottom, 24)
                    }
                    .scrollIndicators(.hidden)
                }
            }
        }
        .task(id: song) { await extras.loadRelated(player) }
    }
}

private struct RelatedShelf: View {
    let shelf: Shelf

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                if let strapline = shelf.strapline {
                    Text(strapline.uppercased())
                        .font(BoomFont.strapline)
                        .foregroundStyle(BoomColor.textTertiary)
                }
                Text(shelf.title)
                    .font(BoomFont.shelfTitle)
                    .foregroundStyle(BoomColor.textPrimary)
            }
            .padding(.horizontal, BoomMetrics.margin)
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(.isHeader)

            switch shelf.layout {
            case .text:
                if let text = shelf.text {
                    Text(text)
                        .font(BoomFont.itemSubtitle)
                        .foregroundStyle(BoomColor.textSecondary)
                        .padding(.horizontal, BoomMetrics.margin)
                }
            case .carousel, .grid:
                ScrollView(.horizontal) {
                    LazyHStack(alignment: .top, spacing: 12) {
                        ForEach(shelf.items) { item in RelatedCard(item: item) }
                    }
                    .padding(.horizontal, BoomMetrics.margin)
                }
                .scrollIndicators(.hidden)
            case .columns:
                RelatedColumns(items: shelf.items)
            case .list, .ranked, .topResult:
                VStack(spacing: 0) {
                    ForEach(shelf.items) { item in RelatedRow(item: item) }
                }
                .padding(.horizontal, BoomMetrics.margin)
            }
        }
    }
}

/// Rows four to a column, columns side by side ("Quick picks" style).
private struct RelatedColumns: View {
    let items: [MusicItem]

    var body: some View {
        let columns = stride(from: 0, to: items.count, by: 4).map { Array(items[$0..<min($0 + 4, items.count)]) }
        ScrollView(.horizontal) {
            LazyHStack(alignment: .top, spacing: 16) {
                ForEach(columns.indices, id: \.self) { index in
                    VStack(spacing: 0) {
                        ForEach(columns[index]) { item in RelatedRow(item: item) }
                    }
                    .containerRelativeFrame(.horizontal) { width, _ in width - 2 * BoomMetrics.margin - 28 }
                }
            }
            .scrollTargetLayout()
            .padding(.horizontal, BoomMetrics.margin)
        }
        .scrollTargetBehavior(.viewAligned)
        .scrollIndicators(.hidden)
    }
}

/// Plays a song, or leaves the player for an album, artist or playlist.
private struct ItemAction: ViewModifier {
    let item: MusicItem
    @Environment(AppServices.self) private var services
    @Environment(OverlayModel.self) private var model
    @Environment(\.boomNavigator) private var navigator

    func body(content: Content) -> some View {
        Button {
            switch item {
            case .song(let song):
                services.player.play(song)
            default:
                guard let endpoint = BrowseEndpoint(item) else { return }
                model.dismiss()
                navigator.open(endpoint)
            }
        } label: {
            content.contentShape(Rectangle())
        }
        .buttonStyle(PressScaleStyle(scale: 0.97))
    }
}

private struct RelatedCard: View {
    let item: MusicItem

    var body: some View {
        let isArtist = if case .artist = item { true } else { false }
        let width: CGFloat = 140
        VStack(alignment: isArtist ? .center : .leading, spacing: 6) {
            ArtworkView(
                item.artwork, width: width, height: isVideo ? width * 9 / 16 : width,
                cornerRadius: BoomMetrics.coverRadius, isCircle: isArtist)
            Text(item.title)
                .font(BoomFont.itemTitle)
                .foregroundStyle(BoomColor.textPrimary)
                .lineLimit(2)
                .multilineTextAlignment(isArtist ? .center : .leading)
            Text(item.subtitle)
                .font(BoomFont.itemSubtitle)
                .foregroundStyle(BoomColor.textSecondary)
                .lineLimit(1)
        }
        .frame(width: width, alignment: isArtist ? .center : .leading)
        .accessibilityElement(children: .combine)
        .modifier(ItemAction(item: item))
    }

    private var isVideo: Bool {
        if case .song(let song) = item { return song.kind == .video }
        return false
    }
}

private struct RelatedRow: View {
    let item: MusicItem

    var body: some View {
        let isArtist = if case .artist = item { true } else { false }
        HStack(spacing: 12) {
            ArtworkView(item.artwork, width: 48, isCircle: isArtist)
            VStack(alignment: .leading, spacing: 2) {
                Text(item.title)
                    .font(BoomFont.itemTitle)
                    .foregroundStyle(BoomColor.textPrimary)
                    .lineLimit(1)
                Text(item.subtitle)
                    .font(BoomFont.itemSubtitle)
                    .foregroundStyle(BoomColor.textSecondary)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.vertical, 6)
        .accessibilityElement(children: .combine)
        .modifier(ItemAction(item: item))
    }
}
