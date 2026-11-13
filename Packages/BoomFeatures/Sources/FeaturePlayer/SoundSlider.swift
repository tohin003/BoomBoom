import BoomDesign
import BoomPlayer
import SwiftUI

/// Every horizontal control on the sound screen: its range, default, detents, words and how it reaches the store.
/// Sliders take one of these rather than closures, so SwiftUI can tell an unchanged slider from a changed one and
/// leave the others alone while one moves.
enum SoundControl: String, CaseIterable {
    case preamp, bass, treble, boost, deepBass, air, width, balance, crossfeed

    var title: String {
        switch self {
        case .preamp: "Preamp"
        case .bass: "Bass"
        case .treble: "Treble"
        case .boost: "Volume boost"
        case .deepBass: "Deep bass"
        case .air: "Air"
        case .width: "Stereo width"
        case .balance: "Balance"
        case .crossfeed: "Headphone crossfeed"
        }
    }

    var range: ClosedRange<Float> {
        switch self {
        case .preamp: EqualizerStore.gainRange
        case .bass, .treble: EqualizerStore.toneRange
        case .boost: EqualizerStore.boostRange
        case .deepBass, .air, .crossfeed: EqualizerStore.amountRange
        case .width: EqualizerStore.widthRange
        case .balance: EqualizerStore.balanceRange
        }
    }

    /// Where a double tap puts it, and where its coloured fill starts.
    var origin: Float { self == .width ? 1 : 0 }

    /// Values the slider settles on with a haptic.
    var detents: [Float] {
        switch self {
        case .preamp, .bass, .treble, .balance: [0]
        case .width: [1]
        case .boost, .deepBass, .air, .crossfeed: []
        }
    }

    /// The finest step: a tenth of a decibel, or a percent.
    var step: Float {
        switch self {
        case .preamp, .bass, .treble, .boost: 0.1
        case .deepBass, .air, .crossfeed, .width, .balance: 0.01
        }
    }

    /// One VoiceOver swipe.
    var accessibilityStep: Float {
        switch self {
        case .preamp, .bass, .treble, .boost: 0.5
        case .deepBass, .air, .crossfeed, .width, .balance: 0.05
        }
    }

    /// The words under the track's start, middle (when it has one) and end.
    var scale: (String, String?, String) {
        switch self {
        case .preamp, .bass, .treble: ("−12", "0", "+12")
        case .boost: ("0", "+6", "+12 dB")
        case .deepBass, .air, .crossfeed: ("Off", nil, "100%")
        case .width: ("Mono", "As recorded", "Wide")
        case .balance: ("L", "Centre", "R")
        }
    }

    var identifier: String { "sound.\(rawValue)" }

    func value(in settings: SoundSettings) -> Float {
        switch self {
        case .preamp: settings.preamp
        case .bass: settings.bass
        case .treble: settings.treble
        case .boost: settings.boost
        case .deepBass: settings.deepBass
        case .air: settings.air
        case .width: settings.width
        case .balance: settings.balance
        case .crossfeed: settings.crossfeed
        }
    }

    @MainActor
    func apply(_ value: Float, to equalizer: EqualizerStore) {
        // Moving a control means wanting to hear it: with the master switch off nothing would change.
        if !equalizer.settings.isEnabled { equalizer.setEnabled(true) }
        switch self {
        case .preamp: equalizer.setPreamp(value)
        case .bass: equalizer.setBass(value)
        case .treble: equalizer.setTreble(value)
        case .boost: equalizer.setBoost(value)
        case .deepBass: equalizer.setDeepBass(value)
        case .air: equalizer.setAir(value)
        case .width: equalizer.setWidth(value)
        case .balance: equalizer.setBalance(value)
        case .crossfeed: equalizer.setCrossfeed(value)
        }
    }

    func readout(_ value: Float) -> String {
        switch self {
        case .preamp, .bass, .treble, .boost: Self.decibels(value)
        case .deepBass, .air, .crossfeed, .width: "\(Int((value * 100).rounded()))%"
        case .balance:
            abs(value) < 0.005 ? "Centre" : "\(value < 0 ? "L" : "R") \(Int((abs(value) * 100).rounded()))%"
        }
    }

    func accessibilityValue(_ value: Float) -> String {
        switch self {
        case .boost: "\(Self.decibels(value)), \(Self.percent(boost: value)) level"
        case .width:
            value == 0 ? "Mono" : value == 1 ? "100%, as recorded" : "\(Int((value * 100).rounded()))%"
        default: readout(value)
        }
    }

    /// "0.0 dB", "+3.5 dB", "−2.0 dB".
    static func decibels(_ value: Float) -> String {
        "\(SoundFaders.readout(value)) dB"
    }

    /// The boost as a level: +6 dB is about twice the level, 200 %.
    static func percent(boost: Float) -> String {
        "\(Int((pow(10, Double(boost) / 20) * 100).rounded()))%"
    }
}

/// A horizontal slider for the sound screen. A sideways drag anywhere on it moves the value from where it is (the
/// thumb does not jump to the finger); a vertical drag scrolls the sheet as usual; double tap returns it to its
/// default. Detents and the ends give a haptic.
struct SoundSlider: View {
    let control: SoundControl
    let value: Float
    let isEnabled: Bool
    let equalizer: EqualizerStore
    /// Shows the title and readout above the track (the boost's hero layout draws its own).
    var showsHeader = true
    var detail: String?

    @State private var touch = SliderTouch.idle
    @State private var trackWidth: CGFloat = 1

    /// A finger on the slider. Changed only through `move(to:)`.
    enum SliderTouch: Equatable {
        case idle
        /// Moving the value relative to `startValue`, which it had when the finger was at `startX`.
        case dragging(startX: CGFloat, startValue: Float)
    }

    private var isDragging: Bool { touch != .idle }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if showsHeader {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(control.title)
                        .font(BoomFont.itemTitle)
                        .foregroundStyle(BoomColor.textPrimary)
                        .layoutPriority(1)
                    Spacer(minLength: 4)
                    Text(control.readout(value))
                        .font(BoomFont.time.weight(.semibold))
                        .foregroundStyle(isDragging ? BoomColor.onAccent : BoomColor.textPrimary)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(isDragging ? BoomColor.accent : Color.white.opacity(0.08), in: .rect(cornerRadius: 5))
                        .lineLimit(1)
                        .fixedSize()
                }
                if let detail {
                    Text(detail)
                        .font(BoomFont.caption)
                        .foregroundStyle(BoomColor.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            SliderTrack(
                fraction: fraction(value), origin: fraction(control.origin), marks: control.detents.map(fraction),
                isDragging: isDragging, isEnabled: isEnabled)
                .frame(height: 30)
                .onGeometryChange(for: CGFloat.self, of: \.size.width) { trackWidth = max($0, 1) }
            scaleLabels
        }
        .padding(.vertical, 10)
        .contentShape(Rectangle())
        .overlay { TouchSurface(mode: .horizontal, onDoubleTap: { returnToOrigin() }) { handle($0) } }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(control.title)
        .accessibilityValue(control.accessibilityValue(value))
        .accessibilityHint(detail ?? "")
        .accessibilityIdentifier(control.identifier)
        .accessibilityAdjustableAction { direction in
            let step = direction == .increment ? control.accessibilityStep : -control.accessibilityStep
            control.apply(quantised(value + step), to: equalizer)
        }
        .accessibilityAction(named: "Reset") { control.apply(control.origin, to: equalizer) }
    }

    private var scaleLabels: some View {
        let (start, middle, end) = control.scale
        return ZStack {
            HStack {
                Text(start)
                Spacer()
                Text(end)
            }
            if let middle {
                Text(middle)
                    .position(x: SliderTrack.inset + fraction(midpoint) * max(trackWidth - 2 * SliderTrack.inset, 1), y: 7)
            }
        }
        .font(.caption2.weight(.medium).monospacedDigit())
        .foregroundStyle(BoomColor.textTertiary)
        .frame(height: 14)
        .accessibilityHidden(true)
    }

    private var midpoint: Float { control.detents.first ?? (control.range.lowerBound + control.range.upperBound) / 2 }

    private func fraction(_ value: Float) -> CGFloat {
        let range = control.range
        return CGFloat((min(max(value, range.lowerBound), range.upperBound) - range.lowerBound) / (range.upperBound - range.lowerBound))
    }

    // MARK: The finger

    private func handle(_ event: FingerEvent) {
        switch (event, touch) {
        case (.began(let point), _):
            SoundHaptics.prepare()
            move(to: .dragging(startX: point.x, startValue: control.value(in: equalizer.settings)))
        case (.moved(let point), .dragging(let startX, let startValue)):
            let span = control.range.upperBound - control.range.lowerBound
            let usable = max(trackWidth - 2 * SliderTrack.inset, 1)
            set(snapped(startValue + Float((point.x - startX) / usable) * span))
        case (.ended, _), (.cancelled, _):
            move(to: .idle)
        case (.moved, .idle), (.tapped, _):
            break
        }
    }

    private func returnToOrigin() {
        guard control.value(in: equalizer.settings) != control.origin else { return }
        control.apply(control.origin, to: equalizer)
        SoundHaptics.detent()
    }

    private func move(to next: SliderTouch) {
        guard touch != next else { return }
        touch = next
    }

    private func set(_ next: Float) {
        let old = control.value(in: equalizer.settings)
        guard next != old else { return }
        control.apply(next, to: equalizer)
        if control.detents.contains(next) { SoundHaptics.detent() }
        let range = control.range
        if (next == range.lowerBound || next == range.upperBound), old != next { SoundHaptics.limit() }
    }

    /// Clamped, stepped, and held on a detent within 2 % of the range.
    private func snapped(_ raw: Float) -> Float {
        let range = control.range
        let clamped = min(max(raw, range.lowerBound), range.upperBound)
        let zone = (range.upperBound - range.lowerBound) * 0.02
        if let detent = control.detents.first(where: { abs(clamped - $0) < zone }) { return detent }
        return quantised(clamped)
    }

    private func quantised(_ value: Float) -> Float {
        let range = control.range
        let stepped = (value / control.step).rounded() * control.step
        // Keeps 0.1 steps from drifting to 0.30000001 in the readout and the store.
        let tidy = (stepped * 100).rounded() / 100
        return min(max(tidy, range.lowerBound), range.upperBound)
    }
}

/// The track: a groove, the fill from the origin to the value, the detent marks and the thumb.
private struct SliderTrack: View {
    let fraction: CGFloat
    let origin: CGFloat
    let marks: [CGFloat]
    let isDragging: Bool
    let isEnabled: Bool

    nonisolated static let inset: CGFloat = 12

    var body: some View {
        Canvas { context, size in
            let usable = size.width - 2 * Self.inset
            let midY = size.height / 2
            func x(_ fraction: CGFloat) -> CGFloat { Self.inset + fraction * usable }
            let tint = isEnabled ? BoomColor.accent : Color.white.opacity(0.5)

            let groove = CGRect(x: Self.inset - 2, y: midY - 2.5, width: usable + 4, height: 5)
            context.fill(Path(roundedRect: groove, cornerRadius: 2.5), with: .color(Color.white.opacity(0.12)))
            for mark in marks {
                let tick = CGRect(x: x(mark) - 1, y: midY - 9, width: 2, height: 18)
                context.fill(Path(roundedRect: tick, cornerRadius: 1), with: .color(Color.white.opacity(0.35)))
            }
            let from = min(x(origin), x(fraction)), to = max(x(origin), x(fraction))
            if to - from > 0.5 {
                context.fill(
                    Path(roundedRect: CGRect(x: from, y: midY - 2.5, width: to - from, height: 5), cornerRadius: 2.5),
                    with: .color(tint))
            }
            let radius: CGFloat = isDragging ? 13 : 11
            let center = CGPoint(x: x(fraction), y: midY)
            if isDragging {
                context.fill(
                    Path(ellipseIn: CGRect(x: center.x - 20, y: center.y - 20, width: 40, height: 40)),
                    with: .color(tint.opacity(0.18)))
            }
            let thumb = Path(ellipseIn: CGRect(x: center.x - radius, y: center.y - radius, width: radius * 2, height: radius * 2))
            context.drawLayer { layer in
                layer.addFilter(.shadow(color: .black.opacity(0.5), radius: 3, y: 1))
                layer.fill(thumb, with: .color(isEnabled ? .white : Color(white: 0.7)))
            }
            let core = Path(ellipseIn: CGRect(x: center.x - 3.5, y: center.y - 3.5, width: 7, height: 7))
            context.fill(core, with: .color(tint))
        }
        .accessibilityHidden(true)
    }
}
