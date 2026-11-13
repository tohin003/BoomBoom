import AVKit
import BoomDesign
import BoomModels
import BoomPlayer
import SwiftUI

// MARK: Cover

/// The large cover, with the songs before and after it waiting off to the sides: a sideways swipe slides to them.
struct CoverCarousel: View {
    let side: CGFloat

    @Environment(AppServices.self) private var services
    @Environment(OverlayModel.self) private var model
    @State private var slide: CGFloat = 0
    @State private var swipe = Swipe.idle
    @GestureState private var isTouching = false

    /// What a touch on the cover is doing.
    private enum Swipe: Equatable {
        case idle
        /// A finger is on the cover. `.vertical` hands the drag to the player's own drag-down.
        case dragging(Axis)
        /// Sliding the rest of the way to a neighbour; that entry plays once it gets there. Touches wait.
        case settling(onto: UUID)
    }

    private struct Slot: Identifiable {
        let id: UUID
        let song: Song
        let position: Int
    }

    var body: some View {
        let slots = slots(of: services.player)
        let step = side + 28
        ZStack {
            if slots.isEmpty {
                ArtworkView(.empty, width: side, cornerRadius: BoomMetrics.cardRadius)
            }
            ForEach(slots) { slot in
                CoverImage(song: slot.song, side: side)
                    .offset(x: slide + CGFloat(slot.position) * step)
            }
        }
        .frame(width: side, height: side)
        .contentShape(Rectangle())
        .gesture(drag(slots: slots, step: step))
        .onDragCancel(isTouching: isTouching) { dragCancelled() }
        .accessibilityHidden(true)
    }

    private func slots(of player: PlayerStore) -> [Slot] {
        guard let index = player.currentIndex else { return [] }
        let queue = player.queue
        var slots = [Slot(id: queue[index].id, song: queue[index].song, position: 0)]
        if index > 0 {
            slots.append(Slot(id: queue[index - 1].id, song: queue[index - 1].song, position: -1))
        }
        let following = index + 1 < queue.count ? queue[index + 1] : (player.repeatMode == .all ? queue.first : nil)
        if let following, !slots.contains(where: { $0.id == following.id }) {
            slots.append(Slot(id: following.id, song: following.song, position: 1))
        }
        return slots
    }

    private func drag(slots: [Slot], step: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 10, coordinateSpace: .global)
            .updating($isTouching) { _, touching, _ in touching = true }
            .onChanged { value in
                let t = value.translation
                switch swipe {
                case .settling:
                    return
                case .idle:
                    swipe = .dragging(abs(t.width) > abs(t.height) ? .horizontal : .vertical)
                case .dragging:
                    break
                }
                if swipe == .dragging(.vertical) {
                    model.dragChanged(t)
                    return
                }
                let hasNeighbour = slots.contains { $0.position == (t.width < 0 ? 1 : -1) }
                slide = hasNeighbour ? t.width : t.width / 3
            }
            .onEnded { value in
                // A quick flick can end before any change was reported: its direction is decided here.
                if swipe == .idle {
                    let t = value.translation
                    swipe = .dragging(abs(t.width) > abs(t.height) ? .horizontal : .vertical)
                }
                guard case .dragging(let axis) = swipe else { return }
                if axis == .vertical {
                    swipe = .idle
                    model.dragChanged(value.translation)
                    model.dragEnded(
                        translation: value.translation, predicted: value.predictedEndTranslation,
                        velocity: value.velocity.height)
                    return
                }
                let dx = value.translation.width, predicted = value.predictedEndTranslation.width
                let following = slots.first { $0.position == 1 }
                // Straight to the song before, even late into this one: the cover already shows it.
                let before = slots.first { $0.position == -1 }
                if dx < -side * 0.28 || predicted < -side * 0.6, let following {
                    settle(onto: following.id, at: -step)
                } else if dx > side * 0.28 || predicted > side * 0.6, let before {
                    settle(onto: before.id, at: step)
                } else {
                    swipe = .idle
                    withAnimation(.spring(duration: 0.35, bounce: 0.2)) { slide = 0 }
                }
            }
    }

    /// Slides the neighbour into the middle, then plays it. The entry is fixed when the finger lifts, so a song that
    /// ends during the slide does not make it skip one further.
    private func settle(onto entryID: UUID, at target: CGFloat) {
        let player = services.player
        swipe = .settling(onto: entryID)
        withAnimation(.spring(duration: 0.28, bounce: 0), completionCriteria: .logicallyComplete) {
            slide = target
        } completion: {
            var transaction = Transaction()
            transaction.disablesAnimations = true
            withTransaction(transaction) {
                if player.currentEntryID != entryID { player.jump(to: entryID) }
                slide = 0
                swipe = .idle
            }
        }
    }

    private func dragCancelled() {
        guard case .dragging(let axis) = swipe else { return }
        swipe = .idle
        if axis == .vertical {
            model.dragCancelled()
        } else {
            withAnimation(.spring(duration: 0.35, bounce: 0.2)) { slide = 0 }
        }
    }
}

/// A square cover, or a video still letterboxed inside the square.
private struct CoverImage: View {
    let song: Song
    let side: CGFloat

    var body: some View {
        Group {
            if song.kind == .video {
                ZStack {
                    Color.black
                    ArtworkView(song.artwork, width: side, height: side * 9 / 16, cornerRadius: 0)
                }
                .frame(width: side, height: side)
                .clipShape(.rect(cornerRadius: BoomMetrics.cardRadius))
            } else {
                ArtworkView(song.artwork, width: side, cornerRadius: BoomMetrics.cardRadius)
            }
        }
        .shadow(color: .black.opacity(0.45), radius: 22, y: 12)
    }
}

// MARK: Scrubber

/// The song's position: thin track, buffered part, played part in accent. Dragging previews the time and seeks on
/// release only. The only part of the page that redraws as time runs.
struct Scrubber: View {
    let player: PlayerStore

    @State private var width: CGFloat = 1
    @State private var scrub = Scrub.idle
    @GestureState private var isTouching = false
    @State private var showsRemaining = false

    /// The thumb's situation. Each case names the queue entry it was for: once another song is playing it no longer
    /// applies, so it is neither shown nor sought.
    private enum Scrub: Equatable {
        case idle
        /// A finger holds the thumb at `fraction`; nothing is sought until it lifts.
        case dragging(fraction: Double, entry: UUID)
        /// Lifted and sought. The engine moves a moment later than asked, so the thumb holds `fraction` until the
        /// clock reports the new place (or a short while passes) instead of flicking back to where the song was.
        case landing(fraction: Double, entry: UUID, since: Date)
    }

    /// How long a seek may take to show up in the clock before the thumb follows the clock again.
    private static let landingTimeout: Duration = .seconds(1.5)

    var body: some View {
        let clock = player.clock
        let duration = clock.duration
        let held = heldFraction
        let fraction = held ?? clock.progress
        let isDragging = isDraggingThisSong
        VStack(spacing: 0) {
            ZStack(alignment: .leading) {
                Capsule().fill(Color.white.opacity(0.18))
                Capsule().fill(Color.white.opacity(0.3))
                    .scaleEffect(x: max(clock.bufferedFraction, fraction, 0.001), y: 1, anchor: .leading)
                Capsule().fill(BoomColor.accent)
                    .scaleEffect(x: max(fraction, 0.001), y: 1, anchor: .leading)
            }
            .frame(height: isDragging ? 5 : 3)
            .overlay(alignment: .leading) {
                Circle()
                    .fill(BoomColor.accent)
                    .frame(width: isDragging ? 20 : 12, height: isDragging ? 20 : 12)
                    .shadow(color: .black.opacity(0.3), radius: 3)
                    .offset(x: fraction * width - (isDragging ? 10 : 6))
            }
            .frame(height: 32)
            .frame(maxWidth: .infinity)
            .onGeometryChange(for: CGFloat.self, of: \.size.width) { width = max($0, 1) }
            .overlay(alignment: .topLeading) {
                if isDragging {
                    Text(boomTime(fraction * duration))
                        .font(BoomFont.time.weight(.semibold))
                        .foregroundStyle(BoomColor.onAccent)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 3)
                        .background(BoomColor.accent, in: Capsule())
                        .fixedSize()
                        .offset(x: min(max(fraction * width - 24, 0), width - 48), y: -22)
                        .transition(.opacity)
                }
            }
            .contentShape(Rectangle().inset(by: -8))
            .gesture(scrubGesture(duration: duration))
            // The track is the adjustable element; the length button beside the times stays its own control.
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Position")
            .accessibilityValue("\(boomTime(clock.position)) of \(boomTime(duration))")
            .accessibilityAdjustableAction { direction in
                switch direction {
                case .increment: player.seek(to: clock.position + 10)
                case .decrement: player.seek(to: clock.position - 10)
                @unknown default: break
                }
            }
            .onDragCancel(isTouching: isTouching) {
                if case .dragging = scrub { scrub = .idle }
            }
            .animation(.snappy(duration: 0.2), value: isDragging)

            HStack {
                Text(boomTime(held.map { $0 * duration } ?? clock.position))
                    .foregroundStyle(isDragging ? BoomColor.accent : BoomColor.textSecondary)
                    .accessibilityHidden(true)
                Spacer()
                Button {
                    showsRemaining.toggle()
                } label: {
                    Text(showsRemaining ? "-" + boomTime(max(0, duration - fraction * duration)) : boomTime(duration))
                        .foregroundStyle(BoomColor.textSecondary)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(showsRemaining ? "Show length" : "Show time left")
            }
            .font(BoomFont.time)
        }
        .onChange(of: clock.position) { _, position in landIfArrived(at: position, duration: duration) }
        .onChange(of: player.currentEntryID) { _, _ in
            // A song change ends a landing at once. A drag stays (its finger is still down) but no longer counts.
            if case .landing = scrub { scrub = .idle }
        }
        .task(id: landingStart) {
            guard landingStart != nil else { return }
            try? await Task.sleep(for: Self.landingTimeout)
            guard !Task.isCancelled, case .landing = scrub else { return }
            scrub = .idle
        }
    }

    /// Where the thumb is held instead of following the clock, while the drag or landing is for this song.
    private var heldFraction: Double? {
        switch scrub {
        case .dragging(let fraction, let entry), .landing(let fraction, let entry, _):
            entry == player.currentEntryID ? fraction : nil
        case .idle:
            nil
        }
    }

    private var isDraggingThisSong: Bool {
        if case .dragging(_, let entry) = scrub { return entry == player.currentEntryID }
        return false
    }

    private var landingStart: Date? {
        if case .landing(_, _, let since) = scrub { return since }
        return nil
    }

    /// The clock first echoes the asked-for time at once (the store sets it), then the engine's real position. Only
    /// a report a little after the release, near the target, means the engine got there.
    private func landIfArrived(at position: TimeInterval, duration: TimeInterval) {
        guard case .landing(let fraction, _, let since) = scrub,
              Date.now.timeIntervalSince(since) > 0.25,
              abs(position - fraction * duration) < 1
        else { return }
        scrub = .idle
    }

    private func scrubGesture(duration: TimeInterval) -> some Gesture {
        DragGesture(minimumDistance: 0)
            .updating($isTouching) { _, touching, _ in touching = true }
            .onChanged { value in
                guard duration > 0, let entry = player.currentEntryID else { return }
                // A drag begun on an earlier song stays dead until the finger lifts.
                if case .dragging(_, let held) = scrub, held != entry { return }
                scrub = .dragging(fraction: min(1, max(0, value.location.x / width)), entry: entry)
            }
            .onEnded { value in
                guard case .dragging(_, let entry) = scrub else { return }
                guard duration > 0, entry == player.currentEntryID else {
                    scrub = .idle
                    return
                }
                let fraction = min(1, max(0, value.location.x / width))
                player.seek(to: fraction * duration)
                scrub = .landing(fraction: fraction, entry: entry, since: .now)
            }
    }
}

// MARK: AirPlay

/// The system's AirPlay and Bluetooth route picker.
struct RoutePickerButton: UIViewRepresentable {
    func makeUIView(context: Context) -> AVRoutePickerView {
        let picker = AVRoutePickerView()
        picker.tintColor = UIColor.white.withAlphaComponent(0.7)
        picker.activeTintColor = UIColor(BoomColor.accent)
        picker.prioritizesVideoDevices = false
        return picker
    }

    func updateUIView(_ uiView: AVRoutePickerView, context: Context) {}
}
