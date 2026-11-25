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

    private func setRestorePosition(_ enabled: Bool) {
        let toggle = element("music-restore-position")
        waitFor(toggle)
        XCTAssertTrue(toggle.isHittable)
        let expected = enabled ? "1" : "0"
        if (toggle.value as? String) != expected {
            toggle.tap()
        }
        let state = NSPredicate(format: "value == %@", expected)
        let result = XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: state, object: toggle)], timeout: 5)
        XCTAssertEqual(result, .completed, "Restore-position switch did not reach \(expected)")
    }

    private func assertPaused(position: String) {
        let player = element("music-mini-player")
        waitFor(player)
        XCTAssertTrue(player.label.contains("Restore check B"), "Restored the wrong song: \(player.label)")
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
        XCTAssertEqual(toggle.value as? String, "0")
        assertPaused(position: "0:00")
        capture("restore-disabled-zero-position")
        setRestorePosition(true)
    }
}
