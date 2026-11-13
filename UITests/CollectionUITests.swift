import XCTest

/// Flow 5: an album from search, its Play button and row menus, the artist page, Radio, Shuffle, "More" on the top
/// songs (a playlist page), and back.
final class CollectionUITests: BoomUITestCase {

    func testAlbumArtistPlaylist() {
        launch(["-BoomStart", "search:arijit singh"])
        expect(any("chip")["Albums"], "the results' filter chips", timeout: network)
        any("chip")["Albums"].tap()
        let albumRow = firstHittable(any("row.album"), timeout: network)
        albumRow.tap()

        // The track list.
        expect(app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'More actions for '")).firstMatch,
               "the album's tracks", timeout: network)
        expect(any("album.artist").firstMatch, "the album's artist line")
        pause(1)
        let trackMenus = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'More actions for '"))
        let titles = trackMenus.allElementsBoundByIndex.map { String($0.label.dropFirst("More actions for ".count)) }
        XCTAssertGreaterThan(titles.count, 2, "the album has a track list (\(titles))")
        XCTAssertGreaterThan(songRows.count, 2, "the tracks are song rows")
        checkpoint("album")

        // The yellow Play button plays the album.
        let play = firstHittable(app.buttons.matching(NSPredicate(format: "label == 'Play'")))
        play.tap()
        waitUntilPlaying()
        let playing = nowPlayingLabel
        XCTAssertTrue(titles.contains { playing.hasPrefix($0) }, "an album track plays (\(playing), album \(titles))")
        openFullPlayer()
        let from = expect(label(beginningWith: "PLAYING FROM").firstMatch, "the player's source line")
        XCTAssertTrue(from.label.hasPrefix("PLAYING FROM ALBUM"), "the player plays from the album (\(from.label))")
        checkpoint("playing from album")
        app.buttons["Close player"].tap()
        expectGone(app.buttons["Close player"], "the full player")

        // A track's menu: Play next, Add to queue.
        let nextTitle = titles[min(4, titles.count - 1)]
        let queueTitle = titles[min(2, titles.count - 1)]
        openTrackMenu(nextTitle)
        expect(app.buttons["Play next"], "\"Play next\" in the row menu", timeout: 5)
        expect(app.buttons["Add to queue"], "\"Add to queue\" in the row menu", timeout: 5)
        checkpoint("row menu")
        app.buttons["Play next"].tap()
        expectGone(app.buttons["Add to queue"], "the row menu")
        openTrackMenu(queueTitle)
        expect(app.buttons["Add to queue"], "\"Add to queue\"", timeout: 5).tap()
        expectGone(app.buttons["Add to queue"], "the row menu")

        // In Up next: the "Play next" track right after the playing one, the queued track at the end.
        openFullPlayer()
        fullPlayer.buttons["UP NEXT"].firstMatch.tap()
        let rows = any("queue.row")
        eventually("the queue has rows", timeout: 10) { rows.count > 1 }
        pause(1)
        let visible = rows.allElementsBoundByIndex.filter { isOnScreen($0) }
        if let current = visible.firstIndex(where: { $0.isSelected }), current + 1 < visible.count {
            XCTAssertTrue(visible[current + 1].label.hasPrefix(nextTitle),
                          "\"Play next\" put \(nextTitle) after the playing song (found \(visible[current + 1].label))")
        } else {
            XCTFail("the playing row and the one after it are on screen")
        }
        checkpoint("play next in queue")
        for _ in 0..<6 { scrollDown() }
        let last = rows.allElementsBoundByIndex.filter { isOnScreen($0) }.last
        XCTAssertTrue(last?.label.hasPrefix(queueTitle) == true,
                      "\"Add to queue\" put \(queueTitle) at the end (found \(last?.label ?? "nothing"))")
        XCTAssertGreaterThan(rows.count, 0)
        checkpoint("add to queue at end")
        app.buttons["Close player"].tap()   // the panel closes with the player
        expectGone(app.buttons["Close player"], "the full player")

        // The artist.
        let artist = any("album.artist").firstMatch
        if !artist.isHittable { for _ in 0..<4 { drag(from: CGVector(dx: 0.5, dy: 0.3), to: CGVector(dx: 0.5, dy: 0.9), velocity: .fast, hold: 0) } }
        let artistName = artist.label
        artist.tap()
        let radio = expect(app.buttons["Radio"], "the artist page's Radio", timeout: network)
        checkpoint("artist \(artistName)")

        let beforeRadio = nowPlayingLabel
        eventually("Radio is enabled", timeout: 5) { radio.isEnabled }
        radio.tap()
        eventually("Radio starts another song (was \(beforeRadio))", timeout: network) { nowPlayingLabel != beforeRadio }
        waitUntilPlaying()
        openFullPlayer()
        let radioFrom = expect(label(beginningWith: "PLAYING FROM").firstMatch, "the player's source line")
        XCTAssertTrue(radioFrom.label.hasPrefix("PLAYING FROM ARTIST"), "Radio plays from the artist (\(radioFrom.label))")
        app.buttons["Close player"].tap()
        expectGone(app.buttons["Close player"], "the full player")
        checkpoint("radio")

        let beforeShuffle = nowPlayingLabel
        let shuffle = firstHittable(app.buttons.matching(NSPredicate(format: "label == 'Shuffle'")))
        shuffle.tap()
        eventually("Shuffle starts another song (was \(beforeShuffle))", timeout: network) { nowPlayingLabel != beforeShuffle }
        waitUntilPlaying()
        checkpoint("shuffle")

        // "More" beside the top songs opens their whole list.
        let more = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'More ' AND NOT (label BEGINSWITH 'More actions')"))
        var tries = 0
        while !more.allElementsBoundByIndex.contains(where: { isOnScreen($0) }), tries < 4 {
            scrollDown(); tries += 1
        }
        let moreButton = firstHittable(more)
        let shelfName = moreButton.label
        moreButton.tap()
        eventually("\(shelfName) opens a list of songs", timeout: network) {
            songRows.count > 5 && app.buttons["Radio"].exists == false
        }
        checkpoint("top songs list")

        goBack()
        expect(app.buttons["Radio"], "the artist page after Back", timeout: 10)
        goBack()
        expect(any("album.artist").firstMatch, "the album after Back", timeout: 10)
        checkpoint("back on album")
    }

    private func openTrackMenu(_ title: String) {
        let menu = app.buttons["More actions for \(title)"].firstMatch
        var tries = 0
        while !isOnScreen(menu) || menu.frame.maxY > miniPlayer.frame.minY - 4, tries < 6 {
            drag(from: CGVector(dx: 0.5, dy: 0.7), to: CGVector(dx: 0.5, dy: 0.55), velocity: .slow, hold: 0.2)
            tries += 1
        }
        menu.tap()
    }
}
