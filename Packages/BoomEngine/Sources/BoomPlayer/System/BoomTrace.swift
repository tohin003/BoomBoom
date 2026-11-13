import Foundation
import os

/// A short running record of what the engine did, kept in the app's Documents folder (`boom-trace.log`, the last
/// few hundred kilobytes). It exists so that a problem seen on the phone (a song that would not start, playback
/// that stopped in the background) can be read afterwards from the Mac. It holds times, video ids, formats and
/// states: no account details, cookies or search words.
public enum BoomTrace {
    private static let logger = Logger(subsystem: "io.github.tohin003.BoomBoom", category: "trace")
    private static let queue = DispatchQueue(label: "boom.trace", qos: .utility)
    private static let limit = 300_000
    private static let started = Date()
    private nonisolated(unsafe) static var handle: FileHandle?
    private nonisolated(unsafe) static var written = 0

    /// On the phone: the app's Documents folder. On a Mac (tests): the temporary folder.
    public static let fileURL: URL = {
        #if os(iOS)
        let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        #else
        let folder = FileManager.default.temporaryDirectory
        #endif
        return folder.appending(path: "boom-trace.log")
    }()

    /// Notes are few (a handful per song), so the text is built where it is noted.
    public static func note(_ text: String) {
        // The clock the "+seconds" count from starts with the first note, not when the writer gets round to it.
        let stamp = max(Date(), started)
        queue.async {
            logger.info("\(text, privacy: .public)")
            let line = String(format: "%@ +%.1f %@\n", stamp.formatted(.iso8601), stamp.timeIntervalSince(started), text)
            write(line)
        }
    }

    private static func write(_ line: String) {
        if handle == nil {
            // Start each launch with the tail of the previous one, so a crash's last words survive.
            let previous = (try? Data(contentsOf: fileURL)) ?? Data()
            let kept = previous.suffix(limit / 3)
            try? kept.write(to: fileURL)
            handle = try? FileHandle(forWritingTo: fileURL)
            _ = try? handle?.seekToEnd()
            written = kept.count
            let header = "---- launch \(Date().formatted(.iso8601)) ----\n"
            try? handle?.write(contentsOf: Data(header.utf8))
        }
        guard let data = line.data(using: .utf8) else { return }
        try? handle?.write(contentsOf: data)
        written += data.count
        if written > limit {
            try? handle?.close()
            handle = nil
        }
    }
}
