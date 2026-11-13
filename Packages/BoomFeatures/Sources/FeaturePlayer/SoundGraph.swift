import BoomDesign
import BoomPlayer
import SwiftUI

/// A list of numbers SwiftUI can animate between (curve points, fader positions), so a preset glides into place.
struct AnimatableValues: VectorArithmetic {
    var values: [Double]

    init(_ values: [Double]) { self.values = values }

    static var zero: AnimatableValues { AnimatableValues([]) }

    static func + (lhs: AnimatableValues, rhs: AnimatableValues) -> AnimatableValues { combine(lhs, rhs, +) }
    static func - (lhs: AnimatableValues, rhs: AnimatableValues) -> AnimatableValues { combine(lhs, rhs, -) }

    mutating func scale(by rhs: Double) {
        for index in values.indices { values[index] *= rhs }
    }

    var magnitudeSquared: Double { values.reduce(0) { $0 + $1 * $1 } }

    private static func combine(
        _ lhs: AnimatableValues, _ rhs: AnimatableValues, _ operation: (Double, Double) -> Double
    ) -> AnimatableValues {
        let count = max(lhs.values.count, rhs.values.count)
        var result = [Double](repeating: 0, count: count)
        for index in 0..<count {
            let left = index < lhs.values.count ? lhs.values[index] : 0
            let right = index < rhs.values.count ? rhs.values[index] : 0
            result[index] = operation(left, right)
        }
        return AnimatableValues(result)
    }
}

/// Where frequencies and decibels land on the graph. The curve's scale is ±12 dB; the spectrum's is −90…0 dB.
struct GraphScale {
    static let minFrequency = LevelMeter.spectrumRange.lowerBound
    static let maxFrequency = LevelMeter.spectrumRange.upperBound
    static let range: Double = 12
    /// 160 points evenly spaced on the log scale: smooth enough for the steepest shelf.
    static let frequencies: [Double] = (0..<160).map {
        minFrequency * pow(maxFrequency / minFrequency, Double($0) / 159)
    }
    /// The centre of each of the spectrum's bars.
    static let spectrumFrequencies: [Double] = (0..<LevelMeter.spectrumBands).map {
        minFrequency * pow(maxFrequency / minFrequency, (Double($0) + 0.5) / Double(LevelMeter.spectrumBands))
    }
    static let labelledFrequencies: [(Double, String)] = [
        (50, "50"), (100, "100"), (200, "200"), (500, "500"), (1000, "1K"), (2000, "2K"), (5000, "5K"),
        (10_000, "10K"),
    ]
    static let minorFrequencies: [Double] = [
        30, 40, 60, 70, 80, 90, 300, 400, 600, 700, 800, 900, 3000, 4000, 6000, 7000, 8000, 9000, 20_000,
    ]

    let size: CGSize
    /// Room above +12 and below −12 so the line and the points are not cut off.
    let padding: CGFloat = 10

    func x(_ frequency: Double) -> CGFloat {
        CGFloat(log(frequency / Self.minFrequency) / log(Self.maxFrequency / Self.minFrequency)) * size.width
    }

    func y(_ decibels: Double) -> CGFloat {
        let clamped = min(max(decibels, -Self.range - 0.8), Self.range + 0.8)
        return size.height / 2 - CGFloat(clamped / Self.range) * (size.height / 2 - padding)
    }

    /// The spectrum fills the whole height: its floor at the bottom, full scale at the top.
    func spectrumY(_ decibels: Float) -> CGFloat {
        let floor = Double(LevelMeter.spectrumFloor)
        let fraction = (min(max(Double(decibels), floor), 0) - floor) / -floor
        return size.height - CGFloat(fraction) * size.height
    }
}

/// The analyser: the live spectrum of what is playing, with the equaliser's response curve over it and the ten
/// band points on the curve. The width the dB labels take matches the faders' scale below, so the two line up.
struct SoundGraph: View {
    /// The response at `GraphScale.frequencies`; all zero while the equaliser is off.
    let curve: [Double]
    /// The response at each band's centre.
    let points: [Double]
    let isEnabled: Bool
    let isComparing: Bool
    let meter: LevelMeter
    /// The band a finger is on, highlighted on the curve.
    let activeBand: Int?

    static let height: CGFloat = 176
    static let scaleWidth: CGFloat = 28

    var body: some View {
        VStack(spacing: 4) {
            HStack(spacing: 0) {
                GraphDecibelScale()
                    .frame(width: Self.scaleWidth)
                ZStack {
                    GraphGrid()
                    SpectrumLayer(meter: meter)
                    CurveLayer(
                        curve: AnimatableValues(curve), points: AnimatableValues(points),
                        isLive: isEnabled && !isComparing, activeBand: activeBand)
                        .animation(.snappy(duration: 0.3), value: isEnabled)
                }
                .clipShape(.rect(cornerRadius: 6))
                .overlay(alignment: .topTrailing) { badge }
            }
            .frame(height: Self.height)
            HStack(spacing: 0) {
                Color.clear.frame(width: Self.scaleWidth)
                GraphFrequencyAxis()
            }
            .frame(height: 12)
        }
        .accessibilityElement()
        .accessibilityLabel("Frequency response and spectrum")
        .accessibilityValue(isComparing ? "Original" : isEnabled ? "Equaliser on" : "Equaliser off")
        .accessibilityIdentifier("sound.graph")
    }

    @ViewBuilder private var badge: some View {
        if isComparing {
            Label("ORIGINAL", systemImage: "ear")
                .font(.caption2.weight(.heavy))
                .foregroundStyle(BoomColor.onAccent)
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(BoomColor.accent, in: Capsule())
                .padding(8)
                .transition(.opacity.combined(with: .scale(scale: 0.9)))
        } else if !isEnabled {
            Text("OFF")
                .font(.caption2.weight(.heavy))
                .foregroundStyle(BoomColor.textSecondary)
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(BoomColor.surfaceRaised, in: Capsule())
                .padding(8)
        }
    }
}

private struct GraphDecibelScale: View {
    var body: some View {
        Canvas { context, size in
            let scale = GraphScale(size: size)
            for (decibels, label) in [(12.0, "+12"), (6, "+6"), (0, "0"), (-6, "−6"), (-12, "−12")] {
                context.draw(
                    Text(label).font(.system(size: 9, weight: .semibold).monospacedDigit())
                        .foregroundStyle(decibels == 0 ? BoomColor.textSecondary : BoomColor.textTertiary),
                    at: CGPoint(x: size.width - 5, y: scale.y(decibels)), anchor: .trailing)
            }
        }
        .accessibilityHidden(true)
    }
}

private struct GraphGrid: View {
    var body: some View {
        Canvas { context, size in
            let scale = GraphScale(size: size)
            context.fill(Path(CGRect(origin: .zero, size: size)), with: .color(Color.white.opacity(0.035)))
            var minor = Path()
            for frequency in GraphScale.minorFrequencies {
                minor.move(to: CGPoint(x: scale.x(frequency), y: 0))
                minor.addLine(to: CGPoint(x: scale.x(frequency), y: size.height))
            }
            context.stroke(minor, with: .color(Color.white.opacity(0.04)), lineWidth: 1)
            var major = Path()
            for (frequency, _) in GraphScale.labelledFrequencies {
                major.move(to: CGPoint(x: scale.x(frequency), y: 0))
                major.addLine(to: CGPoint(x: scale.x(frequency), y: size.height))
            }
            for decibels in [-12.0, -6, 6, 12] {
                major.move(to: CGPoint(x: 0, y: scale.y(decibels)))
                major.addLine(to: CGPoint(x: size.width, y: scale.y(decibels)))
            }
            context.stroke(major, with: .color(Color.white.opacity(0.08)), lineWidth: 1)
            var zero = Path()
            zero.move(to: CGPoint(x: 0, y: scale.y(0)))
            zero.addLine(to: CGPoint(x: size.width, y: scale.y(0)))
            context.stroke(zero, with: .color(Color.white.opacity(0.22)), lineWidth: 1)
        }
        .accessibilityHidden(true)
    }
}

private struct GraphFrequencyAxis: View {
    var body: some View {
        Canvas { context, size in
            let scale = GraphScale(size: size)
            for (frequency, label) in GraphScale.labelledFrequencies {
                context.draw(
                    Text(label).font(.system(size: 9, weight: .semibold).monospacedDigit())
                        .foregroundStyle(BoomColor.textTertiary),
                    at: CGPoint(x: scale.x(frequency), y: size.height / 2))
            }
            context.draw(
                Text("Hz").font(.system(size: 9, weight: .semibold)).foregroundStyle(BoomColor.textTertiary),
                at: CGPoint(x: size.width - 1, y: size.height / 2), anchor: .trailing)
        }
        .accessibilityHidden(true)
    }
}

/// The live spectrum. The only part of the graph that reads the meter, so only it redraws thirty times a second.
private struct SpectrumLayer: View {
    let meter: LevelMeter

    var body: some View {
        let bars = meter.spectrum
        Canvas { context, size in
            guard bars.count == GraphScale.spectrumFrequencies.count, bars.contains(where: { $0 > LevelMeter.spectrumFloor + 1 })
            else { return }
            let scale = GraphScale(size: size)
            let points = zip(GraphScale.spectrumFrequencies, bars).map { CGPoint(x: scale.x($0), y: scale.spectrumY($1)) }
            var outline = Path()
            outline.move(to: CGPoint(x: 0, y: points[0].y))
            outline.addLine(to: points[0])
            // Through the midpoints between bars, with each bar as the control point: a smooth line, no overshoot.
            for index in 1..<points.count {
                let middle = CGPoint(x: (points[index - 1].x + points[index].x) / 2, y: (points[index - 1].y + points[index].y) / 2)
                outline.addQuadCurve(to: middle, control: points[index - 1])
            }
            outline.addLine(to: points[points.count - 1])
            outline.addLine(to: CGPoint(x: size.width, y: points[points.count - 1].y))
            var fill = outline
            fill.addLine(to: CGPoint(x: size.width, y: size.height))
            fill.addLine(to: CGPoint(x: 0, y: size.height))
            fill.closeSubpath()
            context.fill(
                fill,
                with: .linearGradient(
                    Gradient(colors: [Color.white.opacity(0.20), Color.white.opacity(0.03)]),
                    startPoint: CGPoint(x: 0, y: 0), endPoint: CGPoint(x: 0, y: size.height)))
            context.stroke(outline, with: .color(Color.white.opacity(0.32)), lineWidth: 1)
        }
        .accessibilityHidden(true)
    }
}

private struct CurveLayer: View, @MainActor Animatable {
    var curve: AnimatableValues
    var points: AnimatableValues
    let isLive: Bool
    let activeBand: Int?

    var animatableData: AnimatablePair<AnimatableValues, AnimatableValues> {
        get { AnimatablePair(curve, points) }
        set {
            curve = newValue.first
            points = newValue.second
        }
    }

    var body: some View {
        Canvas { context, size in
            let scale = GraphScale(size: size)
            let values = curve.values
            guard values.count == GraphScale.frequencies.count else { return }
            let tint = isLive ? BoomColor.accent : Color.white.opacity(0.4)
            var line = Path()
            for (index, frequency) in GraphScale.frequencies.enumerated() {
                let point = CGPoint(x: scale.x(frequency), y: scale.y(values[index]))
                index == 0 ? line.move(to: point) : line.addLine(to: point)
            }
            var fill = line
            fill.addLine(to: CGPoint(x: size.width, y: scale.y(0)))
            fill.addLine(to: CGPoint(x: 0, y: scale.y(0)))
            fill.closeSubpath()
            context.fill(
                fill,
                with: .linearGradient(
                    Gradient(stops: [
                        .init(color: tint.opacity(0.32), location: 0),
                        .init(color: tint.opacity(0.03), location: 0.5),
                        .init(color: tint.opacity(0.32), location: 1),
                    ]),
                    startPoint: CGPoint(x: 0, y: scale.y(12)), endPoint: CGPoint(x: 0, y: scale.y(-12))))
            if isLive {
                context.drawLayer { glow in
                    glow.addFilter(.blur(radius: 4))
                    glow.stroke(line, with: .color(tint.opacity(0.55)), lineWidth: 4)
                }
            }
            context.stroke(line, with: .color(tint), style: StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))

            for (index, band) in EqualizerStore.bands.enumerated() where index < points.values.count {
                let center = CGPoint(x: scale.x(band.frequency), y: scale.y(points.values[index]))
                let isActive = index == activeBand
                let radius: CGFloat = isActive ? 6 : 4
                if isActive {
                    let halo = Path(ellipseIn: CGRect(x: center.x - 13, y: center.y - 13, width: 26, height: 26))
                    context.fill(halo, with: .color(tint.opacity(0.22)))
                }
                let dot = Path(ellipseIn: CGRect(x: center.x - radius, y: center.y - radius, width: radius * 2, height: radius * 2))
                context.fill(dot, with: .color(isActive ? tint : BoomColor.background))
                context.stroke(dot, with: .color(tint), lineWidth: 2)
            }
        }
        .accessibilityHidden(true)
    }
}
