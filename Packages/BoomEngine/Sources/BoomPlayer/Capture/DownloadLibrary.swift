import BoomAudio
import Foundation

/// The songs the listener chose to keep. Unlike the automatic cache, nothing here is ever removed except by the
/// listener: the files live in Application Support (which the system does not clear when space runs low) and are
/// left out of device backups, since they can be fetched again.
final class DownloadLibrary: @unchecked Sendable {
    private let folder: URL
    private let files = FileManager.default
    private let work = DispatchQueue(label: "boom.downloads", qos: .utility)

    init(folder: URL? = nil) {
        var base = folder ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appending(path: "BoomDownloads", directoryHint: .isDirectory)
        try? files.createDirectory(at: base, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? base.setResourceValues(values)
        self.folder = base
    }

    /// Where the list of downloads (titles, covers, what belongs to which playlist) is kept.
    var indexURL: URL { folder.appending(path: "index.json") }

    private func audioURL(_ videoId: String) -> URL { folder.appending(path: Self.safe(videoId) + ".boomtrk") }
    private func detailsURL(_ videoId: String) -> URL { folder.appending(path: Self.safe(videoId) + ".json") }

    /// Video ids are letters, digits, "-" and "_"; anything else never reaches a file name.
    private static func safe(_ videoId: String) -> String {
        String(videoId.unicodeScalars.filter { CharacterSet.alphanumerics.contains($0) || $0 == "-" || $0 == "_" })
    }

    func contains(_ videoId: String) -> Bool {
        files.fileExists(atPath: audioURL(videoId).path)
    }

    /// The file's size in bytes, 0 when the song is not kept.
    func size(of videoId: String) -> Int64 {
        let size = try? audioURL(videoId).resourceValues(forKeys: [.fileSizeKey]).fileSize
        return Int64(size ?? 0)
    }

    func details(of videoId: String) -> CachedTrackDetails? {
        (try? Data(contentsOf: detailsURL(videoId))).flatMap { try? JSONDecoder().decode(CachedTrackDetails.self, from: $0) }
    }

    /// Reads a kept song. A few megabytes from flash: quick enough to do on a tap.
    func load(_ videoId: String) -> (buffer: TrackBuffer, details: CachedTrackDetails?)? {
        guard let data = try? Data(contentsOf: audioURL(videoId), options: .mappedIfSafe),
              let buffer = TrackBuffer(serialized: data), buffer.isComplete else { return nil }
        return (buffer, details(of: videoId))
    }

    /// Keeps a fully fetched song and says how many bytes it took (nil when it could not be written).
    func store(
        _ videoId: String, buffer: TrackBuffer, details: CachedTrackDetails,
        then completion: @escaping @Sendable (Int64?) -> Void
    ) {
        work.async {
            guard buffer.isComplete, let data = buffer.serialized() else { return completion(nil) }
            do {
                try data.write(to: self.audioURL(videoId), options: .atomic)
                try JSONEncoder().encode(details).write(to: self.detailsURL(videoId), options: .atomic)
                completion(Int64(data.count))
            } catch {
                try? self.files.removeItem(at: self.audioURL(videoId))
                completion(nil)
            }
        }
    }

    func remove(_ videoIds: [String]) {
        work.async {
            for videoId in videoIds {
                try? self.files.removeItem(at: self.audioURL(videoId))
                try? self.files.removeItem(at: self.detailsURL(videoId))
            }
        }
    }
}
