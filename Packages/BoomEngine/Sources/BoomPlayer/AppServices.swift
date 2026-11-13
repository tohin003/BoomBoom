import BoomModels
import Foundation
import Observation

/// How far along the hidden YouTube Music tab is.
public enum BackendState: Sendable, Equatable {
    /// The tab is loading. Browsing already works; the first song waits for it.
    case starting
    case ready
    /// The tab could not load (no connection). The text is for the listener.
    case failed(String)
}

/// One browser profile: a Google account kept apart from the others (or a profile not signed in yet).
public struct SessionProfile: Sendable, Equatable, Identifiable {
    public let id: String
    public let isSignedIn: Bool
    public let isActive: Bool

    public init(id: String, isSignedIn: Bool, isActive: Bool) {
        self.id = id
        self.isSignedIn = isSignedIn
        self.isActive = isActive
    }
}

/// The sign-in and the hidden tab behind it, as `SessionStore` drives them. The hidden browser implements it.
@MainActor
public protocol SessionBackend: AnyObject {
    var state: BackendState { get }
    /// Whether the active profile is signed in.
    var isSignedIn: Bool { get }
    /// Called whenever `state`, `isSignedIn` or the profiles change (a switch included).
    var onChange: (@MainActor () -> Void)? { get set }
    /// Every profile, in a stable order; exactly one is active.
    var profiles: [SessionProfile] { get }
    /// How many profiles there may be.
    var profileLimit: Int { get }
    /// Makes another profile the active one. The hidden tab reloads as that account.
    func switchProfile(to id: String)
    /// A profile to sign another account in to (not switched to). Nil when all are taken.
    func addProfile() -> String?
    /// Signs a profile out and drops it. When it was the active one, another signed-in one takes over.
    func removeProfile(_ id: String) async
    /// The sign-in sheet for this profile closed, signed in or not.
    func didFinishSignIn(profile id: String) async
    /// Signs the active profile out.
    func signOut() async
    /// Loads the hidden tab again (after a failure, or when the connection is back).
    func reload()
}

/// A signed-in Google account as the account switcher shows it.
public struct SessionAccount: Sendable, Equatable, Identifiable {
    /// The profile the account lives in. Not the Google account's own id, which the app never sees.
    public let id: String
    /// Name, handle and picture: fetched for the active account, remembered from earlier for the others.
    public let account: Account?
    public let isActive: Bool

    public init(id: String, account: Account?, isActive: Bool) {
        self.id = id
        self.account = account
        self.isActive = isActive
    }
}

/// The listener's Google accounts in the app: up to three, one of them active.
@MainActor
@Observable
public final class SessionStore {
    /// Whether the active account is signed in.
    public private(set) var isSignedIn: Bool
    /// The active account's name and picture, once fetched.
    public private(set) var account: Account?
    public private(set) var backendState: BackendState
    /// Goes up each time the active account changes (a sign-in, a sign-out or a switch); screens that show personal
    /// data reload when it does.
    public private(set) var revision = 0
    /// Every signed-in account, in the order they were added.
    public private(set) var accounts: [SessionAccount] = []
    /// Whether another account can be added.
    public private(set) var canAddAccount = false
    /// How many accounts the app keeps at most.
    public private(set) var accountLimit = 1

    @ObservationIgnored private let api: any MusicAPI
    @ObservationIgnored private let backend: (any SessionBackend)?
    @ObservationIgnored private let defaults: UserDefaults
    /// The profile that was active at the last look: a change of it is a change of account.
    @ObservationIgnored private var activeProfileID: String?
    /// Each signed-in profile's account as last fetched, so the switcher can show the ones not active.
    @ObservationIgnored private var remembered: [String: Account]
    private static let rememberedKey = "session.accounts"

    public init(api: any MusicAPI, backend: (any SessionBackend)?, defaults: UserDefaults = .standard) {
        self.api = api
        self.backend = backend
        self.defaults = defaults
        remembered = defaults.data(forKey: Self.rememberedKey)
            .flatMap { try? JSONDecoder().decode([String: Account].self, from: $0) } ?? [:]
        isSignedIn = backend?.isSignedIn ?? false
        backendState = backend?.state ?? .ready
        accountLimit = backend?.profileLimit ?? 1
        activeProfileID = backend?.profiles.first(where: \.isActive)?.id
        if isSignedIn, let activeProfileID { account = remembered[activeProfileID] }
        backend?.onChange = { [weak self] in self?.backendDidChange() }
        rebuildAccounts()
        if isSignedIn { Task { await refreshAccount() } }
    }

    /// For previews: a fixed state without a browser behind it.
    public init(api: any MusicAPI, previewAccount: Account?, otherAccounts: [Account] = []) {
        self.api = api
        backend = nil
        defaults = .standard
        remembered = [:]
        isSignedIn = previewAccount != nil
        account = previewAccount
        backendState = .ready
        accountLimit = 3
        if let previewAccount {
            accounts = [SessionAccount(id: "preview-0", account: previewAccount, isActive: true)]
                + otherAccounts.enumerated().map { SessionAccount(id: "preview-\($0.offset + 1)", account: $0.element, isActive: false) }
        }
        canAddAccount = isSignedIn && accounts.count < accountLimit
    }

    public func refreshAccount() async {
        guard isSignedIn, let profile = activeProfileID else {
            account = nil
            return
        }
        let asked = revision
        guard let fetched = try? await api.account() else { return }
        // The answer belongs to whichever account was active when it was asked for.
        guard asked == revision, profile == activeProfileID else { return }
        account = fetched
        remember(fetched, for: profile)
        rebuildAccounts()
    }

    /// Signs the active account out.
    public func signOut() async {
        await backend?.signOut()
        backendDidChange()
    }

    /// Signs one account out, the active one or another.
    public func signOut(accountID: String) async {
        await backend?.removeProfile(accountID)
        backendDidChange()
    }

    /// Makes another signed-in account the active one. Returns once its name and picture are in.
    public func switchAccount(to id: String) async {
        guard let backend, id != activeProfileID, accounts.contains(where: { $0.id == id }) else { return }
        backend.switchProfile(to: id)
        backendDidChange()
        await refreshAccount()
    }

    /// A profile for the sign-in sheet to sign another account in to. Nil when no more can be added.
    public func beginAddingAccount() -> String? {
        guard canAddAccount || !isSignedIn else { return nil }
        let id = backend?.addProfile()
        backendDidChange()
        return id
    }

    /// The sign-in sheet for that profile came back from Google: the new account becomes the active one.
    public func finishAddingAccount(_ id: String) {
        Task {
            await backend?.didFinishSignIn(profile: id)
            backendDidChange()
        }
    }

    /// The listener closed the sign-in sheet without signing in: an empty new profile is dropped.
    public func cancelAddingAccount(_ id: String) {
        // The browser looks at the profile itself: a sign-in that went through after all is kept.
        finishAddingAccount(id)
    }

    /// Loads the hidden tab again after a failure.
    public func retryBackend() {
        backend?.reload()
    }

    /// The one place the store follows the backend.
    private func backendDidChange() {
        guard let backend else { return }
        backendState = backend.state
        let active = backend.profiles.first(where: \.isActive)?.id
        let accountChanged = active != activeProfileID || backend.isSignedIn != isSignedIn
        activeProfileID = active
        isSignedIn = backend.isSignedIn
        if accountChanged {
            revision += 1
            account = isSignedIn ? active.flatMap { remembered[$0] } : nil
            if isSignedIn { Task { await refreshAccount() } }
        }
        rebuildAccounts()
    }

    private func rebuildAccounts() {
        guard let backend else { return }
        let profiles = backend.profiles
        let signedIn = profiles.filter(\.isSignedIn)
        // Accounts no longer signed in are forgotten, names and pictures with them.
        let kept = remembered.filter { id, _ in signedIn.contains { $0.id == id } }
        if kept.count != remembered.count {
            remembered = kept
            saveRemembered()
        }
        accounts = signedIn.map { profile in
            SessionAccount(
                id: profile.id, account: profile.isActive ? account ?? remembered[profile.id] : remembered[profile.id],
                isActive: profile.isActive)
        }
        accountLimit = backend.profileLimit
        canAddAccount = isSignedIn && signedIn.count < backend.profileLimit
    }

    private func remember(_ account: Account, for profile: String) {
        guard remembered[profile] != account else { return }
        remembered[profile] = account
        saveRemembered()
    }

    private func saveRemembered() {
        if let data = try? JSONEncoder().encode(remembered) { defaults.set(data, forKey: Self.rememberedKey) }
    }
}

/// The audio kept on the phone so songs start at once and replay without the network.
@MainActor
public protocol AudioCacheBackend: AnyObject {
    func usedBytes() async -> Int64
    func songCount() async -> Int
    func clear() async
    var limitBytes: Int64 { get set }
}

@MainActor
@Observable
public final class CacheStore {
    public private(set) var usedBytes: Int64 = 0
    public private(set) var songCount = 0
    /// The most the cache may hold; the least recently played songs go first.
    public var limitBytes: Int64 {
        didSet {
            backend?.limitBytes = limitBytes
            defaults.set(limitBytes, forKey: Self.limitKey)
        }
    }
    public static let limitChoices: [Int64] = [500, 1000, 2000, 5000, 10000].map { $0 * 1_000_000 }

    @ObservationIgnored private let backend: (any AudioCacheBackend)?
    @ObservationIgnored private let defaults: UserDefaults
    private static let limitKey = "cache.limitBytes"

    public init(backend: (any AudioCacheBackend)?, defaults: UserDefaults = .standard) {
        self.backend = backend
        self.defaults = defaults
        let saved = defaults.object(forKey: Self.limitKey) as? Int64
        limitBytes = saved ?? 2_000_000_000
        backend?.limitBytes = limitBytes
    }

    public func refresh() async {
        guard let backend else { return }
        usedBytes = await backend.usedBytes()
        songCount = await backend.songCount()
    }

    public func clear() async {
        await backend?.clear()
        await refresh()
    }
}

/// Everything a screen may need, handed down through the SwiftUI environment:
/// `@Environment(AppServices.self) private var services`.
@MainActor
@Observable
public final class AppServices {
    /// YouTube Music's data: search, pages, the library.
    public let api: any MusicAPI
    public let player: PlayerStore
    public let equalizer: EqualizerStore
    public let session: SessionStore
    public let cache: CacheStore
    /// The songs, albums and playlists the listener keeps on the phone.
    public let downloads: DownloadStore

    public init(
        api: any MusicAPI, player: PlayerStore, equalizer: EqualizerStore, session: SessionStore, cache: CacheStore,
        downloads: DownloadStore? = nil
    ) {
        self.api = api
        self.player = player
        self.equalizer = equalizer
        self.session = session
        self.cache = cache
        // Without one (previews, galleries) downloads "finish" at once, in memory.
        self.downloads = downloads ?? DownloadStore(backend: nil, defaults: UserDefaults(suiteName: "boom.preview") ?? .standard)
    }

    /// A silent, self-contained set for previews and UI work: the given data source (canned data), a player that
    /// keeps time without sound, and settings kept apart from the real app's.
    /// `otherAccounts` fills the account switcher with signed-in accounts that are not active.
    public static func preview(api: any MusicAPI, account: Account? = nil, otherAccounts: [Account] = []) -> AppServices {
        let defaults = UserDefaults(suiteName: "boom.preview") ?? .standard
        return AppServices(
            api: api,
            player: PlayerStore(api: api, engine: SimulatedEngine(), defaults: defaults),
            equalizer: EqualizerStore(defaults: defaults),
            session: SessionStore(api: api, previewAccount: account, otherAccounts: otherAccounts),
            cache: CacheStore(backend: nil, defaults: defaults))
    }
}
