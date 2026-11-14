import XCTest

/// Home, the mini player, the full player, and state kept across tabs.
final class HomeAndPlayerUITests: BoomUITestCase {

    /// Flow 1: shelves appear, more load while scrolling, a filter chip applies and clears.
    func testHomeLoadsAndScrolls() {
        launch()
        expect(any("shelf.header").firstMatch, "a shelf header on Home", timeout: network)
        checkpoint("home loaded")

        var headers = Set(any("shelf.header").allElementsBoundByIndex.map(\.label))
        let first = headers.count
        for _ in 0..<8 {
            scrollDown()
            headers.formUnion(any("shelf.header").allElementsBoundByIndex.map(\.label))
        }
        checkpoint("home scrolled")
        XCTAssertGreaterThan(headers.count, first + 2, "scrolling Home shows more shelves (saw \(headers.sorted()))")

        // Back to the top with a tap on the selected tab, then a chip.
        tab("Home").tap()
        pause(1.0)
        let chip = firstHittable(any("chip"))
        let chipTitle = chip.label
        chip.tap()
        eventually("the chip \(chipTitle) is selected") { any("chip")[chipTitle].isSelected }
        expect(any("shelf.header").firstMatch, "shelves for \(chipTitle)", timeout: network)
        checkpoint("chip \(chipTitle) on")

        any("chip")[chipTitle].tap()
        eventually("the chip \(chipTitle) clears") { !any("chip")[chipTitle].isSelected }
        expect(any("shelf.header").firstMatch, "Home's shelves again", timeout: network)
        checkpoint("chip cleared")
    }

    /// Flow 2: a song row plays in the mini player; pause, play, next.
    func testPlayFromHome() {
        launch()
        let row = firstHittable(songRows, timeout: network)
        row.tap()
        expect(miniPlayer, "the mini player above the tab bar", timeout: 10)
        XCTAssertLessThan(miniPlayer.frame.maxY, tab("Home").frame.minY + 2, "the mini player sits above the tab bar")
        checkpoint("mini player up")
        waitUntilPlaying()
        checkpoint("playing")

        miniPlayer.buttons["Pause"].tap()
        eventually("the mini player offers Play after Pause", timeout: 5) { miniPlayer.buttons["Play"].exists }
        checkpoint("paused")
        miniPlayer.buttons["Play"].tap()
        eventually("the mini player plays again", timeout: audio) { isPlaying }

        let before = nowPlayingLabel
        let next = miniPlayer.buttons["Next"]
        eventually("Next is enabled (the radio queue arrived)", timeout: network) { next.isEnabled }
        next.tap()
        eventually("the song changes after Next (was \(before))", timeout: 10) { nowPlayingLabel != before }
        checkpoint("next song")
        waitUntilPlaying()
    }

    /// Flow 3: the full player's controls and gestures.
    func testFullPlayer() {
        launch()
        firstHittable(songRows, timeout: network).tap()
        waitUntilPlaying()
        openFullPlayer()
        for tab in ["UP NEXT", "LYRICS", "RELATED"] {
            XCTAssertTrue(fullPlayer.buttons[tab].firstMatch.exists, "the full player has \(tab)")
        }
        checkpoint("full player")

        // Time runs.
        eventually("the length is known", timeout: audio) { length > 0 }
        let t0 = elapsed ?? 0
        pause(3)
        let t1 = elapsed ?? 0
        XCTAssertGreaterThan(t1, t0, "the elapsed time advances while playing (\(t0) → \(t1))")

        // Scrub to the middle.
        let scrubber = fullPlayer.descendants(matching: .any)["Position"]
        let value = scrubber.value as? String ?? ""
        let length = self.length
        XCTAssertGreaterThan(length, 30, "the song has a length (\(value))")
        let track = scrubber.coordinate(withNormalizedOffset: CGVector(dx: 0.1, dy: 0.5))
        let middle = scrubber.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        track.press(forDuration: 0.2, thenDragTo: middle, withVelocity: .slow, thenHoldForDuration: 0.2)
        checkpoint("scrubbed")
        let target = length * 0.5
        eventually("the elapsed time jumps to about the middle (\(elapsed ?? -1) of \(length))", timeout: 5) {
            abs((elapsed ?? 0) - target) < max(10, length * 0.08)
        }
        // It must not flick back.
        for _ in 0..<6 {
            pause(0.5)
            let now = elapsed ?? 0
            XCTAssertGreaterThan(now, target - max(10, length * 0.08), "the time stayed near the middle (now \(now))")
        }

        // Like. Signed out, a rating needs an account: the sign-in sheet opens and the thumb stays as it was.
        let like = fullPlayer.buttons["Like"]
        let wasLiked = like.isSelected
        like.tap()
        expect(app.navigationBars["Sign in with Google"], "the sign-in sheet after Like while signed out", timeout: 10)
        checkpoint("like asks to sign in")
        app.buttons["Cancel"].tap()
        expectGone(app.navigationBars["Sign in with Google"], "the sign-in sheet after Cancel")
        XCTAssertEqual(fullPlayer.buttons["Like"].isSelected, wasLiked, "the thumb did not change while signed out")

        // Shuffle and repeat.
        fullPlayer.buttons["Shuffle off"].tap()
        expect(fullPlayer.buttons["Shuffle on"], "Shuffle on", timeout: 5)
        fullPlayer.buttons["Shuffle on"].tap()
        expect(fullPlayer.buttons["Shuffle off"], "Shuffle off", timeout: 5)
        fullPlayer.buttons["Repeat off"].tap()
        expect(fullPlayer.buttons["Repeat all"], "Repeat all", timeout: 5)
        fullPlayer.buttons["Repeat all"].tap()
        expect(fullPlayer.buttons["Repeat one"], "Repeat one", timeout: 5)
        checkpoint("repeat one")
        fullPlayer.buttons["Repeat one"].tap()
        expect(fullPlayer.buttons["Repeat off"], "Repeat off", timeout: 5)

        // Up next: tap another row.
        fullPlayer.buttons["UP NEXT"].firstMatch.tap()
        let rows = any("queue.row")
        eventually("the queue has rows", timeout: 10) { rows.count > 1 }
        checkpoint("up next")
        let before = nowPlayingLabel
        let other = rows.allElementsBoundByIndex.first { !$0.isSelected && isOnScreen($0) && $0.frame.minY > 200 }
        XCTAssertNotNil(other, "another queue row is on screen")
        other?.tap()
        eventually("the song changes after tapping a queue row", timeout: 10) { nowPlayingLabel != before }
        checkpoint("queue row played")

        // Close the panel by dragging its header (the selected tab) down.
        let grabber = fullPlayer.buttons.matching(NSPredicate(format: "label == 'UP NEXT' AND selected == true")).firstMatch
        expect(grabber, "the panel's selected UP NEXT tab")
        grabber.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
            .press(forDuration: 0.1, thenDragTo: grabber.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 10)),
                   withVelocity: .default, thenHoldForDuration: 0.05)
        eventually("the panel closes", timeout: 5) { !rows.firstMatch.exists || !rows.firstMatch.isHittable }
        checkpoint("panel closed")

        // Drag the player down: the mini player is back.
        drag(from: CGVector(dx: 0.5, dy: 0.25), to: CGVector(dx: 0.5, dy: 0.9), velocity: .fast, hold: 0)
        expectGone(app.buttons["Close player"], "the full player after dragging it down")
        XCTAssertTrue(miniSong.isHittable, "the mini player is back")
        checkpoint("dragged down")

        // Open again, close with the chevron.
        openFullPlayer()
        app.buttons["Close player"].tap()
        expectGone(app.buttons["Close player"], "the full player after the chevron")
        checkpoint("chevron closed")

        // Swipe the cover left: next song.
        openFullPlayer()
        let beforeSwipe = nowPlayingLabel
        drag(from: CGVector(dx: 0.8, dy: 0.33), to: CGVector(dx: 0.1, dy: 0.33), velocity: .fast, hold: 0)
        eventually("swiping the cover left plays the next song (was \(beforeSwipe))", timeout: 10) {
            nowPlayingLabel != beforeSwipe
        }
        checkpoint("cover swiped")
    }

    /// Flow 9: the mini player stays across tabs and pages, and lists end above it.
    func testStateAcrossTabs() {
        launch()
        firstHittable(songRows, timeout: network).tap()
        waitUntilPlaying()
        let song = nowPlayingLabel

        tab("Explore").tap()
        expect(app.buttons["Charts"], "Explore's tiles")
        XCTAssertTrue(miniSong.isHittable, "the mini player stays on Explore")
        app.buttons["Charts"].tap()
        // The page is live: some days it opens with rows of songs or artists, some days with shelves of cards. Once
        // it is pushed, Explore's own tiles are gone.
        expectGone(app.buttons["Charts"], "Explore's tiles, with the Charts page pushed", timeout: network)
        expect(items.firstMatch, "the Charts page's rows or cards", timeout: network)
        checkpoint("charts with mini player")

        // Scroll to the very end (the rows on screen stop changing); the last row must clear the mini player.
        scrollToEnd()
        checkpoint("charts at the end")
        let window = app.windows.firstMatch.frame
        let onScreen = items.allElementsBoundByIndex
            .filter { $0.frame.height > 0 && $0.frame.minY < window.maxY && $0.frame.maxY > window.minY }
        let lowest = onScreen.max { $0.frame.maxY < $1.frame.maxY }
        XCTAssertFalse(onScreen.isEmpty, "rows are on screen at the end")
        if let lowest, lowest.frame.maxY > miniPlayer.frame.minY + 1 {
            failureShot("row under the bars")
            XCTFail("the last row ends above the mini player (\(lowest.identifier) '\(lowest.label)' ends \(lowest.frame.maxY), bar starts \(miniPlayer.frame.minY))")
        }

        tab("Library").tap()
        pause(1)
        XCTAssertTrue(miniSong.isHittable, "the mini player stays on Library")
        tab("Home").tap()
        pause(1)
        XCTAssertTrue(miniSong.isHittable, "the mini player stays on Home")
        XCTAssertEqual(nowPlayingLabel.isEmpty, false)
        _ = song
        tab("Explore").tap()
        expect(items.firstMatch, "the Charts page's rows or cards, back on Explore", timeout: 5)
        XCTAssertFalse(app.buttons["Charts"].exists, "Explore kept the Charts page, not its root")
        checkpoint("back on explore")
    }

    /// Rows and cards of the page on screen.
    private var items: XCUIElementQuery {
        app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier BEGINSWITH 'row.' OR identifier BEGINSWITH 'card.'"))
    }

    /// Scrolls down until two scrolls in a row leave the same rows in the same places.
    private func scrollToEnd() {
        var last = ""
        var still = 0
        for _ in 0..<60 {
            scrollDown()
            let window = app.windows.firstMatch.frame
            let now = items.allElementsBoundByIndex
                .filter { $0.frame.minY < window.maxY && $0.frame.maxY > window.minY }
                .map { "\($0.label)@\(Int($0.frame.minY))" }.joined(separator: "|")
            still = now == last ? still + 1 : 0
            if still >= 2 { return }
            last = now
        }
    }
}
