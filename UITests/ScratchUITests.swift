import XCTest

/// Temporary: hidden tabs must be out of the accessibility tree.
final class ScratchUITests: BoomUITestCase {

    func testHiddenTabsAreHidden() {
        launch()
        expect(any("chip").firstMatch, "Home's chips", timeout: network)
        tab("Explore").tap()
        expect(app.buttons["Charts"], "Explore's tiles")
        pause(1)
        let leaked = any("chip").count
        print("LEAK home chips on explore: \(leaked)")
        if leaked > 0 { failureShot("home leaks into explore") }
        app.buttons["Charts"].tap()
        expect(any("row.artist").firstMatch, "chart rows", timeout: network)
        tab("Home").tap()
        expect(any("chip").firstMatch, "Home's chips")
        pause(1)
        let leakedRows = any("row.artist").count
        let leakedBack = app.navigationBars["Charts"].exists
        print("LEAK charts rows on home: \(leakedRows), nav bar: \(leakedBack)")
        if leakedRows > 0 || leakedBack { failureShot("explore leaks into home") }
        XCTAssertEqual(leaked, 0)
        XCTAssertEqual(leakedRows, 0)
        XCTAssertFalse(leakedBack)
    }
}
