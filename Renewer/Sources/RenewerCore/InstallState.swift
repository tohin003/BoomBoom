import Foundation

/// What `scripts/renew.sh` records in `state.json` after a successful install: which snapshot of the sources is on the
/// iPhone, and its signing profile's dates. Only the dates and the app id's bundle part come from the profile; nothing else of it
/// (no certificate, device list or team name) is kept. Unknown keys are ignored and every key may be missing.
public struct InstallState: Codable, Equatable, Sendable {
    public var version: Int?
    public var installedAt: Date?
    /// The identity of the snapshot that was built: the first 12 hex digits of a hash over its files.
    public var snapshot: String?
    /// When that snapshot was taken from the working tree (the last `renew.sh --from-tree`).
    public var snapshotAt: Date?
    /// "tree" (renew.sh --from-tree: a fresh copy of the working tree) or "snapshot" (a renewal of the saved copy).
    public var builtFrom: String?
    /// The devicectl identifier it was installed on.
    public var device: String?
    public var bundleId: String?
    public var profileCreated: Date?
    public var profileExpires: Date?

    public init(
        version: Int? = 1, installedAt: Date? = nil, snapshot: String? = nil, snapshotAt: Date? = nil,
        builtFrom: String? = nil, device: String? = nil, bundleId: String? = nil, profileCreated: Date? = nil,
        profileExpires: Date? = nil
    ) {
        self.version = version
        self.installedAt = installedAt
        self.snapshot = snapshot
        self.snapshotAt = snapshotAt
        self.builtFrom = builtFrom
        self.device = device
        self.bundleId = bundleId
        self.profileCreated = profileCreated
        self.profileExpires = profileExpires
    }

    /// The snapshot to rebuild, only when its identity is plain hex digits (it is shown in the log and `--check`).
    public var buildSnapshot: String? {
        guard let snapshot, (7...64).contains(snapshot.count), snapshot.allSatisfy(\.isHexDigit) else { return nil }
        return snapshot.lowercased()
    }

    public var shortSnapshot: String? { buildSnapshot.map { String($0.prefix(8)) } }

    /// The recorded device, only when it looks like a devicectl identifier or UDID.
    public var buildDevice: String? {
        guard let device, DeviceList.isPlainIdentifier(device) else { return nil }
        return device
    }

    public static func decode(_ data: Data) throws -> InstallState {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return try decoder.decode(InstallState.self, from: data)
    }

    /// The state file's contents, or nil when it is missing or unreadable.
    public static func load(from url: URL) -> InstallState? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? decode(data)
    }
}

/// Where the end date shown in the menu came from.
public enum ExpirySource: String, Equatable, Sendable {
    /// state.json: the build renew.sh last installed.
    case installedBuild
    /// Until renew.sh has installed once: the newest Xcode profile for the app's bundle id.
    case xcodeProfile
}

public struct ExpiryInfo: Equatable, Sendable {
    public var expires: Date
    public var source: ExpirySource

    public init(expires: Date, source: ExpirySource) {
        self.expires = expires
        self.source = source
    }
}

public enum Expiry {
    /// The build last installed wins; Xcode's newest profile is only the fallback before renew.sh has run once.
    public static func resolve(state: InstallState?, profile: ProfileSummary?) -> ExpiryInfo? {
        if let expires = state?.profileExpires { return ExpiryInfo(expires: expires, source: .installedBuild) }
        if let profile { return ExpiryInfo(expires: profile.expires, source: .xcodeProfile) }
        return nil
    }
}
