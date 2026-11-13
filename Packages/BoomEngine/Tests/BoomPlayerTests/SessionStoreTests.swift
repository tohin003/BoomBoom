import BoomModels
import Foundation
import Testing
@testable import BoomPlayer

/// A stand-in for the hidden browser's profiles, with the same rules: the default profile always exists, an empty
/// profile is reused before a new one is made, a profile signed out goes (the default one is only emptied), and the
/// active one is replaced by the first other signed-in one.
@MainActor
private final class FakeBackend: SessionBackend {
    var state: BackendState = .ready
    var onChange: (@MainActor () -> Void)?
    let profileLimit = 3
    private(set) var ids = ["default"]
    private(set) var signedIn: Set<String> = []
    private(set) var active = "default"
    /// What signing in to a profile in the sheet would do: the listener completes it, or gives up.
    var nextSignInSucceeds = true
    private var counter = 0

    var isSignedIn: Bool { signedIn.contains(active) }
    var profiles: [SessionProfile] {
        ids.map { SessionProfile(id: $0, isSignedIn: signedIn.contains($0), isActive: $0 == active) }
    }

    func switchProfile(to id: String) {
        guard ids.contains(id), id != active else { return }
        active = id
        onChange?()
    }

    func addProfile() -> String? {
        if let empty = ids.first(where: { !signedIn.contains($0) }) { return empty }
        guard ids.count < profileLimit else { return nil }
        counter += 1
        let id = "p\(counter)"
        ids.append(id)
        onChange?()
        return id
    }

    func removeProfile(_ id: String) async {
        guard ids.contains(id) else { return }
        signedIn.remove(id)
        if id == active {
            active = ids.first { $0 != id && signedIn.contains($0) } ?? "default"
        }
        if id != "default" { ids.removeAll { $0 == id } }
        onChange?()
    }

    func didFinishSignIn(profile id: String) async {
        guard ids.contains(id) else { return }
        if nextSignInSucceeds {
            signedIn.insert(id)
            active = id
        } else if id != "default", id != active {
            ids.removeAll { $0 == id }
        }
        onChange?()
    }

    func signOut() async { await removeProfile(active) }
    func reload() {}

    /// Signs a new account in the way the screens do it, and waits for the store to have it.
    func add(to store: SessionStore) async throws -> String {
        let id = try #require(store.beginAddingAccount())
        await didFinishSignIn(profile: id)
        return id
    }
}

/// Answers `account()` as whichever profile is active, the way the data client borrows the tab's cookies.
private final class AccountAPI: MusicAPI, @unchecked Sendable {
    @MainActor weak var backend: FakeBackend?

    func account() async throws -> Account? {
        await MainActor.run {
            guard let backend, backend.isSignedIn else { return nil }
            return Account(name: "Listener \(backend.active)", handle: "@\(backend.active)")
        }
    }

    func watchQueue(_ endpoint: WatchEndpoint) async throws -> WatchQueue { WatchQueue(songs: [], currentIndex: 0) }
    func watchQueueContinuation(_ token: String) async throws -> SongsContinuation { .init(songs: [], continuation: nil) }
    func lyrics(browseId: String) async throws -> Lyrics { Lyrics(lines: []) }
    func related(browseId: String) async throws -> [Shelf] { [] }
    func rate(videoId: String, _ status: LikeStatus) async throws {}
    func searchSuggestions(_ input: String) async throws -> [SearchSuggestion] { [] }
    func search(_ query: String, filter: SearchFilter?) async throws -> SearchResults { SearchResults(query: query) }
    func searchContinuation(_ token: String) async throws -> SongsOrItemsContinuation { .init(items: [], continuation: nil) }
    func home(chip: Chip?) async throws -> BrowsePage { BrowsePage() }
    func explore() async throws -> BrowsePage { BrowsePage() }
    func charts(country: String?) async throws -> BrowsePage { BrowsePage() }
    func moodsAndGenres() async throws -> [ChipGroup] { [] }
    func browse(_ endpoint: BrowseEndpoint) async throws -> BrowsePage { BrowsePage() }
    func browseContinuation(_ token: String) async throws -> BrowsePage { BrowsePage() }
    func album(id: String) async throws -> AlbumPage { AlbumPage(album: AlbumItem(id: id, title: id)) }
    func artist(id: String) async throws -> ArtistPage { ArtistPage(artist: ArtistItem(id: id, name: id)) }
    func playlist(id: String) async throws -> PlaylistPage { PlaylistPage(playlist: PlaylistItem(id: id, title: id)) }
    func playlistContinuation(_ token: String) async throws -> SongsContinuation { .init(songs: [], continuation: nil) }
    func library(_ section: LibrarySection) async throws -> BrowsePage { BrowsePage() }
    func likedSongs() async throws -> PlaylistPage { PlaylistPage(playlist: PlaylistItem(id: "LM", title: "Liked")) }
    func history() async throws -> BrowsePage { BrowsePage() }
    func ratePlaylist(id: String, _ status: LikeStatus) async throws {}
    func subscribe(channelId: String, _ subscribed: Bool) async throws {}
    func sendLibraryFeedback(token: String) async throws {}
    func createPlaylist(title: String, description: String, privacy: PlaylistPrivacy, videoIds: [String]) async throws -> String { "PL" }
    func deletePlaylist(id: String) async throws {}
    func editPlaylist(id: String, title: String?, description: String?, privacy: PlaylistPrivacy?) async throws {}
    func addToPlaylist(id: String, videoIds: [String]) async throws {}
    func removeFromPlaylist(id: String, songs: [Song]) async throws {}
}

@MainActor
@Suite struct SessionStoreTests {
    private let defaults: UserDefaults
    private let backend = FakeBackend()
    private let api = AccountAPI()

    init() throws {
        defaults = try #require(UserDefaults(suiteName: "boom.session.\(UUID().uuidString)"))
        api.backend = backend
    }

    private func makeStore() -> SessionStore {
        SessionStore(api: api, backend: backend, defaults: defaults)
    }

    /// Waits for the account fetches the store starts by itself.
    private func settle() async {
        for _ in 0..<20 { await Task.yield() }
        try? await Task.sleep(for: .milliseconds(30))
    }

    @Test func signingInTheFirstAccountUsesTheDefaultProfile() async throws {
        let store = makeStore()
        #expect(!store.isSignedIn && store.accounts.isEmpty && !store.canAddAccount)
        let id = try await backend.add(to: store)
        #expect(id == "default")
        await settle()
        #expect(store.isSignedIn)
        #expect(store.accounts.map(\.id) == ["default"])
        #expect(store.account?.name == "Listener default")
        #expect(store.canAddAccount)
    }

    @Test func accountsCanBeAddedUpToTheLimitAndNoFurther() async throws {
        let store = makeStore()
        _ = try await backend.add(to: store)
        let second = try await backend.add(to: store)
        let third = try await backend.add(to: store)
        await settle()
        #expect(store.accounts.count == 3)
        #expect(store.accounts.first(where: \.isActive)?.id == third)
        #expect(second != third)
        #expect(!store.canAddAccount)
        #expect(store.beginAddingAccount() == nil)
        #expect(backend.ids.count == 3)
    }

    @Test func switchingChangesTheActiveAccountAndRaisesTheRevision() async throws {
        let store = makeStore()
        _ = try await backend.add(to: store)
        let second = try await backend.add(to: store)
        await settle()
        #expect(store.account?.name == "Listener \(second)")
        let before = store.revision
        await store.switchAccount(to: "default")
        #expect(store.revision > before)
        #expect(store.account?.name == "Listener default")
        #expect(store.accounts.first(where: \.isActive)?.id == "default")
        // The account not active is still shown by name.
        #expect(store.accounts.first { $0.id == second }?.account?.name == "Listener \(second)")

        let again = store.revision
        await store.switchAccount(to: "default")
        #expect(store.revision == again, "switching to the active account changes nothing")
    }

    @Test func signingOutTheActiveAccountMovesToAnotherOne() async throws {
        let store = makeStore()
        _ = try await backend.add(to: store)
        let second = try await backend.add(to: store)
        await settle()
        let before = store.revision
        await store.signOut(accountID: second)
        await settle()
        #expect(store.revision > before)
        #expect(store.isSignedIn)
        #expect(store.accounts.map(\.id) == ["default"])
        #expect(store.accounts.first?.isActive == true)
        #expect(!backend.ids.contains(second))
    }

    @Test func signingOutAnotherAccountLeavesTheActiveOne() async throws {
        let store = makeStore()
        _ = try await backend.add(to: store)
        let second = try await backend.add(to: store)
        await settle()
        let before = store.revision
        await store.signOut(accountID: "default")
        await settle()
        #expect(store.revision == before, "the active account did not change")
        #expect(store.accounts.map(\.id) == [second])
        #expect(store.account?.name == "Listener \(second)")
        // The emptied default profile is used again for the next account rather than a new one.
        #expect(store.canAddAccount)
        #expect(store.beginAddingAccount() == "default")
    }

    @Test func signingOutTheLastAccountLooksSignedOut() async throws {
        let store = makeStore()
        _ = try await backend.add(to: store)
        await settle()
        await store.signOut()
        #expect(!store.isSignedIn && store.accounts.isEmpty && store.account == nil)
    }

    @Test func givingUpAnAddLeavesNoEmptyAccount() async throws {
        let store = makeStore()
        _ = try await backend.add(to: store)
        await settle()
        let revision = store.revision
        let id = try #require(store.beginAddingAccount())
        #expect(id != "default")
        backend.nextSignInSucceeds = false
        store.cancelAddingAccount(id)
        await settle()
        #expect(!backend.ids.contains(id))
        #expect(store.accounts.map(\.id) == ["default"])
        #expect(store.revision == revision)
        #expect(store.canAddAccount)
    }

    @Test func givingUpTheFirstSignInLeavesTheSignedOutLook() async throws {
        let store = makeStore()
        let id = try #require(store.beginAddingAccount())
        backend.nextSignInSucceeds = false
        store.cancelAddingAccount(id)
        await settle()
        #expect(!store.isSignedIn && store.accounts.isEmpty)
        #expect(backend.ids == ["default"])
    }

    @Test func rememberedAccountsSurviveARestart() async throws {
        let store = makeStore()
        _ = try await backend.add(to: store)
        let second = try await backend.add(to: store)
        await settle()
        await store.switchAccount(to: "default")
        await settle()

        // The app starts again: the account not active is shown from what was remembered, before any fetch.
        api.backend = nil
        let later = makeStore()
        #expect(later.accounts.map(\.id) == ["default", second])
        #expect(later.accounts.first { $0.id == second }?.account?.name == "Listener \(second)")
        #expect(later.account?.name == "Listener default")
    }
}
