import BoomDesign
import BoomModels
import BoomPlayer
import SwiftUI

/// The tab's top bar: the title, then history, search and the account. Home's `TopBar` has no room for a third
/// button, so this one repeats its layout.
struct LibraryTopBar: View {
    @Environment(AppServices.self) private var services
    @Environment(\.isShellTabActive) private var isTabActive

    var body: some View {
        HStack(spacing: 4) {
            Text("Library")
                .font(BoomFont.pageTitle)
                .foregroundStyle(BoomColor.textPrimary)
                .accessibilityAddTraits(.isHeader)
            Spacer()
            NavigationLink {
                HistoryScreen()
                    .reservesShellBars()
            } label: {
                barIcon("clock.arrow.circlepath", size: 20)
            }
            .accessibilityLabel("History")
            NavigationLink(value: BrowseRoute.search(query: nil)) {
                barIcon("magnifyingglass", size: 20)
            }
            .accessibilityLabel("Search")
            accountButton
        }
        .foregroundStyle(BoomColor.textPrimary)
        .padding(.leading, BoomMetrics.margin)
        .padding(.trailing, 8)
        .frame(height: 52)
        .outOfVoiceOversReach(!isTabActive)
    }

    private func barIcon(_ name: String, size: CGFloat) -> some View {
        Image(systemName: name)
            .font(.system(size: size, weight: .medium))
            .frame(width: BoomMetrics.minimumTarget, height: BoomMetrics.minimumTarget)
            .contentShape(Rectangle())
    }

    /// Straight to Settings, signed in or not: the accounts, the sound, the cache and downloads are all there, and
    /// signed out the page itself offers the sign-in.
    private var accountButton: some View {
        NavigationLink(value: BrowseRoute.settings) {
            Group {
                if let account = services.session.account {
                    ArtworkView(account.artwork, width: 30, isCircle: true)
                } else if services.session.isSignedIn {
                    Image(systemName: "person.crop.circle.fill").font(.system(size: 26))
                } else {
                    Image(systemName: "person.crop.circle").font(.system(size: 24))
                }
            }
            .frame(width: BoomMetrics.minimumTarget, height: BoomMetrics.minimumTarget)
            .contentShape(Rectangle())
        }
        .accessibilityLabel("Account and settings")
        .accessibilityIdentifier("library.account")
    }
}

/// What a screen of personal data says while nobody is signed in.
struct SignedOutView: View {
    let title: String
    let message: String

    @Environment(\.boomNavigator) private var navigator

    var body: some View {
        VStack(spacing: 20) {
            VStack(spacing: 12) {
                Image(systemName: "person.crop.circle.badge.questionmark")
                    .font(.system(size: 40))
                    .foregroundStyle(BoomColor.textTertiary)
                    .accessibilityHidden(true)
                Text(title)
                    .font(.system(.title3, weight: .semibold))
                    .foregroundStyle(BoomColor.textPrimary)
                    .multilineTextAlignment(.center)
                Text(message)
                    .font(BoomFont.itemSubtitle)
                    .foregroundStyle(BoomColor.textSecondary)
                    .multilineTextAlignment(.center)
            }
            Button("Sign in") { navigator.signIn() }
                .buttonStyle(FilledPillButtonStyle())
        }
        .padding(32)
        .frame(maxWidth: .infinity)
        .padding(.top, 60)
    }
}

/// A row that darkens a little while pressed, like `ItemRow`.
struct LibraryRowStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background {
                if configuration.isPressed {
                    BoomColor.surfaceRaised.opacity(0.6)
                        .padding(.horizontal, -BoomMetrics.margin)
                        .padding(.vertical, -4)
                }
            }
    }
}

/// The two rows pinned above the playlists: "New playlist" and "Liked music".
struct PinnedRow: View {
    enum Kind {
        case newPlaylist
        case likedMusic
    }

    let kind: Kind
    let action: () -> Void

    @Environment(\.boomRowInset) private var inset

    var body: some View {
        Button(action: action) {
            HStack(spacing: BoomMetrics.rowSpacing) {
                cover
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(BoomFont.itemTitle)
                        .foregroundStyle(BoomColor.textPrimary)
                        .lineLimit(1)
                    Text(subtitle)
                        .font(BoomFont.itemSubtitle)
                        .foregroundStyle(BoomColor.textSecondary)
                        .lineLimit(1)
                }
                Spacer(minLength: 0)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(LibraryRowStyle())
        .accessibilityElement(children: .combine)
        .accessibilityHint(kind == .newPlaylist ? "Opens a form" : "Opens")
        .padding(.horizontal, inset)
        .frame(minHeight: BoomMetrics.rowCover + 8)
    }

    private var title: String {
        kind == .newPlaylist ? "New playlist" : "Liked music"
    }

    private var subtitle: String {
        kind == .newPlaylist ? "Name it, then add songs" : "Songs you've liked"
    }

    @ViewBuilder
    private var cover: some View {
        let shape = RoundedRectangle(cornerRadius: BoomMetrics.coverRadius)
        switch kind {
        case .newPlaylist:
            Image(systemName: "plus")
                .font(.system(size: 22, weight: .medium))
                .foregroundStyle(BoomColor.textPrimary)
                .frame(width: BoomMetrics.rowCover, height: BoomMetrics.rowCover)
                .background(BoomColor.surfaceRaised, in: shape)
        case .likedMusic:
            Image(systemName: "hand.thumbsup.fill")
                .font(.system(size: 22, weight: .semibold))
                .foregroundStyle(BoomColor.onAccent)
                .frame(width: BoomMetrics.rowCover, height: BoomMetrics.rowCover)
                .background(
                    LinearGradient(
                        colors: [BoomColor.accent, BoomColor.accentDeep], startPoint: .topLeading,
                        endPoint: .bottomTrailing),
                    in: shape)
        }
    }
}

/// A playlist of the listener's own as a row: the menu adds Edit and Delete to the usual entries.
struct OwnPlaylistRow: View {
    let playlist: PlaylistItem
    let onEdit: () -> Void
    let onDelete: () -> Void

    @Environment(AppServices.self) private var services
    @Environment(\.boomNavigator) private var navigator
    @Environment(\.boomRowInset) private var inset

    private var item: MusicItem { .playlist(playlist) }

    var body: some View {
        HStack(spacing: BoomMetrics.rowSpacing) {
            Button {
                ItemActions(services: services, navigator: navigator).tap(item)
            } label: {
                HStack(spacing: BoomMetrics.rowSpacing) {
                    ArtworkView(playlist.artwork, width: BoomMetrics.rowCover)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(playlist.title)
                            .font(BoomFont.itemTitle)
                            .foregroundStyle(BoomColor.textPrimary)
                            .lineLimit(1)
                        Text(item.subtitle)
                            .font(BoomFont.itemSubtitle)
                            .foregroundStyle(BoomColor.textSecondary)
                            .lineLimit(1)
                    }
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(LibraryRowStyle())
            .accessibilityElement(children: .combine)
            .accessibilityHint("Opens")

            Menu {
                OwnPlaylistMenu(playlist: playlist, onEdit: onEdit, onDelete: onDelete)
            } label: {
                Image(systemName: "ellipsis")
                    .font(.system(size: 17, weight: .semibold))
                    .rotationEffect(.degrees(90))
                    .foregroundStyle(BoomColor.textSecondary)
                    .frame(width: BoomMetrics.minimumTarget, height: BoomMetrics.minimumTarget)
                    .contentShape(Rectangle())
            }
            .accessibilityLabel("More actions for \(playlist.title)")
            .padding(.trailing, -10)
        }
        .padding(.horizontal, inset)
        .frame(minHeight: BoomMetrics.rowCover + 8)
        .contextMenu {
            OwnPlaylistMenu(playlist: playlist, onEdit: onEdit, onDelete: onDelete)
        }
    }
}

/// A playlist of the listener's own as a card in the grid.
struct OwnPlaylistCard: View {
    let playlist: PlaylistItem
    let width: CGFloat
    let onEdit: () -> Void
    let onDelete: () -> Void

    @Environment(AppServices.self) private var services
    @Environment(\.boomNavigator) private var navigator

    private var item: MusicItem { .playlist(playlist) }

    var body: some View {
        Button {
            ItemActions(services: services, navigator: navigator).tap(item)
        } label: {
            VStack(alignment: .leading, spacing: 8) {
                ArtworkView(playlist.artwork, width: width, cornerRadius: BoomMetrics.cardRadius)
                VStack(alignment: .leading, spacing: 2) {
                    Text(playlist.title)
                        .font(BoomFont.itemTitle)
                        .foregroundStyle(BoomColor.textPrimary)
                        .lineLimit(2)
                        .fixedSize(horizontal: false, vertical: true)
                    Text(item.subtitle)
                        .font(BoomFont.itemSubtitle)
                        .foregroundStyle(BoomColor.textSecondary)
                        .lineLimit(2)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(width: width, alignment: .leading)
            }
            .frame(width: width, alignment: .top)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityHint("Opens")
        .contextMenu {
            OwnPlaylistMenu(playlist: playlist, onEdit: onEdit, onDelete: onDelete)
        }
    }
}

/// The usual item menu, then Edit and Delete.
struct OwnPlaylistMenu: View {
    let playlist: PlaylistItem
    let onEdit: () -> Void
    let onDelete: () -> Void

    var body: some View {
        ItemMenu(.playlist(playlist))
        Section {
            Button("Edit", systemImage: "pencil", action: onEdit)
            Button("Delete", systemImage: "trash", role: .destructive, action: onDelete)
        }
    }
}
