import BoomDesign
import BoomModels
import BoomPlayer
import SwiftUI

/// The tabs at the foot of the full player.
public enum NowPlayingPanel: String, CaseIterable, Hashable, Sendable {
    case upNext, lyrics, related

    var title: String {
        switch self {
        case .upNext: "UP NEXT"
        case .lyrics: "LYRICS"
        case .related: "RELATED"
        }
    }
}

/// The full player, laid over everything (the tab bar too). Put it at the app's root above the frame; it slides
/// itself up when `isExpanded` turns true and down when the listener drags it away or taps the chevron. While
/// collapsed it draws nothing and lets every touch through.
public struct NowPlayingOverlay: View {
    @Binding private var isExpanded: Bool
    private let panel: Binding<NowPlayingPanel?>?

    @State private var model = OverlayModel()

    /// - Parameter panel: Optionally drives which of "Up next", "Lyrics", "Related" is pulled up over the player.
    public init(isExpanded: Binding<Bool>, panel: Binding<NowPlayingPanel?>? = nil) {
        _isExpanded = isExpanded
        self.panel = panel
    }

    public var body: some View {
        OverlayStage(model: model)
            .onAppear {
                model.requestCollapse = { [$isExpanded] in $isExpanded.wrappedValue = false }
                model.onPanelChange = { [panel] tab in
                    if let panel, panel.wrappedValue != tab { panel.wrappedValue = tab }
                }
            }
            .onChange(of: isExpanded, initial: true) { _, expanded in
                expanded ? model.expand() : model.collapse()
            }
            .onChange(of: panel?.wrappedValue, initial: true) { _, tab in
                guard let panel, model.panel != tab else { return }
                model.setPanel(panel.wrappedValue)
            }
    }
}

/// Where the overlay is and what it shows. Its drag offset is read only by `OverlayStage`, so following a finger
/// redraws one modifier and not the player.
@MainActor
@Observable
final class OverlayModel {
    /// Where the player is. Changed only through `move(to:)`.
    enum Presentation: Equatable {
        /// Not in the hierarchy; touches pass through.
        case hidden
        /// Just put in the hierarchy at the bottom; `didAppear` slides it up.
        case mounting
        /// Up, or springing up (after opening, or after a drag that did not go far enough).
        case shown
        /// A finger is on the player. `.horizontal` is a sideways touch that does not move it.
        case dragging(Axis)
        /// Sliding down. `id` tells this slide from a later one, so only its own completion takes the player away;
        /// `closing` is the panel that was up, cleared when the slide ends.
        case dismissing(id: Int, closing: NowPlayingPanel?)
    }

    private(set) var presentation: Presentation = .hidden
    /// 0 when fully up; `travel` when fully down.
    private(set) var offset: CGFloat = 2000
    private(set) var panel: NowPlayingPanel?
    /// The screen's full height, safe areas included.
    @ObservationIgnored var travel: CGFloat = 2000 {
        didSet { if presentation == .hidden || presentation == .mounting { offset = travel } }
    }
    @ObservationIgnored var requestCollapse: () -> Void = {}
    @ObservationIgnored var onPanelChange: (NowPlayingPanel?) -> Void = { _ in }
    @ObservationIgnored private var dismissals = 0

    static let presentSpring = Animation.spring(duration: 0.42, bounce: 0.1)

    /// True while the player is on screen or moving.
    var isRendered: Bool { presentation != .hidden }

    /// False while it slides away, so a touch then reaches what is behind it (the mini player, to open it again).
    var isInteractive: Bool {
        switch presentation {
        case .hidden, .dismissing: false
        case .mounting, .shown, .dragging: true
        }
    }

    private func move(to next: Presentation) {
        guard presentation != next else { return }
        presentation = next
    }

    func expand() {
        switch presentation {
        case .hidden:
            offset = travel
            move(to: .mounting)
        case .dismissing:
            // Caught on its way down: back up from where it is.
            move(to: .shown)
            withAnimation(Self.presentSpring) { offset = 0 }
        case .mounting, .shown, .dragging:
            break
        }
    }

    func didAppear() {
        guard presentation == .mounting else { return }
        move(to: .shown)
        withAnimation(Self.presentSpring) { offset = 0 }
    }

    func collapse(velocity: CGFloat = 0) {
        switch presentation {
        case .hidden, .dismissing:
            return
        case .mounting:
            // Never got to slide up: nothing to animate.
            offset = travel
            finishDismissal(closing: panel)
            return
        case .shown, .dragging:
            break
        }
        dismissals += 1
        let id = dismissals
        move(to: .dismissing(id: id, closing: panel))
        let remaining = max(travel - offset, 1)
        let animation: Animation = velocity > 0
            ? .interpolatingSpring(mass: 1, stiffness: 260, damping: 32, initialVelocity: Double(velocity / remaining))
            : .spring(duration: 0.38, bounce: 0)
        withAnimation(animation, completionCriteria: .logicallyComplete) {
            offset = travel
        } completion: { [weak self] in
            guard let self, case .dismissing(id: id, closing: let closing) = self.presentation else { return }
            self.finishDismissal(closing: closing)
        }
    }

    private func finishDismissal(closing: NowPlayingPanel?) {
        move(to: .hidden)
        // A panel asked for while the player was on its way down is kept for the next time it opens.
        guard panel == closing, panel != nil else { return }
        panel = nil
        onPanelChange(nil)
    }

    /// Closes the player from inside it (the chevron, or before going to a page).
    func dismiss() {
        requestCollapse()
        collapse()
    }

    func setPanel(_ tab: NowPlayingPanel?) {
        guard panel != tab else { return }
        withAnimation(.spring(duration: 0.4, bounce: 0.08)) { panel = tab }
        onPanelChange(tab)
    }

    // MARK: Dragging the player down

    func dragChanged(_ translation: CGSize) {
        switch presentation {
        case .shown:
            move(to: .dragging(abs(translation.width) > abs(translation.height) ? .horizontal : .vertical))
        case .dragging:
            break
        case .hidden, .mounting, .dismissing:
            // On its way down (or not up yet): a finger does not catch it.
            return
        }
        guard presentation == .dragging(.vertical) else { return }
        let dy = translation.height
        offset = dy >= 0 ? dy : -pow(-dy, 0.7)
    }

    func dragEnded(translation: CGSize, predicted: CGSize, velocity: CGFloat) {
        // A quick flick can end before any change was reported: it starts here.
        if presentation == .shown { dragChanged(translation) }
        guard case .dragging(let axis) = presentation else { return }
        guard axis == .vertical else {
            move(to: .shown)
            return
        }
        if translation.height > travel * 0.22 || (predicted.height > travel * 0.45 && velocity > 300) {
            requestCollapse()
            collapse(velocity: max(velocity, 0))
        } else {
            settleUp()
        }
    }

    /// The system took the touch away mid-drag (a call, Control Center): the player goes back up.
    func dragCancelled() {
        guard case .dragging(let axis) = presentation else { return }
        if axis == .vertical {
            settleUp()
        } else {
            move(to: .shown)
        }
    }

    private func settleUp() {
        move(to: .shown)
        withAnimation(.spring(duration: 0.4, bounce: 0.2)) { offset = 0 }
    }
}

/// Moves the player with the model's offset and dims what is behind it.
private struct OverlayStage: View {
    let model: OverlayModel

    var body: some View {
        ZStack {
            if model.isRendered {
                Color.black
                    .opacity(0.55 * max(0, 1 - model.offset / max(model.travel, 1)))
                    .ignoresSafeArea()
                    .allowsHitTesting(false)
                PlayerScreen()
                    .environment(model)
                    .offset(y: model.offset)
                    .onAppear { model.didAppear() }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .onGeometryChange(for: CGFloat.self) { proxy in
            proxy.size.height + proxy.safeAreaInsets.top + proxy.safeAreaInsets.bottom
        } action: { model.travel = $0 + 40 }
        .allowsHitTesting(model.isInteractive)
    }
}
