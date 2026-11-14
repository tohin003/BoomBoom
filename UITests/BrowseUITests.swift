import XCTest

/// Flows 6 and 7: Explore and its pages; Library, sign-in and Settings while signed out.
final class BrowseUITests: BoomUITestCase {

    /// Flow 6: the tiles open Charts, New releases and Moods & genres; a mood opens shelves; the tab pops to root.
    func testExplore() {
        launch()
        expect(any("shelf.header").firstMatch, "Home", timeout: network)
        tab("Explore").tap()
        expect(app.buttons["Charts"], "Explore's tiles")
        XCTAssertTrue(tab("Explore").isSelected, "the Explore tab is selected")
        checkpoint("explore")

        app.buttons["Charts"].tap()
        expect(any("shelf.header").firstMatch, "the charts' shelves", timeout: network)
        checkpoint("charts")
        goBack()
        expect(app.buttons["New releases"], "Explore after Back").tap()
        expect(any("shelf.header").firstMatch, "new releases' shelves", timeout: network)
        XCTAssertGreaterThan(any("card.album").count + any("row.album").count + any("card.song").count
                             + any("row.song").count + any("card.video").count, 0, "new releases has items")
        checkpoint("new releases")
        goBack()
        expect(app.buttons["Moods & genres"], "Explore after Back").tap()
        let mood = firstHittable(any("mood.tile"), timeout: network)
        checkpoint("moods")
        let moodName = mood.label
        mood.tap()
        expect(any("shelf.header").firstMatch, "the shelves of \(moodName)", timeout: network)
        XCTAssertFalse(app.buttons["Charts"].exists, "a mood page, not Explore")
        checkpoint("mood \(moodName)")

        // The selected tab again: back to Explore's root.
        tab("Explore").tap()
        expect(app.buttons["Charts"], "Explore's root after tapping its tab", timeout: 5)
        XCTAssertFalse(any("mood.tile").firstMatch.exists, "the pushed pages are gone")
        checkpoint("explore root")
    }

    /// Flow 7a: Library asks to sign in; the sheet opens on Google's page and Cancel closes it; Home's account
    /// menu opens it too. Nothing is typed into the page.
    func testLibrarySignedOut() {
        launch()
        expect(any("shelf.header").firstMatch, "Home", timeout: network)
        tab("Library").tap()
        let signIn = firstHittable(app.buttons.matching(NSPredicate(format: "label == 'Sign in'")), timeout: 10)
        checkpoint("library signed out")
        // The prompt's own button (not the bar's account button): the lower one.
        let prompt = app.buttons.matching(NSPredicate(format: "label == 'Sign in'")).allElementsBoundByIndex
            .filter { isOnScreen($0) }.max { $0.frame.minY < $1.frame.minY } ?? signIn
        prompt.tap()
        expectSignInSheet()
        app.buttons["Cancel"].tap()
        expectGone(app.navigationBars["Sign in with Google"], "the sign-in sheet after Cancel")
        checkpoint("sheet closed")

        tab("Home").tap()
        expect(any("shelf.header").firstMatch, "Home", timeout: network)
        // Home's account button is a menu; its "Sign in" is the only one on screen once the menu is open.
        firstHittable(any("topbar.account")).tap()
        firstHittable(app.buttons.matching(NSPredicate(format: "label == 'Sign in'"))).tap()
        expectSignInSheet()
        app.buttons["Cancel"].tap()
        expectGone(app.navigationBars["Sign in with Google"], "the sign-in sheet after Cancel")
    }

    /// Flow 7b: Settings while signed out: autoplay, the sound sheet, storage numbers, the clear confirmation.
    func testSettingsSignedOut() {
        // A song plays first, so a song gets kept and "Empty the cache" has something to clear.
        launch(["-BoomStart", "settings", "-BoomPlay", "dQw4w9WgXcQ"])
        expect(app.navigationBars["Settings"], "Settings", timeout: 10)
        checkpoint("settings")

        let autoplay = app.switches.matching(NSPredicate(format: "label BEGINSWITH 'Autoplay'")).firstMatch
        expect(autoplay, "the autoplay switch")
        let wasOn = isOn(autoplay)
        flip(autoplay, "autoplay toggles")
        flip(autoplay, "autoplay toggles back")
        XCTAssertEqual(isOn(autoplay), wasOn, "autoplay is back where it was")

        app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Equaliser and amplifier'")).firstMatch.tap()
        expect(app.buttons["Done"], "the sound sheet")
        expect(app.staticTexts["Sound"], "the sound sheet's title")
        checkpoint("sound sheet")
        app.buttons["Done"].tap()
        expectGone(app.buttons["Done"], "the sound sheet after Done")

        // Storage: numbers, and the clear confirmation (cancelled).
        for _ in 0..<2 { scrollDown() }
        let kept = expect(label(beginningWith: "Songs in the cache").firstMatch, "the kept-songs row")
        let used = expect(label(beginningWith: "Space used").firstMatch, "the space-used row")
        XCTAssertNotNil(kept.label.rangeOfCharacter(from: .decimalDigits), "kept songs shows a number (\(kept.label))")
        XCTAssertNotNil(used.label.rangeOfCharacter(from: .decimalDigits), "space used shows a number (\(used.label))")
        checkpoint("storage")

        let clear = app.buttons["Empty the cache"].firstMatch
        eventually("a song is kept, so Clear is enabled", timeout: 90) {
            if !clear.isEnabled { scrollDown() }
            return clear.isEnabled
        }
        checkpoint("storage with a kept song")
        clear.tap()
        expect(app.staticTexts["Empty the song cache?"], "the confirmation", timeout: 5)
        checkpoint("confirmation")
        cancelConfirmation(of: app.staticTexts["Empty the song cache?"])
        expectGone(app.staticTexts["Empty the song cache?"], "the confirmation after Cancel")
        XCTAssertTrue(clear.isEnabled, "nothing was cleared")
        checkpoint("cancelled")
    }

    private func expectSignInSheet() {
        expect(app.navigationBars["Sign in with Google"], "the sign-in sheet")
        expect(app.webViews.firstMatch, "the sign-in page", timeout: network)
        pause(2)
        checkpoint("sign-in sheet")
    }

    private func isOn(_ toggle: XCUIElement) -> Bool {
        (toggle.value as? String) == "1"
    }

    /// Flips a switch by tapping the switch itself (on the right), not its label, and waits until it shows.
    ///
    /// While a song is loading or playing, the simulator ignores many synthetic taps on a switch (measured: 4 of 14
    /// and 14 of 20; an idle app took 10 of 10), so a tap that changed nothing is made again. Whether a finger on a
    /// phone ever sees this is not known from here.
    private func flip(_ toggle: XCUIElement, _ what: String, file: StaticString = #filePath, line: UInt = #line) {
        let before = isOn(toggle)
        for _ in 0..<6 {
            toggle.coordinate(withNormalizedOffset: CGVector(dx: 0.93, dy: 0.5)).tap()
            let deadline = Date.now.addingTimeInterval(2.5)
            repeat {
                if isOn(toggle) != before { return }
                RunLoop.current.run(until: .now.addingTimeInterval(0.25))
            } while Date.now < deadline
        }
        failureShot("never: \(what)")
        XCTFail("\(what): six taps on the switch changed nothing", file: file, line: line)
    }

    /// A confirmation's Cancel: a button when there is one, else a tap outside it (on this iPhone the question is a
    /// small bubble by its button, with no Cancel). As with `flip`, a tap the simulator ignored is made again.
    private func cancelConfirmation(of question: XCUIElement) {
        for _ in 0..<4 where question.exists {
            let cancel = app.buttons["Cancel"]
            if cancel.exists, cancel.isHittable {
                cancel.tap()
            } else {
                app.windows.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.25, dy: 0.12)).tap()
            }
            if question.waitForNonExistence(timeout: 2.5) { return }
        }
    }
}
