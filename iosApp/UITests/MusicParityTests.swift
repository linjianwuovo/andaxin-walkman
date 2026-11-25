import XCTest

final class MusicParityTests: XCTestCase {
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication(bundleIdentifier: "com.andaxin.ios")
        app.launchArguments = [
            "-AndaXinGuest",
            "-music.queueSeed", "1\\tRestore check A\\tTest artist\\tTest album\\t\\n2\\tRestore check B\\tTest artist\\tTest album\\t",
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

    private func openSettingsTab() {
        // setUp 启动参数已把音乐页选中在“设置”，这里只需把顺序区滚进视野。
        scrollTo(element("music-tab-order-row-0"))
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
        for _ in 0..<16 {
            if hittableCenter(control) != nil { return }
            let above = control.exists && control.frame.midY < app.frame.minY + 140
            let upper = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.35))
            let lower = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.60))
            if above {
                upper.press(forDuration: 0.05, thenDragTo: lower)
            } else {
                lower.press(forDuration: 0.05, thenDragTo: upper)
            }
        }
        waitFor(control)
        XCTAssertNotNil(hittableCenter(control), "Still outside the usable viewport: \(control.debugDescription)")
    }

    /**
     * 那颗控件的中心真的在屏幕上、没被底栏盖住时返回它的中点。
     *
     * 上一发 "Failed to not hittable: music-tab-order-up-播客 {{285,669}...}" 就是这么来的：
     * row 自己露头了、isHittable 却是 false —— 40pt 的箭头整个压在悬浮底栏底下，
     * 中心点落在屏幕外/栏后。所以"看得见"要按**中心点在可用区内**判，不能只看 exists。
     */
    private func hittableCenter(_ control: XCUIElement) -> CGPoint? {
        guard control.exists else { return nil }
        let frame = control.frame
        guard frame.width > 0, frame.height > 0 else { return nil }
        let center = CGPoint(x: frame.midX, y: frame.midY)
        let screen = app.frame
        // 底部预留 170pt：悬浮底栏约 83 + 迷你播放器约 76（带内边距），中心点必须整颗露在这两样之上。
        guard screen.contains(center), center.y >= screen.minY + 140, center.y < screen.maxY - 170 else { return nil }
        return control.isHittable ? center : nil
    }

    private func scrollPresetIntoView(_ preset: XCUIElement) {
        let firstPreset = element("music-eq-preset-Flat")
        scrollTo(firstPreset)
        let screen = app.frame
        let origin = app.coordinate(withNormalizedOffset: CGVector(dx: 0, dy: 0))
        let y = firstPreset.frame.midY - screen.minY
        let start = origin.withOffset(CGVector(dx: screen.width * 0.85, dy: y))
        let end = origin.withOffset(CGVector(dx: screen.width * 0.15, dy: y))
        for _ in 0..<8 {
            if hittableCenter(preset) != nil { return }
            start.press(forDuration: 0.05, thenDragTo: end)
        }
        XCTAssertNotNil(hittableCenter(preset), "Preset remains outside the horizontal viewport")
    }

    private func assertPageOrder(_ labels: [String]) {
        for (index, label) in labels.enumerated() {
            let row = element("music-tab-order-row-\(index)")
            scrollTo(row)
            // Compose exposes the title beside the row, rather than as its AX child.
            let matchesRow = NSPredicate { _, _ in
                let bounds = row.frame
                return self.app.staticTexts.matching(identifier: label).allElementsBoundByIndex.contains { title in
                    let frame = title.frame
                    return bounds.contains(CGPoint(x: frame.midX, y: frame.midY))
                }
            }
            let result = XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: matchesRow, object: row)], timeout: 5)
            XCTAssertEqual(result, .completed, "Unexpected page at index \(index): \(row.debugDescription)")
        }
    }

    private func assertPaused(position: String, song: String = "Restore check B") {
        let player = element("music-mini-player")
        waitFor(player)
        XCTAssertTrue(player.label.contains(song), "Restored the wrong song: \(player.label) | tree: \(app.descendants(matching: .any).matching(identifier: "music-mini-player").count) mini players")
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
        let seedArgument = app.launchArguments.firstIndex(of: "-music.queueSeed")!
        app.launchArguments[seedArgument + 1] += "\\n3\\tRestore check C\\tTest artist\\tTest album\\t"
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
        for key in ["-music.queueSeed", "-music.index"] {
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

    func testMusicPageReorderControlsPersistAcrossRelaunch() {
        app.launch()
        musicTab()
        openSettingsTab()
        // 箭头那颗要单独滚进视野再点：row 露出来了不代表 40pt 的按钮也没被底栏压住。
        let upPodcast = element("music-tab-order-up-播客")
        scrollTo(upPodcast)
        guard let center = hittableCenter(upPodcast) else {
            XCTFail("Page reorder arrow is not visible")
            return
        }
        let screen = app.frame
        app.coordinate(withNormalizedOffset: CGVector(dx: 0, dy: 0))
            .withOffset(CGVector(dx: center.x - screen.minX, dy: center.y - screen.minY)).tap()
        assertPageOrder(["首页", "播客", "发现"])
        app.terminate()
        app.launch()
        musicTab()
        openSettingsTab()
        assertPageOrder(["首页", "播客", "发现"])
        capture("music-page-order-persisted")
    }

    func testMusicPageVisibilityControlsToggle() {
        app.launch()
        musicTab()
        openSettingsTab()
        let historyChip = element("music-tab-visible-历史")
        scrollTo(historyChip)
        assertSelected(historyChip, false)
        historyChip.tap()
        assertSelected(historyChip, true)
        historyChip.tap()
        assertSelected(historyChip, false)
        capture("music-page-visibility-toggle")
    }

    /// 均衡器：开关能拨、预设能选、拖过滑杆之后自动变「自定义」、并且重启后还在。
    /// 这一条证明的是**控件与持久化**；"声音真的变了"只能在真机上听，见账本里的说明。
    func testEqualizerSettingsPersistAcrossRelaunch() {
        app.launch()
        musicTab()
        openSettingsTab()
        let toggle = element("music-eq-switch")
        scrollTo(toggle)
        if toggle.isSelected { toggle.tap() }
        assertSelected(toggle, false)
        // 打开之后选「摇滚」
        toggle.tap()
        assertSelected(toggle, true)
        let rock = element("music-eq-preset-Rock")
        scrollPresetIntoView(rock)
        rock.tap()
        // 拖第 0 段：预设应当自己变成「自定义」
        let band0 = element("music-eq-band-0")
        scrollTo(band0)
        let originalGain = band0.value as? String
        band0.adjust(toNormalizedSliderPosition: 0.85)
        guard let savedGain = band0.value as? String, !savedGain.isEmpty else {
            XCTFail("均衡器滑杆没有可读取的增益")
            return
        }
        XCTAssertNotEqual(savedGain, originalGain, "拖动没有改变增益")
        capture("music-equalizer-rock-band0")
        app.terminate()
        app.launch()
        musicTab()
        openSettingsTab()
        let toggle2 = element("music-eq-switch")
        scrollTo(toggle2)
        assertSelected(toggle2, true)
        let restoredBand = element("music-eq-band-0")
        scrollTo(restoredBand)
        XCTAssertEqual(restoredBand.value as? String, savedGain, "重启后增益没有保留")
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
