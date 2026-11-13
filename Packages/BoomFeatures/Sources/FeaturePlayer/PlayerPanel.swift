import BoomDesign
import BoomModels
import BoomPlayer
import SwiftUI

/// What the "Lyrics" and "Related" tabs fetched for a song, kept while the player is open so switching tabs does
/// not fetch again.
@MainActor
@Observable
final class PlayerExtras {
    /// One tab's fetch and the song (video id) it is for. Changed only in `load`.
    enum Load<Value> {
        case idle
        /// `ticket` tells this request from a later one for the same tab, which replaces it.
        case loading(song: String?, ticket: Int)
        case loaded(song: String?, Value)
        case failed(song: String?)

        /// What the tab shows while `song` is playing. Content for another song is never shown: it reads as loading
        /// until this song's own arrives.
        func shown(for song: String?) -> Shown<Value> {
            switch self {
            case .loaded(let owner, let value) where owner == song: .loaded(value)
            case .failed(let owner) where owner == song: .failed
            case .idle, .loading, .loaded, .failed: .loading
            }
        }
    }

    enum Shown<Value> {
        case loading
        case loaded(Value)
        case failed
    }

    private var lyricsLoad: Load<Lyrics?> = .idle
    private var relatedLoad: Load<[Shelf]> = .idle
    @ObservationIgnored private var tickets = 0

    func lyrics(for song: String?) -> Shown<Lyrics?> { lyricsLoad.shown(for: song) }
    func related(for song: String?) -> Shown<[Shelf]> { relatedLoad.shown(for: song) }

    /// Fetches the current song's lyrics unless they are already here. Also the "Try again" action.
    func loadLyrics(_ player: PlayerStore) async {
        await load(\.lyricsLoad, song: player.current?.videoId) { try await player.lyrics() }
    }

    func loadRelated(_ player: PlayerStore) async {
        await load(\.relatedLoad, song: player.current?.videoId) { try await player.related() }
    }

    private func load<Value>(
        _ slot: ReferenceWritableKeyPath<PlayerExtras, Load<Value>>, song: String?,
        fetch: () async throws -> Value
    ) async {
        if case .loaded(let owner, _) = self[keyPath: slot], owner == song { return }
        tickets += 1
        let ticket = tickets
        self[keyPath: slot] = .loading(song: song, ticket: ticket)
        let outcome: Load<Value>
        do {
            outcome = .loaded(song: song, try await fetch())
        } catch {
            // Given up because the tab closed or the song changed, not failed: the next look fetches again.
            outcome = Task.isCancelled || error is CancellationError ? .idle : .failed(song: song)
        }
        // Only the latest request may land, so a slow answer (or failure) for an earlier song or try is dropped.
        guard case .loading(_, ticket) = self[keyPath: slot] else { return }
        self[keyPath: slot] = outcome
    }
}

/// The pulled-up panel over the player with "Up next", "Lyrics" and "Related".
struct PanelHost: View {
    @Environment(OverlayModel.self) private var model
    @State private var drag: CGFloat = 0

    var body: some View {
        ZStack(alignment: .bottom) {
            if let tab = model.panel {
                Color.black.opacity(0.35 * (1 - min(drag / 400, 1)))
                    .ignoresSafeArea()
                    .onTapGesture { model.setPanel(nil) }
                    .transition(.opacity)
                PanelSheet(tab: tab, onHeaderDrag: headerDrag)
                    .offset(y: max(drag, 0))
                    .transition(.move(edge: .bottom))
            }
        }
        .onChange(of: model.panel) { _, panel in
            if panel != nil { drag = 0 }
        }
    }

    private var headerDrag: AnyGesture<Void> {
        AnyGesture(
            DragGesture(minimumDistance: 6, coordinateSpace: .global)
                .onChanged { value in
                    let dy = value.translation.height
                    drag = dy >= 0 ? dy : -pow(-dy, 0.6)
                }
                .onEnded { value in
                    if value.translation.height > 120 || value.predictedEndTranslation.height > 320 {
                        model.setPanel(nil)
                    } else {
                        withAnimation(.spring(duration: 0.35, bounce: 0.2)) { drag = 0 }
                    }
                }
                .map { _ in () })
    }
}

private struct PanelSheet: View {
    let tab: NowPlayingPanel
    let onHeaderDrag: AnyGesture<Void>

    @Environment(OverlayModel.self) private var model

    var body: some View {
        VStack(spacing: 0) {
            VStack(spacing: 0) {
                Capsule()
                    .fill(Color.white.opacity(0.3))
                    .frame(width: 36, height: 5)
                    .padding(.top, 8)
                    .padding(.bottom, 4)
                    .accessibilityHidden(true)
                PanelNowPlayingRow()
                    .padding(.horizontal, BoomMetrics.margin)
                    .padding(.vertical, 6)
                tabs
            }
            .contentShape(Rectangle())
            .gesture(onHeaderDrag)

            Rectangle().fill(BoomColor.separator).frame(height: 1)

            Group {
                switch tab {
                case .upNext: UpNextView()
                case .lyrics: LyricsPanel()
                case .related: RelatedPanel()
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .background {
            UnevenRoundedRectangle(topLeadingRadius: 16, topTrailingRadius: 16, style: .continuous)
                .fill(BoomColor.surface)
                .ignoresSafeArea(edges: .bottom)
                .shadow(color: .black.opacity(0.4), radius: 20, y: -4)
        }
        .padding(.top, 8)
    }

    private var tabs: some View {
        HStack(spacing: 0) {
            ForEach(NowPlayingPanel.allCases, id: \.self) { item in
                Button {
                    model.setPanel(item)
                } label: {
                    Text(item.title)
                        .font(.system(.footnote, weight: .bold))
                        .kerning(0.6)
                        .foregroundStyle(item == tab ? BoomColor.textPrimary : BoomColor.textTertiary)
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .overlay(alignment: .bottom) {
                            if item == tab {
                                Capsule()
                                    .fill(BoomColor.accent)
                                    .frame(width: 44, height: 3)
                                    .matchedGeometryEffect(id: "underline", in: underline)
                            }
                        }
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(item == tab ? .isSelected : [])
            }
        }
        .padding(.horizontal, BoomMetrics.margin)
    }

    @Namespace private var underline
}

/// The song and a play button at the top of the panel, so the player stays in reach while the panel covers it.
private struct PanelNowPlayingRow: View {
    @Environment(AppServices.self) private var services

    var body: some View {
        let player = services.player
        HStack(spacing: 12) {
            ArtworkView(player.current?.artwork ?? .empty, width: 44)
            VStack(alignment: .leading, spacing: 2) {
                Text(player.current?.title ?? "")
                    .font(BoomFont.itemTitle)
                    .foregroundStyle(BoomColor.textPrimary)
                    .lineLimit(1)
                Text(player.current?.artistLine ?? "")
                    .font(BoomFont.itemSubtitle)
                    .foregroundStyle(BoomColor.textSecondary)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
            IconButton(
                systemName: player.isPlayingOrAboutTo ? "pause.fill" : "play.fill",
                label: player.isPlayingOrAboutTo ? "Pause" : "Play", size: 22
            ) { player.togglePlayPause() }
                .contentTransition(.symbolEffect(.replace))
            IconButton(systemName: "forward.end.fill", label: "Next", size: 20) { player.next() }
                .disabled(!player.hasNext)
                .opacity(player.hasNext ? 1 : 0.4)
        }
    }
}

/// A centred message for an empty or failed tab.
struct PanelMessage: View {
    let systemImage: String
    let text: String
    var retry: (() -> Void)?

    var body: some View {
        VStack(spacing: 14) {
            Image(systemName: systemImage)
                .font(.system(size: 36))
                .foregroundStyle(BoomColor.textTertiary)
            Text(text)
                .font(BoomFont.itemTitle)
                .foregroundStyle(BoomColor.textSecondary)
                .multilineTextAlignment(.center)
            if let retry {
                Button("Try again", action: retry)
                    .font(BoomFont.button)
                    .foregroundStyle(BoomColor.background)
                    .padding(.horizontal, 18)
                    .frame(minHeight: 36)
                    .background(BoomColor.textPrimary, in: Capsule())
                    .frame(minHeight: BoomMetrics.minimumTarget)
            }
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
