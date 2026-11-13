import XCTest

/// The accounts, signed out as the simulator is: Settings offers the sign-in, Home's account button opens Google's
/// sheet, and giving up leaves no empty account behind, now and after a relaunch. Nothing is typed into Google's
/// page: a real sign-in cannot be automated, and is checked on the phone.
@MainActor
final class AccountsUITests: BoomUITestCase {
    /// Settings signed out: the one "Sign in" row, under "Account", and nothing about other accounts.
    func testSettingsOffersSignIn() {
        launch(["-BoomStart", "settings"])
        expect(app.navigationBars["Settings"], "Settings", timeout: 10)
        expectSignedOutLook("Settings at launch")
        checkpoint("settings signed out")

        // Its sign-in row opens Google's page; Cancel closes it and Settings looks as before.
        any("settings.signIn").firstMatch.tap()
        expectSignInSheet()
        app.buttons["Cancel"].tap()
        expectGone(app.navigationBars["Sign in with Google"], "the sign-in sheet after Cancel")
        expectSignedOutLook("Settings after Cancel")
        checkpoint("settings after cancel")
    }

    /// Home's account button opens the sign-in sheet; Cancel leaves no account (or empty profile) behind, in this
    /// run and after a relaunch.
    func testHomeAccountButtonOpensSignInAndCancelLeavesNothing() {
        launch()
        expect(any("shelf.header").firstMatch, "Home", timeout: network)
        let button = expect(any("topbar.signIn").firstMatch, "Home's sign-in button", timeout: 10)
        XCTAssertEqual(button.label, "Sign in")
        XCTAssertFalse(any("topbar.account").firstMatch.exists, "no account menu while signed out")
        button.tap()
        expectSignInSheet()
        app.buttons["Cancel"].tap()
        expectGone(app.navigationBars["Sign in with Google"], "the sign-in sheet after Cancel")
        // Still signed out: the plain sign-in button, not the account menu.
        expect(any("topbar.signIn").firstMatch, "Home's sign-in button after Cancel", timeout: 10)
        XCTAssertFalse(any("topbar.account").firstMatch.exists, "no account menu after Cancel")

        // Swiping the sheet away is giving up too.
        any("topbar.signIn").firstMatch.tap()
        expectSignInSheet()
        app.navigationBars["Sign in with Google"].swipeDown(velocity: .fast)
        if app.navigationBars["Sign in with Google"].exists { app.buttons["Cancel"].tap() }
        expectGone(app.navigationBars["Sign in with Google"], "the sign-in sheet after a swipe down")
        checkpoint("home after giving up")

        // A relaunch, straight onto Settings: the same signed-out look.
        app.terminate()
        launch(["-BoomStart", "settings"])
        expect(app.navigationBars["Settings"], "Settings after a relaunch", timeout: 10)
        expectSignedOutLook("Settings after a relaunch")
        checkpoint("settings after relaunch")
    }

    // MARK: Pieces

    /// The signed-out look: the "Account" header with the sign-in row, no account rows, nothing to add.
    private func expectSignedOutLook(_ moment: String, file: StaticString = #filePath, line: UInt = #line) {
        expect(any("settings.signIn").firstMatch, "the sign-in row (\(moment))", file: file, line: line)
        XCTAssertTrue(app.staticTexts["Account"].exists, "the section is called Account (\(moment))", file: file, line: line)
        XCTAssertFalse(app.staticTexts["Accounts"].exists, "no Accounts list (\(moment))", file: file, line: line)
        XCTAssertFalse(any("settings.account").firstMatch.exists, "no account rows (\(moment))", file: file, line: line)
        XCTAssertFalse(any("settings.addAccount").firstMatch.exists, "no Add another account (\(moment))",
                       file: file, line: line)
    }

    private func expectSignInSheet() {
        expect(app.navigationBars["Sign in with Google"], "the sign-in sheet")
        expect(app.webViews.firstMatch, "the sign-in page", timeout: network)
        pause(2)
        checkpoint("sign-in sheet")
    }
}
