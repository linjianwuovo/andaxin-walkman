import XCTest

final class MusicParityTests: XCTestCase {
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication(bundleIdentifier: "com.andaxin.ios")
        app.launchArguments = [
            "-AndaXinGuest",
            "-music.queue", "1\tRestore check A\tTest artist\tTest album\t\n2\tRestore check B\tTest artist\tTest album\t",
            "-music.index", "1",
            "-music.position", "42.5",
            "-music.playing", "YES",
            "-music.tabSelected", "设置",
        ]
    }

    override func tearDownWithError() throws {
        if let app {
            let tree = XCTAttachment(string: app.debugDescription)
            tree.name = name + "-accessibility"
            tree.lifetime = .keepAlways
            add(tree)
            app.terminate()
        }
        app = nil
    }

    private func element(_ identifier: String) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: identifier).firstMatch
    }

    private func waitFor(_ element: XCUIElement, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertTrue(element.waitForExistence(timeout: 25), "Missing UI element: \(element)", file: file, line: line)
    }

    private func musicTab() {
        let tab = app.tabBars.buttons["音乐"]
        waitFor(tab)
        tab.tap()
        waitFor(element("music-restore-position"))
    }

    private func assertSelected(_ control: XCUIElement, _ enabled: Bool, file: StaticString = #filePath, line: UInt = #line) {
        waitFor(control, file: file, line: line)
        let state = NSPredicate { _, _ in control.isSelected == enabled }
        let result = XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: state, object: control)], timeout: 5)
        XCTAssertEqual(result, .completed, "Unexpected selected state: \(control.debugDescription)", file: file, line: line)
    }

    private func setToggle(_ identifier: String, _ enabled: Bool) {
        let toggle = element(identifier)
        waitFor(toggle)
        XCTAssertTrue(toggle.isHittable)
        if toggle.isSelected != enabled {
            toggle.tap()
        }
        assertSelected(toggle, enabled)
    }

    private func setRestorePosition(_ enabled: Bool) {
        setToggle("music-restore-position", enabled)
    }

    private func scrollTo(_ control: XCUIElement) {
        for _ in 0..<5 {
            if control.exists && control.isHittable { return }
            app.swipeUp()
        }
        waitFor(control)
        XCTAssertTrue(control.isHittable)
    }

    private func assertPaused(position: String, song: String = "Restore check B") {
        let player = element("music-mini-player")
        waitFor(player)
        XCTAssertTrue(player.label.contains(song), "Restored the wrong song: \(player.label)")
        XCTAssertTrue(player.label.contains(position + " / "), "Unexpected playback position: \(player.label)")
        let play = element("music-mini-toggle")
        waitFor(play)
        XCTAssertEqual(play.label, "播放", "A restored player must stay paused even with the legacy playing flag")
    }

    private func capture(_ label: String) {
        let image = XCTAttachment(screenshot: app.screenshot())
        image.name = label
        image.lifetime = .keepAlways
        add(image)
    }

    func testMyMusicEntryReturnsAndSharesPausedPlayer() {
        app.launch()
        musicTab()
        setRestorePosition(true)
        app.terminate()
        app.launch()

        let me = app.tabBars.buttons["我的"]
        waitFor(me)
        me.tap()
        let entry = element("me-entry-music")
        waitFor(entry)
        XCTAssertTrue(entry.isHittable)
        entry.tap()
        let bar = app.navigationBars["音乐"]
        waitFor(bar)
        assertPaused(position: "0:42")
        capture("my-music-pushed-paused")

        let back = bar.buttons.element(boundBy: 0)
        XCTAssertTrue(back.isHittable)
        back.tap()
        XCTAssertTrue(entry.waitForExistence(timeout: 5))
        XCTAssertTrue(entry.isHittable)
        musicTab()
        assertPaused(position: "0:42")
        capture("music-tab-same-paused-player")
    }

    func testLegacyPlayingFlagDoesNotAutoplayAfterRelaunch() {
        app.launch()
        musicTab()
        setRestorePosition(true)
        app.terminate()
        app.launch()
        musicTab()
        assertPaused(position: "0:42")
        app.terminate()
        app.launch()
        musicTab()
        assertPaused(position: "0:42")
        capture("legacy-playing-remains-paused")
    }

    func testRestorePositionPreferencePersistsAcrossRelaunch() {
        app.launch()
        musicTab()
        setRestorePosition(true)
        app.terminate()
        app.launch()
        musicTab()
        assertPaused(position: "0:42")
        setRestorePosition(false)
        app.terminate()
        app.launch()
        musicTab()
        let toggle = element("music-restore-position")
        assertSelected(toggle, false)
        assertPaused(position: "0:00")
        capture("restore-disabled-zero-position")
        setRestorePosition(true)
    }

    private func launchQueueEditor() {
        let queueArgument = app.launchArguments.firstIndex(of: "-music.queue")!
        app.launchArguments[queueArgument + 1] += "\n3\tRestore check C\tTest artist\tTest album\t"
        app.launchArguments += ["-music.restorePosition", "YES"]
        app.launch()
        musicTab()
        assertPaused(position: "0:42")
        element("music-mini-player").tap()
        let queue = element("music-player-queue")
        waitFor(queue)
        queue.tap()
        assertQueueRow(0, song: "Restore check A")
        assertQueueRow(1, song: "Restore check B")
        assertQueueRow(2, song: "Restore check C")
    }

    private func assertQueueRow(_ index: Int, song: String, file: StaticString = #filePath, line: UInt = #line) {
        let row = element("music-queue-row-\(index)")
        waitFor(row, file: file, line: line)
        let expected = NSPredicate(format: "label CONTAINS %@", song)
        let result = XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: expected, object: row)], timeout: 5)
        XCTAssertEqual(result, .completed, "Unexpected queue row \(index): \(row.label)", file: file, line: line)
    }

    private func queueAction(_ action: String, at index: Int) {
        let menu = element("music-queue-actions-\(index)")
        waitFor(menu)
        XCTAssertTrue(menu.isHittable)
        menu.tap()
        let item = element("music-queue-\(action)-\(index)")
        waitFor(item)
        XCTAssertTrue(item.isEnabled)
        XCTAssertTrue(item.isHittable)
        item.tap()
    }

    private func closeQueueEditor() {
        let close = element("music-queue-close")
        waitFor(close)
        close.tap()
        let collapse = element("music-player-collapse")
        waitFor(collapse)
        collapse.tap()
        waitFor(element("music-mini-player"))
    }

    func testQueueReorderPersistsAndKeepsCurrentPausedSong() {
        launchQueueEditor()
        queueAction("up", at: 1)
        assertQueueRow(0, song: "Restore check B")
        assertQueueRow(1, song: "Restore check A")
        queueAction("down", at: 0)
        assertQueueRow(0, song: "Restore check A")
        assertQueueRow(1, song: "Restore check B")
        queueAction("up", at: 1)
        capture("queue-reordered-current-first")
        closeQueueEditor()
        assertPaused(position: "0:42")
        app.terminate()
        for key in ["-music.queue", "-music.index"] {
            let index = app.launchArguments.firstIndex(of: key)!
            app.launchArguments.removeSubrange(index...(index + 1))
        }
        app.launch()
        musicTab()
        assertPaused(position: "0:42")
        element("music-mini-player").tap()
        element("music-player-queue").tap()
        assertQueueRow(0, song: "Restore check B")
        assertQueueRow(1, song: "Restore check A")
        assertQueueRow(2, song: "Restore check C")
        capture("queue-reorder-persisted")
    }

    func testQueueRemovalBeforeCurrentPreservesPausedPosition() {
        launchQueueEditor()
        queueAction("remove", at: 0)
        assertQueueRow(0, song: "Restore check B")
        assertQueueRow(1, song: "Restore check C")
        XCTAssertFalse(element("music-queue-row-2").exists)
        closeQueueEditor()
        assertPaused(position: "0:42")
        capture("queue-remove-earlier-keeps-current")
    }

    func testQueueCurrentRemovalSelectsNeighbourAndKeepsLastSong() {
        launchQueueEditor()
        queueAction("remove", at: 1)
        assertQueueRow(0, song: "Restore check A")
        assertQueueRow(1, song: "Restore check C")
        closeQueueEditor()
        assertPaused(position: "0:00", song: "Restore check C")
        element("music-mini-player").tap()
        element("music-player-queue").tap()
        queueAction("remove", at: 1)
        assertQueueRow(0, song: "Restore check A")
        XCTAssertFalse(element("music-queue-row-1").exists)
        element("music-queue-actions-0").tap()
        for action in ["up", "down", "remove"] {
            let item = element("music-queue-\(action)-0")
            waitFor(item)
            XCTAssertFalse(item.isEnabled, "Single-item action must be disabled: \(action)")
        }
        capture("queue-last-song-protected")
    }

    func testRepeatAndShuffleControlsRemainIndependent() {
        launchQueueEditor()
        let repeatButton = element("music-queue-repeat")
        waitFor(repeatButton)
        XCTAssertTrue(repeatButton.label.contains("全部循环"))
        repeatButton.tap()
        let shuffle = element("music-queue-shuffle")
        waitFor(shuffle)
        XCTAssertTrue(element("music-repeat-mode-label").label.contains("单曲循环"))
        XCTAssertTrue(element("music-shuffle-mode-label").label.contains("随机关闭"))
        shuffle.tap()
        XCTAssertTrue(element("music-repeat-mode-label").label.contains("单曲循环"))
        XCTAssertTrue(element("music-shuffle-mode-label").label.contains("随机开启"))
        repeatButton.tap()
        XCTAssertTrue(element("music-repeat-mode-label").label.contains("循环关闭"))
        XCTAssertTrue(element("music-shuffle-mode-label").label.contains("随机开启"))
        repeatButton.tap()
        XCTAssertTrue(element("music-repeat-mode-label").label.contains("全部循环"))
        XCTAssertTrue(element("music-shuffle-mode-label").label.contains("随机开启"))
        capture("repeat-and-shuffle-independent")
    }

    func testRomanizationIsEnabledByDefaultAndIndependentFromTranslation() {
        app.launch()
        musicTab()
        let romanize = element("music-romanize-lyrics")
        let translate = element("music-translate-lyrics")
        scrollTo(romanize)
        assertSelected(romanize, true)
        assertSelected(translate, true)
        romanize.tap()
        assertSelected(romanize, false)
        assertSelected(translate, true)
        capture("romanization-independent-default-on")
    }

    func testPlaybackPreferencesPersistAndTranslationDefaultsEnabled() {
        app.launch()
        musicTab()
        assertSelected(element("music-loop-playback"), true)
        assertSelected(element("music-always-previous"), true)
        setToggle("music-loop-playback", false)
        setToggle("music-always-previous", false)
        let translation = element("music-translate-lyrics")
        scrollTo(translation)
        assertSelected(translation, true)
        translation.tap()
        assertSelected(translation, false)
        app.terminate()
        app.launch()
        musicTab()
        assertSelected(element("music-loop-playback"), false)
        assertSelected(element("music-always-previous"), false)
        scrollTo(element("music-translate-lyrics"))
        assertSelected(element("music-translate-lyrics"), false)
        let romanize = element("music-romanize-lyrics")
        scrollTo(romanize)
        assertSelected(romanize, true)
        romanize.tap()
        assertSelected(romanize, false)
        app.terminate()
        app.launch()
        musicTab()
        scrollTo(element("music-romanize-lyrics"))
        assertSelected(element("music-romanize-lyrics"), false)
        capture("loop-previous-translation-romanization-preferences-persisted")
    }
}
