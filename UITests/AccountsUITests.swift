import XCTest

/// The accounts, signed out as the simulator is: Settings offers the sign-in and can be reached from every tab,
/// Home's account menu opens Google's sheet, and giving up leaves no empty account behind, now and after a relaunch.
/// Nothing is typed into Google's page: a real sign-in cannot be automated, and is checked on the phone.
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

    /// Home's account button is a menu, signed out too: "Sign in" opens the sign-in sheet, and Cancel leaves no
    /// account (or empty profile) behind, in this run and after a relaunch.
    func testHomeAccountMenuOpensSignInAndCancelLeavesNothing() {
        launch()
        expect(any("shelf.header").firstMatch, "Home", timeout: network)
        let button = expect(any("topbar.account").firstMatch, "Home's account button", timeout: 10)
        XCTAssertEqual(button.label, "Account and settings")
        button.tap()
        let signIn = menuItem("Sign in")
        // Signed out: nothing to switch to and nothing to add, only the sign-in and Settings.
        XCTAssertTrue(isOnScreen(menuItem("Settings")), "Settings in the account menu while signed out")
        XCTAssertFalse(app.buttons["Add another account"].exists, "no Add another account while signed out")
        checkpoint("account menu signed out")
        signIn.tap()
        expectSignInSheet()
        app.buttons["Cancel"].tap()
        expectGone(app.navigationBars["Sign in with Google"], "the sign-in sheet after Cancel")

        // Still signed out: the menu offers the sign-in again. Swiping the sheet away is giving up too.
        expect(any("topbar.account").firstMatch, "Home's account button after Cancel", timeout: 10).tap()
        menuItem("Sign in").tap()
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

    /// Settings can be reached without an account, from every tab: Home's and Explore's account menu, and the
    /// Library's account button. (The sound, the cache, downloads and the diagnostic log live there.)
    func testSettingsIsReachableSignedOut() {
        launch()
        expect(any("shelf.header").firstMatch, "Home", timeout: network)
        expect(any("topbar.account").firstMatch, "Home's account button", timeout: 10).tap()
        menuItem("Settings").tap()
        expect(app.navigationBars["Settings"], "Settings from Home's account menu", timeout: 10)
        expectSignedOutLook("Settings from Home")
        checkpoint("settings from home")
        goBack()
        expect(any("shelf.header").firstMatch, "Home after leaving Settings", timeout: 10)

        tab("Explore").tap()
        expect(app.buttons["Charts"], "Explore", timeout: network)
        firstHittable(any("topbar.account"), timeout: 10).tap()
        menuItem("Settings").tap()
        expect(app.navigationBars["Settings"], "Settings from Explore's account menu", timeout: 10)
        goBack()
        expect(app.buttons["Charts"], "Explore after leaving Settings", timeout: 10)

        tab("Library").tap()
        let library = firstHittable(any("library.account"), timeout: 10)
        XCTAssertEqual(library.label, "Account and settings")
        library.tap()
        expect(app.navigationBars["Settings"], "Settings from the Library's account button", timeout: 10)
        expectSignedOutLook("Settings from the Library")
        checkpoint("settings from library")
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

    /// An item of the account menu, once the menu is open.
    private func menuItem(_ title: String) -> XCUIElement {
        firstHittable(app.buttons.matching(NSPredicate(format: "label == %@", title)), timeout: 10)
    }

    private func expectSignInSheet() {
        expect(app.navigationBars["Sign in with Google"], "the sign-in sheet")
        expect(app.webViews.firstMatch, "the sign-in page", timeout: network)
        pause(2)
        checkpoint("sign-in sheet")
    }
}
