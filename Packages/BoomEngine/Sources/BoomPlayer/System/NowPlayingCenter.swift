import BoomModels
import Foundation

#if canImport(MediaPlayer) && canImport(UIKit)
import MediaPlayer
import UIKit

/// The song on the Lock Screen, in Control Centre and in the Dynamic Island, and the buttons there, on headphones
/// and in a car. The system draws all of it from what is published here.
@MainActor
final class NowPlayingCenter {
    private weak var player: PlayerStore?
    private var artworkVideoId: String?
    private var artwork: MPMediaItemArtwork?
    private var artworkTask: Task<Void, Never>?

    init(player: PlayerStore) {
        self.player = player
        player.onChange = { [weak self] _ in self?.publish() }
        installCommands()
        UIApplication.shared.beginReceivingRemoteControlEvents()
    }

    private func installCommands() {
        let commands = MPRemoteCommandCenter.shared()
        func on(_ command: MPRemoteCommand, _ action: @escaping @MainActor (PlayerStore, MPRemoteCommandEvent) -> Bool) {
            command.isEnabled = true
            // The system does not promise which thread it calls this on, so the closure is tied to none
            // (`@Sendable`) and gets itself onto the main thread.
            command.addTarget { @Sendable [weak self] event in
                nonisolated(unsafe) let event = event
                return Self.onMain {
                    guard let player = self?.player, player.current != nil else {
                        return MPRemoteCommandHandlerStatus.noActionableNowPlayingItem
                    }
                    return action(player, event) ? .success : .commandFailed
                }
            }
        }
        on(commands.playCommand) { player, _ in player.resume(); return true }
        on(commands.pauseCommand) { player, _ in player.pause(); return true }
        on(commands.togglePlayPauseCommand) { player, _ in player.togglePlayPause(); return true }
        on(commands.nextTrackCommand) { player, _ in player.next(); return true }
        on(commands.previousTrackCommand) { player, _ in player.previous(); return true }
        on(commands.changePlaybackPositionCommand) { player, event in
            guard let event = event as? MPChangePlaybackPositionCommandEvent else { return false }
            player.seek(to: event.positionTime)
            return true
        }
    }

    private func publish() {
        guard let player, let song = player.current else {
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
            return
        }
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: song.title,
            MPMediaItemPropertyArtist: song.artistLine,
            MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.audio.rawValue,
            MPNowPlayingInfoPropertyIsLiveStream: false,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: player.clock.position,
            MPNowPlayingInfoPropertyPlaybackRate: player.status == .playing ? 1.0 : 0.0,
            MPNowPlayingInfoPropertyDefaultPlaybackRate: 1.0,
        ]
        if let album = song.album?.name { info[MPMediaItemPropertyAlbumTitle] = album }
        if player.clock.duration > 0 { info[MPMediaItemPropertyPlaybackDuration] = player.clock.duration }
        if artworkVideoId == song.videoId, let artwork { info[MPMediaItemPropertyArtwork] = artwork }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info

        let commands = MPRemoteCommandCenter.shared()
        commands.nextTrackCommand.isEnabled = player.hasNext
        if artworkVideoId != song.videoId { loadArtwork(for: song) }
    }

    private func loadArtwork(for song: Song) {
        artworkVideoId = song.videoId
        artwork = nil
        artworkTask?.cancel()
        guard let url = song.artwork.url(pixelWidth: 600) else { return }
        artworkTask = Task { [weak self] in
            guard let (data, _) = try? await URLSession.shared.data(from: url), !Task.isCancelled,
                  let image = UIImage(data: data) else { return }
            guard let self, self.artworkVideoId == song.videoId else { return }
            self.artwork = Self.makeArtwork(image)
            self.publish()
        }
    }

    /// The system asks for the picture on a queue of its own (never the main thread), so the closure handed to it
    /// must not belong to the main actor: one made inside a main-actor method would, and the runtime stops the app
    /// when it is then called from elsewhere.
    nonisolated private static func makeArtwork(_ image: UIImage) -> MPMediaItemArtwork {
        MPMediaItemArtwork(boundsSize: image.size) { @Sendable _ in image }
    }

    /// Runs `body` on the main thread and hands back its answer, from whichever thread this is called on.
    nonisolated private static func onMain<T: Sendable>(_ body: @MainActor @Sendable () -> T) -> T {
        if Thread.isMainThread { return MainActor.assumeIsolated { body() } }
        return DispatchQueue.main.sync { MainActor.assumeIsolated { body() } }
    }
}
#endif
