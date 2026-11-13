import Foundation
import RenewerCore

/// Where the repository and its script are, and how long each step may take.
struct AppConfiguration: Sendable {
    /// BoomBoom's repository: renew.sh runs from its `scripts/`.
    var repository: URL
    var paths: RenewerPaths
    /// The iPhone app's bundle id (App/Base.xcconfig), for the Xcode-profile fallback before renew.sh has run.
    var bundleId = "io.github.tohin003.BoomBoom"
    var policy = RenewalPolicy()
    /// Xcode for every subprocess, set per process and never through the global xcode-select.
    var developerDir = "/Applications/Xcode.app/Contents/Developer"
    /// A renewal is stopped after this long (renew.sh has its own, shorter limits; this is the last resort).
    var renewTimeout: TimeInterval = 40 * 60
    var probeTimeout: TimeInterval = 90

    var renewScript: URL { repository.appending(path: "scripts/renew.sh") }

    /// The Info.plist key install.sh fills with the repository's path.
    static let repositoryKey = "BoomBoomRepository"

    /// The repository is `--repo PATH`, else the app's Info.plist, else `BOOMBOOM_REPO`, else the nearest folder
    /// above the executable that holds scripts/renew.sh (a development run from Renewer/.build).
    static func resolve(arguments: [String], environment: [String: String], bundle: Bundle) -> AppConfiguration {
        let home = URL(filePath: NSHomeDirectory(), directoryHint: .isDirectory)
        var candidates: [String] = []
        if let index = arguments.firstIndex(of: "--repo"), index + 1 < arguments.count {
            candidates.append(arguments[index + 1])
        }
        if let named = bundle.object(forInfoDictionaryKey: repositoryKey) as? String { candidates.append(named) }
        if let named = environment["BOOMBOOM_REPO"] { candidates.append(named) }
        let repository = candidates.first(where: { !$0.isEmpty }).map { URL(filePath: $0, directoryHint: .isDirectory) }
            ?? bundle.executableURL.flatMap { findRepository(above: $0) }
            ?? URL(filePath: FileManager.default.currentDirectoryPath, directoryHint: .isDirectory)
        return AppConfiguration(
            repository: repository, paths: RenewerPaths.standard(home: home, environment: environment))
    }

    static func findRepository(above executable: URL) -> URL? {
        var folder = executable.deletingLastPathComponent()
        for _ in 0..<10 {
            let script = folder.appending(path: "scripts/renew.sh").path(percentEncoded: false)
            if FileManager.default.fileExists(atPath: script) { return folder }
            let parent = folder.deletingLastPathComponent()
            if parent.path(percentEncoded: false) == folder.path(percentEncoded: false) { return nil }
            folder = parent
        }
        return nil
    }
}
