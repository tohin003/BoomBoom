import BoomModels
import BoomPlayer
import SwiftUI

/// How the songs of a shelf play when tapped.
public enum ShelfSongMode: Equatable, Sendable {
    /// Each song plays alone with its radio (Home, search).
    case radio
    /// The shelf is a tracklist: a tap plays the shelf's songs from that one ("Top songs" on an artist).
    case tracklist(QueueSource?)
}

/// A `Shelf`, drawn by its `layout`, under a header with an optional strapline, round picture and "More" pill.
public struct ShelfView: View {
    private let shelf: Shelf
    private let songMode: ShelfSongMode
    private let rowLimit: Int?
    private let onShowAll: ((SearchFilter) -> Void)?

    @Environment(\.boomNavigator) private var navigator

    /// - Parameters:
    ///   - songMode: Whether songs play alone or as the shelf's list.
    ///   - rowLimit: For list layouts, shows at most this many rows and a "Show all" button under them.
    ///   - onShowAll: Applies a search shelf's `moreFilter`. Without it, only `more` endpoints get a button.
    public init(
        _ shelf: Shelf, songMode: ShelfSongMode = .radio, rowLimit: Int? = nil,
        onShowAll: ((SearchFilter) -> Void)? = nil
    ) {
        self.shelf = shelf
        self.songMode = songMode
        self.rowLimit = rowLimit
        self.onShowAll = onShowAll
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            if shelf.layout != .topResult || !shelf.title.isEmpty {
                ShelfHeader(shelf, action: moreAction)
            }
            content
        }
    }

    private var moreAction: (() -> Void)? {
        if let filter = shelf.moreFilter, let onShowAll { return { onShowAll(filter) } }
        if let more = shelf.more {
            let navigator = navigator
            return { navigator.open(more) }
        }
        return nil
    }

    private var songs: [Song] { shelf.items.compactMap(\.song) }

    /// All plain songs ("Top songs"), so the rows need not say "Song" each time.
    private var isOneKind: Bool {
        shelf.items.allSatisfy { $0.song?.kind == .song }
    }

    private var context: PlayContext {
        switch songMode {
        case .radio: .single
        case .tracklist(let source): .list(songs, source: source)
        }
    }

    @ViewBuilder
    private var content: some View {
        switch shelf.layout {
        case .carousel:
            ShelfCarousel(items: shelf.items, context: context)
        case .columns:
            ShelfColumns(items: shelf.items, context: context)
        case .list:
            rows(leading: { _ in .cover }, showsKind: !isOneKind)
        case .ranked:
            rows(leading: { .rank($0 + 1) }, showsKind: false)
        case .grid:
            ShelfGrid(items: shelf.items, context: context)
        case .topResult:
            if let first = shelf.items.first {
                TopResultCard(item: first)
                    .padding(.horizontal, BoomMetrics.margin)
                ForEach(shelf.items.dropFirst()) { item in
                    ItemRow(item, context: context)
                }
            }
        case .text:
            if let text = shelf.text, !text.isEmpty {
                ExpandableText(text)
                    .padding(.horizontal, BoomMetrics.margin)
            }
        }
    }

    @ViewBuilder
    private func rows(leading: @escaping (Int) -> RowLeading, showsKind: Bool) -> some View {
        let limit = rowLimit ?? shelf.items.count
        let shown = Array(shelf.items.prefix(limit).enumerated())
        VStack(spacing: 4) {
            ForEach(shown, id: \.element.id) { index, item in
                ItemRow(item, leading: leading(index), context: context, showsKind: showsKind)
            }
        }
        if let rowLimit, shelf.items.count > rowLimit || shelf.more != nil, let moreAction {
            Button("Show all", action: moreAction)
                .buttonStyle(OutlinePillButtonStyle())
                .padding(.horizontal, BoomMetrics.margin)
        }
    }
}

/// A shelf's header: strapline, round picture, title and the "More" pill.
public struct ShelfHeader: View {
    private let title: String
    private let strapline: String?
    private let artwork: Artwork?
    private let action: (() -> Void)?

    public init(_ shelf: Shelf, action: (() -> Void)?) {
        self.init(title: shelf.title, strapline: shelf.strapline, artwork: shelf.headerArtwork, action: action)
    }

    public init(title: String, strapline: String? = nil, artwork: Artwork? = nil, action: (() -> Void)? = nil) {
        self.title = title
        self.strapline = strapline
        self.artwork = artwork
        self.action = action
    }

    public var body: some View {
        HStack(alignment: .center, spacing: 12) {
            if let artwork {
                ArtworkView(artwork, width: 48, isCircle: true)
            }
            VStack(alignment: .leading, spacing: 2) {
                if let strapline, !strapline.isEmpty {
                    Text(strapline.uppercased())
                        .font(BoomFont.strapline)
                        .foregroundStyle(BoomColor.textSecondary)
                        .lineLimit(1)
                }
                Text(title)
                    .font(BoomFont.shelfTitle)
                    .foregroundStyle(BoomColor.textPrimary)
                    .lineLimit(2)
            }
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(.isHeader)
            .accessibilityIdentifier("shelf.header")
            Spacer(minLength: 8)
            if let action {
                Button("More", action: action)
                    .buttonStyle(OutlinePillButtonStyle(isCompact: true))
                    .accessibilityLabel("More \(title)")
            }
        }
        .padding(.horizontal, BoomMetrics.margin)
    }
}

/// Side-scrolling cards.
struct ShelfCarousel: View {
    var items: [MusicItem]
    var context: PlayContext

    var body: some View {
        ScrollView(.horizontal) {
            LazyHStack(alignment: .top, spacing: 12) {
                ForEach(items) { item in
                    ItemCard(item, context: context)
                }
            }
            .scrollTargetLayout()
        }
        .contentMargins(.horizontal, BoomMetrics.margin, for: .scrollContent)
        .scrollTargetBehavior(.viewAligned)
        .scrollIndicators(.hidden)
    }
}

/// "Quick picks": rows in columns of four that scroll sideways and snap to a column.
struct ShelfColumns: View {
    var items: [MusicItem]
    var context: PlayContext

    private var columns: [[MusicItem]] {
        stride(from: 0, to: items.count, by: 4).map { Array(items[$0..<min($0 + 4, items.count)]) }
    }

    var body: some View {
        ScrollView(.horizontal) {
            LazyHStack(alignment: .top, spacing: 0) {
                ForEach(columns, id: \.first?.id) { column in
                    VStack(spacing: 4) {
                        ForEach(column) { item in
                            ItemRow(item, context: context, showsKind: false)
                                .environment(\.boomRowInset, 0)
                                .padding(.trailing, 14)
                        }
                    }
                    .containerRelativeFrame(.horizontal) { width, _ in (width * 0.88).rounded() }
                }
            }
            .scrollTargetLayout()
        }
        .contentMargins(.horizontal, BoomMetrics.margin, for: .scrollContent)
        .scrollTargetBehavior(.viewAligned)
        .scrollIndicators(.hidden)
    }
}

/// Cards two across.
struct ShelfGrid: View {
    var items: [MusicItem]
    var context: PlayContext
    @State private var width: CGFloat = 0

    var body: some View {
        let spacing: CGFloat = 12
        let cover = max(80, ((width - BoomMetrics.margin * 2 - spacing) / 2).rounded(.down))
        LazyVGrid(
            columns: [GridItem(.fixed(cover), spacing: spacing, alignment: .top), GridItem(.fixed(cover))],
            alignment: .leading, spacing: 20
        ) {
            ForEach(items) { item in
                ItemCard(item, width: item.isVideo ? cover * 9 / 16 : cover, context: context)
            }
        }
        .padding(.horizontal, BoomMetrics.margin)
        .frame(maxWidth: .infinity, alignment: .leading)
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
        .opacity(width > 0 ? 1 : 0)
    }
}

/// The big "Top result" card in search: picture, name, kind, and Play with Radio or Shuffle.
public struct TopResultCard: View {
    private let item: MusicItem

    @Environment(AppServices.self) private var services
    @Environment(\.boomNavigator) private var navigator
    @State private var likeOverride: LikeStatus?

    public init(item: MusicItem) {
        self.item = item
    }

    private var actions: ItemActions { ItemActions(services: services, navigator: navigator) }

    public var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Button {
                actions.tap(item)
            } label: {
                HStack(spacing: 14) {
                    ArtworkView(
                        item.artwork, width: item.isVideo ? 128 : 80, height: 80,
                        cornerRadius: BoomMetrics.cardRadius, isCircle: item.isArtist)
                    VStack(alignment: .leading, spacing: 4) {
                        HStack(spacing: 6) {
                            Text(item.title)
                                .font(.system(.title3, weight: .bold))
                                .foregroundStyle(BoomColor.textPrimary)
                                .lineLimit(2)
                            if item.isExplicit { ExplicitBadge() }
                        }
                        Text(item.subtitle)
                            .font(BoomFont.itemSubtitle)
                            .foregroundStyle(BoomColor.textSecondary)
                            .lineLimit(2)
                    }
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityElement(children: .combine)

            HStack(spacing: 10) {
                switch item {
                case .artist:
                    Button { actions.play(item, shuffled: true) } label: {
                        Label("Shuffle", systemImage: "shuffle").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(FilledPillButtonStyle())
                    Button { actions.startRadio(item) } label: {
                        Label("Radio", systemImage: "dot.radiowaves.left.and.right").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(TonalPillButtonStyle())
                case .song:
                    Button { actions.play(item) } label: {
                        Label("Play", systemImage: "play.fill").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(FilledPillButtonStyle())
                    Button { actions.enqueue(item, next: true) } label: {
                        Label("Play next", systemImage: "text.line.first.and.arrowtriangle.forward")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(TonalPillButtonStyle())
                case .album, .playlist:
                    Button { actions.play(item) } label: {
                        Label("Play", systemImage: "play.fill").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(FilledPillButtonStyle())
                    Button { actions.play(item, shuffled: true) } label: {
                        Label("Shuffle", systemImage: "shuffle").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(TonalPillButtonStyle())
                }
            }
            .labelStyle(.titleAndIcon)
        }
        .padding(14)
        .background(BoomColor.surface, in: RoundedRectangle(cornerRadius: BoomMetrics.cardRadius))
        .itemContextMenu(item, likeOverride: $likeOverride)
    }
}

/// Text that shows a few lines and opens fully on "More".
public struct ExpandableText: View {
    private let text: String
    private let collapsedLines: Int
    @State private var isExpanded = false

    public init(_ text: String, collapsedLines: Int = 3) {
        self.text = text
        self.collapsedLines = collapsedLines
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(text)
                .font(BoomFont.itemSubtitle)
                .foregroundStyle(BoomColor.textSecondary)
                .lineLimit(isExpanded ? nil : collapsedLines)
                .frame(maxWidth: .infinity, alignment: .leading)
            Button(isExpanded ? "Less" : "More") {
                withAnimation(.easeInOut(duration: 0.2)) { isExpanded.toggle() }
            }
            .font(BoomFont.button)
            .foregroundStyle(BoomColor.textPrimary)
            .frame(minHeight: BoomMetrics.minimumTarget)
            .accessibilityLabel(isExpanded ? "Show less" : "Show more")
        }
    }
}

extension EnvironmentValues {
    /// The side padding of `ItemRow`s. Containers that pad themselves set it to 0.
    @Entry public var boomRowInset: CGFloat = BoomMetrics.margin
}
