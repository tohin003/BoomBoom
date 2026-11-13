import BoomDesign
import BoomModels
import BoomPlayer
import SwiftUI

/// What the accounts section asks the listener to confirm.
private enum AccountConfirmation: Identifiable, Hashable {
    case signOut(accountID: String)

    var id: Self { self }
}

/// Settings' first section: the Google accounts (up to three, each kept apart), the active one marked; a tap
/// switches, a swipe or the row's menu signs one out. Signed out entirely, it is the one "Sign in" row it always was.
struct AccountsSection: View {
    @Environment(AppServices.self) private var services
    @Environment(\.boomNavigator) private var navigator
    @State private var confirmation: AccountConfirmation?
    /// The account being switched to: its row shows progress until the switch is done. One switch at a time.
    @State private var switchingTo: String?

    var body: some View {
        let session = services.session
        Section {
            if case .failed(let message) = session.backendState {
                VStack(alignment: .leading, spacing: 10) {
                    Label(message, systemImage: "exclamationmark.triangle")
                        .font(BoomFont.itemSubtitle)
                        .foregroundStyle(BoomColor.textPrimary)
                    Button("Try again") { session.retryBackend() }
                        .buttonStyle(OutlinePillButtonStyle(isCompact: true))
                }
                .padding(.vertical, 4)
            }
            ForEach(session.accounts) { entry in
                row(entry)
            }
            if !session.isSignedIn {
                signInRow
            } else if session.canAddAccount {
                addRow
            }
        } header: {
            Text(session.accounts.isEmpty ? "Account" : "Accounts")
                .font(BoomFont.button)
                .foregroundStyle(BoomColor.textSecondary)
                .textCase(nil)
        } footer: {
            if session.accounts.count > 1 {
                Text("Each account keeps its own library, likes and history. Tap one to switch.")
            }
        }
        .listRowBackground(BoomColor.surface)
    }

    /// Whether the listener is being asked about `kind`. Each question is attached to the row that raises it, so it
    /// opens from that row (see `SettingsScreen.isAsking(_:)`).
    private func isAsking(_ kind: AccountConfirmation) -> Binding<Bool> {
        Binding(get: { confirmation == kind }, set: { if !$0, confirmation == kind { confirmation = nil } })
    }

    // MARK: One account

    private func row(_ entry: SessionAccount) -> some View {
        HStack(spacing: 4) {
            Button {
                switchTo(entry)
            } label: {
                HStack(spacing: BoomMetrics.rowSpacing) {
                    avatar(entry.account)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(entry.account?.name ?? "Google account")
                            .font(BoomFont.itemTitle)
                            .foregroundStyle(BoomColor.textPrimary)
                            .lineLimit(1)
                        if let handle = entry.account?.handle {
                            Text(handle)
                                .font(BoomFont.itemSubtitle)
                                .foregroundStyle(BoomColor.textSecondary)
                                .lineLimit(1)
                        }
                    }
                    Spacer(minLength: 0)
                    if switchingTo == entry.id {
                        ProgressView().tint(BoomColor.accent)
                    } else if entry.isActive {
                        Image(systemName: "checkmark.circle.fill")
                            .font(.system(size: 20))
                            .foregroundStyle(BoomColor.accent)
                    }
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityElement(children: .combine)
            .accessibilityValue(entry.isActive ? "Active" : switchingTo == entry.id ? "Switching" : "")
            .accessibilityHint(entry.isActive ? "" : "Switches to this account")
            .accessibilityIdentifier("settings.account")

            Menu {
                if !entry.isActive {
                    Button("Switch to this account", systemImage: "arrow.left.arrow.right") { switchTo(entry) }
                }
                Button("Sign out", systemImage: "rectangle.portrait.and.arrow.right", role: .destructive) {
                    confirmation = .signOut(accountID: entry.id)
                }
            } label: {
                Image(systemName: "ellipsis")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(BoomColor.textSecondary)
                    .frame(width: BoomMetrics.minimumTarget, height: BoomMetrics.minimumTarget)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.borderless)
            .accessibilityLabel("Account options")
            .accessibilityIdentifier("settings.account.menu")
        }
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            // No destructive role: that would take the row away before the listener has confirmed.
            Button("Sign out") { confirmation = .signOut(accountID: entry.id) }
                .tint(BoomColor.destructive)
        }
        .confirmationDialog(
            entry.account.map { "Sign out of \($0.name)?" } ?? "Sign out of this account?",
            isPresented: isAsking(.signOut(accountID: entry.id)), titleVisibility: .visible
        ) {
            Button("Sign out", role: .destructive) {
                Task { await services.session.signOut(accountID: entry.id) }
            }
        } message: {
            Text(signOutMessage(for: entry))
        }
    }

    private func signOutMessage(for entry: SessionAccount) -> String {
        let others = services.session.accounts.count > 1
        if entry.isActive, others { return "Its library, likes and history are hidden, and BoomBoom switches to your other account." }
        if entry.isActive { return "Your library, likes and history will be hidden until you sign in again." }
        return "It is removed from BoomBoom until you add it again."
    }

    @ViewBuilder
    private func avatar(_ account: Account?) -> some View {
        if let account {
            ArtworkView(account.artwork, width: 48, isCircle: true)
        } else {
            Image(systemName: "person.crop.circle.fill")
                .font(.system(size: 40))
                .foregroundStyle(BoomColor.textSecondary)
                .frame(width: 48, height: 48)
        }
    }

    private func switchTo(_ entry: SessionAccount) {
        guard !entry.isActive, switchingTo == nil else { return }
        switchingTo = entry.id
        Task {
            await services.session.switchAccount(to: entry.id)
            switchingTo = nil
        }
    }

    // MARK: Adding

    private var addRow: some View {
        let session = services.session
        return Button {
            navigator.addAccount()
        } label: {
            HStack(spacing: BoomMetrics.rowSpacing) {
                Image(systemName: "person.crop.circle.badge.plus")
                    .font(.system(size: 30, weight: .light))
                    .foregroundStyle(BoomColor.accent)
                    .frame(width: 48, height: 48)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Add another account")
                        .font(BoomFont.itemTitle)
                        .foregroundStyle(BoomColor.accent)
                    Text("\(session.accounts.count) of \(session.accountLimit) accounts used")
                        .font(BoomFont.itemSubtitle)
                        .foregroundStyle(BoomColor.textSecondary)
                }
                Spacer(minLength: 0)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("settings.addAccount")
    }

    private var signInRow: some View {
        Button {
            navigator.signIn()
        } label: {
            HStack(spacing: BoomMetrics.rowSpacing) {
                Image(systemName: "person.crop.circle")
                    .font(.system(size: 34, weight: .light))
                    .foregroundStyle(BoomColor.textSecondary)
                    .frame(width: 48, height: 48)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Sign in")
                        .font(BoomFont.itemTitle)
                        .foregroundStyle(BoomColor.accent)
                    Text("To see your library, likes and playlists")
                        .font(BoomFont.itemSubtitle)
                        .foregroundStyle(BoomColor.textSecondary)
                }
                Spacer(minLength: 0)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("settings.signIn")
    }
}
