import BoomDesign
import BoomModels
import BoomPlayer
import SwiftUI

/// How far a page has scrolled, kept apart from the page's state so only the views that move with it redraw.
@MainActor
@Observable
final class ScrollTracker {
    var offset: CGFloat = 0
}

/// The soft, blurred copy of a cover behind an album's or playlist's header, fading into the background and
/// sliding up with the page.
struct CollectionBackdrop: View {
    let artwork: Artwork
    let tracker: ScrollTracker
    var height: CGFloat = 470

    var body: some View {
        // The cover is drawn small (sharing the header cover's download) and blown up: a blur hides the pixels.
        ArtworkView(artwork, width: 240, cornerRadius: 0)
            .blur(radius: 26, opaque: true)
            .scaleEffect(2.6)
            .frame(maxWidth: .infinity)
            .frame(height: height)
            .clipped()
            .opacity(0.6)
            .overlay {
                LinearGradient(
                    stops: [
                        .init(color: BoomColor.background.opacity(0.15), location: 0),
                        .init(color: BoomColor.background.opacity(0.55), location: 0.55),
                        .init(color: BoomColor.background, location: 1),
                    ],
                    startPoint: .top, endPoint: .bottom)
            }
            .offset(y: -max(0, tracker.offset))
            .ignoresSafeArea(edges: .top)
            .allowsHitTesting(false)
            .accessibilityHidden(true)
    }
}

/// The large centred cover, title and lines at the top of an album or playlist.
struct CollectionHeader<Lines: View, Actions: View>: View {
    let artwork: Artwork
    let title: String
    var isExplicit = false
    var description: String?
    @ViewBuilder let lines: () -> Lines
    @ViewBuilder let actions: () -> Actions

    var body: some View {
        VStack(spacing: 0) {
            ArtworkView(artwork, width: 240, cornerRadius: BoomMetrics.cardRadius)
                .shadow(color: .black.opacity(0.45), radius: 20, y: 10)
                .padding(.bottom, 20)
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(title)
                    .font(.system(.title2, weight: .bold))
                    .foregroundStyle(BoomColor.textPrimary)
                    .multilineTextAlignment(.center)
                    .lineLimit(3)
                    .accessibilityAddTraits(.isHeader)
                if isExplicit { ExplicitBadge() }
            }
            .padding(.bottom, 6)
            VStack(spacing: 4) {
                lines()
            }
            .font(BoomFont.itemSubtitle)
            .foregroundStyle(BoomColor.textSecondary)
            .multilineTextAlignment(.center)
            if let description, !description.isEmpty {
                ExpandableText(description, collapsedLines: 2)
                    .padding(.top, 12)
            }
            actions()
                .padding(.top, description?.isEmpty == false ? 4 : 18)
        }
        .padding(.horizontal, BoomMetrics.margin)
        .padding(.top, 8)
        .padding(.bottom, 16)
    }
}

/// The row of buttons under a header: two round buttons, the big yellow Play, two more round buttons.
struct CollectionActions<Leading: View, Trailing: View>: View {
    let play: () -> Void
    @ViewBuilder let leading: () -> Leading
    @ViewBuilder let trailing: () -> Trailing

    var body: some View {
        HStack(spacing: 0) {
            HStack(spacing: 14) { leading() }
                .frame(maxWidth: .infinity, alignment: .trailing)
            AccentPlayButton(size: 64, action: play)
                .padding(.horizontal, 22)
            HStack(spacing: 14) { trailing() }
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

/// A round "more" button that opens an item's menu.
struct CircleMenuButton: View {
    let item: MusicItem

    var body: some View {
        Menu {
            ItemMenu(item)
        } label: {
            Image(systemName: "ellipsis")
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(BoomColor.textPrimary)
                .frame(width: 44, height: 44)
                .background(Color.white.opacity(0.1), in: Circle())
                .contentShape(Circle())
        }
        .accessibilityLabel("More actions")
    }
}

/// A round share button.
struct CircleShareButton: View {
    let item: MusicItem

    var body: some View {
        if let url = item.shareURL {
            ShareLink(item: url, subject: Text(item.title)) {
                Image(systemName: "square.and.arrow.up")
                    .font(.system(size: 17, weight: .medium))
                    .foregroundStyle(BoomColor.textPrimary)
                    .frame(width: 44, height: 44)
                    .background(Color.white.opacity(0.1), in: Circle())
                    .contentShape(Circle())
            }
            .accessibilityLabel("Share")
        }
    }
}
