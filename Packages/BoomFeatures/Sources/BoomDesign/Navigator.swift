import BoomModels
import SwiftUI

/// What a screen asks the app's frame to do when the answer lies outside that screen. The app sets it at the root;
/// read it with `@Environment(\.boomNavigator) private var navigator` and call its actions from buttons.
public struct BoomNavigator: Sendable {
    /// Shows an album, an artist, a playlist or another page of shelves in the current tab. When the full player
    /// is open, the app closes it first.
    public var open: @MainActor @Sendable (BrowseEndpoint) -> Void
    /// Shows the equaliser and amplifier.
    public var openEqualizer: @MainActor @Sendable () -> Void
    /// Shows the "Save to playlist" sheet for these songs.
    public var addToPlaylist: @MainActor @Sendable ([Song]) -> Void
    /// Shows Google's sign-in.
    public var signIn: @MainActor @Sendable () -> Void
    /// Shows Google's sign-in for another account, kept apart from the ones already signed in.
    public var addAccount: @MainActor @Sendable () -> Void

    public init(
        open: @escaping @MainActor @Sendable (BrowseEndpoint) -> Void = { _ in },
        openEqualizer: @escaping @MainActor @Sendable () -> Void = {},
        addToPlaylist: @escaping @MainActor @Sendable ([Song]) -> Void = { _ in },
        signIn: @escaping @MainActor @Sendable () -> Void = {},
        addAccount: @escaping @MainActor @Sendable () -> Void = {}
    ) {
        self.open = open
        self.openEqualizer = openEqualizer
        self.addToPlaylist = addToPlaylist
        self.signIn = signIn
        self.addAccount = addAccount
    }
}

extension EnvironmentValues {
    @Entry public var boomNavigator = BoomNavigator()
}

extension BrowseEndpoint {
    /// The page behind a card or a row. Songs have none: they play.
    public init?(_ item: MusicItem) {
        switch item {
        case .song: return nil
        case .album(let album): self.init(browseId: album.id, pageType: .album)
        case .artist(let artist): self.init(browseId: artist.id, pageType: .artist)
        case .playlist(let playlist): self.init(browseId: playlist.id, pageType: .playlist)
        }
    }
}
