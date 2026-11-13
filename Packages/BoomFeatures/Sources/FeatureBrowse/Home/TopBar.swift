import BoomDesign
import BoomModels
import BoomPlayer
import SwiftUI

/// The bar at the top of a tab's root: the brand (or a title), search, and the account button.
struct TopBar: View {
    /// Nil shows the brand.
    var title: String?

    @Environment(AppServices.self) private var services
    @Environment(\.boomNavigator) private var navigator
    @State private var isShowingSettings = false

    var body: some View {
        HStack(spacing: 4) {
            if let title {
                Text(title)
                    .font(BoomFont.pageTitle)
                    .foregroundStyle(BoomColor.textPrimary)
                    .accessibilityAddTraits(.isHeader)
            } else {
                BrandMark()
            }
            Spacer()
            NavigationLink(value: BrowseRoute.search(query: nil)) {
                Image(systemName: "magnifyingglass")
                    .font(.system(size: 20, weight: .medium))
                    .frame(width: BoomMetrics.minimumTarget, height: BoomMetrics.minimumTarget)
                    .contentShape(Rectangle())
            }
            .accessibilityLabel("Search")
            accountButton
        }
        .foregroundStyle(BoomColor.textPrimary)
        .padding(.leading, BoomMetrics.margin)
        .padding(.trailing, 8)
        .frame(height: 52)
        // A menu cannot hold a navigation link, so the account menu's Settings is pushed from here. It sits outside
        // the signed-in branch so that signing the last account out in Settings does not pull Settings away.
        .navigationDestination(isPresented: $isShowingSettings) {
            SettingsScreen().reservesShellBars()
        }
    }

    /// Signed in, a menu: the accounts (a second tap switches), adding one, and Settings. Signed out, the sign-in.
    @ViewBuilder
    private var accountButton: some View {
        let session = services.session
        if session.isSignedIn || !session.accounts.isEmpty {
            Menu {
                Section {
                    ForEach(session.accounts) { entry in
                        Button {
                            guard !entry.isActive else { return }
                            Task { await session.switchAccount(to: entry.id) }
                        } label: {
                            let name = entry.account?.name ?? "Google account"
                            if entry.isActive { Label(name, systemImage: "checkmark") } else { Text(name) }
                            if let handle = entry.account?.handle { Text(handle) }
                        }
                    }
                }
                Section {
                    // The active account's Google session ended while other accounts are still signed in.
                    if !session.isSignedIn {
                        Button("Sign in", systemImage: "person.crop.circle") { navigator.signIn() }
                    }
                    if session.canAddAccount {
                        Button("Add another account", systemImage: "person.crop.circle.badge.plus") {
                            navigator.addAccount()
                        }
                    }
                    Button("Settings", systemImage: "gearshape") { isShowingSettings = true }
                }
            } label: {
                Group {
                    if let account = session.account {
                        ArtworkView(account.artwork, width: 30, isCircle: true)
                    } else {
                        Image(systemName: "person.crop.circle.fill").font(.system(size: 26))
                    }
                }
                .frame(width: BoomMetrics.minimumTarget, height: BoomMetrics.minimumTarget)
                .contentShape(Rectangle())
            }
            .accessibilityLabel("Account and settings")
            .accessibilityIdentifier("topbar.account")
        } else {
            Button {
                navigator.signIn()
            } label: {
                Image(systemName: "person.crop.circle")
                    .font(.system(size: 24))
                    .frame(width: BoomMetrics.minimumTarget, height: BoomMetrics.minimumTarget)
                    .contentShape(Rectangle())
            }
            .accessibilityLabel("Sign in")
            .accessibilityIdentifier("topbar.signIn")
        }
    }
}

/// BoomBoom's name with a small yellow sound-burst glyph drawn in SwiftUI.
struct BrandMark: View {
    var body: some View {
        HStack(spacing: 7) {
            ZStack {
                Circle().fill(BoomColor.accent)
                Circle()
                    .strokeBorder(BoomColor.onAccent, lineWidth: 1.6)
                    .padding(5.5)
                Image(systemName: "play.fill")
                    .font(.system(size: 7.5, weight: .black))
                    .foregroundStyle(BoomColor.onAccent)
                    .offset(x: 0.6)
            }
            .frame(width: 26, height: 26)
            Text("BoomBoom")
                .font(.system(size: 21, weight: .heavy))
                .tracking(-0.6)
                .foregroundStyle(BoomColor.textPrimary)
                .dynamicTypeSize(...DynamicTypeSize.xxLarge)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("BoomBoom")
        .accessibilityAddTraits(.isHeader)
    }
}
