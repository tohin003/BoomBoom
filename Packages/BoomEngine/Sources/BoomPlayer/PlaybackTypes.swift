import BoomModels
import Foundation

/// What the player is doing, in the terms a screen shows.
public enum PlaybackStatus: Sendable, Equatable {
    /// Nothing is loaded.
    case idle
    /// A song was asked for and its first audio has not arrived yet.
    case loading
    case playing
    case paused
    /// Playing, but waiting for more audio (a slow connection).
    case buffering
    /// The song could not be played. The text is for the listener.
    case failed(String)

    public var isFailure: Bool {
        if case .failed = self { return true }
        return false
    }
}

public enum RepeatMode: String, Sendable, Codable, CaseIterable {
    case off, all, one
}

/// One place in the queue. The same song can be queued twice, so entries have their own identity.
public struct QueueEntry: Identifiable, Hashable, Sendable {
    public let id: UUID
    public var song: Song

    public init(id: UUID = UUID(), song: Song) {
        self.id = id
        self.song = song
    }
}

/// Where the queue came from, for the "Playing from" line of the player.
public struct QueueSource: Hashable, Sendable {
    public enum Kind: String, Sendable { case album, playlist, artist, radio, search, library, other }

    public var kind: Kind
    /// "Album", "Playlist", "Radio".
    public var label: String
    /// The album's or playlist's name, or the song a radio grew from.
    public var title: String
    /// Opens the album or playlist from the player.
    public var endpoint: BrowseEndpoint?

    public init(kind: Kind, label: String, title: String, endpoint: BrowseEndpoint? = nil) {
        self.kind = kind
        self.label = label
        self.title = title
        self.endpoint = endpoint
    }
}

public enum SleepTimer: Sendable, Equatable {
    /// Playback pauses at this moment.
    case at(Date)
    /// Playback pauses when the current song ends.
    case endOfSong
}
