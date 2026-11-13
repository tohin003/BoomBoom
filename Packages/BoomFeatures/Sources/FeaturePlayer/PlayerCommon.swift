import BoomDesign
import BoomModels
import BoomPlayer
import SwiftUI

/// A glyph button with a full 44 pt target and a VoiceOver label.
struct IconButton: View {
    let systemName: String
    let label: String
    var size: CGFloat = 22
    var weight: Font.Weight = .regular
    var tint: Color = BoomColor.textPrimary
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: size, weight: weight))
                .foregroundStyle(tint)
                .frame(minWidth: BoomMetrics.minimumTarget, minHeight: BoomMetrics.minimumTarget)
                .contentShape(Rectangle())
        }
        .buttonStyle(PressScaleStyle())
        .accessibilityLabel(label)
    }
}

/// Shrinks and dims a little while pressed, like the player's controls on a phone should.
struct PressScaleStyle: ButtonStyle {
    var scale: CGFloat = 0.88

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? scale : 1)
            .opacity(configuration.isPressed ? 0.7 : 1)
            .animation(.snappy(duration: 0.18), value: configuration.isPressed)
    }
}

/// A turning arc, drawn around a play button while the song loads or buffers.
struct SpinnerRing: View {
    var color: Color = BoomColor.accent
    var lineWidth: CGFloat = 3

    var body: some View {
        TimelineView(.animation) { context in
            let turn = context.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 0.9) / 0.9
            Circle()
                .trim(from: 0, to: 0.28)
                .stroke(color, style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
                .rotationEffect(.degrees(turn * 360))
        }
        .accessibilityHidden(true)
    }
}

/// Three bouncing bars marking the entry that is playing. Still while paused.
struct PlayingBars: View {
    var isPlaying: Bool
    var color: Color = BoomColor.accent

    var body: some View {
        TimelineView(.animation(minimumInterval: 1 / 30, paused: !isPlaying)) { context in
            let time = context.date.timeIntervalSinceReferenceDate
            Canvas { graphics, size in
                let barWidth = size.width / 5
                for bar in 0..<3 {
                    let speed = [5.1, 6.7, 4.3][bar]
                    let level = isPlaying ? 0.35 + 0.65 * abs(sin(time * speed + Double(bar) * 1.3)) : [0.5, 0.8, 0.35][bar]
                    let height = size.height * level
                    let rect = CGRect(
                        x: CGFloat(bar) * barWidth * 2, y: size.height - height, width: barWidth, height: height)
                    graphics.fill(Path(roundedRect: rect, cornerRadius: barWidth / 2), with: .color(color))
                }
            }
        }
        .accessibilityHidden(true)
    }
}

/// One line of text that scrolls sideways when it does not fit, then rests, then scrolls again.
struct MarqueeText: View {
    let text: String
    var font: Font = BoomFont.itemTitle
    var color: Color = BoomColor.textPrimary
    var alignment: Alignment = .leading
    /// Points per second.
    var speed: CGFloat = 32

    @State private var textWidth: CGFloat = 0
    @State private var boxWidth: CGFloat = 0
    @State private var phase: CGFloat = 0
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private let gap: CGFloat = 40
    private var overflows: Bool { textWidth > boxWidth + 1 && boxWidth > 0 }

    var body: some View {
        Text(text)
            .font(font)
            .lineLimit(1)
            .hidden()
            .frame(maxWidth: .infinity, alignment: alignment)
            .onGeometryChange(for: CGFloat.self, of: \.size.width) { boxWidth = $0 }
            .overlay(alignment: overflows && !reduceMotion ? .leading : alignment) {
                if overflows && !reduceMotion {
                    HStack(spacing: gap) {
                        label
                        label
                    }
                    .offset(x: phase)
                    .frame(width: boxWidth, alignment: .leading)
                    .clipped()
                    .mask(fadeMask)
                } else {
                    label.frame(width: max(boxWidth, 1), alignment: alignment)
                }
            }
            .background(alignment: .leading) {
                // Measures the text at its natural width.
                Text(text).font(font).lineLimit(1).fixedSize().hidden()
                    .onGeometryChange(for: CGFloat.self, of: \.size.width) { textWidth = $0 }
            }
            .accessibilityElement()
            .accessibilityLabel(text)
            .task(id: "\(text)|\(overflows)|\(reduceMotion)") { await run() }
    }

    private var label: some View {
        Text(text)
            .font(font)
            .foregroundStyle(color)
            .lineLimit(1)
            .fixedSize()
    }

    private var fadeMask: some View {
        LinearGradient(
            stops: [
                .init(color: .clear, location: 0), .init(color: .black, location: 0.04),
                .init(color: .black, location: 0.92), .init(color: .clear, location: 1),
            ], startPoint: .leading, endPoint: .trailing)
    }

    private func run() async {
        resetPhase()
        guard overflows, !reduceMotion else { return }
        while !Task.isCancelled {
            try? await Task.sleep(for: .seconds(2.5))
            guard !Task.isCancelled else { return }
            let distance = textWidth + gap
            let duration = Double(distance / speed)
            withAnimation(.linear(duration: duration)) { phase = -distance }
            try? await Task.sleep(for: .seconds(duration))
            // The second copy now sits where the first began, so the jump back is invisible.
            resetPhase()
        }
    }

    private func resetPhase() {
        var transaction = Transaction()
        transaction.disablesAnimations = true
        withTransaction(transaction) { phase = 0 }
    }
}

extension View {
    /// Notices a drag the system took away (a call, Control Center): SwiftUI then never calls the gesture's
    /// `onEnded`, but it does put a `@GestureState` back. Pass that state as `isTouching`; `perform` runs a moment
    /// after it falls, by when a normal lift's `onEnded` has already run, so `perform` should act only on a drag
    /// that is still under way.
    func onDragCancel(isTouching: Bool, perform: @escaping @MainActor () -> Void) -> some View {
        onChange(of: isTouching) { _, touching in
            guard !touching else { return }
            Task { @MainActor in perform() }
        }
    }
}

extension Song {
    /// The first artist that has a page.
    var firstLinkedArtist: ArtistRef? { artists.first { $0.id != nil } }

    var shareURL: URL? { URL(string: "https://music.youtube.com/watch?v=\(videoId)") }
}

extension PlaybackStatus {
    var isWaiting: Bool { self == .loading || self == .buffering }

    var failureMessage: String? {
        if case .failed(let message) = self { return message }
        return nil
    }
}

/// The sleep timer's choices, shared by the player's menu and its moon button.
struct SleepTimerMenuItems: View {
    let player: PlayerStore

    var body: some View {
        ForEach([5, 10, 15, 30, 45, 60], id: \.self) { minutes in
            Button("\(minutes) minutes") {
                player.setSleepTimer(.at(Date().addingTimeInterval(TimeInterval(minutes * 60))))
            }
        }
        Button("End of song") { player.setSleepTimer(.endOfSong) }
        if player.sleepTimer != nil {
            Button("Turn off timer", role: .destructive) { player.setSleepTimer(nil) }
        }
    }
}
