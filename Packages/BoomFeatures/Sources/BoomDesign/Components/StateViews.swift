import SwiftUI

/// Placeholders shaped like the content that is on its way, with a soft shimmer.
public struct LoadingView: View {
    /// The shape of the page being loaded.
    public enum Style: Sendable {
        /// Shelves of cards and rows (Home, Explore, a page of shelves).
        case shelves
        /// A plain list of rows (filtered search, a library tab).
        case list
        /// A big centred cover, title lines and a tracklist (album, playlist).
        case collection
        /// A wide banner, a name and shelves (artist).
        case artist
    }

    private let style: Style

    public init(_ style: Style = .shelves) {
        self.style = style
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: BoomMetrics.shelfSpacing) {
            switch style {
            case .shelves:
                SkeletonColumns()
                SkeletonCarousel()
                SkeletonCarousel()
            case .list:
                SkeletonRows(count: 9)
            case .collection:
                VStack(spacing: 14) {
                    SkeletonBox(width: 240, height: 240, radius: BoomMetrics.cardRadius)
                    SkeletonBox(width: 200, height: 22)
                    SkeletonBox(width: 140, height: 14)
                    HStack(spacing: 20) {
                        ForEach(0..<2, id: \.self) { _ in SkeletonBox(width: 44, height: 44, radius: 22) }
                        SkeletonBox(width: 64, height: 64, radius: 32)
                        ForEach(0..<2, id: \.self) { _ in SkeletonBox(width: 44, height: 44, radius: 22) }
                    }
                    .padding(.top, 6)
                }
                .frame(maxWidth: .infinity)
                .padding(.top, 12)
                SkeletonRows(count: 6, covers: false)
            case .artist:
                VStack(alignment: .leading, spacing: 12) {
                    SkeletonBox(height: 260, radius: 0)
                    SkeletonBox(width: 120, height: 14).padding(.horizontal, BoomMetrics.margin)
                    HStack(spacing: 10) {
                        SkeletonBox(width: 110, height: 36, radius: 18)
                        SkeletonBox(width: 110, height: 36, radius: 18)
                    }
                    .padding(.horizontal, BoomMetrics.margin)
                }
                SkeletonRows(count: 5)
            }
        }
        .frame(maxWidth: .infinity, alignment: .topLeading)
        .shimmering()
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Loading")
    }
}

/// A grey block in a skeleton.
public struct SkeletonBox: View {
    private let width: CGFloat?
    private let height: CGFloat
    private let radius: CGFloat

    public init(width: CGFloat? = nil, height: CGFloat, radius: CGFloat = 4) {
        self.width = width
        self.height = height
        self.radius = radius
    }

    public var body: some View {
        RoundedRectangle(cornerRadius: radius)
            .fill(BoomColor.surfaceRaised)
            .frame(width: width, height: height)
            .frame(maxWidth: width == nil ? .infinity : nil)
    }
}

struct SkeletonRows: View {
    var count: Int
    var covers = true

    var body: some View {
        VStack(alignment: .leading, spacing: BoomMetrics.rowSpacing) {
            ForEach(0..<count, id: \.self) { index in
                HStack(spacing: BoomMetrics.rowSpacing) {
                    if covers {
                        SkeletonBox(width: BoomMetrics.rowCover, height: BoomMetrics.rowCover)
                    } else {
                        SkeletonBox(width: 16, height: 14).padding(.horizontal, 6)
                    }
                    VStack(alignment: .leading, spacing: 8) {
                        SkeletonBox(width: [180, 140, 210, 160][index % 4], height: 14)
                        SkeletonBox(width: [120, 160, 100, 140][index % 4], height: 12)
                    }
                }
                .frame(height: BoomMetrics.rowCover + 4)
            }
        }
        .padding(.horizontal, BoomMetrics.margin)
    }
}

struct SkeletonCarousel: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            SkeletonBox(width: 170, height: 22)
                .padding(.horizontal, BoomMetrics.margin)
            HStack(spacing: 12) {
                ForEach(0..<3, id: \.self) { _ in
                    VStack(alignment: .leading, spacing: 8) {
                        SkeletonBox(
                            width: BoomMetrics.cardCover, height: BoomMetrics.cardCover, radius: BoomMetrics.cardRadius)
                        SkeletonBox(width: 120, height: 13)
                        SkeletonBox(width: 90, height: 11)
                    }
                }
            }
            .padding(.horizontal, BoomMetrics.margin)
            .frame(maxWidth: .infinity, alignment: .leading)
            .clipped()
        }
    }
}

struct SkeletonColumns: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            SkeletonBox(width: 90, height: 11).padding(.horizontal, BoomMetrics.margin)
            SkeletonBox(width: 150, height: 22).padding(.horizontal, BoomMetrics.margin)
            SkeletonRows(count: 4)
        }
    }
}

extension View {
    /// A light band sweeping across, for skeletons.
    public func shimmering() -> some View {
        modifier(Shimmer())
    }
}

private struct Shimmer: ViewModifier {
    @State private var phase: CGFloat = -1
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        content
            .overlay {
                if !reduceMotion {
                    GeometryReader { proxy in
                        LinearGradient(
                            colors: [.clear, Color.white.opacity(0.07), .clear],
                            startPoint: .leading, endPoint: .trailing
                        )
                        .frame(width: proxy.size.width * 0.6)
                        .offset(x: phase * proxy.size.width * 1.4)
                    }
                    .mask(content)
                    .allowsHitTesting(false)
                }
            }
            .onAppear {
                withAnimation(.linear(duration: 1.3).repeatForever(autoreverses: false)) { phase = 1 }
            }
    }
}

/// A message with a Retry button, for a page that could not load.
public struct ErrorView: View {
    private let message: String
    private let retry: (() -> Void)?

    public init(_ message: String = "Couldn't load this page.", retry: (() -> Void)? = nil) {
        self.message = message
        self.retry = retry
    }

    public var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "wifi.exclamationmark")
                .font(.system(size: 36, weight: .regular))
                .foregroundStyle(BoomColor.textTertiary)
                .accessibilityHidden(true)
            Text(message)
                .font(BoomFont.itemTitle)
                .foregroundStyle(BoomColor.textSecondary)
                .multilineTextAlignment(.center)
            if let retry {
                Button("Retry", action: retry)
                    .buttonStyle(OutlinePillButtonStyle())
            }
        }
        .padding(32)
        .frame(maxWidth: .infinity)
        .padding(.top, 80)
    }
}

/// A quiet message for an empty list ("No results", "Nothing here yet").
public struct MessageView: View {
    private let title: String
    private let message: String?
    private let systemImage: String

    public init(_ title: String, message: String? = nil, systemImage: String = "music.note.list") {
        self.title = title
        self.message = message
        self.systemImage = systemImage
    }

    public var body: some View {
        VStack(spacing: 12) {
            Image(systemName: systemImage)
                .font(.system(size: 36))
                .foregroundStyle(BoomColor.textTertiary)
                .accessibilityHidden(true)
            Text(title)
                .font(.system(.title3, weight: .semibold))
                .foregroundStyle(BoomColor.textPrimary)
                .multilineTextAlignment(.center)
            if let message {
                Text(message)
                    .font(BoomFont.itemSubtitle)
                    .foregroundStyle(BoomColor.textSecondary)
                    .multilineTextAlignment(.center)
            }
        }
        .padding(32)
        .frame(maxWidth: .infinity)
        .padding(.top, 60)
    }
}
