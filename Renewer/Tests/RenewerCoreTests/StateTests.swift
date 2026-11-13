import Foundation
import Testing
@testable import RenewerCore

struct InstallStateTests {
    /// What scripts/renew.sh writes (with a key a later version might add).
    static let sample = #"""
    {
      "builtFrom": "tree",
      "bundleId": "io.github.tohin003.BoomBoom",
      "device": "1A2B3C4D-1111-2222-3333-444455556666",
      "installedAt": "2026-10-31T03:44:05Z",
      "profileCreated": "2026-10-29T20:37:30Z",
      "profileExpires": "2026-11-05T20:37:30Z",
      "snapshot": "563c2b9e0f1d",
      "snapshotAt": "2026-10-31T03:40:00Z",
      "version": 1,
      "somethingNew": [1, 2]
    }
    """#

    @Test func readsWhatRenewShWrites() throws {
        let state = try InstallState.decode(Data(Self.sample.utf8))
        #expect(state.buildSnapshot == "563c2b9e0f1d")
        #expect(state.shortSnapshot == "563c2b9e")
        #expect(state.snapshotAt == Fixture.date("2026-10-31T03:40:00Z"))
        #expect(state.buildDevice == "1A2B3C4D-1111-2222-3333-444455556666")
        #expect(state.profileExpires == Fixture.expires)
        #expect(state.profileCreated == Fixture.date("2026-10-29T20:37:30Z"))
        #expect(state.installedAt == Fixture.date("2026-10-31T03:44:05Z"))
        #expect(state.builtFrom == "tree")
    }

    @Test func everyKeyMayBeMissing() throws {
        let state = try InstallState.decode(Data("{}".utf8))
        #expect(state.snapshot == nil)
        #expect(state.buildSnapshot == nil)
        #expect(state.profileExpires == nil)
    }

    @Test func onlyPlainHashesAndIdentifiersAreUsed() {
        let odd = InstallState(snapshot: "HEAD; rm -rf ~", device: "--force")
        #expect(odd.buildSnapshot == nil)
        #expect(odd.buildDevice == nil)
        #expect(InstallState(snapshot: "ABCDEF1").buildSnapshot == "abcdef1")
        #expect(InstallState(snapshot: "abc12").buildSnapshot == nil)
    }

    @Test func garbageIsAnError() {
        #expect(throws: (any Error).self) { try InstallState.decode(Data("not a state file".utf8)) }
    }

    @Test func theInstalledBuildWinsOverXcode() {
        let profile = ProfileSummary(
            created: Fixture.date("2026-10-30T10:58:27Z"), expires: Fixture.date("2026-11-06T10:58:27Z"),
            bundleId: Fixture.bundleId)
        #expect(Expiry.resolve(state: InstallState(profileExpires: Fixture.expires), profile: profile)
            == ExpiryInfo(expires: Fixture.expires, source: .installedBuild))
        #expect(Expiry.resolve(state: InstallState(), profile: profile)
            == ExpiryInfo(expires: profile.expires, source: .xcodeProfile))
        #expect(Expiry.resolve(state: nil, profile: nil) == nil)
    }

    @Test func pathsShareRenewShsFolder() {
        let home = URL(filePath: "/Users/someone", directoryHint: .isDirectory)
        let paths = RenewerPaths.standard(home: home)
        #expect(paths.stateFile.path(percentEncoded: false)
            == "/Users/someone/Library/Application Support/BoomBoomRenewer/state.json")
        #expect(paths.snapshot.path(percentEncoded: false)
            .hasPrefix("/Users/someone/Library/Application Support/BoomBoomRenewer/snapshot"))
        #expect(paths.logFile.path(percentEncoded: false) == "/Users/someone/Library/Logs/BoomBoomRenewer.log")
        let moved = RenewerPaths.standard(home: home, environment: ["BOOMBOOM_RENEWER_DIR": "/Volumes/x/renewer"])
        #expect(moved.snapshot.path(percentEncoded: false).hasPrefix("/Volumes/x/renewer/snapshot"))
    }
}

struct RenewerRecordTests {
    @Test func roundTrips() throws {
        var record = RenewerRecord(
            autoRenew: false, lastAutomaticFailure: Fixture.date("2026-11-05T01:00:00Z"), automaticFailures: 2,
            unmovedExpiry: Fixture.expires)
        record.lastAttempt = RenewerRecord.Attempt(
            at: Fixture.date("2026-11-05T00:30:00Z"), finishedAt: Fixture.date("2026-11-05T00:36:00Z"),
            automatic: true, succeeded: false, message: Copy.unreachable)
        record.markNotified(.unreachable, for: Fixture.expires)
        let again = RenewerRecord.decode(try record.encoded())
        #expect(again == record)
        #expect(again.wasNotified(.unreachable, for: Fixture.expires))
        #expect(!again.wasNotified(.failed, for: Fixture.expires))
        #expect(!again.wasNotified(.unreachable, for: Fixture.expires.addingTimeInterval(7 * 86_400)))
        #expect(!again.wasNotified(.unreachable, for: nil))
    }

    @Test func defaultsWhenMissingOrUnreadable() {
        #expect(RenewerRecord.decode(Data("{}".utf8)).autoRenew, "Renew Automatically is on by default")
        #expect(RenewerRecord.decode(Data("{}".utf8)).automaticFailures == 0)
        #expect(RenewerRecord.decode(Data("garbage".utf8)) == RenewerRecord())
        #expect(RenewerRecord.decode(Data(#"{"autoRenew": false}"#.utf8)).autoRenew == false)
    }

    @Test func ownerOnlyFiles() throws {
        let folder = FileManager.default.temporaryDirectory.appending(path: "renewer-files-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: folder) }
        let file = folder.appending(path: "inner/renewer.json")
        try OwnerOnlyFile.write(Data("{}".utf8), to: file)
        try OwnerOnlyFile.write(Data(#"{"autoRenew": false}"#.utf8), to: file)
        #expect(RenewerRecord.load(from: file).autoRenew == false)
        let manager = FileManager.default
        let fileMode = try manager.attributesOfItem(atPath: file.path(percentEncoded: false))[.posixPermissions]
        let folderMode = try manager.attributesOfItem(
            atPath: file.deletingLastPathComponent().path(percentEncoded: false))[.posixPermissions]
        #expect((fileMode as? NSNumber)?.intValue == 0o600)
        #expect((folderMode as? NSNumber)?.intValue == 0o700)
        let names = try manager.contentsOfDirectory(atPath: file.deletingLastPathComponent().path(percentEncoded: false))
        #expect(names == ["renewer.json"], "no temporary file is left behind")
    }
}
