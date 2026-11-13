import BoomModels
import Foundation

/// Checks of the Library's request handling and of the build-expiry reader, run against a `PreviewMusicAPI` whose
/// answers are slow or refused on demand. The package has no test target, so a demo app runs these
/// (`-screen selfcheck`) and prints the outcome.
public enum LibrarySelfCheck {
    /// One line per check, starting with "PASS" or "FAIL". Takes a few seconds.
    @MainActor
    public static func run() async -> [String] {
        var report: [String] = []
        func check(_ name: String, _ condition: Bool, _ detail: @autoclosure () -> String = "") {
            report.append((condition ? "PASS " : "FAIL ") + name + (condition ? "" : " (\(detail()))"))
        }
        func sleep(_ milliseconds: Int) async {
            try? await Task.sleep(for: .milliseconds(milliseconds))
        }

        let api = PreviewMusicAPI(delays: false, signedIn: true, failsBrowsing: false) { call, _ in
            switch call {
            case "library": .milliseconds(150)
            case "deletePlaylist": .milliseconds(300)
            default: .milliseconds(10)
            }
        }
        let refusing = PreviewMusicAPI(delays: false, signedIn: false, failsBrowsing: false) { _, _ in .milliseconds(10) }

        // 1. A section loads, without the "Liked music" entry that has its own row.
        let model = LibraryModel()
        await model.loadIfNeeded(.playlists, api: api)
        let playlists = model.state(of: .playlists).content?.items ?? []
        check("playlists load", !playlists.isEmpty, "\(model.state(of: .playlists))")
        check("liked music is left out of the list", !playlists.contains { $0.id == "playlist:LM" })
        check("another section is untouched", model.state(of: .songs) == .loading)

        // 2. Sections keep their own states.
        await model.loadIfNeeded(.songs, api: api)
        check("songs load beside playlists",
              model.state(of: .songs).content?.songs.isEmpty == false && model.state(of: .playlists).content != nil)

        // 3. An answer that arrives after a reset is dropped.
        do {
            let fresh = LibraryModel()
            let task = Task { await fresh.loadIfNeeded(.albums, api: api) }
            await sleep(40)
            fresh.reset()
            await task.value
            check("an answer after a reset is ignored", fresh.state(of: .albums) == .loading,
                  "\(fresh.state(of: .albums))")
        }

        // 4. Cancelling the request that loads a section leaves it to be asked for again.
        do {
            let fresh = LibraryModel()
            let task = Task { await fresh.loadIfNeeded(.artists, api: api) }
            await sleep(40)
            task.cancel()
            await task.value
            check("a cancelled load shows nothing", fresh.state(of: .artists).content == nil)
            await fresh.loadIfNeeded(.artists, api: api)
            check("and loads again when asked", fresh.state(of: .artists).content != nil)
        }

        // 5. A signed-out answer is a failure with words for the listener, not a crash.
        do {
            let fresh = LibraryModel()
            await fresh.loadIfNeeded(.playlists, api: refusing)
            if case .failed(let message) = fresh.state(of: .playlists) {
                check("a refused request fails with a message", !message.isEmpty)
            } else {
                check("a refused request fails with a message", false, "\(fresh.state(of: .playlists))")
            }
        }

        // 6. Delete: gone at once, still gone when it succeeds.
        let doomed = playlists.compactMap { item -> PlaylistItem? in
            if case .playlist(let playlist) = item, playlist.isOwn { return playlist }
            return nil
        }.first
        if let doomed {
            let before = model.state(of: .playlists).content?.items.count ?? 0
            let task = Task { await model.deletePlaylist(doomed, api: api) }
            await sleep(60)
            let during = model.state(of: .playlists).content?.items.count ?? 0
            check("delete: the row is gone before the answer", during == before - 1, "\(before) -> \(during)")
            await task.value
            let after = model.state(of: .playlists).content?.items.count ?? 0
            check("delete: it stays gone", after == before - 1 && model.notice == nil, "\(after)")

            // 7. Delete refused: the row comes back where it was, and the listener is told.
            let second = playlists.compactMap { item -> PlaylistItem? in
                if case .playlist(let playlist) = item, playlist.isOwn, playlist.id != doomed.id { return playlist }
                return nil
            }.first
            if let second {
                let index = model.state(of: .playlists).content?.items.firstIndex(of: .playlist(second))
                await model.deletePlaylist(second, api: refusing)
                let restored = model.state(of: .playlists).content?.items.firstIndex(of: .playlist(second))
                check("refused delete: the row returns to its place", restored == index && index != nil,
                      "\(String(describing: index)) vs \(String(describing: restored))")
                check("refused delete: the listener is told", model.notice?.message.contains("still in your library") == true)
            }
        } else {
            check("the preview has playlists of the listener's own", false)
        }

        // 8. The expiry date is found inside a signed blob.
        do {
            let expiry = Date(timeIntervalSince1970: 1_800_000_000)
            let plist = try? PropertyListSerialization.data(
                fromPropertyList: ["ExpirationDate": expiry, "Name": "x"], format: .xml, options: 0)
            var blob = Data([0x30, 0x82, 0x0A, 0xFF, 0x06, 0x09, 0x2A])
            blob.append(plist ?? Data())
            blob.append(Data([0x00, 0x31, 0x82, 0x01]))
            check("expiry: read from a signed profile", BuildInfo.expirationDate(inProfile: blob) == expiry)
            check("expiry: nil without a property list", BuildInfo.expirationDate(inProfile: Data([1, 2, 3])) == nil)
        }

        return report
    }
}
