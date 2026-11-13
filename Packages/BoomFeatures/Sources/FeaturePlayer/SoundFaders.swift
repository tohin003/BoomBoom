import BoomDesign
import BoomPlayer
import SwiftUI

/// The ten band faders. A drag that starts upward or downward moves the band under the finger from where it is; a
/// drag that starts sideways draws a curve across the bands it passes. Double tap a band to zero it. The faders take
/// the finger at once, so the sheet neither scrolls nor pulls down under them.
struct SoundFaders: View {
    let gains: [Float]
    let isEnabled: Bool
    let equalizer: EqualizerStore
    /// The band under the finger, for the screen (the graph highlights it, the sheet stops dismissing).
    @Binding var activeBand: Int?

    @State private var touch = FaderTouch.idle
    @State private var lastTap: FaderTap?
    @State private var size = CGSize(width: 1, height: SoundFaders.trackHeight)

    /// A finger on the faders. Changed only through `move(to:)`.
    enum FaderTouch: Equatable {
        case idle
        /// Down on `band`, not yet moved far enough to tell an adjustment from a stroke (or a tap).
        case pressed(band: Int, start: CGPoint, time: TimeInterval)
        /// Moving one band relative to where it was picked up: `startGain` at `startY`.
        case adjusting(band: Int, startY: CGFloat, startGain: Float)
        /// Drawing a curve: each band the finger reaches takes the level under the finger.
        case drawing(band: Int, gain: Float)

        var band: Int? {
            switch self {
            case .idle: nil
            case .pressed(let band, _, _), .adjusting(let band, _, _), .drawing(let band, _): band
            }
        }
    }

    struct FaderTap: Equatable {
        let band: Int
        let time: TimeInterval
    }

    /// The fader area's height: 220 pt of travel plus room for the cap at either end.
    nonisolated static let trackHeight: CGFloat = 236
    nonisolated static let capHeight: CGFloat = 16
    nonisolated static var inset: CGFloat { capHeight / 2 }
    private static let range = EqualizerStore.gainRange.upperBound
    private static let count = EqualizerStore.bands.count

    var body: some View {
        VStack(spacing: 6) {
            HStack(spacing: 0) {
                Color.clear.frame(width: SoundGraph.scaleWidth, height: 1)
                ForEach(EqualizerStore.bands) { band in
                    readout(band.id)
                }
            }
            HStack(spacing: 0) {
                FaderScale()
                    .frame(width: SoundGraph.scaleWidth)
                FaderCanvas(gains: AnimatableValues(gains.map(Double.init)), activeBand: touch.band, isEnabled: isEnabled)
                    .animation(touch == .idle ? .snappy(duration: 0.32) : nil, value: gains)
                    .onGeometryChange(for: CGSize.self, of: \.size) { size = $0 }
                    .overlay { TouchSurface(mode: .immediate) { handle($0) } }
                    // For VoiceOver only: it takes no touches, so the faders' own surface gets every finger.
                    .overlay { accessibilityBands.allowsHitTesting(false) }
            }
            .frame(height: Self.trackHeight)
            HStack(spacing: 0) {
                Color.clear.frame(width: SoundGraph.scaleWidth, height: 1)
                ForEach(EqualizerStore.bands) { band in
                    Text(band.label)
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(touch.band == band.id ? BoomColor.accent : BoomColor.textTertiary)
                        .frame(maxWidth: .infinity)
                }
            }
            .accessibilityHidden(true)
        }
    }

    private func readout(_ band: Int) -> some View {
        let isActive = touch.band == band
        return Text(Self.readout(gains[band]))
            .font(.system(size: 11, weight: isActive ? .heavy : .semibold).monospacedDigit())
            .foregroundStyle(isActive ? BoomColor.onAccent : gains[band] == 0 ? BoomColor.textTertiary : BoomColor.textPrimary)
            .lineLimit(1)
            .minimumScaleFactor(0.7)
            .padding(.vertical, 3)
            .frame(maxWidth: .infinity)
            .background(isActive ? BoomColor.accent : Color.clear, in: .rect(cornerRadius: 4))
            .padding(.horizontal, 1)
            .accessibilityHidden(true)
    }

    /// "+3.5", "0.0", "−2.0": one decimal, a true minus sign.
    nonisolated static func readout(_ gain: Float) -> String {
        let rounded = (gain * 10).rounded() / 10
        if rounded == 0 { return "0.0" }
        let text = String(format: "%+.1f", rounded)
        return text.replacingOccurrences(of: "-", with: "−")
    }

    // MARK: The finger

    private func handle(_ event: FingerEvent) {
        switch (event, touch) {
        case (.began(let point), _):
            SoundHaptics.prepare()
            move(to: .pressed(band: band(at: point.x), start: point, time: soundNow()))

        case (.moved(let point), .pressed(let band, let start, _)):
            let dx = point.x - start.x, dy = point.y - start.y
            guard hypot(dx, dy) >= 6 else { return }
            if abs(dy) >= abs(dx) {
                let startGain = equalizer.settings.gains[band]
                move(to: .adjusting(band: band, startY: start.y, startGain: startGain))
                adjust(band: band, startY: start.y, startGain: startGain, to: point.y)
            } else {
                let gain = Self.snapped(gain(at: start.y))
                set(gain, band: band)
                move(to: .drawing(band: band, gain: gain))
                draw(from: band, gain: gain, to: point)
            }

        case (.moved(let point), .adjusting(let band, let startY, let startGain)):
            adjust(band: band, startY: startY, startGain: startGain, to: point.y)

        case (.moved(let point), .drawing(let band, let gain)):
            draw(from: band, gain: gain, to: point)

        case (.ended, .pressed(let band, _, let time)):
            if soundNow() - time < 0.35 { tapped(band) }
            move(to: .idle)

        case (.ended, _), (.cancelled, _):
            move(to: .idle)

        case (.moved, .idle), (.tapped, _):
            break
        }
    }

    private func move(to next: FaderTouch) {
        guard touch != next else { return }
        touch = next
        if activeBand != next.band { activeBand = next.band }
    }

    private func adjust(band: Int, startY: CGFloat, startGain: Float, to y: CGFloat) {
        let travel = max(size.height - 2 * Self.inset, 1)
        let raw = Double(startGain) + Double((startY - y) / travel) * Double(2 * Self.range)
        set(Self.snapped(raw), band: band)
    }

    private func draw(from last: Int, gain lastGain: Float, to point: CGPoint) {
        let band = band(at: point.x)
        let target = Self.snapped(gain(at: point.y))
        // A fast finger skips columns: fill them in so the stroke draws a continuous curve.
        if abs(band - last) > 1 {
            let step = band > last ? 1 : -1
            for skipped in stride(from: last + step, to: band, by: step) {
                let fraction = Double(skipped - last) / Double(band - last)
                set(Self.snapped(Double(lastGain) + (Double(target) - Double(lastGain)) * fraction), band: skipped)
            }
        }
        if band != last { SoundHaptics.tick() }
        set(target, band: band)
        move(to: .drawing(band: band, gain: target))
    }

    private func tapped(_ band: Int) {
        let now = soundNow()
        if let lastTap, lastTap.band == band, now - lastTap.time < 0.4 {
            self.lastTap = nil
            if equalizer.settings.gains[band] != 0 {
                equalizer.setGain(0, band: band)
                SoundHaptics.detent()
            }
        } else {
            lastTap = FaderTap(band: band, time: now)
        }
    }

    private func set(_ gain: Float, band: Int) {
        let old = equalizer.settings.gains[band]
        guard gain != old else { return }
        equalizer.setGain(gain, band: band)
        if gain == 0 { SoundHaptics.detent() }
        if abs(gain) == Self.range, abs(old) != Self.range { SoundHaptics.limit() }
    }

    private func band(at x: CGFloat) -> Int {
        min(max(Int(x / (size.width / CGFloat(Self.count))), 0), Self.count - 1)
    }

    private func gain(at y: CGFloat) -> Double {
        let travel = max(size.height - 2 * Self.inset, 1)
        return Double(Self.range) - Double((y - Self.inset) / travel) * Double(2 * Self.range)
    }

    /// Steps of 0.1 dB, clamped, with a gentle pull to 0.
    static func snapped(_ gain: Double) -> Float {
        let limit = Double(range)
        let clamped = min(max(gain, -limit), limit)
        if abs(clamped) < 0.4 { return 0 }
        return Float((clamped * 10).rounded() / 10)
    }

    // MARK: VoiceOver

    private var accessibilityBands: some View {
        HStack(spacing: 0) {
            ForEach(EqualizerStore.bands) { band in
                let gain = gains[band.id]
                Color.clear
                    .contentShape(Rectangle())
                    .accessibilityElement()
                    .accessibilityLabel("\(band.label) hertz")
                    .accessibilityValue("\(Self.readout(gain)) dB")
                    .accessibilityIdentifier("sound.band.\(band.id)")
                    .accessibilityAdjustableAction { direction in
                        let step: Float = direction == .increment ? 0.5 : -0.5
                        equalizer.setGain(((gain + step) * 2).rounded() / 2, band: band.id)
                    }
                    .accessibilityAction(named: "Set to 0 dB") { equalizer.setGain(0, band: band.id) }
            }
        }
    }
}

/// The dB marks beside the faders, level with the faders' own lines.
private struct FaderScale: View {
    var body: some View {
        Canvas { context, size in
            for (decibels, label) in [(12.0, "+12"), (6, "+6"), (0, "0"), (-6, "−6"), (-12, "−12")] {
                context.draw(
                    Text(label).font(.system(size: 9, weight: .semibold).monospacedDigit())
                        .foregroundStyle(decibels == 0 ? BoomColor.textSecondary : BoomColor.textTertiary),
                    at: CGPoint(x: size.width - 5, y: FaderCanvas.y(decibels, height: size.height)), anchor: .trailing)
            }
        }
        .accessibilityHidden(true)
    }
}

/// All ten faders in one drawing: grooves, the fill from 0 dB, and console-style caps.
private struct FaderCanvas: View, @MainActor Animatable {
    var gains: AnimatableValues
    let activeBand: Int?
    let isEnabled: Bool

    var animatableData: AnimatableValues {
        get { gains }
        set { gains = newValue }
    }

    nonisolated static func y(_ gain: Double, height: CGFloat) -> CGFloat {
        let range = Double(EqualizerStore.gainRange.upperBound)
        let inset = SoundFaders.inset
        return inset + CGFloat((range - gain) / (2 * range)) * (height - 2 * inset)
    }

    var body: some View {
        Canvas { context, size in
            let count = EqualizerStore.bands.count
            let column = size.width / CGFloat(count)
            let tint = isEnabled ? BoomColor.accent : Color.white.opacity(0.45)
            func y(_ gain: Double) -> CGFloat { Self.y(gain, height: size.height) }

            var guides = Path()
            for decibels in [-12.0, -6, 6, 12] {
                guides.move(to: CGPoint(x: 0, y: y(decibels)))
                guides.addLine(to: CGPoint(x: size.width, y: y(decibels)))
            }
            context.stroke(guides, with: .color(Color.white.opacity(0.06)), style: StrokeStyle(lineWidth: 1, dash: [2, 3]))
            var zero = Path()
            zero.move(to: CGPoint(x: 0, y: y(0)))
            zero.addLine(to: CGPoint(x: size.width, y: y(0)))
            context.stroke(zero, with: .color(Color.white.opacity(0.18)), lineWidth: 1)

            for index in 0..<count {
                let gain = index < gains.values.count ? gains.values[index] : 0
                let x = column * (CGFloat(index) + 0.5)
                let isActive = index == activeBand
                if isActive {
                    let lane = CGRect(x: x - column / 2 + 1, y: 0, width: column - 2, height: size.height)
                    context.fill(Path(roundedRect: lane, cornerRadius: 6), with: .color(BoomColor.accent.opacity(0.08)))
                }
                let groove = CGRect(x: x - 2, y: y(12), width: 4, height: y(-12) - y(12))
                context.fill(Path(roundedRect: groove, cornerRadius: 2), with: .color(Color.black.opacity(0.9)))
                context.stroke(Path(roundedRect: groove, cornerRadius: 2), with: .color(Color.white.opacity(0.10)), lineWidth: 0.5)

                let capY = y(gain)
                let fill = CGRect(x: x - 1.5, y: min(y(0), capY), width: 3, height: abs(y(0) - capY))
                context.fill(Path(roundedRect: fill, cornerRadius: 1.5), with: .color(tint))

                let capWidth = min(column - 6, 28)
                let cap = CGRect(x: x - capWidth / 2, y: capY - SoundFaders.capHeight / 2, width: capWidth, height: SoundFaders.capHeight)
                let capPath = Path(roundedRect: cap, cornerRadius: 4)
                context.drawLayer { layer in
                    layer.addFilter(.shadow(color: .black.opacity(0.6), radius: 3, y: 2))
                    layer.fill(
                        capPath,
                        with: isActive
                            ? .color(BoomColor.accent)
                            : .linearGradient(
                                Gradient(colors: [Color(white: isEnabled ? 0.92 : 0.6), Color(white: isEnabled ? 0.68 : 0.42)]),
                                startPoint: CGPoint(x: 0, y: cap.minY), endPoint: CGPoint(x: 0, y: cap.maxY)))
                }
                var mark = Path()
                mark.move(to: CGPoint(x: cap.minX + 4, y: capY))
                mark.addLine(to: CGPoint(x: cap.maxX - 4, y: capY))
                context.stroke(mark, with: .color(Color.black.opacity(0.75)), lineWidth: 1.5)
            }
        }
        .accessibilityHidden(true)
    }
}
