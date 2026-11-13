import XCTest

/// Flow 4: typing, suggestions, the fill arrow, results and their filters, paging, playing, recent searches.
final class SearchUITests: BoomUITestCase {

    func testSearch() {
        launch()
        expect(any("shelf.header").firstMatch, "Home", timeout: network)
        app.buttons["Search"].firstMatch.tap()
        let field = expect(searchField, "the search field")
        eventually("the keyboard is up", timeout: 5) { app.keyboards.firstMatch.exists }
        checkpoint("search open")

        // One character at a time: suggestions appear and change as the words grow.
        var seen: [Set<String>] = []
        for character in "arij" {
            field.typeText(String(character))
            pause(1.2)
            seen.append(Set(fillButtons.allElementsBoundByIndex.map(\.label)))
        }
        eventually("suggestions appear for \"arij\"", timeout: network) { fillButtons.count > 0 }
        let latest = Set(fillButtons.allElementsBoundByIndex.map(\.label))
        XCTAssertGreaterThan(Set(seen.filter { !$0.isEmpty }).count + (latest.isEmpty ? 0 : 1), 1,
                             "the suggestions change while typing (saw \(seen))")
        checkpoint("suggestions for arij")

        // The arrow puts a suggestion's words in the field and keeps suggesting.
        let arrow = firstHittable(fillButtons)
        let suggestion = String(arrow.label.dropFirst("Use ".count))
        arrow.tap()
        eventually("the field holds \"\(suggestion)\"", timeout: 5) {
            (searchField.value as? String)?.trimmingCharacters(in: .whitespaces) == suggestion
        }
        eventually("still suggestions after the arrow", timeout: network) { fillButtons.count > 0 }
        XCTAssertTrue(app.keyboards.firstMatch.exists, "the keyboard stays up after the arrow")
        checkpoint("filled")

        // Tapping a suggestion searches: results with filter chips.
        let query = firstHittable(querySuggestions)
        let words = query.label
        query.tap()
        expect(any("chip")["Songs"], "the results' filter chips", timeout: network)
        expect(any("shelf.header").firstMatch, "result shelves for \(words)", timeout: network)
        checkpoint("results for \(words)")

        // Songs only, then page through.
        any("chip")["Songs"].tap()
        eventually("the Songs chip is selected", timeout: 5) { any("chip")["Songs"].isSelected }
        expect(songRows.firstMatch, "a list of songs", timeout: network)
        pause(1)
        var titles = Set(songRows.allElementsBoundByIndex.map(\.label))
        let firstPage = titles.count
        for _ in 0..<10 {
            scrollDown()
            titles.formUnion(songRows.allElementsBoundByIndex.map(\.label))
        }
        checkpoint("songs scrolled")
        XCTAssertGreaterThan(titles.count, max(firstPage, 20), "more songs load while scrolling (first \(firstPage), then \(titles.count))")

        // A song plays.
        let row = firstHittable(songRows)
        row.tap()
        waitUntilPlaying()
        checkpoint("search song playing")

        // Clearing the field shows the recent searches, with this one.
        scrollToTopOfSearch()
        app.buttons["Clear search"].tap()
        expect(app.buttons["Remove \(words) from recent searches"], "\"\(words)\" in recent searches", timeout: 5)
        checkpoint("recent searches")

        // Back to Home.
        app.buttons["Back"].tap()
        expectGone(searchField, "the search page after Back")
        expect(any("shelf.header").firstMatch, "Home after Back", timeout: network)
        XCTAssertTrue(tab("Home").isSelected, "the Home tab is selected")
        checkpoint("back home")
    }

    private var searchField: XCUIElement { app.textFields.firstMatch }

    /// The arrows beside the query suggestions ("Use …").
    private var fillButtons: XCUIElementQuery {
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Use '"))
    }

    /// The query suggestions themselves (the rows that search when tapped).
    private var querySuggestions: XCUIElementQuery {
        let labels = Set(fillButtons.allElementsBoundByIndex.map { String($0.label.dropFirst("Use ".count)) })
        return app.buttons.matching(NSPredicate(format: "label IN %@", Array(labels)))
    }

    /// The field sits above the results; a quick flick down brings it into reach if a scroll moved anything.
    private func scrollToTopOfSearch() {
        if !app.buttons["Clear search"].isHittable {
            for _ in 0..<3 {
                drag(from: CGVector(dx: 0.5, dy: 0.3), to: CGVector(dx: 0.5, dy: 0.9), velocity: .fast, hold: 0)
            }
        }
    }
}
