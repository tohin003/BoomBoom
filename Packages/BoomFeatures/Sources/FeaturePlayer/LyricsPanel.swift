import BoomDesign
import BoomModels
import BoomPlayer
import SwiftUI

/// The "Lyrics" tab: plain lyrics as large text, or timed lyrics that follow the song.
struct LyricsPanel: View {
    @Environment(AppServices.self) private var services
    @Environment(PlayerExtras.self) private var extras

    var body: some View {
        let player = services.player
        let song = player.current?.videoId
        Group {
            switch extras.lyrics(for: song) {
            case .loading:
                ProgressView().tint(BoomColor.textSecondary).frame(maxWidth: .infinity, maxHeight: .infinity)
            case .failed:
                PanelMessage(systemImage: "wifi.exclamationmark", text: "Couldn't load the lyrics") {
                    Task { await extras.loadLyrics(player) }
                }
            case .loaded(nil):
                PanelMessage(systemImage: "text.quote", text: "No lyrics for this song")
            case .loaded(let lyrics?):
                if lyrics.lines.isEmpty {
                    PanelMessage(systemImage: "text.quote", text: "No lyrics for this song")
                } else if lyrics.isTimed {
                    TimedLyrics(lyrics: lyrics, player: player)
                } else {
                    PlainLyrics(lyrics: lyrics)
                }
            }
        }
        .task(id: song) { await extras.loadLyrics(player) }
    }
}

private struct PlainLyrics: View {
    let lyrics: Lyrics

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Text(lyrics.lines.map(\.text).joined(separator: "\n"))
                    .font(.system(.title3, weight: .semibold))
                    .lineSpacing(8)
                    .foregroundStyle(BoomColor.textPrimary)
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
                SourceLine(source: lyrics.source)
            }
            .padding(.horizontal, BoomMetrics.margin + 4)
            .padding(.vertical, 20)
        }
        .scrollIndicators(.hidden)
    }
}

private struct SourceLine: View {
    let source: String?

    var body: some View {
        if let source {
            Text(source)
                .font(BoomFont.caption)
                .foregroundStyle(BoomColor.textTertiary)
                .padding(.top, 28)
                .padding(.bottom, 40)
        }
    }
}

/// Timed lyrics: the current line lit, held in the upper third, a tap on a line seeks there. Scrolling by hand
/// pauses the following for a few seconds.
private struct TimedLyrics: View {
    let lyrics: Lyrics
    let player: PlayerStore

    @State private var activeLine: Int?
    @State private var follow = Follow.following

    /// Whether the list keeps the current line in view.
    private enum Follow: Equatable {
        case following
        /// The listener's finger, or the fling it left, is moving the list.
        case scrolledByHand
        /// The listener stopped scrolling at `since`; following starts again a few seconds later.
        case resting(since: Date)

        /// Whether a new line should be scrolled to now.
        func allowsScroll(now: Date) -> Bool {
            switch self {
            case .following: true
            case .scrolledByHand: false
            case .resting(let since): now.timeIntervalSince(since) > 3
            }
        }
    }

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 18) {
                    ForEach(Array(lyrics.lines.enumerated()), id: \.offset) { index, line in
                        lineView(index: index, line: line)
                            .id(index)
                    }
                    SourceLine(source: lyrics.source)
                }
                .padding(.horizontal, BoomMetrics.margin + 4)
                .padding(.top, 24)
                .padding(.bottom, 240)
            }
            .scrollIndicators(.hidden)
            .onScrollPhaseChange { _, phase in
                switch phase {
                case .interacting, .decelerating:
                    follow = .scrolledByHand
                default:
                    // Our own animated `scrollTo` passes through here too; it only ends a scroll by hand.
                    if follow == .scrolledByHand { follow = .resting(since: .now) }
                }
            }
            .background {
                // Watches the clock in its own small view so the lyrics redraw only when the line changes.
                LineTracker(lyrics: lyrics, clock: player.clock, activeLine: $activeLine)
            }
            .onChange(of: activeLine) { _, line in
                guard let line, follow.allowsScroll(now: .now) else { return }
                follow = .following
                withAnimation(.spring(duration: 0.55, bounce: 0)) {
                    proxy.scrollTo(line, anchor: UnitPoint(x: 0.5, y: 0.3))
                }
            }
            .onAppear {
                if let activeLine { proxy.scrollTo(activeLine, anchor: UnitPoint(x: 0.5, y: 0.3)) }
            }
        }
    }

    private func lineView(index: Int, line: Lyrics.Line) -> some View {
        let isActive = index == activeLine
        let isPast = activeLine.map { index < $0 } ?? false
        return Button {
            if let start = line.start {
                follow = .following
                player.seek(to: start)
                if player.status == .paused { player.resume() }
            }
        } label: {
            Text(line.text.isEmpty ? "♪" : line.text)
                .font(.system(.title2, weight: .bold))
                .foregroundStyle(
                    isActive ? BoomColor.textPrimary : (isPast ? BoomColor.textTertiary : Color.white.opacity(0.38)))
                .scaleEffect(isActive ? 1.0 : 0.96, anchor: .leading)
                .frame(maxWidth: .infinity, alignment: .leading)
                .multilineTextAlignment(.leading)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .animation(.easeOut(duration: 0.25), value: isActive)
        .accessibilityHint(line.start == nil ? "" : "Plays from this line")
    }
}

/// Finds the line the clock is in. Draws nothing.
private struct LineTracker: View {
    let lyrics: Lyrics
    let clock: PlaybackClock
    @Binding var activeLine: Int?

    var body: some View {
        let line = Self.line(at: clock.position, in: lyrics.lines)
        Color.clear
            .onChange(of: line, initial: true) { _, line in activeLine = line }
    }

    static func line(at position: TimeInterval, in lines: [Lyrics.Line]) -> Int? {
        // Lines are in time order; the active one is the last that has started.
        var low = 0, high = lines.count - 1, found: Int?
        while low <= high {
            let middle = (low + high) / 2
            if let start = lines[middle].start, start <= position + 0.15 {
                found = middle
                low = middle + 1
            } else if lines[middle].start == nil {
                // An untimed line among timed ones: search linearly around it.
                return lines.lastIndex { ($0.start ?? .infinity) <= position + 0.15 }
            } else {
                high = middle - 1
            }
        }
        return found
    }
}
