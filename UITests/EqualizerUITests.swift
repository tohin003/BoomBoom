import XCTest

/// The sound sheet, touched as a finger would: the faders, the sliders, the switches, "Original", Reset and Done.
/// Every test opens the sheet over a playing song (silently) and starts from flat with the equaliser on.
final class EqualizerUITests: BoomUITestCase {

    // MARK: Faders

    /// A fader dragged up and then flicked down follows the finger; the sheet neither scrolls nor pulls down.
    func testFaderDragsUpAndDownWithoutMovingTheSheet() {
        openSound()
        let band = expect(element("sound.band.5"), "the 1K fader")
        let doneBefore = element("sound.done").frame
        let bandBefore = band.frame
        XCTAssertEqual(bandValue(5), 0, "the 1K band starts flat")

        drag(band, by: CGVector(dx: 0, dy: -80), velocity: .slow)
        checkpoint("1K dragged up")
        let raised = bandValue(5) ?? 0
        XCTAssertGreaterThan(raised, 5, "dragging 80 pt up raises the band by about 8.7 dB (it reads \(raised))")
        XCTAssertLessThan(raised, 12, "the band moves from where it was, not to the finger (it reads \(raised))")
        assertSame(band.frame, bandBefore, "the faders did not scroll")
        assertSame(element("sound.done").frame, doneBefore, "the sheet stayed where it was")

        // A quick downward flick: the gesture that would pull a sheet down if the fader did not hold the finger.
        drag(band, by: CGVector(dx: 0, dy: 150), velocity: .fast, hold: 0)
        pause(0.8)
        checkpoint("1K flicked down")
        let lowered = bandValue(5) ?? 99
        XCTAssertLessThan(lowered, raised - 8, "flicking down lowers the band (\(raised) → \(lowered))")
        XCTAssertTrue(element("sound.done").exists, "the sheet is still open after a downward flick on a fader")
        assertSame(element("sound.done").frame, doneBefore, "the sheet stayed where it was")
        assertSame(band.frame, bandBefore, "the faders did not scroll")
    }

    func testDoubleTapZeroesAFader() {
        openSound()
        let band = expect(element("sound.band.2"), "the 125 Hz fader")
        drag(band, by: CGVector(dx: 0, dy: -60), velocity: .slow)
        let raised = bandValue(2) ?? 0
        XCTAssertGreaterThan(raised, 3, "the 125 Hz band went up first (\(raised))")
        checkpoint("125 raised")
        band.doubleTap()
        eventually("a double tap sets the band to 0 (it reads \(bandValue(2) ?? -99))", timeout: 3) { bandValue(2) == 0 }
        checkpoint("125 zeroed")
    }

    /// A sideways stroke across the faders draws a curve through every band it crosses.
    func testStrokeAcrossFadersDrawsACurve() {
        openSound()
        let from = expect(element("sound.band.2"), "the 125 Hz fader")
        let to = element("sound.band.7")
        let start = from.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.25))
        let end = to.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.25))
        start.press(forDuration: 0.05, thenDragTo: end, withVelocity: .slow, thenHoldForDuration: 0.1)
        pause(0.5)
        checkpoint("stroke drawn")
        let values = bandValues()
        for index in 2...7 {
            XCTAssertGreaterThan(values[index], 3, "band \(index) follows the stroke (\(values))")
        }
        XCTAssertEqual(values[0], 0, "bands outside the stroke stay put (\(values))")
        XCTAssertEqual(values[9], 0, "bands outside the stroke stay put (\(values))")
    }

    // MARK: Presets

    func testPresetChipChangesTheReadouts() {
        openSound()
        let flat = bandValues()
        let rock = expect(element("sound.preset.rock"), "the Rock preset")
        revealChip(rock)
        rock.tap()
        eventually("Rock is selected", timeout: 5) { element("sound.preset.rock").isSelected }
        eventually("the readouts follow the preset (\(bandValues()))", timeout: 5) {
            let values = bandValues()
            return values != flat && values.first == 5 && values.last == 4.5
        }
        checkpoint("rock preset")
    }

    /// A starred preset moves to the front of the row, and is still starred and in front after the sheet closes
    /// and opens again.
    func testStarringAPresetMovesItToTheFrontAndIsRemembered() {
        openSound()
        let chip = element("sound.preset.bass-boost")
        revealChip(chip)
        chip.tap()
        eventually("Bass boost is selected", timeout: 5) { element("sound.preset.bass-boost").isSelected }
        let star = expect(element("sound.preset.star"), "the star button")
        if (star.label).hasPrefix("Remove") {
            star.tap()    // A run that failed half-way left it starred.
            pause(0.5)
        }
        let flatBefore = element("sound.preset.flat").frame
        XCTAssertLessThan(flatBefore.minX, element("sound.preset.bass-boost").frame.minX, "unstarred, Flat comes first")
        star.tap()
        eventually("the star button shows it starred (\(element("sound.preset.star").label))", timeout: 5) {
            element("sound.preset.star").label.hasPrefix("Remove")
        }
        eventually("Bass boost moves in front of Flat", timeout: 5) {
            element("sound.preset.bass-boost").frame.minX < element("sound.preset.flat").frame.minX
        }
        XCTAssertEqual(element("sound.preset.bass-boost").value as? String, "Favourite")
        checkpoint("bass boost starred")

        reopenSound()
        let again = element("sound.preset.bass-boost")
        revealChip(again)
        checkpoint("sheet reopened")
        XCTAssertEqual(again.value as? String, "Favourite", "still starred after reopening")
        XCTAssertLessThan(again.frame.minX, element("sound.preset.flat").frame.minX, "still in front after reopening")

        // Leave the row as it was.
        again.press(forDuration: 1.2)
        let remove = app.buttons["Remove from favourites"].firstMatch
        expect(remove, "the context menu's unstar item", timeout: 5)
        remove.tap()
        eventually("unstarred from the context menu", timeout: 5) {
            (element("sound.preset.bass-boost").value as? String ?? "") != "Favourite"
        }
    }

    /// A preset of the listener's own keeps the whole sound: the boost comes back with it.
    func testSavedPresetBringsBackTheBoost() {
        openSound()
        // Presets an interrupted run left behind.
        let leftovers = label(beginningWith: "Boost test", in: app.buttons)
        for _ in 0..<5 where leftovers.count > 0 {
            let chip = leftovers.firstMatch
            revealChip(chip)
            chip.press(forDuration: 1.2)
            let delete = app.buttons["Delete"].firstMatch
            if delete.waitForExistence(timeout: 5) { delete.tap() }
            pause(0.8)
        }
        let boost = element("sound.boost")
        scrollTo(boost)
        drag(boost, from: CGVector(dx: 0.1, dy: 0.5), by: CGVector(dx: boost.frame.width * 0.4, dy: 0), velocity: .slow)
        let saved = decibels(boost.value as? String) ?? 0
        XCTAssertGreaterThan(saved, 2, "the boost is up before saving (\(saved))")

        let save = element("sound.preset.save")
        scrollTo(save)
        save.tap()
        let name = "Boost test \(Int.random(in: 100...999))"
        let field = expect(app.alerts.textFields.firstMatch, "the name field")
        field.tap()
        field.typeText(name)
        checkpoint("naming the preset")
        app.alerts.buttons["Save"].firstMatch.tap()
        let chip = expect(app.buttons[name].firstMatch, "the new preset's chip")
        eventually("the new preset is selected", timeout: 5) { chip.isSelected }
        checkpoint("preset saved")

        scrollTo(boost)
        boost.doubleTap()
        eventually("the boost is back to 0 after a double tap", timeout: 5) { decibels(boost.value as? String) == 0 }
        revealChip(chip)
        XCTAssertFalse(chip.isSelected, "changing the boost leaves the preset")
        chip.tap()
        eventually("choosing the preset brings the boost back to \(saved) (\(boost.value as? String ?? "-"))", timeout: 5) {
            decibels(boost.value as? String) == saved
        }
        checkpoint("preset chosen again")

        // Remove it again, through the context menu.
        chip.press(forDuration: 1.2)
        let delete = app.buttons["Delete"].firstMatch
        expect(delete, "the context menu's Delete", timeout: 5)
        delete.tap()
        expectGone(app.buttons[name].firstMatch, "the deleted preset")
    }

    // MARK: Spatial

    /// Spatial switches on, brings its head-movement switch (disabled here, with the reason, since the simulator has
    /// no such headphones), dims crossfeed, and is still on after the sheet closes and opens again.
    func testSpatialTogglesAndIsRemembered() {
        openSound()
        let spatial = element("sound.spatial")
        scrollTo(spatial)
        XCTAssertFalse(isOn(spatial), "Spatial starts off after Reset")
        XCTAssertFalse(element("sound.headTracking").exists, "no head-movement switch while Spatial is off")
        tapSwitch(spatial)
        eventually("Spatial turns on", timeout: 5) { isOn(spatial) }
        let tracking = expect(element("sound.headTracking"), "the head-movement switch", timeout: 5)
        XCTAssertFalse(tracking.isEnabled, "head movement is unavailable without AirPods that report it")
        XCTAssertTrue(tracking.label.contains("Needs AirPods"), "the reason is shown (\(tracking.label))")
        scrollTo(tracking)
        checkpoint("spatial on")

        reopenSound()
        let again = element("sound.spatial")
        scrollTo(again)
        XCTAssertTrue(isOn(again), "Spatial is still on after reopening the sheet")
        XCTAssertTrue(element("sound.headTracking").exists, "and so is its head-movement switch")
        checkpoint("spatial on after reopening")
        tapSwitch(again)
        eventually("Spatial turns off", timeout: 5) { !isOn(again) }
        expectGone(element("sound.headTracking"), "the head-movement switch once Spatial is off")
    }

    // MARK: Sliders

    func testBoostSliderMovesAndItsReadoutFollows() {
        openSound()
        let boost = element("sound.boost")
        scrollTo(boost)
        let before = boost.frame
        let readout = element("sound.boost.readout")
        let oldReadout = readout.label
        drag(boost, from: CGVector(dx: 0.1, dy: 0.5), by: CGVector(dx: before.width * 0.45, dy: 0), velocity: .slow)
        checkpoint("boost moved")
        let value = decibels(boost.value as? String) ?? 0
        XCTAssertGreaterThan(value, 3, "the boost went up (\(boost.value as? String ?? "-"))")
        eventually("the big readout follows (\(oldReadout) → \(readout.label))", timeout: 3) {
            readout.label != oldReadout && readout.label.contains(String(format: "%.1f", value))
        }
        assertSame(boost.frame, before, "the sheet did not scroll during a sideways drag")
    }

    /// Sideways over a slider moves it and leaves the sheet; up over the same slider scrolls the sheet and leaves it.
    func testSliderTakesSidewaysDragsAndLeavesVerticalOnesToTheSheet() {
        openSound()
        let bass = element("sound.bass")
        scrollTo(bass)
        let before = bass.frame
        drag(bass, from: CGVector(dx: 0.5, dy: 0.6), by: CGVector(dx: 70, dy: 0), velocity: .slow)
        checkpoint("bass dragged sideways")
        let moved = decibels(bass.value as? String) ?? 0
        XCTAssertGreaterThan(moved, 1, "a sideways drag moves the bass slider (\(bass.value as? String ?? "-"))")
        assertSame(bass.frame, before, "a sideways drag does not scroll the sheet")

        drag(bass, from: CGVector(dx: 0.5, dy: 0.6), by: CGVector(dx: 0, dy: -160), velocity: .slow)
        pause(0.6)
        checkpoint("vertical drag over bass")
        XCTAssertLessThan(bass.frame.minY, before.minY - 60, "a vertical drag over the slider scrolls the sheet")
        XCTAssertEqual(decibels(bass.value as? String), moved, "a vertical drag leaves the slider's value alone")
        XCTAssertTrue(element("sound.done").exists, "the sheet is still open")
    }

    // MARK: Switches

    func testEverySwitchToggles() {
        openSound()
        for identifier in ["sound.master", "sound.limiter", "sound.autoHeadroom", "sound.normalise", "sound.spatial"] {
            let toggle = element(identifier)
            if identifier != "sound.master" { scrollTo(toggle) }
            let was = isOn(toggle)
            tapSwitch(toggle)
            eventually("\(identifier) turns \(was ? "off" : "on")", timeout: 5) { isOn(toggle) != was }
            checkpoint("\(identifier) toggled")
            tapSwitch(toggle)
            eventually("\(identifier) turns back \(was ? "on" : "off")", timeout: 5) { isOn(toggle) == was }
        }
    }

    // MARK: Original

    func testHoldingOriginalShowsItAndReleasingEndsIt() {
        openSound()
        let band = element("sound.band.4")
        drag(band, by: CGVector(dx: 0, dy: -70), velocity: .slow)
        let original = expect(element("sound.original"), "the Original button")
        XCTAssertEqual(original.value as? String, "Off")

        // XCUITest's press blocks until the finger lifts. A timer on the run loop it spins looks in the middle.
        let box = HoldProbe()
        let timer = Timer(timeInterval: 1.2, repeats: false) { _ in
            MainActor.assumeIsolated {
                box.shot = XCUIScreen.main.screenshot()
                box.value = self.element("sound.original").value as? String
                box.graph = self.element("sound.graph").value as? String
            }
        }
        RunLoop.main.add(timer, forMode: .common)
        original.press(forDuration: 2.5)
        timer.invalidate()
        if let shot = box.shot {
            let attachment = XCTAttachment(screenshot: shot)
            attachment.name = "while holding Original"
            attachment.lifetime = .keepAlways
            add(attachment)
        }
        XCTAssertEqual(box.value, "Hearing the original", "while held, the button shows it (\(box.value ?? "not read"))")
        XCTAssertEqual(box.graph, "Original", "while held, the graph shows it (\(box.graph ?? "not read"))")
        eventually("releasing Original ends the comparison", timeout: 3) {
            (element("sound.original").value as? String) == "Off"
        }
        XCTAssertEqual(element("sound.graph").value as? String, "Equaliser on")
        checkpoint("Original released")
    }

    // MARK: Reset

    func testResetReturnsEverythingToFlat() {
        openSound()
        let rock = element("sound.preset.rock")
        revealChip(rock)
        rock.tap()
        eventually("Rock applied", timeout: 5) { bandValues().contains { $0 != 0 } }
        let boost = element("sound.boost")
        scrollTo(boost)
        drag(boost, from: CGVector(dx: 0.1, dy: 0.5), by: CGVector(dx: boost.frame.width * 0.5, dy: 0), velocity: .slow)
        let width = element("sound.width")
        scrollTo(width)
        drag(width, from: CGVector(dx: 0.5, dy: 0.6), by: CGVector(dx: 60, dy: 0), velocity: .slow)
        XCTAssertGreaterThan(decibels(boost.value as? String) ?? 0, 0, "the boost is up before Reset")
        checkpoint("before reset")

        element("sound.reset").tap()
        let confirm = app.buttons["Reset everything"].firstMatch
        expect(confirm, "Reset asks first when something is set", timeout: 5)
        checkpoint("reset asks")
        confirm.tap()
        eventually("every band is flat after Reset (\(bandValues()))", timeout: 5) { bandValues().allSatisfy { $0 == 0 } }
        XCTAssertEqual(decibels(element("sound.boost").value as? String), 0, "the boost is back to 0")
        XCTAssertEqual(element("sound.width").value as? String, "100%, as recorded", "the width is back to as recorded")
        XCTAssertTrue(isOn(element("sound.master")), "the master switch stays on")
        checkpoint("after reset")
    }

    // MARK: Limiter

    func testLimiterMeterShowsReductionOnceTheBoostIsUp() {
        openSound()
        let output = element("sound.output")
        scrollTo(output)
        eventually("the output meters move (music is playing): \(output.value as? String ?? "-")", timeout: audio) {
            guard let value = output.value as? String else { return false }
            return !value.hasPrefix("Left -60") && !value.hasPrefix("Left −60")
        }
        let boost = element("sound.boost")
        scrollTo(boost)
        drag(boost, from: CGVector(dx: 0.05, dy: 0.5), by: CGVector(dx: boost.frame.width, dy: 0), velocity: .slow)
        XCTAssertEqual(decibels(boost.value as? String), 12, "the boost is at its top")
        let limiter = element("sound.limiterMeter")
        eventually("the limiter meter shows a reduction (\(limiter.value as? String ?? "-"))", timeout: 20) {
            (limiter.value as? String ?? "").hasPrefix("Holding back")
        }
        checkpoint("limiter working")
    }

    // MARK: Done

    func testDoneClosesTheSheet() {
        openSound()
        element("sound.done").tap()
        expectGone(element("sound.done"), "the sound sheet after Done")
        checkpoint("closed")
    }

    // MARK: Helpers

    /// Opens the sheet over a playing song, then: equaliser on, everything flat.
    private func openSound() {
        launch(["-BoomPlay", "dQw4w9WgXcQ", "-BoomShow", "equalizer", "-BoomShowAfter", "2"])
        expect(element("sound.done"), "the sound sheet", timeout: network)
        pause(1)
        let master = element("sound.master")
        if !isOn(master) {
            tapSwitch(master)
            eventually("the equaliser turns on", timeout: 5) { isOn(master) }
        }
        // Reset, asking again if a slow launch let the first tap or the dialog slip by.
        for _ in 0..<3 where !isFlat {
            element("sound.reset").tap()
            let confirm = app.buttons["Reset everything"].firstMatch
            if confirm.waitForExistence(timeout: 4) { confirm.tap() }
            let deadline = Date.now.addingTimeInterval(4)
            while !isFlat, Date.now < deadline { pause(0.4) }
        }
        eventually("everything starts flat", timeout: 3) { isFlat }
        checkpoint("sound sheet, flat")
    }

    private func element(_ identifier: String) -> XCUIElement { any(identifier).firstMatch }

    private var isFlat: Bool {
        bandValues().allSatisfy { $0 == 0 } && decibels(element("sound.boost").value as? String) == 0
    }

    /// Brings a preset chip on screen: the sheet scrolls it into view, then the chip row slides sideways.
    private func revealChip(_ chip: XCUIElement, file: StaticString = #filePath, line: UInt = #line) {
        scrollTo(chip, file: file, line: line)
        let window = app.windows.firstMatch.frame
        for _ in 0..<24 {
            let frame = chip.frame
            if frame.minX >= 8, frame.maxX <= window.maxX - 8 { return }
            let left = frame.minX > window.midX
            let y = frame.midY / window.height
            let start = app.windows.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: left ? 0.85 : 0.15, dy: y))
            start.press(
                forDuration: 0.05, thenDragTo: start.withOffset(CGVector(dx: left ? -280 : 280, dy: 0)),
                withVelocity: .slow, thenHoldForDuration: 0.15)
            pause(0.4)
        }
        failureShot("could not reveal \(chip)")
        XCTFail("could not bring \(chip) on screen", file: file, line: line)
    }

    /// Done, then the sheet again through the full player's sound button.
    private func reopenSound() {
        element("sound.done").tap()
        expectGone(element("sound.done"), "the sheet after Done")
        openFullPlayer()
        firstHittable(label(beginningWith: "Sound, equaliser")).tap()
        expect(element("sound.done"), "the sound sheet, opened again", timeout: 10)
        pause(1)
    }

    /// "+3.5 dB" → 3.5, "−2.0 dB" → -2, "0.0 dB, 100% level" → 0.
    private func decibels(_ value: String?) -> Double? {
        guard let value else { return nil }
        let number = value.components(separatedBy: " dB").first ?? ""
        return Double(number.replacingOccurrences(of: "+", with: "").replacingOccurrences(of: "−", with: "-"))
    }

    private func bandValue(_ band: Int) -> Double? {
        decibels(element("sound.band.\(band)").value as? String)
    }

    private func bandValues() -> [Double] {
        (0..<10).map { bandValue($0) ?? .nan }
    }

    /// A finger put down on `element` (at `from`, as fractions of its frame), moved by `offset` points, lifted.
    private func drag(
        _ element: XCUIElement, from: CGVector = CGVector(dx: 0.5, dy: 0.5), by offset: CGVector,
        velocity: XCUIGestureVelocity, hold: TimeInterval = 0.1
    ) {
        let start = element.coordinate(withNormalizedOffset: from)
        start.press(forDuration: 0.05, thenDragTo: start.withOffset(offset), withVelocity: velocity, thenHoldForDuration: hold)
        pause(0.4)
    }

    private func assertSame(_ frame: CGRect, _ before: CGRect, _ what: String, file: StaticString = #filePath, line: UInt = #line) {
        if abs(frame.minY - before.minY) > 1.5 || abs(frame.minX - before.minX) > 1.5 {
            failureShot("moved: \(what)")
            XCTFail("\(what): \(before) → \(frame)", file: file, line: line)
        }
    }

    private func isOn(_ toggle: XCUIElement) -> Bool { (toggle.value as? String) == "1" }

    /// The whole row is the switch's target.
    private func tapSwitch(_ toggle: XCUIElement) {
        toggle.tap()
    }

    /// Scrolls the sheet until `element` sits fully below the header and above the screen's foot. The finger goes
    /// down in the left margin, which only ever scrolls.
    private func scrollTo(_ target: XCUIElement, file: StaticString = #filePath, line: UInt = #line) {
        let window = app.windows.firstMatch.frame
        for _ in 0..<12 {
            let top = element("sound.master").frame.maxY + 14
            let frame = target.frame
            if target.exists, frame.minY >= top, frame.maxY <= window.maxY - 30 { return }
            let up = frame.minY > window.maxY - 30 || !target.exists
            let distance = min(max(abs(frame.midY - window.midY), 120), up ? 360 : 300)
            let start = app.windows.firstMatch.coordinate(
                withNormalizedOffset: CGVector(dx: 12 / window.width, dy: up ? 0.6 : 0.4))
            start.press(
                forDuration: 0.05, thenDragTo: start.withOffset(CGVector(dx: 0, dy: up ? -distance : distance)),
                withVelocity: .slow, thenHoldForDuration: 0.15)
            pause(0.4)
        }
        failureShot("could not scroll to \(target)")
        XCTFail("could not scroll \(target) into view", file: file, line: line)
    }
}

/// What the timer saw while "Original" was held.
@MainActor
private final class HoldProbe {
    var shot: XCUIScreenshot?
    var value: String?
    var graph: String?
}
