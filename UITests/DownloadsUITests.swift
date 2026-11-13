import XCTest

/// Downloads with a finger, against the real service: a song from a search result's menu arrives in Downloads with a
/// size and plays from there; swipe to remove; select two and delete them; an album from its page, its button going
/// from a count to done; the list kept across a relaunch; Remove all.
///
/// The tests run in name order and share the phone's downloads: the album downloaded in test 3 is the one test 4
/// finds after relaunching. Each test that needs a clean start clears the list first.
final class DownloadsUITests: BoomUITestCase {
    /// A song takes 10 to 20 seconds; a minute each allows for a slow moment.
    let perSong: TimeInterval = 60

    func test1SongFromSearchPlaysAndSwipesAway() {
        launch(["-BoomStart", "search:norah jones"])
        clearDownloads(thenReturnTo: "Home")
        let title = downloadSongsFromSearch(count: 1).first ?? ""

        openDownloads()
        let row = expect(downloadRows.firstMatch, "the song in Downloads", timeout: 10)
        XCTAssertTrue(row.label.contains(title), "the row is \(title) (\(row.label))")
        eventually("\(title) arrives with a size", timeout: perSong) { self.sizedRows.count == 1 }
        let space = any("downloads.space").firstMatch.label
        XCTAssertFalse(space.hasPrefix("Zero"), "the header states the space taken (\(space))")
        checkpoint("downloaded with size")

        // Played from Downloads.
        firstHittable(downloadRows).tap()
        waitUntilPlaying()
        XCTAssertTrue(nowPlayingLabel.hasPrefix(title), "\(title) plays (\(nowPlayingLabel))")
        checkpoint("playing from downloads")

        // Swiped away.
        swipeAway(firstHittable(downloadRows))
        expect(label(beginningWith: "No downloads yet").firstMatch, "the empty state after the swipe", timeout: 10)
        checkpoint("swiped away")
    }

    func test2SelectTwoAndDelete() {
        launch(["-BoomStart", "search:norah jones"])
        clearDownloads(thenReturnTo: "Home")
        let titles = downloadSongsFromSearch(count: 3)
        XCTAssertEqual(titles.count, 3, "three songs were asked for (\(titles))")

        openDownloads()
        eventually("three rows in Downloads", timeout: 10) { self.downloadRows.count == 3 }
        eventually("two songs downloaded", timeout: perSong * 2) { self.sizedRows.count >= 2 }
        checkpoint("three songs")

        any("downloads.select").firstMatch.tap()
        let sized = sizedRows.allElementsBoundByIndex.prefix(2)
        for row in sized { row.tap() }
        let frees = expect(any("downloads.frees").firstMatch, "the space the selection frees")
        XCTAssertTrue(frees.label.hasPrefix("Frees"), "the bar says what the selection frees (\(frees.label))")
        XCTAssertFalse(frees.label.contains("Zero"), "two downloaded songs free some space (\(frees.label))")
        let delete = any("downloads.delete").firstMatch
        XCTAssertTrue(delete.label.contains("2"), "the button says Delete (2) (\(delete.label))")
        checkpoint("two selected")
        delete.tap()
        expect(app.buttons["Remove 2"], "the question before deleting", timeout: 5).tap()
        eventually("one row left", timeout: 10) { self.downloadRows.count == 1 }
        XCTAssertFalse(any("downloads.delete").firstMatch.exists, "select mode ends after deleting")
        checkpoint("one left")
    }

    func test3AlbumFromItsPage() {
        launch(["-BoomStart", "search:norah jones"])
        clearDownloads(thenReturnTo: "Home")
        expect(any("chip")["Albums"], "the results' filter chips", timeout: network).tap()
        _ = firstHittable(any("row.album"), timeout: network)
        // Several songs, so the button shows a real count; an EP first, as it arrives within the test.
        let rows = any("row.album").allElementsBoundByIndex
        let short = rows.first { $0.label.contains("EP") } ?? rows.first { $0.label.contains("Album") } ?? rows[0]
        short.tap()

        let button = expect(any("collection.download").firstMatch, "the album's download button", timeout: network)
        XCTAssertEqual(button.label, "Download")
        checkpoint("album not downloaded")
        button.tap()
        eventually("the button shows a count", timeout: network) {
            (button.value as? String ?? "").contains(" of ") || button.label == "Downloaded"
        }
        let status = any("collection.download.status").firstMatch
        checkpoint("album arriving: \(status.exists ? status.label : "")")
        let total = Int((button.value as? String ?? "").components(separatedBy: " of ").last ?? "") ?? 12
        XCTAssertGreaterThan(total, 1, "the album has several songs to count")
        eventually("the album is downloaded", timeout: perSong * Double(max(total, 1))) {
            button.label == "Downloaded"
        }
        XCTAssertTrue(status.label.hasPrefix("Downloaded"), "the line under the buttons says so (\(status.label))")
        checkpoint("album downloaded")

        // Tapping the downloaded button asks before removing; this time it stays.
        button.tap()
        expect(app.buttons["Remove download"], "the question before removing", timeout: 5)
        checkpoint("asks before removing")
        dismissDialog()
        XCTAssertEqual(button.label, "Downloaded", "nothing was removed")
    }

    func test4ListKeptAcrossRelaunchThenRemoveAll() {
        launch()
        openDownloads()
        // The list is lazy: rows off screen are not there to count, so the header's count is compared instead.
        let count = expect(any("downloads.count").firstMatch, "the number of songs").label
        let songs = Int(count.components(separatedBy: " ").first ?? "") ?? 0
        XCTAssertGreaterThan(songs, 0, "the album's songs are still listed after relaunching (\(count))")
        let shown = downloadRows.count
        XCTAssertGreaterThan(shown, 0, "rows are listed")
        XCTAssertEqual(sizedRows.count, shown, "every song on screen is still downloaded, with its size")
        any("chip")["Albums & playlists"].tap()
        expect(any("downloads.collection").firstMatch, "the album in Albums & playlists", timeout: 5)
        checkpoint("kept across relaunch")

        // Its own page, from the phone.
        any("downloads.collection").firstMatch.tap()
        expect(label(beginningWith: "\(songs) songs · ").firstMatch, "its \(songs) songs", timeout: 5)
        XCTAssertGreaterThan(downloadRows.count, 0, "its songs are listed")
        checkpoint("downloaded album page")
        goBack()

        removeAll()
        expect(label(beginningWith: "No downloads yet").firstMatch, "the empty state after Remove all", timeout: 10)
        checkpoint("all removed")
    }

    // MARK: Helpers

    private var downloadRows: XCUIElementQuery { any("downloads.row") }

    /// Rows that show a size ("6.4 MB · Opus 256 kbps"): downloaded ones.
    private var sizedRows: XCUIElementQuery {
        downloadRows.matching(NSPredicate(format: "label CONTAINS ' MB' OR label CONTAINS ' KB'"))
    }

    private func openDownloads() {
        tab("Library").tap()
        let entry = expect(any("library.downloads").firstMatch, "the Downloads row in Library", timeout: 10)
        if !isOnScreen(entry) { tab("Library").tap() }
        entry.tap()
        // The header with the space taken, or the empty state when there is nothing.
        eventually("the Downloads screen", timeout: 10) {
            self.any("downloads.space").firstMatch.exists
                || self.label(beginningWith: "No downloads yet").firstMatch.exists
        }
        pause(0.5)
    }

    /// Opens Downloads, removes everything there, and goes back to a tab.
    private func clearDownloads(thenReturnTo tabName: String) {
        openDownloads()
        if any("downloads.removeAll").firstMatch.exists {
            removeAll()
            expect(label(beginningWith: "No downloads yet").firstMatch, "the empty state", timeout: 10)
        }
        goBack()
        tab(tabName).tap()
        pause(0.8)
    }

    /// "Remove all downloads", asked twice.
    private func removeAll() {
        let button = any("downloads.removeAll").firstMatch
        var tries = 0
        while !isOnScreen(button), tries < 8 { scrollDown(); tries += 1 }
        button.tap()
        expect(app.buttons["Remove all"], "the first question", timeout: 5).tap()
        let again = app.alerts.firstMatch.buttons["Remove all"]
        expect(again, "the second question", timeout: 5).tap()
    }

    /// Downloads songs from the search results' Songs list through each row's menu; returns their titles.
    private func downloadSongsFromSearch(count: Int) -> [String] {
        expect(any("chip")["Songs"], "the results' filter chips", timeout: network).tap()
        _ = firstHittable(songRows, timeout: network)
        pause(1)
        let menus = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'More actions for '"))
        let titles = menus.allElementsBoundByIndex.filter { isOnScreen($0) }.prefix(count)
            .map { String($0.label.dropFirst("More actions for ".count)) }
        for title in titles {
            app.buttons["More actions for \(title)"].firstMatch.tap()
            expect(app.buttons["Download"], "Download in the menu of \(title)", timeout: 5).tap()
            expectGone(app.buttons["Download"], "the menu")
        }
        checkpoint("asked for \(titles.count)")
        return Array(titles)
    }

    private func swipeAway(_ row: XCUIElement) {
        row.swipeLeft()
        let remove = app.buttons["Remove"]
        if remove.waitForExistence(timeout: 2) { remove.tap() }
    }

    /// Closes a confirmation dialog without choosing.
    private func dismissDialog() {
        let cancel = app.buttons["Cancel"]
        if cancel.exists && cancel.isHittable {
            cancel.tap()
        } else {
            app.windows.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.1)).tap()
        }
        pause(0.8)
    }
}
