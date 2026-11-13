import BoomModels
import BoomPlayer
import SwiftUI

/// A carousel card: a cover (square, wide for videos, round for artists) over two lines of title and two of
/// subtitle. Tapping follows `ItemActions.tap(_:context:)`; long press shows `ItemMenu`.
public struct ItemCard: View {
    private let item: MusicItem
    private let width: CGFloat
    private let context: PlayContext

    @Environment(AppServices.self) private var services
    @Environment(\.boomNavigator) private var navigator
    @State private var likeOverride: LikeStatus?

    /// - Parameter width: The cover's width for square items; videos get 16:9 of the same height. Defaults to
    ///   `BoomMetrics.cardCover`.
    public init(_ item: MusicItem, width: CGFloat = BoomMetrics.cardCover, context: PlayContext = .single) {
        self.item = item
        self.width = width
        self.context = context
    }

    /// The card's full width for a given cover size, so carousels can size their pages.
    public static func cardWidth(for item: MusicItem, cover: CGFloat = BoomMetrics.cardCover) -> CGFloat {
        item.isVideo ? (cover * 16 / 9).rounded() : cover
    }

    public var body: some View {
        let coverWidth = Self.cardWidth(for: item, cover: width)
        let isCurrent = item.song.map { $0.videoId == services.player.current?.videoId } ?? false
        Button {
            ItemActions(services: services, navigator: navigator).tap(item, context: context)
        } label: {
            VStack(alignment: item.isArtist ? .center : .leading, spacing: 8) {
                ArtworkView(
                    item.artwork, width: coverWidth, height: width,
                    cornerRadius: BoomMetrics.cardRadius, isCircle: item.isArtist
                )
                .overlay {
                    if isCurrent {
                        ZStack {
                            Color.black.opacity(0.45)
                            NowPlayingBars(isAnimating: services.player.isPlayingOrAboutTo)
                                .frame(width: 26, height: 22)
                        }
                        .clipShape(RoundedRectangle(cornerRadius: BoomMetrics.cardRadius))
                    }
                }
                VStack(alignment: item.isArtist ? .center : .leading, spacing: 2) {
                    HStack(alignment: .firstTextBaseline, spacing: 6) {
                        if item.isExplicit { ExplicitBadge() }
                        Text(item.title)
                            .font(BoomFont.itemTitle)
                            .foregroundStyle(isCurrent ? BoomColor.accent : BoomColor.textPrimary)
                            .lineLimit(2)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    Text(item.isArtist ? (artistDetail ?? "") : item.subtitle)
                        .font(BoomFont.itemSubtitle)
                        .foregroundStyle(BoomColor.textSecondary)
                        .lineLimit(2)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .multilineTextAlignment(item.isArtist ? .center : .leading)
                .frame(width: coverWidth, alignment: item.isArtist ? .center : .leading)
            }
            .frame(width: coverWidth, alignment: .top)
            .contentShape(Rectangle())
        }
        .buttonStyle(CardButtonStyle())
        .accessibilityElement(children: .combine)
        .accessibilityHint(item.song == nil ? "Opens" : "Plays")
        .accessibilityIdentifier("card.\(item.accessibilityKind)")
        .itemContextMenu(item, likeOverride: $likeOverride)
    }

    private var artistDetail: String? {
        if case .artist(let artist) = item { return artist.detailText }
        return nil
    }
}

/// Cards shrink a touch while pressed.
struct CardButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .opacity(configuration.isPressed ? 0.85 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}
