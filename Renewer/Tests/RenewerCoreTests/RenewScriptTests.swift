import Foundation
import Testing
@testable import RenewerCore

struct RenewScriptTests {
    func outcome(_ status: Int32, _ lines: [String], timedOut: Bool = false, couldNotStart: Bool = false)
        -> RenewOutcome
    {
        RenewScript.outcome(
            status: status, output: RenewOutput(lines: lines), timedOut: timedOut, couldNotStart: couldNotStart,
            timeZone: Fixture.zone)
    }

    let building = "renew.sh: building for iOS (this renews the signing profile)..."
    let installing = "renew.sh: installing on 1A2B3C4D-1111-2222-3333-444455556666..."

    @Test func successCarriesTheNewEndDate() {
        let lines = [
            "renew.sh: copying the working tree to the snapshot (563c2b9e0f1d)...",
            building,
            "renew.sh: built.",
            installing,
            "renew.sh: installed. This build expires Fri 06 Nov 2026, 02:07 (in 5.7 days). Run scripts/renew.sh again "
                + "before then.",
            "renew.sh: recorded this install for the menu-bar renewer in /state.json.",
        ]
        #expect(outcome(0, lines) == .renewed(expires: Fixture.date("2026-11-05T20:37:00Z")))
        #expect(outcome(0, lines).succeeded)
        #expect(outcome(0, ["renew.sh: installed."]) == .renewed(expires: nil))
    }

    @Test func everyExitCodeInPlainTerms() {
        #expect(outcome(75, ["renew.sh: another renewal is running (pid 4242)."]) == .busy)
        #expect(outcome(71, ["renew.sh: Xcode is not installed or not selected."]) == .noXcode)
        #expect(outcome(69, ["renew.sh: no paired iPhone found."]) == .noIPhone)
        #expect(outcome(66, ["renew.sh: no snapshot yet."]) == .noProject)
        #expect(outcome(73, ["renew.sh: could not copy the working tree."]) == .snapshotFailed)
        #expect(outcome(74, [building, "renew.sh: Xcode could not sign the app."]) == .signingFailed)
        #expect(outcome(76, [building, "renew.sh: the build failed."]) == .buildFailed)
        #expect(outcome(78, [building]) == .timedOut)
        #expect(outcome(64, ["renew.sh: unknown option: --nope (see --help)"]) == .usage)
        #expect(outcome(1, [building, "renew.sh: the build finished, but no BoomBoom.app was found."]) == .noAppOrDate)
        #expect(outcome(2, []) == .failed(code: 2))
        #expect(outcome(0, [], timedOut: true) == .timedOut)
        #expect(outcome(-1, [], couldNotStart: true) == .couldNotStart)
    }

    @Test func installFailuresSayWhetherTheIPhoneWasLocked() {
        let retry = "renew.sh: the install failed; trying once more in 10 seconds."
        #expect(outcome(77, [building, installing, "ERROR: The device is locked.", retry])
            == .installFailed(locked: true))
        #expect(outcome(77, [building, installing, "ERROR: IXRemoteErrorDomain error 6.", retry])
            == .installFailed(locked: false))
    }

    @Test func onlyTheEndOfLongOutputIsKept() {
        var output = RenewOutput()
        for index in 0..<500 { output.add("SwiftCompile normal arm64 File\(index).swift") }
        for index in 0..<100 { output.add("File.swift:\(index): error: something") }
        output.add(building)
        output.add("   ")
        #expect(output.tail.count == RenewOutput.tailLimit)
        #expect(output.errorLines.count == RenewOutput.errorLimit)
        #expect(output.scriptLines == [building])
        #expect(output.tail.last == "File.swift:99: error: something")
    }

    @Test func worthANotice() {
        #expect(!RenewOutcome.busy.worthANotice)
        #expect(!RenewOutcome.renewed(expires: nil).worthANotice)
        #expect(RenewOutcome.signingFailed.worthANotice)
        #expect(RenewOutcome.noIPhone.worthANotice)
    }

    /// The codes in the script's header (scripts/renew.sh) and the ones read here must stay the same list.
    @Test func exitCodesMatchTheScriptHeader() throws {
        let script = URL(filePath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent().appending(path: "scripts/renew.sh")
        guard let text = try? String(contentsOf: script, encoding: .utf8) else { return }
        let codes: [(Int32, String)] = [
            (RenewScript.done, "done"), (RenewScript.missingAppOrDate, "no app"), (RenewScript.usage, "usage"),
            (RenewScript.noProject, "no project"), (RenewScript.noIPhone, "iPhone not reachable"),
            (RenewScript.noXcode, "Xcode not installed"), (RenewScript.snapshotFailed, "snapshot"),
            (RenewScript.signingFailed, "signing failed"), (RenewScript.busy, "busy"),
            (RenewScript.buildFailed, "build failed"), (RenewScript.installFailed, "install failed"),
            (RenewScript.timeLimit, "time limit"),
        ]
        for (code, words) in codes {
            #expect(text.contains("#   \(code) "), "exit code \(code) (\(words)) in renew.sh's header")
        }
    }
}
