import Foundation
import Testing
@testable import RenewerCore

/// Runs the real `scripts/renew.sh` against fake `xcrun` and `xcodegen` tools in a throwaway folder, then reads what
/// it printed, its exit code and `state.json` the way the menu-bar item does. This is the check that the script and
/// RenewerCore agree on exit codes, output lines and the state file's fields. Nothing here touches a real iPhone, the
/// real Xcode build, or the person's `~/Library`.
struct RenewScriptContractTests {
    static let xcode = "/Applications/Xcode.app/Contents/Developer"
    static let xcodeInstalled = FileManager.default.isExecutableFile(atPath: xcode + "/usr/bin/xcodebuild")

    static var realScript: URL {
        URL(filePath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent().appending(path: "scripts/renew.sh")
    }

    /// A fake Mac: a repository with renew.sh in it, fake tools, a support folder, and one connected iPhone.
    final class Sandbox {
        let base: URL
        let repo: URL
        let support: URL
        let bin: URL
        let iPhone = "AAAA-1111-BBBB"

        init() throws {
            let fm = FileManager.default
            base = fm.temporaryDirectory.appending(path: "renew-contract-\(UUID().uuidString)", directoryHint: .isDirectory)
            repo = base.appending(path: "repo", directoryHint: .isDirectory)
            support = base.appending(path: "support", directoryHint: .isDirectory)
            bin = base.appending(path: "bin", directoryHint: .isDirectory)
            try fm.createDirectory(at: repo.appending(path: "scripts"), withIntermediateDirectories: true)
            try fm.createDirectory(at: repo.appending(path: "App"), withIntermediateDirectories: true)
            try fm.createDirectory(at: bin, withIntermediateDirectories: true)
            try fm.copyItem(at: RenewScriptContractTests.realScript, to: repo.appending(path: "scripts/renew.sh"))
            try "name: Fake\n".write(to: repo.appending(path: "project.yml"), atomically: true, encoding: .utf8)
            try write("App/Main.swift", "let version = 1\n")
            try tool("xcrun", Sandbox.fakeXcrun)
            try tool("xcodegen", Sandbox.fakeXcodegen)
            try setDevices([Sandbox.device(id: iPhone)])
            try setProfile(expires: Date().addingTimeInterval(6.5 * 86_400))
        }

        deinit { try? FileManager.default.removeItem(at: base) }

        var stateFile: URL { support.appending(path: "state.json") }
        var snapshot: URL { support.appending(path: "snapshot") }

        func write(_ path: String, _ text: String, in folder: URL? = nil) throws {
            let url = (folder ?? repo).appending(path: path)
            try FileManager.default.createDirectory(
                at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            try text.write(to: url, atomically: true, encoding: .utf8)
        }

        func tool(_ name: String, _ body: String) throws {
            let url = bin.appending(path: name)
            try body.write(to: url, atomically: true, encoding: .utf8)
            try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: url.path(percentEncoded: false))
        }

        static func device(
            id: String, transport: String = "wired", tunnel: String = "connected", pairing: String = "paired",
            name: String = "Phone", type: String = "iPhone"
        ) -> String {
            """
            {"identifier":"\(id)","hardwareProperties":{"platform":"iOS","deviceType":"\(type)","udid":"0000-\(id)"},\
            "deviceProperties":{"name":"\(name)"},"connectionProperties":{"pairingState":"\(pairing)",\
            "tunnelState":"\(tunnel)","transportType":"\(transport)"}}
            """
        }

        func setDevices(_ devices: [String]) throws {
            try write("devices.json", "{\"result\":{\"devices\":[\(devices.joined(separator: ","))]}}", in: base)
        }

        func setProfile(expires: Date, created: Date? = nil) throws {
            let formatter = ISO8601DateFormatter()
            let made = formatter.string(from: created ?? expires.addingTimeInterval(-7 * 86_400))
            let ends = formatter.string(from: expires)
            try write("profile.plist", """
                <?xml version="1.0" encoding="UTF-8"?>
                <plist version="1.0"><dict><key>CreationDate</key><date>\(made)</date>\
                <key>ExpirationDate</key><date>\(ends)</date><key>Entitlements</key><dict>\
                <key>application-identifier</key><string>TEAM123456.io.github.tohin003.BoomBoom</string></dict>\
                <key>TeamName</key><string>Somebody's Team</string>\
                <key>DeveloperCertificates</key><array><data>Q0VSVEJZVEVT</data></array></dict></plist>
                """, in: base)
        }

        struct Result {
            var status: Int32
            var output: String
            var lines: [String] { output.split(separator: "\n", omittingEmptySubsequences: true).map(String.init) }
            var outcome: RenewOutcome {
                RenewScript.outcome(status: status, output: RenewOutput(lines: lines))
            }
        }

        /// Runs renew.sh. `env` adds to or replaces the fake machine's environment.
        @discardableResult
        func renew(_ arguments: [String] = [], env: [String: String] = [:]) throws -> Result {
            let process = Process()
            process.executableURL = URL(filePath: "/bin/bash")
            process.arguments = [repo.appending(path: "scripts/renew.sh").path(percentEncoded: false)] + arguments
            var environment = ProcessInfo.processInfo.environment
            environment["HOME"] = base.appending(path: "home").path(percentEncoded: false)
            environment["BOOMBOOM_RENEWER_DIR"] = support.path(percentEncoded: false)
            environment["DEVELOPER_DIR"] = RenewScriptContractTests.xcode
            environment["BOOMBOOM_RENEW_XCRUN"] = bin.appending(path: "xcrun").path(percentEncoded: false)
            environment["BOOMBOOM_RENEW_XCODEGEN"] = bin.appending(path: "xcodegen").path(percentEncoded: false)
            environment["BOOMBOOM_RENEW_RETRY_WAIT"] = "0"
            environment["FAKE_BASE"] = base.path(percentEncoded: false)
            for (key, value) in env { environment[key] = value }
            process.environment = environment
            let pipe = Pipe()
            process.standardOutput = pipe
            process.standardError = pipe
            process.standardInput = FileHandle.nullDevice
            try process.run()
            let data = pipe.fileHandleForReading.readDataToEndOfFile()
            process.waitUntilExit()
            return Result(status: process.terminationStatus, output: String(decoding: data, as: UTF8.self))
        }

        func calls() -> [String] {
            let text = (try? String(contentsOf: base.appending(path: "calls.log"), encoding: .utf8)) ?? ""
            return text.split(separator: "\n").map(String.init)
        }

        func state() -> InstallState? { InstallState.load(from: stateFile) }

        static let fakeXcrun = """
            #!/bin/bash
            # Fake xcrun: devicectl list devices, devicectl device install app, xcodebuild.
            echo "$*" >> "$FAKE_BASE/calls.log"
            case "$1" in
              devicectl)
                if [ "$2" = list ]; then
                  out=""
                  while [ $# -gt 0 ]; do [ "$1" = "--json-output" ] && out="$2"; shift; done
                  cp "$FAKE_BASE/devices.json" "$out"
                else
                  if [ -e "$FAKE_BASE/install-fails" ]; then cat "$FAKE_BASE/install-fails" >&2; exit 1; fi
                  echo "App installed"
                fi ;;
              xcodebuild)
                if [ -e "$FAKE_BASE/build-sleeps" ]; then exec /bin/sleep 60; fi
                if [ -e "$FAKE_BASE/build-fails" ]; then cat "$FAKE_BASE/build-fails"; exit 65; fi
                app=build/ios/Build/Products/Release-iphoneos/BoomBoom.app
                mkdir -p "$app"
                { printf '\\x30\\x82\\x01garbage'; cat "$FAKE_BASE/profile.plist"; printf 'tail\\x00\\x01'; } \\
                  > "$app/embedded.mobileprovision"
                cp -R "$PWD/App" "$app/" 2>/dev/null || true ;;
            esac
            """

        static let fakeXcodegen = """
            #!/bin/bash
            mkdir -p BoomBoom.xcodeproj
            echo generated > BoomBoom.xcodeproj/project.pbxproj
            """
    }

    // MARK: The first install and a renewal

    @Test(.enabled(if: xcodeInstalled)) func firstInstallFromTheTreeWorksWithoutGitOrState() throws {
        let box = try Sandbox()
        let result = try box.renew(["--from-tree"])
        #expect(result.status == RenewScript.done, "\(result.output)")
        guard case .renewed(let printed) = result.outcome else {
            Issue.record("not renewed: \(result.output)")
            return
        }
        let state = try #require(box.state())
        #expect(state.version == 1)
        #expect(state.builtFrom == "tree")
        #expect(state.device == box.iPhone)
        #expect(state.bundleId == "io.github.tohin003.BoomBoom")
        #expect(state.buildSnapshot?.count == 12)
        #expect(state.snapshotAt != nil && state.installedAt != nil && state.profileCreated != nil)
        let expires = try #require(state.profileExpires)
        // The line renew.sh prints carries the same end date, to the minute.
        let shown = try #require(printed)
        #expect(abs(shown.timeIntervalSince(expires)) < 60)
        #expect(result.lines.last?.hasPrefix("renew.sh: recorded") == true)
        #expect(result.lines.contains { $0.hasPrefix("renew.sh: installed. This build expires ") })
        #expect(box.calls().contains { $0.contains("install app --device \(box.iPhone)") })
        #expect(box.calls().contains { $0.contains("-configuration Release") && $0.contains("-allowProvisioningUpdates") })
        #expect(FileManager.default.fileExists(atPath: box.snapshot.appending(path: "project.yml").path))
    }

    @Test(.enabled(if: xcodeInstalled)) func stateFileHoldsOnlyTheAgreedFieldsAndIsOwnerOnly() throws {
        let box = try Sandbox()
        try box.renew(["--from-tree"])
        let data = try Data(contentsOf: box.stateFile)
        let keys = try #require(try JSONSerialization.jsonObject(with: data) as? [String: Any]).keys
        #expect(Set(keys) == [
            "version", "installedAt", "snapshot", "snapshotAt", "builtFrom", "device", "bundleId", "profileCreated",
            "profileExpires",
        ])
        let text = String(decoding: data, as: UTF8.self)
        #expect(!text.contains("TEAM123456") && !text.contains("Somebody") && !text.contains("Q0VSVEJZVEVT"))
        func mode(_ url: URL) -> Int {
            let attributes = try? FileManager.default.attributesOfItem(atPath: url.path(percentEncoded: false))
            return (attributes?[.posixPermissions] as? Int) ?? -1
        }
        #expect(mode(box.stateFile) == 0o600)
        #expect(mode(box.support) == 0o700)
    }

    @Test(.enabled(if: xcodeInstalled)) func aRenewalRebuildsTheSnapshotNotTheWorkingTree() throws {
        let box = try Sandbox()
        try box.renew(["--from-tree"])
        let first = try #require(box.state())
        // A half-finished edit in the working tree, and a new profile with a later end date.
        try box.write("App/Main.swift", "let version = 2 // half done\n")
        try box.setProfile(expires: Date().addingTimeInterval(7 * 86_400))
        let result = try box.renew(["--device", box.iPhone])
        #expect(result.status == 0, "\(result.output)")
        let second = try #require(box.state())
        #expect(second.snapshot == first.snapshot, "the snapshot did not change")
        #expect(second.builtFrom == "snapshot")
        #expect(second.snapshotAt == first.snapshotAt)
        #expect(second.profileExpires != first.profileExpires)
        let built = try String(
            contentsOf: box.snapshot.appending(path: "build/ios/Build/Products/Release-iphoneos/BoomBoom.app/App/Main.swift"),
            encoding: .utf8)
        #expect(built == "let version = 1\n")
        #expect(!result.output.contains("did not move"))
    }

    @Test(.enabled(if: xcodeInstalled)) func aRenewalWithoutAnyNewDateSaysTheDateDidNotMove() throws {
        let box = try Sandbox()
        try box.renew(["--from-tree"])
        let result = try box.renew()
        #expect(result.status == 0)
        #expect(result.lines.contains { $0.hasPrefix("renew.sh: note: the end date did not move") })
    }

    @Test(.enabled(if: xcodeInstalled)) func aNewTreeBecomesTheNewSnapshot() throws {
        let box = try Sandbox()
        try box.renew(["--from-tree"])
        let first = try #require(box.state())
        try box.write("App/Main.swift", "let version = 3\n")
        try box.renew(["--from-tree"])
        let second = try #require(box.state())
        #expect(second.snapshot != first.snapshot)
        let source = try String(contentsOf: box.snapshot.appending(path: "App/Main.swift"), encoding: .utf8)
        #expect(source == "let version = 3\n")
    }

    @Test(.enabled(if: xcodeInstalled)) func theTreeCopyLeavesOutBuildProductsAndGit() throws {
        let box = try Sandbox()
        try box.write("build/junk.txt", "x")
        try box.write(".build/junk.txt", "x")
        try box.write("DerivedData/junk.txt", "x")
        try box.write(".git/HEAD", "x")
        try box.write("Renewer/.build/junk.txt", "x")
        try box.write("BoomBoom.xcodeproj/xcuserdata/me.xcuserdatad/a", "x")
        try box.write("Packages/Core/Package.swift", "// package\n")
        try box.renew(["--from-tree"])
        let fm = FileManager.default
        for left in ["build/junk.txt", ".build/junk.txt", "DerivedData/junk.txt", ".git/HEAD", "Renewer/.build/junk.txt",
                     "BoomBoom.xcodeproj/xcuserdata/me.xcuserdatad/a"] {
            #expect(!fm.fileExists(atPath: box.snapshot.appending(path: left).path), "\(left) was copied")
        }
        #expect(fm.fileExists(atPath: box.snapshot.appending(path: "Packages/Core/Package.swift").path))
    }

    // MARK: Every failure has its own exit code

    @Test(.enabled(if: xcodeInstalled)) func nothingInstalledYetIsNoProject() throws {
        let box = try Sandbox()
        let result = try box.renew()
        #expect(result.status == RenewScript.noProject)
        #expect(result.outcome == .noProject)
        #expect(box.calls().isEmpty)
    }

    @Test(.enabled(if: xcodeInstalled)) func aTreeWithoutProjectYmlIsNoProject() throws {
        let box = try Sandbox()
        try FileManager.default.removeItem(at: box.repo.appending(path: "project.yml"))
        let result = try box.renew(["--from-tree"])
        #expect(result.status == RenewScript.noProject)
    }

    @Test(.enabled(if: xcodeInstalled)) func usageErrors() throws {
        let box = try Sandbox()
        #expect(try box.renew(["--nope"]).outcome == .usage)
        #expect(try box.renew(["--device"]).status == RenewScript.usage)
        #expect(try box.renew(["--device", "--from-tree"]).status == RenewScript.usage)
        #expect(try box.renew(["--device", "a b"]).status == RenewScript.usage)
        #expect(try box.renew(["--time-limit", "soon"]).status == RenewScript.usage)
    }

    @Test(.enabled(if: xcodeInstalled)) func noXcode() throws {
        let box = try Sandbox()
        let result = try box.renew(["--from-tree"], env: ["DEVELOPER_DIR": box.base.path(percentEncoded: false)])
        #expect(result.status == RenewScript.noXcode)
        #expect(result.outcome == .noXcode)
    }

    @Test(.enabled(if: xcodeInstalled)) func iPhoneNotReachable() throws {
        let box = try Sandbox()
        // Paired, but the Mac can't reach it: no transport, tunnel down.
        try box.write("devices.json", "{\"result\":{\"devices\":[\(Sandbox.device(id: box.iPhone, transport: "gone", tunnel: "disconnected"))]}}", in: box.base)
        // devicectl names a transport only while reachable; "gone" stands for "not wired, not on the network".
        var result = try box.renew(["--from-tree"])
        #expect(result.status == RenewScript.noIPhone, "\(result.output)")
        #expect(result.outcome == .noIPhone)

        try box.setDevices([])
        result = try box.renew(["--from-tree"])
        #expect(result.status == RenewScript.noIPhone)
        #expect(box.calls().allSatisfy { !$0.hasPrefix("xcodebuild") }, "no build before the iPhone is found")

        try box.write("devices.json", "not json at all", in: box.base)
        #expect(try box.renew(["--from-tree"]).status == RenewScript.noIPhone)
    }

    @Test(.enabled(if: xcodeInstalled)) func severalIPhonesNeedTheOneLastInstalledOn() throws {
        let box = try Sandbox()
        let other = "CCCC-2222-DDDD"
        try box.setDevices([Sandbox.device(id: box.iPhone), Sandbox.device(id: other, name: "Other")])
        let result = try box.renew(["--from-tree"])
        #expect(result.status == RenewScript.noIPhone)
        #expect(result.output.contains("several iPhones"))

        // Named on the command line, it works, and is then the one used from now on, even if another is connected.
        try box.renew(["--from-tree", "--device", box.iPhone])
        #expect(box.state()?.device == box.iPhone)
        let renewal = try box.renew()
        #expect(renewal.status == 0, "\(renewal.output)")
        #expect(box.calls().last { $0.contains("install app") }?.contains("--device \(box.iPhone)") == true)
    }

    @Test(.enabled(if: xcodeInstalled)) func theRecordedIPhoneIsNeverReplacedByAnother() throws {
        let box = try Sandbox()
        try box.renew(["--from-tree"])
        let other = "CCCC-2222-DDDD"
        try box.setDevices([
            Sandbox.device(id: box.iPhone, transport: "gone", tunnel: "disconnected"), Sandbox.device(id: other),
        ])
        let result = try box.renew()
        #expect(result.status == RenewScript.noIPhone, "\(result.output)")
        #expect(box.calls().filter { $0.contains("install app") }.count == 1, "only the first install ran")
    }

    @Test(.enabled(if: xcodeInstalled)) func simulatorsAndOtherDevicesAreNotChosen() throws {
        let box = try Sandbox()
        try box.setDevices([
            Sandbox.device(id: "SIM-1", transport: "sameMachine"), Sandbox.device(id: "WATCH-1", type: "appleWatch"),
            Sandbox.device(id: box.iPhone, transport: "localNetwork", tunnel: "disconnected"),
            Sandbox.device(id: "UNPAIRED-1", pairing: "unpaired"),
        ])
        let result = try box.renew(["--from-tree"])
        #expect(result.status == 0, "\(result.output)")
        #expect(box.state()?.device == box.iPhone)
    }

    @Test(.enabled(if: xcodeInstalled)) func signingFailure() throws {
        let box = try Sandbox()
        try box.write("build-fails", "error: No Accounts: Add a new account in Accounts settings.\n", in: box.base)
        let result = try box.renew(["--from-tree"])
        #expect(result.status == RenewScript.signingFailed)
        #expect(result.outcome == .signingFailed)
        #expect(box.state() == nil)
    }

    @Test(.enabled(if: xcodeInstalled)) func buildFailure() throws {
        let box = try Sandbox()
        try box.write("build-fails", "Main.swift:1:1: error: cannot find 'x' in scope\n", in: box.base)
        let result = try box.renew(["--from-tree"])
        #expect(result.status == RenewScript.buildFailed)
        #expect(result.outcome == .buildFailed)
        #expect(result.output.contains("cannot find 'x'"))
    }

    @Test(.enabled(if: xcodeInstalled)) func installFailureWhileLockedIsTold() throws {
        let box = try Sandbox()
        try box.write("install-fails", "ERROR: The device is locked. Unlock it and try again.\n", in: box.base)
        let result = try box.renew(["--from-tree"])
        #expect(result.status == RenewScript.installFailed, "\(result.output)")
        #expect(result.outcome == .installFailed(locked: true))
        #expect(box.state() == nil)
        #expect(box.calls().filter { $0.contains("install app") }.count == 2, "tried twice")
    }

    @Test(.enabled(if: xcodeInstalled)) func installFailureOtherThanLocked() throws {
        let box = try Sandbox()
        try box.write("install-fails", "ERROR: IXRemoteErrorDomain error 6.\n", in: box.base)
        let result = try box.renew(["--from-tree"])
        #expect(result.outcome == .installFailed(locked: false))
    }

    @Test(.enabled(if: xcodeInstalled)) func installFailureBecauseTheIPhoneVanishedIsNotReachable() throws {
        let box = try Sandbox()
        try box.write("install-fails", "ERROR: The device is not connected. (CoreDeviceError error 4000)\n", in: box.base)
        let result = try box.renew(["--from-tree"])
        #expect(result.status == RenewScript.noIPhone)
    }

    @Test(.enabled(if: xcodeInstalled)) func aBuildWithoutTheAppIsNoAppOrDate() throws {
        let box = try Sandbox()
        try box.tool("xcrun", Sandbox.fakeXcrun.replacingOccurrences(of: "mkdir -p \"$app\"", with: "true"))
        let result = try box.renew(["--from-tree"])
        #expect(result.status == RenewScript.missingAppOrDate)
        #expect(result.outcome == .noAppOrDate)
    }

    @Test(.enabled(if: xcodeInstalled)) func aProfileThatCannotBeReadIsNoAppOrDate() throws {
        let box = try Sandbox()
        try box.write("profile.plist", "no property list in here", in: box.base)
        let result = try box.renew(["--from-tree"])
        #expect(result.status == RenewScript.missingAppOrDate)
        #expect(box.calls().allSatisfy { !$0.contains("install app") }, "nothing was installed")
    }

    @Test(.enabled(if: xcodeInstalled)) func theTimeLimitStopsABuild() throws {
        let box = try Sandbox()
        try box.write("build-sleeps", "", in: box.base)
        let started = Date()
        let result = try box.renew(["--from-tree", "--time-limit", "2"])
        #expect(result.status == RenewScript.timeLimit, "\(result.output)")
        #expect(result.outcome == .timedOut)
        #expect(Date().timeIntervalSince(started) < 30)
        #expect(box.state() == nil)
    }

    @Test(.enabled(if: xcodeInstalled)) func anotherRenewalRunningIsBusy() throws {
        let box = try Sandbox()
        // A live process's id in the lock: this test process itself.
        let lock = box.support.appending(path: "renew.lock")
        try FileManager.default.createDirectory(at: lock, withIntermediateDirectories: true)
        try "\(getpid())\n".write(to: lock.appending(path: "pid"), atomically: true, encoding: .utf8)
        let result = try box.renew(["--from-tree"])
        #expect(result.status == RenewScript.busy)
        #expect(result.outcome == .busy)
        #expect(FileManager.default.fileExists(atPath: lock.path), "the other run's lock is left alone")
        #expect(box.calls().isEmpty)
    }

    @Test(.enabled(if: xcodeInstalled)) func aLockLeftByADeadRunIsTakenOver() throws {
        let box = try Sandbox()
        let lock = box.support.appending(path: "renew.lock")
        try FileManager.default.createDirectory(at: lock, withIntermediateDirectories: true)
        try "999999\n".write(to: lock.appending(path: "pid"), atomically: true, encoding: .utf8)
        #expect(try box.renew(["--from-tree"]).status == 0)
        #expect(!FileManager.default.fileExists(atPath: lock.path), "the lock is released at the end")
    }

    // MARK: A --from-tree run that fails never changes what renewals build

    @Test(.enabled(if: xcodeInstalled)) func aFailedFromTreeRunPutsTheOldSnapshotBack() throws {
        let box = try Sandbox()
        try box.renew(["--from-tree"])
        let good = try #require(box.state())
        try box.write("App/Main.swift", "let version = 99 // does not build\n")
        try box.write("build-fails", "Main.swift:1:1: error: nope\n", in: box.base)
        let failed = try box.renew(["--from-tree"])
        #expect(failed.status == RenewScript.buildFailed)
        let source = try String(contentsOf: box.snapshot.appending(path: "App/Main.swift"), encoding: .utf8)
        #expect(source == "let version = 1\n", "the snapshot still holds what was installed")
        #expect(!FileManager.default.fileExists(atPath: box.support.appending(path: "snapshot.prev").path))
        #expect(box.state() == good)

        // The next plain renewal builds the old sources again.
        try FileManager.default.removeItem(at: box.base.appending(path: "build-fails"))
        let renewal = try box.renew()
        #expect(renewal.status == 0, "\(renewal.output)")
        #expect(box.state()?.snapshot == good.snapshot)
        #expect(!renewal.output.contains("differ"), "\(renewal.output)")
    }

    @Test(.enabled(if: xcodeInstalled)) func aCutOffFromTreeRunIsRepairedByTheNextRun() throws {
        let box = try Sandbox()
        try box.renew(["--from-tree"])
        // As if a run died after moving the new sources in: the old ones sit in snapshot.prev.
        let fm = FileManager.default
        try fm.createDirectory(at: box.support.appending(path: "snapshot.prev"), withIntermediateDirectories: true)
        for name in try fm.contentsOfDirectory(atPath: box.snapshot.path(percentEncoded: false)) where name != "build" {
            try fm.copyItem(at: box.snapshot.appending(path: name), to: box.support.appending(path: "snapshot.prev/\(name)"))
        }
        try box.write("App/Main.swift", "let version = 7 // new, never installed\n", in: box.snapshot)
        let result = try box.renew()
        #expect(result.status == 0, "\(result.output)")
        #expect(result.output.contains("putting its previous snapshot back"))
        let source = try String(contentsOf: box.snapshot.appending(path: "App/Main.swift"), encoding: .utf8)
        #expect(source == "let version = 1\n")
        #expect(!result.output.contains("differ"), "\(result.output)")
    }

    // MARK: The script's header and RenewerCore list the same exit codes

    @Test func theHeaderListsExactlyTheCodesRenewerCoreReads() throws {
        let text = try String(contentsOf: Self.realScript, encoding: .utf8)
        var documented: Set<Int32> = []
        var inList = false
        for line in text.split(separator: "\n") {
            if line.hasPrefix("# Exit codes") { inList = true; continue }
            guard inList else { continue }
            guard line.hasPrefix("#   "), let code = Int32(line.dropFirst(4).prefix { $0.isNumber }) else { break }
            documented.insert(code)
        }
        let read: Set<Int32> = [
            RenewScript.done, RenewScript.missingAppOrDate, RenewScript.usage, RenewScript.noProject,
            RenewScript.noIPhone, RenewScript.noXcode, RenewScript.snapshotFailed, RenewScript.signingFailed,
            RenewScript.busy, RenewScript.buildFailed, RenewScript.installFailed, RenewScript.timeLimit,
        ]
        #expect(documented == read)
        // And the script itself exits with no other number.
        let pattern = try NSRegularExpression(pattern: #"\bdie (\d+) "#)
        let whole = NSRange(text.startIndex..., in: text)
        let exits = pattern.matches(in: text, range: whole).compactMap { match in
            Range(match.range(at: 1), in: text).flatMap { Int32(text[$0]) }
        }
        #expect(!exits.isEmpty)
        for code in exits { #expect(read.contains(code), "die \(code)") }
    }

    @Test func noGitAndNoHoldInTheScript() throws {
        let text = try String(contentsOf: Self.realScript, encoding: .utf8)
        for word in ["git -C", "git worktree", "rev-parse", "HOLD", "hold-status", "Pebble", "security cms", "/usr/bin/security", "worktree"] {
            #expect(!text.contains(word), "\(word)")
        }
    }
}
