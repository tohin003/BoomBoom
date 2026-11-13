import SwiftUI

// BoomBoom's look: near-black surfaces, white text, one accent (yellow). Owned by the lead: screens use these
// tokens and do not invent colours or sizes of their own. Ask the lead if a token is missing.

public enum BoomColor {
    /// The app's background.
    public static let background = Color(hex: 0x030303)
    /// Cards, sheets, the mini player and the tab bar.
    public static let surface = Color(hex: 0x121212)
    /// Things raised above a surface: menus, pressed rows, text fields.
    public static let surfaceRaised = Color(hex: 0x212121)
    /// The accent. Where a red would mark "this is the brand" or "this is active", BoomBoom is yellow.
    public static let accent = Color(hex: 0xFFD60A)
    /// A deeper yellow for gradients and pressed accents.
    public static let accentDeep = Color(hex: 0xFFB800)
    /// Text and glyphs on top of the accent.
    public static let onAccent = Color.black
    public static let textPrimary = Color.white
    public static let textSecondary = Color.white.opacity(0.7)
    public static let textTertiary = Color.white.opacity(0.5)
    public static let separator = Color.white.opacity(0.1)
    /// An unselected filter pill.
    public static let chip = Color.white.opacity(0.1)
    /// A selected filter pill (white with black text).
    public static let chipSelected = Color.white
    public static let destructive = Color(hex: 0xFF5A52)
}

public enum BoomFont {
    /// Page titles ("Library", an album's name).
    public static let pageTitle = Font.system(.title, design: .default, weight: .bold)
    /// Shelf headers ("Quick picks").
    public static let shelfTitle = Font.system(.title2, design: .default, weight: .bold)
    /// The small capitals line above a shelf header.
    public static let strapline = Font.system(.footnote, design: .default, weight: .regular)
    /// A row's or card's first line.
    public static let itemTitle = Font.system(.body, design: .default, weight: .medium)
    /// A row's or card's second line.
    public static let itemSubtitle = Font.system(.subheadline, design: .default, weight: .regular)
    public static let button = Font.system(.subheadline, design: .default, weight: .semibold)
    public static let caption = Font.system(.caption, design: .default, weight: .regular)
    /// Times next to the scrubber.
    public static let time = Font.system(.caption, design: .default, weight: .regular).monospacedDigit()
}

public enum BoomMetrics {
    /// The gap between the screen's edge and content.
    public static let margin: CGFloat = 16
    /// Corner radius of covers.
    public static let coverRadius: CGFloat = 4
    /// Corner radius of cards, sheets' inner boxes and large covers.
    public static let cardRadius: CGFloat = 8
    /// A row's cover.
    public static let rowCover: CGFloat = 56
    /// A carousel card's cover.
    public static let cardCover: CGFloat = 160
    public static let rowSpacing: CGFloat = 12
    public static let shelfSpacing: CGFloat = 28
    /// The mini player bar's height. The frame leaves this much room above the tab bar while a song is loaded.
    public static let miniPlayerHeight: CGFloat = 64
    /// The tab bar's height above the home indicator.
    public static let tabBarHeight: CGFloat = 56
    /// The least a tappable thing may measure.
    public static let minimumTarget: CGFloat = 44
}

extension Color {
    /// A colour from 0xRRGGBB.
    public init(hex: UInt32, opacity: Double = 1) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: opacity)
    }
}

/// Seconds as "3:45" or "1:02:03".
public func boomTime(_ seconds: TimeInterval) -> String {
    guard seconds.isFinite, seconds >= 0 else { return "0:00" }
    let total = Int(seconds.rounded(.down))
    let hours = total / 3600, minutes = (total % 3600) / 60, rest = total % 60
    return hours > 0
        ? String(format: "%d:%02d:%02d", hours, minutes, rest)
        : String(format: "%d:%02d", minutes, rest)
}
