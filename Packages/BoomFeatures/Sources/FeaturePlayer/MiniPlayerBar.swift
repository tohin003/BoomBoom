import BoomDesign
import BoomModels
import BoomPlayer
import SwiftUI

/// The bar above the tab bar while a song is loaded: cover, title, play/pause and next. Tapping it opens the full
/// player; swiping it sideways skips.
public struct MiniPlayerBar: View {
    private let onExpand: () -> Void

    @Environment(AppServices.self) private var services
    /// How far the song is pushed sideways. Springs back by itself when the finger lifts or the system takes the
    /// touch away.
    @GestureState(resetTransaction: Transaction(animation: .spring(duration: 0.35, bounce: 0.15)))
    private var swipe: CGFloat = 0

    public init(onExpand: @escaping () -> Void) {
        self.onExpand = onExpand
    }

    public var body: some View {
        let player = services.player
        ZStack(alignment: .top) {
            BoomColor.surface
            if let song = player.current {
                HStack(spacing: 12) {
                    HStack(spacing: 12) {
                        ArtworkView(song.artwork, width: 40, cornerRadius: BoomMetrics.coverRadius)
                        VStack(alignment: .leading, spacing: 2) {
                            MarqueeText(text: song.title, font: BoomFont.itemTitle, speed: 26)
                            Text(song.artistLine)
                                .font(BoomFont.itemSubtitle)
                                .foregroundStyle(BoomColor.textSecondary)
                                .lineLimit(1)
                        }
                    }
                    .offset(x: swipe)
                    .opacity(1 - min(abs(swipe) / 220, 0.6))
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(Rectangle())
                    .onTapGesture(perform: onExpand)
                    .accessibilityElement(children: .combine)
                    .accessibilityLabel("\(song.title), \(song.artistLine)")
                    .accessibilityHint("Opens the player")
                    .accessibilityIdentifier("miniplayer.song")
                    .accessibilityAddTraits(.isButton)
                    .accessibilityAction(named: "Next song") { player.next() }
                    .accessibilityAction(named: "Previous song") { player.previous() }

                    MiniPlayButton(player: player)
                    IconButton(systemName: "forward.end.fill", label: "Next", size: 20) { player.next() }
                        .disabled(!player.hasNext)
                        .opacity(player.hasNext ? 1 : 0.4)
                }
                .padding(.leading, 12)
                .padding(.trailing, 4)
                .frame(maxHeight: .infinity)
                MiniProgressLine(clock: player.clock)
            }
        }
        .frame(height: BoomMetrics.miniPlayerHeight)
        .clipped()
        .gesture(swipeGesture(player))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("miniplayer")
    }

    private func swipeGesture(_ player: PlayerStore) -> some Gesture {
        DragGesture(minimumDistance: 14)
            .updating($swipe) { value, swipe, _ in
                let dx = value.translation.width, dy = value.translation.height
                if abs(dx) > abs(dy) { swipe = dx * 0.9 }
            }
            .onEnded { value in
                let dx = value.translation.width, dy = value.translation.height
                if dy < -40, abs(dy) > abs(dx) {
                    onExpand()
                } else if dx < -70 || value.predictedEndTranslation.width < -180 {
                    if player.hasNext { player.next() }
                } else if dx > 70 || value.predictedEndTranslation.width > 180 {
                    player.previous()
                }
            }
    }
}

private struct MiniPlayButton: View {
    let player: PlayerStore

    var body: some View {
        Button { player.togglePlayPause() } label: {
            ZStack {
                if player.status.isWaiting {
                    SpinnerRing(lineWidth: 2.5).frame(width: 34, height: 34)
                }
                Image(systemName: player.isPlayingOrAboutTo ? "pause.fill" : "play.fill")
                    .font(.system(size: 22))
                    .foregroundStyle(BoomColor.textPrimary)
                    .contentTransition(.symbolEffect(.replace))
            }
            .frame(width: BoomMetrics.minimumTarget, height: BoomMetrics.minimumTarget)
            .contentShape(Rectangle())
        }
        .buttonStyle(PressScaleStyle())
        .accessibilityLabel(player.isPlayingOrAboutTo ? "Pause" : "Play")
        // The spinner around the glyph, in words: the song is on its way, not yet heard.
        .accessibilityValue(player.status.isWaiting ? "Loading" : "")
    }
}

/// The 2 pt line along the bar's top edge. The only part of the bar that redraws as time runs.
private struct MiniProgressLine: View {
    let clock: PlaybackClock

    var body: some View {
        Rectangle()
            .fill(BoomColor.separator)
            .frame(height: 2)
            .overlay(alignment: .leading) {
                Rectangle()
                    .fill(BoomColor.accent)
                    .scaleEffect(x: max(clock.progress, 0.001), y: 1, anchor: .leading)
            }
            .accessibilityHidden(true)
    }
}
