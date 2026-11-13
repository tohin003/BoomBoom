import SwiftUI
import UIKit

// The sound screen's controls sit inside a scroll view inside a sheet, where three things want the same finger: the
// control, the scroll view and the sheet's pull-to-dismiss. SwiftUI gestures cannot say who wins reliably there, so
// the controls take their touches through a UIKit recognizer that makes the scroll view and the sheet wait for it.

/// What a finger did on a `TouchSurface`, in the surface's own coordinates.
enum FingerEvent {
    /// The control owns the finger from here: at touch-down (`.immediate`), or once a drag turned out sideways
    /// (`.horizontal`, with the point where the finger first came down).
    case began(CGPoint)
    case moved(CGPoint)
    case ended(CGPoint)
    /// The system took the touch away (a call, Control Center).
    case cancelled
    /// `.horizontal` only: the finger came down and lifted without moving.
    case tapped(CGPoint)
}

/// One finger, tracked for a sound control.
final class FingerRecognizer: UIGestureRecognizer {
    enum Mode {
        /// Takes every touch at once: nothing behind it scrolls or moves while the finger is down (the band faders,
        /// the "Original" button).
        case immediate
        /// Takes a drag only when it starts sideways; a vertical one is left to the scroll view (horizontal sliders).
        case horizontal
    }

    var mode: Mode
    var onEvent: @MainActor (FingerEvent) -> Void = { _ in }

    private weak var finger: UITouch?
    private var start: CGPoint = .zero
    private var startTime: TimeInterval = 0
    /// How far a finger travels before a `.horizontal` surface decides whose drag it is: about UIKit's own slop.
    private let slop: CGFloat = 8

    init(mode: Mode) {
        self.mode = mode
        super.init(target: nil, action: nil)
        cancelsTouchesInView = false
        delaysTouchesBegan = false
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent) {
        guard finger == nil, let touch = touches.first else {
            for touch in touches where touch !== finger { ignore(touch, for: event) }
            return
        }
        finger = touch
        start = touch.location(in: view)
        startTime = touch.timestamp
        if mode == .immediate {
            state = .began
            onEvent(.began(start))
        }
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent) {
        guard let finger, touches.contains(finger) else { return }
        let point = finger.location(in: view)
        switch state {
        case .possible:
            let dx = point.x - start.x, dy = point.y - start.y
            guard hypot(dx, dy) >= slop else { return }
            if abs(dx) > abs(dy) {
                state = .began
                onEvent(.began(start))
                onEvent(.moved(point))
            } else {
                self.finger = nil
                state = .failed
            }
        case .began, .changed:
            state = .changed
            onEvent(.moved(point))
        default:
            break
        }
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent) {
        guard let finger, touches.contains(finger) else { return }
        let point = finger.location(in: view)
        switch state {
        case .possible:
            if finger.timestamp - startTime < 0.5 { onEvent(.tapped(point)) }
            self.finger = nil
            state = .failed
        case .began, .changed:
            self.finger = nil
            state = .ended
            onEvent(.ended(point))
        default:
            break
        }
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent) {
        guard let finger, touches.contains(finger) else { return }
        switch state {
        case .began, .changed:
            state = .cancelled
            onEvent(.cancelled)
        default:
            state = .failed
        }
    }

    override func reset() {
        super.reset()
        finger = nil
    }

    /// A sideways slider makes the scroll view's and the sheet's pans (both ancestors) wait until it gives the finger
    /// up. Only pans: SwiftUI's own tap handling also lives on an ancestor, and making it wait cost the next tap on
    /// any other control. An `.immediate` surface needs none of this: it owns the finger from touch-down.
    override func shouldBeRequiredToFail(by other: UIGestureRecognizer) -> Bool {
        guard mode == .horizontal, other !== self, other is UIPanGestureRecognizer,
              let mine = view, let theirs = other.view else { return false }
        return mine !== theirs && mine.isDescendant(of: theirs)
    }
}

/// UIKit's own double tap, for the sliders: two quick taps that do not move.
final class DoubleTapRecognizer: UITapGestureRecognizer {
    var onDoubleTap: @MainActor () -> Void = {}

    init() {
        super.init(target: nil, action: nil)
        numberOfTapsRequired = 2
        cancelsTouchesInView = false
        addTarget(self, action: #selector(fire))
    }

    @objc private func fire() {
        if state == .ended { onDoubleTap() }
    }
}

/// A clear surface that reports a finger's moves. Lay it over a control's drawing; give the control its accessibility
/// separately (the surface is hidden from VoiceOver).
struct TouchSurface: UIViewRepresentable {
    let mode: FingerRecognizer.Mode
    var onDoubleTap: (@MainActor () -> Void)?
    let onEvent: @MainActor (FingerEvent) -> Void

    func makeUIView(context: Context) -> UIView {
        let view = UIView()
        view.backgroundColor = .clear
        view.isMultipleTouchEnabled = false
        view.isAccessibilityElement = false
        view.accessibilityElementsHidden = true
        view.addGestureRecognizer(FingerRecognizer(mode: mode))
        if onDoubleTap != nil { view.addGestureRecognizer(DoubleTapRecognizer()) }
        updateUIView(view, context: context)
        return view
    }

    func updateUIView(_ view: UIView, context: Context) {
        for recognizer in view.gestureRecognizers ?? [] {
            if let finger = recognizer as? FingerRecognizer {
                finger.mode = mode
                finger.onEvent = onEvent
            } else if let double = recognizer as? DoubleTapRecognizer, let onDoubleTap {
                double.onDoubleTap = onDoubleTap
            }
        }
    }
}

/// The screen's haptics, played at the moment a control crosses a mark (not on the next render).
@MainActor
enum SoundHaptics {
    private static let soft = UIImpactFeedbackGenerator(style: .light)
    private static let firm = UIImpactFeedbackGenerator(style: .medium)
    private static let selection = UISelectionFeedbackGenerator()

    static func prepare() {
        soft.prepare()
        firm.prepare()
        selection.prepare()
    }

    /// A value landed on its detent (0 dB, centre, 100 %).
    static func detent() { soft.impactOccurred(intensity: 0.8) }
    /// A value reached the end of its range.
    static func limit() { firm.impactOccurred(intensity: 0.9) }
    /// A stroke crossed into the next band; a button was pressed.
    static func tick() { selection.selectionChanged() }
}

/// Seconds since boot, the clock touches are timed on.
@MainActor
func soundNow() -> TimeInterval { ProcessInfo.processInfo.systemUptime }
