import SwiftUI

/// The outlined pill of the genre: "More", "Retry", "Subscribe".
public struct OutlinePillButtonStyle: ButtonStyle {
    private let isCompact: Bool

    /// - Parameter isCompact: The small header variant ("More").
    public init(isCompact: Bool = false) {
        self.isCompact = isCompact
    }

    public func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(isCompact ? BoomFont.caption.weight(.semibold) : BoomFont.button)
            .foregroundStyle(BoomColor.textPrimary)
            .padding(.horizontal, isCompact ? 12 : 18)
            .frame(minHeight: isCompact ? 30 : 36)
            .overlay(Capsule().strokeBorder(Color.white.opacity(0.25), lineWidth: 1))
            .background(configuration.isPressed ? BoomColor.surfaceRaised : .clear, in: Capsule())
            .frame(minHeight: BoomMetrics.minimumTarget)
            .contentShape(Rectangle())
    }
}

/// A white pill with black text and an optional glyph: "Play" on a top result.
public struct FilledPillButtonStyle: ButtonStyle {
    public init() {}

    public func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(BoomFont.button)
            .foregroundStyle(Color.black)
            .padding(.horizontal, 18)
            .frame(minHeight: 36)
            .background(Color.white.opacity(configuration.isPressed ? 0.8 : 1), in: Capsule())
            .frame(minHeight: BoomMetrics.minimumTarget)
            .contentShape(Rectangle())
    }
}

/// A translucent grey pill: secondary actions over pictures ("Shuffle", "Radio" on an artist).
public struct TonalPillButtonStyle: ButtonStyle {
    public init() {}

    public func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(BoomFont.button)
            .foregroundStyle(BoomColor.textPrimary)
            .padding(.horizontal, 18)
            .frame(minHeight: 36)
            .background(Color.white.opacity(configuration.isPressed ? 0.25 : 0.15), in: Capsule())
            .frame(minHeight: BoomMetrics.minimumTarget)
            .contentShape(Rectangle())
    }
}

/// The big round Play button of album, playlist and artist pages: a yellow circle with a black glyph.
public struct AccentPlayButton: View {
    private let size: CGFloat
    private let systemImage: String
    private let label: String
    private let action: () -> Void

    public init(
        size: CGFloat = 64, systemImage: String = "play.fill", label: String = "Play", action: @escaping () -> Void
    ) {
        self.size = size
        self.systemImage = systemImage
        self.label = label
        self.action = action
    }

    public var body: some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: size * 0.4, weight: .bold))
                .foregroundStyle(BoomColor.onAccent)
                .offset(x: systemImage.hasPrefix("play") ? size * 0.03 : 0)
                .frame(width: size, height: size)
                .background(BoomColor.accent, in: Circle())
                .contentShape(Circle())
        }
        .buttonStyle(CardButtonStyle())
        .accessibilityLabel(label)
    }
}

/// A round icon button on a dark page: download, save, share, shuffle beside the Play button.
public struct CircleIconButton: View {
    private let systemImage: String
    private let label: String
    private let isActive: Bool
    private let action: () -> Void

    /// - Parameter isActive: Drawn in the accent ("saved", "liked").
    public init(_ systemImage: String, label: String, isActive: Bool = false, action: @escaping () -> Void) {
        self.systemImage = systemImage
        self.label = label
        self.isActive = isActive
        self.action = action
    }

    public var body: some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: 18, weight: .medium))
                .foregroundStyle(isActive ? BoomColor.accent : BoomColor.textPrimary)
                .frame(width: 44, height: 44)
                .background(Color.white.opacity(0.1), in: Circle())
                .contentShape(Circle())
        }
        .buttonStyle(CardButtonStyle())
        .accessibilityLabel(label)
    }
}
