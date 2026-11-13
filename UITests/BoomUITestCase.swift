import XCTest

/// The base of every BoomBoom UI test: launches the real app (always silent), finds things by kind rather than by
/// title (the data is live), and keeps a screenshot at each checkpoint.
@MainActor
class BoomUITestCase: XCTestCase {
    var app: XCUIApplication!

    /// Network answers take 1-3 s; first audio 1-8 s. Waits are generous so a slow moment is not a failure.
    let network: TimeInterval = 30
    let audio: TimeInterval = 60

    override func setUp() async throws {
        continueAfterFailure = false
    }

    override func tearDown() async throws {
        if let app, app.state != .notRunning {
            checkpoint("end")
            app.terminate()
        }
    }

    /// Launches the app. `-BoomSilent 1` mutes the whole audio chain; it only takes effect together with
    /// `-BoomTrace 1` (or `-BoomPlay`), so both are always passed.
    func launch(_ extra: [String] = []) {
        app = XCUIApplication()
        app.launchArguments = ["-BoomSilent", "1", "-BoomTrace", "1"] + extra
        app.launch()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 30), "the app did not come up")
    }

    // MARK: Screenshots

    private var shots = 0

    func checkpoint(_ name: String) {
        shots += 1
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = String(format: "%02d %@", shots, name)
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    /// A screenshot and the accessibility tree, for a failure: what was on screen and what a finger could find.
    func failureShot(_ name: String) {
        checkpoint(name)
        guard let app else { return }
        let tree = XCTAttachment(string: app.debugDescription)
        tree.name = String(format: "%02d %@ (tree)", shots, name)
        tree.lifetime = .keepAlways
        add(tree)
    }

    // MARK: Finding things

    func any(_ identifier: String) -> XCUIElementQuery {
        app.descendants(matching: .any).matching(identifier: identifier)
    }

    var songRows: XCUIElementQuery { any("row.song") }
    var miniPlayer: XCUIElement { any("miniplayer").firstMatch }
    var miniSong: XCUIElement { any("miniplayer.song").firstMatch }
    var fullPlayer: XCUIElement { any("player").firstMatch }

    func label(beginningWith prefix: String, in query: XCUIElementQuery? = nil) -> XCUIElementQuery {
        (query ?? app.descendants(matching: .any)).matching(NSPredicate(format: "label BEGINSWITH %@", prefix))
    }

    func label(containing text: String, in query: XCUIElementQuery? = nil) -> XCUIElementQuery {
        (query ?? app.descendants(matching: .any)).matching(NSPredicate(format: "label CONTAINS %@", text))
    }

    /// The first element of the query that is on screen and can be touched, waiting for one to appear.
    func firstHittable(_ query: XCUIElementQuery, timeout: TimeInterval = 20, file: StaticString = #filePath,
                       line: UInt = #line) -> XCUIElement {
        let deadline = Date.now.addingTimeInterval(timeout)
        repeat {
            if let found = query.allElementsBoundByIndex.first(where: { isOnScreen($0) }) { return found }
            RunLoop.current.run(until: .now.addingTimeInterval(0.4))
        } while Date.now < deadline
        failureShot("nothing on screen")
        XCTFail("nothing on screen matches \(query)", file: file, line: line)
        return query.firstMatch
    }

    func isOnScreen(_ element: XCUIElement) -> Bool {
        guard element.exists, element.isHittable else { return false }
        let frame = element.frame
        let window = app.windows.firstMatch.frame
        return !frame.isEmpty && window.contains(CGPoint(x: frame.midX, y: frame.midY))
    }

    @discardableResult
    func expect(_ element: XCUIElement, _ what: String, timeout: TimeInterval = 20, file: StaticString = #filePath,
                line: UInt = #line) -> XCUIElement {
        if !element.waitForExistence(timeout: timeout) {
            failureShot("missing: \(what)")
            XCTFail("\(what) did not appear within \(Int(timeout)) s", file: file, line: line)
        }
        return element
    }

    func expectGone(_ element: XCUIElement, _ what: String, timeout: TimeInterval = 10, file: StaticString = #filePath,
                    line: UInt = #line) {
        if !element.waitForNonExistence(timeout: timeout) {
            failureShot("still there: \(what)")
            XCTFail("\(what) is still there after \(Int(timeout)) s", file: file, line: line)
        }
    }

    /// Polls `condition` until it holds; fails with `what` otherwise.
    @discardableResult
    func eventually(_ what: String, timeout: TimeInterval = 20, file: StaticString = #filePath, line: UInt = #line,
                    _ condition: () -> Bool) -> Bool {
        let deadline = Date.now.addingTimeInterval(timeout)
        repeat {
            if condition() { return true }
            RunLoop.current.run(until: .now.addingTimeInterval(0.4))
        } while Date.now < deadline
        failureShot("never: \(what)")
        XCTFail("\(what) (waited \(Int(timeout)) s)", file: file, line: line)
        return false
    }

    func pause(_ seconds: TimeInterval) {
        RunLoop.current.run(until: .now.addingTimeInterval(seconds))
    }

    // MARK: The player

    /// The mini player's play/pause button.
    var miniPlayPause: XCUIElement {
        let buttons = miniPlayer.buttons
        return buttons["Pause"].exists ? buttons["Pause"] : buttons["Play"]
    }

    /// Waits until the mini player is up and really playing: its button offers "Pause" without the "Loading" spinner.
    /// (While a song is on its way the button already offers Pause, so the label alone is not enough.)
    func waitUntilPlaying(timeout: TimeInterval? = nil, file: StaticString = #filePath, line: UInt = #line) {
        expect(miniPlayer, "the mini player", timeout: network, file: file, line: line)
        eventually("the mini player is playing (Pause, not loading)", timeout: timeout ?? audio, file: file, line: line) {
            isPlaying
        }
    }

    /// The mini player offers Pause and is not loading.
    var isPlaying: Bool {
        let pause = miniPlayer.buttons["Pause"]
        return pause.exists && (pause.value as? String ?? "") != "Loading"
    }

    /// The mini player's song line ("Title, Artist").
    var nowPlayingLabel: String { miniSong.exists ? miniSong.label : "" }

    func openFullPlayer(file: StaticString = #filePath, line: UInt = #line) {
        expect(miniSong, "the mini player", file: file, line: line).tap()
        expect(app.buttons["Close player"], "the full player", file: file, line: line)
        pause(0.8)
    }

    /// The scrubber's elapsed time in seconds, from its accessibility value "1:23 of 3:45".
    var elapsed: Double? {
        let scrubber = fullPlayer.descendants(matching: .any)["Position"]
        guard scrubber.exists, let value = scrubber.value as? String else { return nil }
        return Self.seconds(value.components(separatedBy: " of ").first ?? "")
    }

    /// The song's length in seconds from the scrubber, 0 while unknown.
    var length: Double {
        let scrubber = fullPlayer.descendants(matching: .any)["Position"]
        guard scrubber.exists, let value = scrubber.value as? String else { return 0 }
        return Self.seconds(value.components(separatedBy: " of ").last ?? "") ?? 0
    }

    static func seconds(_ text: String) -> Double? {
        let parts = text.trimmingCharacters(in: .whitespaces).split(separator: ":").compactMap { Double($0) }
        guard !parts.isEmpty else { return nil }
        return parts.reduce(0) { $0 * 60 + $1 }
    }

    // MARK: Moving around

    func tab(_ name: String) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label == %@", name)).allElementsBoundByIndex
            .last { $0.frame.minY > app.windows.firstMatch.frame.maxY - 140 } ?? app.buttons[name]
    }

    /// The navigation bar's back button, or an edge swipe when the page hides its bar.
    func goBack() {
        let back = app.navigationBars.buttons.element(boundBy: 0)
        if back.exists, back.isHittable {
            back.tap()
        } else if app.buttons["Back"].exists, app.buttons["Back"].isHittable {
            app.buttons["Back"].tap()
        } else {
            let window = app.windows.firstMatch
            window.coordinate(withNormalizedOffset: CGVector(dx: 0.01, dy: 0.5))
                .press(forDuration: 0.05, thenDragTo: window.coordinate(withNormalizedOffset: CGVector(dx: 0.8, dy: 0.5)))
        }
        pause(0.8)
    }

    /// A finger's drag from one point of the window to another, as fractions of its size.
    func drag(from: CGVector, to: CGVector, velocity: XCUIGestureVelocity = .default, hold: TimeInterval = 0.1) {
        let window = app.windows.firstMatch
        window.coordinate(withNormalizedOffset: from).press(
            forDuration: 0.1, thenDragTo: window.coordinate(withNormalizedOffset: to), withVelocity: velocity,
            thenHoldForDuration: hold)
    }

    /// Scrolls the content up by about a screen (finger moving up).
    func scrollDown() {
        drag(from: CGVector(dx: 0.5, dy: 0.72), to: CGVector(dx: 0.5, dy: 0.22), velocity: .fast, hold: 0)
        pause(0.6)
    }
}
