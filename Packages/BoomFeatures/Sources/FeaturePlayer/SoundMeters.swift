import BoomDesign
import BoomPlayer
import SwiftUI

// The only views that read `LevelMeter`. It changes thirty times a second; anything that reads it redraws that
// often, so each meter is its own small view and the screen around them never touches it.

/// Left and right output, −60…0 dBFS: the average (RMS) level solid, the peak as the bar's lighter tip, a peak-hold
/// mark, and a clip light.
struct SoundOutputMeters: View {
    let meter: LevelMeter

    private static let marks: [Float] = [-60, -40, -30, -20, -12, -6, -3, 0]
    private static let labelWidth: CGFloat = 16
    private static let lightWidth: CGFloat = 30

    var body: some View {
        let channels = [
            Channel(name: "L", peak: meter.left, rms: meter.rmsLeft, hold: meter.holdLeft),
            Channel(name: "R", peak: meter.right, rms: meter.rmsRight, hold: meter.holdRight),
        ]
        let isClipping = meter.isClipping
        VStack(spacing: 6) {
            Canvas { context, size in
                draw(&context, size: size, channels: channels, isClipping: isClipping)
            }
            .frame(height: 38)
            Canvas { context, size in
                let width = size.width - Self.labelWidth - Self.lightWidth
                for mark in Self.marks {
                    context.draw(
                        Text(mark == 0 ? "0" : "\(Int(mark))".replacingOccurrences(of: "-", with: "−"))
                            .font(.system(size: 9, weight: .semibold).monospacedDigit())
                            .foregroundStyle(BoomColor.textTertiary),
                        at: CGPoint(x: Self.labelWidth + Self.position(mark) * width, y: size.height / 2))
                }
                context.draw(
                    Text("CLIP").font(.system(size: 8, weight: .heavy)).foregroundStyle(BoomColor.textTertiary),
                    at: CGPoint(x: size.width - Self.lightWidth / 2 + 3, y: size.height / 2))
            }
            .frame(height: 10)
        }
        .accessibilityElement()
        .accessibilityLabel("Output level")
        .accessibilityValue(
            "Left \(Int(meter.left.rounded())) dB, right \(Int(meter.right.rounded())) dB peak" + (isClipping ? ", clipping" : ""))
        .accessibilityIdentifier("sound.output")
    }

    private struct Channel {
        let name: String
        let peak: Float
        let rms: Float
        let hold: Float
    }

    /// 0…1 along the bar. A gentle curve gives the loud end, where it matters, more room.
    static func position(_ decibels: Float) -> CGFloat {
        let linear = CGFloat((min(max(decibels, LevelMeter.floor), 0) - LevelMeter.floor) / -LevelMeter.floor)
        return pow(linear, 1.6)
    }

    private func draw(_ context: inout GraphicsContext, size: CGSize, channels: [Channel], isClipping: Bool) {
        let barHeight: CGFloat = 14
        let width = size.width - Self.labelWidth - Self.lightWidth
        for (row, channel) in channels.enumerated() {
            let top = CGFloat(row) * (size.height - barHeight)
            context.draw(
                Text(channel.name).font(.system(size: 10, weight: .heavy)).foregroundStyle(BoomColor.textTertiary),
                at: CGPoint(x: 5, y: top + barHeight / 2))
            let track = CGRect(x: Self.labelWidth, y: top, width: width, height: barHeight)
            context.fill(Path(roundedRect: track, cornerRadius: 3), with: .color(Color.white.opacity(0.07)))

            var bar = context
            bar.clip(to: Path(roundedRect: track, cornerRadius: 3))
            let colours = MeterColours.gradient
            let peakX = Self.position(channel.peak) * width
            if peakX > 0.5 {
                bar.opacity = 0.42
                bar.fill(
                    Path(CGRect(x: track.minX, y: top, width: peakX, height: barHeight)),
                    with: .linearGradient(colours, startPoint: CGPoint(x: track.minX, y: 0), endPoint: CGPoint(x: track.maxX, y: 0)))
                bar.opacity = 1
            }
            let rmsX = Self.position(channel.rms) * width
            if rmsX > 0.5 {
                bar.fill(
                    Path(CGRect(x: track.minX, y: top, width: rmsX, height: barHeight)),
                    with: .linearGradient(colours, startPoint: CGPoint(x: track.minX, y: 0), endPoint: CGPoint(x: track.maxX, y: 0)))
            }
            // Segment gaps give the bar its hardware look.
            var gaps = Path()
            var x = track.minX + 5
            while x < track.maxX {
                gaps.addRect(CGRect(x: x, y: top, width: 1.5, height: barHeight))
                x += 5
            }
            bar.fill(gaps, with: .color(BoomColor.surface.opacity(0.75)))

            if channel.hold > LevelMeter.floor + 1 {
                let holdX = track.minX + Self.position(channel.hold) * width
                context.fill(
                    Path(CGRect(x: min(holdX, track.maxX - 2) - 1, y: top - 1, width: 2, height: barHeight + 2)),
                    with: .color(channel.hold >= -0.5 ? BoomColor.destructive : BoomColor.textPrimary))
            }
            let light = CGRect(x: size.width - Self.lightWidth + 8, y: top, width: Self.lightWidth - 10, height: barHeight)
            context.fill(
                Path(roundedRect: light, cornerRadius: 3),
                with: .color(isClipping ? BoomColor.destructive : Color.white.opacity(0.08)))
        }
    }
}

private enum MeterColours {
    /// Green-free on purpose: the app's yellow up to −6 dB, deepening to amber, red at full scale.
    static let gradient = Gradient(stops: [
            .init(color: BoomColor.accent.opacity(0.75), location: 0),
            .init(color: BoomColor.accent, location: 0.72),
            .init(color: BoomColor.accentDeep, location: 0.9),
            .init(color: BoomColor.destructive, location: 1),
    ])
}

/// How hard the limiter is working: a bar that grows downward from 0 to −12 dB, and the figure. Beside the boost so
/// the listener sees the boost being caught.
struct LimiterMeter: View {
    let meter: LevelMeter
    let isLimiterOn: Bool
    let isEnabled: Bool

    private static let scale: Float = 12

    var body: some View {
        let reduction = isEnabled && isLimiterOn ? meter.gainReduction : 0
        let isActive = reduction >= 0.1
        VStack(spacing: 6) {
            Text("LIMITER")
                .font(.system(size: 9, weight: .heavy))
                .foregroundStyle(BoomColor.textTertiary)
                .fixedSize()
            HStack(alignment: .top, spacing: 4) {
                Canvas { context, size in
                    let track = CGRect(origin: .zero, size: size)
                    context.fill(Path(roundedRect: track, cornerRadius: 4), with: .color(Color.white.opacity(0.07)))
                    let depth = CGFloat(min(reduction, Self.scale) / Self.scale) * size.height
                    if depth > 0.5 {
                        context.fill(
                            Path(roundedRect: CGRect(x: 0, y: 0, width: size.width, height: depth), cornerRadius: 4),
                            with: .linearGradient(
                                Gradient(colors: [BoomColor.accentDeep, BoomColor.destructive]),
                                startPoint: .zero, endPoint: CGPoint(x: 0, y: size.height)))
                    }
                    var ticks = Path()
                    for decibels in stride(from: 3, to: Self.scale, by: 3) {
                        let y = CGFloat(Float(decibels) / Self.scale) * size.height
                        ticks.addRect(CGRect(x: 0, y: y, width: size.width, height: 1))
                    }
                    context.fill(ticks, with: .color(BoomColor.surface.opacity(0.8)))
                }
                .frame(width: 14)
                Canvas { context, size in
                    for decibels in [0, 3, 6, 9, 12] {
                        context.draw(
                            Text(decibels == 0 ? "0" : "−\(decibels)")
                                .font(.system(size: 8, weight: .semibold).monospacedDigit())
                                .foregroundStyle(BoomColor.textTertiary),
                            at: CGPoint(x: 0, y: min(max(CGFloat(Float(decibels) / Self.scale) * size.height, 5), size.height - 5)),
                            anchor: .leading)
                    }
                }
                .frame(width: 18)
            }
            .frame(maxHeight: .infinity)
            Text(!isLimiterOn ? "Off" : isActive ? String(format: "−%.1f", reduction) : "Idle")
                .font(BoomFont.time.weight(.bold))
                .foregroundStyle(isActive ? BoomColor.accent : BoomColor.textTertiary)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        }
        .frame(width: 46)
        .accessibilityElement()
        .accessibilityLabel("Limiter")
        .accessibilityValue(
            !isLimiterOn ? "Off" : isActive ? String(format: "Holding back %.1f dB", reduction) : "Idle")
        .accessibilityIdentifier("sound.limiterMeter")
    }
}

/// A red warning while the output goes past full scale (possible only with clip protection off).
struct ClipWarning: View {
    let meter: LevelMeter
    let isLimiterOn: Bool

    var body: some View {
        let isClipping = !isLimiterOn && meter.isClipping
        if !isLimiterOn {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: isClipping ? "exclamationmark.triangle.fill" : "exclamationmark.triangle")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundStyle(isClipping ? BoomColor.onAccent : BoomColor.destructive)
                VStack(alignment: .leading, spacing: 2) {
                    Text(isClipping ? "Clipping: the sound is distorting" : "Clip protection is off")
                        .font(BoomFont.button)
                    Text("Loud passages can distort. Turn clip protection on, or lower the boost or preamp.")
                        .font(BoomFont.caption)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .foregroundStyle(isClipping ? BoomColor.onAccent : BoomColor.textPrimary)
                Spacer(minLength: 0)
            }
            .padding(12)
            .background(
                isClipping ? BoomColor.destructive : BoomColor.destructive.opacity(0.14),
                in: .rect(cornerRadius: BoomMetrics.cardRadius + 2))
            .animation(.easeOut(duration: 0.12), value: isClipping)
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("sound.clipWarning")
        }
    }
}
