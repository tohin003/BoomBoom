import BoomAudio
import Foundation

/// What is remembered about a kept song besides its audio.
struct CachedTrackDetails: Codable, Sendable, Equatable {
    var loudnessDb: Double?
    var format: AudioOffer?
    var savedAt: Date
}

/// Songs kept on the phone after they were fetched once: they start at once and play without the network.
/// Files live in the app's Caches folder (the system may clear it when the phone runs out of space); when the
/// folder passes its limit, the songs played longest ago go first.
final class AudioCache: @unchecked Sendable {
    private let folder: URL
    private let files = FileManager.default
    private let work = DispatchQueue(label: "boom.cache", qos: .utility)
    private let lock = NSLock()
    private var limit: Int64 = 2_000_000_000

    var limitBytes: Int64 {
        get { lock.withLock { limit } }
        set {
            lock.withLock { limit = newValue }
            work.async { self.trim() }
        }
    }

    init(folder: URL? = nil) {
        let base = folder ?? FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appending(path: "BoomTracks", directoryHint: .isDirectory)
        self.folder = base
        try? files.createDirectory(at: base, withIntermediateDirectories: true)
    }

    private func audioURL(_ videoId: String) -> URL { folder.appending(path: Self.safe(videoId) + ".boomtrk") }
    private func detailsURL(_ videoId: String) -> URL { folder.appending(path: Self.safe(videoId) + ".json") }

    /// Video ids are letters, digits, "-" and "_"; anything else never reaches a file name.
    private static func safe(_ videoId: String) -> String {
        String(videoId.unicodeScalars.filter { CharacterSet.alphanumerics.contains($0) || $0 == "-" || $0 == "_" })
    }

    func contains(_ videoId: String) -> Bool {
        files.fileExists(atPath: audioURL(videoId).path)
    }

    /// Reads a kept song. A few megabytes from flash: quick enough to do on a tap.
    func load(_ videoId: String) -> (buffer: TrackBuffer, details: CachedTrackDetails?)? {
        let url = audioURL(videoId)
        guard let data = try? Data(contentsOf: url, options: .mappedIfSafe),
              let buffer = TrackBuffer(serialized: data) else {
            // A cut-off or outdated file is of no use.
            try? files.removeItem(at: url)
            return nil
        }
        let details = (try? Data(contentsOf: detailsURL(videoId)))
            .flatMap { try? JSONDecoder().decode(CachedTrackDetails.self, from: $0) }
        // "Played now": the trimming goes by this date.
        try? files.setAttributes([.modificationDate: Date()], ofItemAtPath: url.path)
        return (buffer, details)
    }

    /// Keeps a fully fetched song. Runs in the background.
    func store(_ videoId: String, buffer: TrackBuffer, details: CachedTrackDetails) {
        work.async {
            guard let data = buffer.serialized() else { return }
            do {
                try data.write(to: self.audioURL(videoId), options: .atomic)
                try JSONEncoder().encode(details).write(to: self.detailsURL(videoId), options: .atomic)
            } catch {
                try? self.files.removeItem(at: self.audioURL(videoId))
            }
            self.trim()
        }
    }

    private func entries() -> [(url: URL, size: Int64, date: Date)] {
        let keys: [URLResourceKey] = [.fileSizeKey, .contentModificationDateKey]
        let urls = (try? files.contentsOfDirectory(at: folder, includingPropertiesForKeys: keys)) ?? []
        return urls.filter { $0.pathExtension == "boomtrk" }.compactMap { url in
            guard let values = try? url.resourceValues(forKeys: Set(keys)) else { return nil }
            return (url, Int64(values.fileSize ?? 0), values.contentModificationDate ?? .distantPast)
        }
    }

    private func trim() {
        let limit = lock.withLock { self.limit }
        var all = entries().sorted { $0.date < $1.date }
        var total = all.reduce(0) { $0 + $1.size }
        while total > limit, !all.isEmpty {
            let oldest = all.removeFirst()
            try? files.removeItem(at: oldest.url)
            try? files.removeItem(at: oldest.url.deletingPathExtension().appendingPathExtension("json"))
            total -= oldest.size
        }
    }

    // MARK: Totals

    func usedBytes() async -> Int64 {
        await withCheckedContinuation { continuation in
            work.async { continuation.resume(returning: self.entries().reduce(0) { $0 + $1.size }) }
        }
    }

    func songCount() async -> Int {
        await withCheckedContinuation { continuation in
            work.async { continuation.resume(returning: self.entries().count) }
        }
    }

    func clear() async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            work.async {
                for url in (try? self.files.contentsOfDirectory(at: self.folder, includingPropertiesForKeys: nil)) ?? [] {
                    try? self.files.removeItem(at: url)
                }
                continuation.resume()
            }
        }
    }
}

/// The cache as the settings screen's store sees it.
@MainActor
final class AudioCacheSettings: AudioCacheBackend {
    private let cache: AudioCache

    init(_ cache: AudioCache) {
        self.cache = cache
    }

    var limitBytes: Int64 {
        get { cache.limitBytes }
        set { cache.limitBytes = newValue }
    }

    func usedBytes() async -> Int64 { await cache.usedBytes() }
    func songCount() async -> Int { await cache.songCount() }
    func clear() async { await cache.clear() }
}
