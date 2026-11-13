import BoomModels
import BoomPlayer
import SwiftUI

/// What sits at the front of a row.
public enum RowLeading: Equatable, Sendable {
    /// The item's cover (round for artists, wide for videos).
    case cover
    /// A chart position before the cover.
    case rank(Int)
    /// An album track's number, without a cover.
    case trackNumber(Int)
}

/// One line of a list: a song, an album, an artist or a playlist.
///
/// Tapping follows `ItemActions.tap(_:context:)` unless `onTap` is given. Long press and the trailing ellipsis show
/// `ItemMenu`. While the row's song is the player's current one, the title turns accent and bars move over the cover.
public struct ItemRow: View {
    private let item: MusicItem
    private let leading: RowLeading
    private let context: PlayContext
    private let showsMore: Bool
    private let showsKind: Bool
    private let onTap: (() -> Void)?

    @Environment(AppServices.self) private var services
    @Environment(\.boomNavigator) private var navigator
    @Environment(\.boomRowInset) private var inset
    @State private var likeOverride: LikeStatus?

    /// - Parameters:
    ///   - leading: Cover, chart rank, or album track number.
    ///   - context: How a song plays when tapped: alone with its radio, or as part of a list.
    ///   - showsMore: The trailing ellipsis menu.
    ///   - showsKind: Starts a song's second line with "Song" or "Video". Lists of one kind (charts, playlists)
    ///     leave it out to make room for the artist and plays.
    ///   - onTap: Replaces the standard tap.
    public init(
        _ item: MusicItem, leading: RowLeading = .cover, context: PlayContext = .single, showsMore: Bool = true,
        showsKind: Bool = true, onTap: (() -> Void)? = nil
    ) {
        self.item = item
        self.leading = leading
        self.context = context
        self.showsMore = showsMore
        self.showsKind = showsKind
        self.onTap = onTap
    }

    private var song: Song? { item.song }

    private var isCurrent: Bool {
        guard let song, let current = services.player.current else { return false }
        return current.videoId == song.videoId
    }

    private var isAvailable: Bool { song?.isAvailable ?? true }

    public var body: some View {
        let isCurrent = isCurrent
        HStack(spacing: BoomMetrics.rowSpacing) {
            Button {
                if let onTap {
                    onTap()
                } else {
                    ItemActions(services: services, navigator: navigator).tap(item, context: context)
                }
            } label: {
                HStack(spacing: BoomMetrics.rowSpacing) {
                    leadingView(isCurrent: isCurrent)
                    VStack(alignment: .leading, spacing: 2) {
                        HStack(spacing: 6) {
                            Text(item.title)
                                .font(BoomFont.itemTitle)
                                .foregroundStyle(isCurrent ? BoomColor.accent : BoomColor.textPrimary)
                                .lineLimit(1)
                            if item.isExplicit { ExplicitBadge() }
                        }
                        let download = downloadState
                        if !secondLine.isEmpty || download != nil {
                            HStack(spacing: 5) {
                                DownloadBadge(download)
                                Text(secondLine)
                                    .font(BoomFont.itemSubtitle)
                                    .foregroundStyle(BoomColor.textSecondary)
                                    .lineLimit(1)
                            }
                        }
                    }
                    Spacer(minLength: 0)
                    if case .trackNumber = leading, let duration = song?.duration {
                        Text(boomTime(duration))
                            .font(BoomFont.time)
                            .foregroundStyle(BoomColor.textSecondary)
                    }
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(RowButtonStyle())
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(isCurrent ? [.isSelected] : [])
            .accessibilityHint(song == nil ? "Opens" : "Plays")
            .accessibilityIdentifier("row.\(item.accessibilityKind)")

            if showsMore {
                ItemMoreButton(item, likeOverride: $likeOverride)
                    .padding(.trailing, -10)
            }
        }
        .padding(.horizontal, inset)
        .frame(minHeight: BoomMetrics.rowCover + 8)
        .opacity(isAvailable ? 1 : 0.4)
        .itemContextMenu(item, likeOverride: $likeOverride)
    }

    @ViewBuilder
    private func leadingView(isCurrent: Bool) -> some View {
        switch leading {
        case .cover:
            cover(isCurrent: isCurrent)
        case .rank(let rank):
            Text("\(rank)")
                .font(.system(.headline, weight: .bold).monospacedDigit())
                .foregroundStyle(BoomColor.textPrimary)
                .frame(minWidth: 24)
            cover(isCurrent: isCurrent)
        case .trackNumber(let number):
            ZStack {
                if isCurrent {
                    NowPlayingBars(isAnimating: services.player.isPlayingOrAboutTo, color: BoomColor.accent)
                        .frame(width: 16, height: 14)
                } else {
                    Text("\(number)")
                        .font(BoomFont.itemSubtitle.monospacedDigit())
                        .foregroundStyle(BoomColor.textSecondary)
                }
            }
            .frame(width: 28, height: 44)
        }
    }

    private func cover(isCurrent: Bool) -> some View {
        let size = BoomMetrics.rowCover
        let width = item.isVideo ? size * 16 / 9 : size
        return ArtworkView(item.artwork, width: width, height: size, isCircle: item.isArtist)
            .overlay {
                if isCurrent {
                    ZStack {
                        Color.black.opacity(0.45)
                        NowPlayingBars(isAnimating: services.player.isPlayingOrAboutTo)
                            .frame(width: 18, height: 16)
                    }
                    .clipShape(RoundedRectangle(cornerRadius: BoomMetrics.coverRadius))
                }
            }
    }

    /// Where the song's download stands, or the album's or playlist's as a whole; nil when never asked for.
    private var downloadState: DownloadItem.State? {
        switch item {
        case .song(let song): services.downloads.state(of: song.videoId)
        case .album(let album): services.downloads.collectionState(album.id)
        case .playlist(let playlist): services.downloads.collectionState(playlist.id)
        case .artist: nil
        }
    }

    /// "Song • Artist • 3:45": the item's own line, then plays or the length where known.
    private var secondLine: String {
        guard let song else { return item.subtitle }
        if case .trackNumber = leading {
            return [song.artistLine, song.playsText ?? ""].filter { !$0.isEmpty }.joined(separator: " • ")
        }
        let extra = song.playsText ?? song.duration.map(boomTime) ?? ""
        let first = showsKind ? item.subtitle : song.artistLine
        return [first, extra].filter { !$0.isEmpty }.joined(separator: " • ")
    }
}

extension MusicItem {
    /// "song", "album", "artist" or "playlist": the end of a row's or card's accessibility identifier, so UI tests
    /// can find "a song row" without knowing any title.
    var accessibilityKind: String {
        switch self {
        case .song: "song"
        case .album: "album"
        case .artist: "artist"
        case .playlist: "playlist"
        }
    }
}

/// `ItemRow` for a song.
public struct SongRow: View {
    private let song: Song
    private let leading: RowLeading
    private let context: PlayContext
    private let showsMore: Bool
    private let showsKind: Bool
    private let onTap: (() -> Void)?

    public init(
        _ song: Song, leading: RowLeading = .cover, context: PlayContext = .single, showsMore: Bool = true,
        showsKind: Bool = true, onTap: (() -> Void)? = nil
    ) {
        self.song = song
        self.leading = leading
        self.context = context
        self.showsMore = showsMore
        self.showsKind = showsKind
        self.onTap = onTap
    }

    public var body: some View {
        ItemRow(
            .song(song), leading: leading, context: context, showsMore: showsMore, showsKind: showsKind,
            onTap: onTap)
    }
}

/// The small "E" next to an explicit title.
public struct ExplicitBadge: View {
    public init() {}

    public var body: some View {
        Text("E")
            .font(.system(size: 9, weight: .bold))
            .foregroundStyle(BoomColor.background)
            .frame(width: 13, height: 13)
            .background(BoomColor.textSecondary, in: RoundedRectangle(cornerRadius: 2))
            .accessibilityLabel("Explicit")
    }
}

/// Three bars that bounce while playing and rest when paused.
public struct NowPlayingBars: View {
    private let isAnimating: Bool
    private let color: Color

    public init(isAnimating: Bool, color: Color = .white) {
        self.isAnimating = isAnimating
        self.color = color
    }

    public var body: some View {
        TimelineView(.animation(minimumInterval: 1 / 30, paused: !isAnimating)) { timeline in
            let time = timeline.date.timeIntervalSinceReferenceDate
            GeometryReader { proxy in
                HStack(alignment: .bottom, spacing: proxy.size.width * 0.18) {
                    ForEach(0..<3, id: \.self) { index in
                        let phase = time * (5.2 + Double(index) * 1.7) + Double(index) * 1.3
                        let level = isAnimating ? 0.3 + 0.7 * abs(sin(phase)) : [0.45, 0.8, 0.6][index]
                        Capsule()
                            .fill(color)
                            .frame(height: proxy.size.height * level)
                    }
                }
                .frame(width: proxy.size.width, height: proxy.size.height, alignment: .bottom)
            }
        }
        .accessibilityLabel(isAnimating ? "Now playing" : "Paused")
    }
}

/// Rows darken a little while pressed, like the genre's lists.
struct RowButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background {
                if configuration.isPressed {
                    BoomColor.surfaceRaised.opacity(0.6)
                        .padding(.horizontal, -BoomMetrics.margin)
                        .padding(.vertical, -4)
                }
            }
    }
}
