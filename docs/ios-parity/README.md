# iOS parity task book — Android stable v0.9.41

## Decisions and immutable baseline
- Baseline: tag `v0.9.41`, commit `7dbd5e6f21ed82ce122d32370eca257f0c1d9dc9`; APK 54,154,884 bytes, SHA-256 `159132c9c78bd6e60be237b9e1009cb0b9bbb04fe8a1500fb31b283f4e8d489f`.
- Start: default `ios` commit `1dbc2d4b8818d5b0a9ba012bb4777a7615211216`; clean branch `fix/ios-parity-v0941-20261125`.
- All user-visible Android abilities require usable iOS-native equivalents. Native UX and independent version sequences are allowed. No reduction to version-number or screenshot parity.
- No test account/sandbox is available. Do not submit real service writes. Implement to the submission boundary and record blocked acceptance.
- User will re-sign/install the unsigned IPA and provide a physical iPhone. Physical, live account and service flows not tested remain incomplete.
- Only after full acceptance: PR to default `ios`, normal merge, new iOS prerelease; no force-push to default.

## Coverage and status
`capabilities.json` keeps routes, behavior and preference discovery separately. Navigation arguments and non-shipping beta routes are not feature completions. A key or route can reveal a missing ability but cannot establish that an implementation works. Every screen/settings family must be expanded into its actual actions during its implementation batch. New evidence adds rows, never deletes inconvenient gaps.

No item is accepted yet. Missing items include real DSP equalization, script-source runtime, MCP and native widget families. Other source-present pages still require action-by-action verification. The existing equalizer explanation is explicitly not an implementation.

## Execution batches
1. Playback restore and normal music entry: keep queue/position paused at startup; preference defaults on; reject non-finite persisted positions; explicit play resumes; My → Music uses normal route and native Back. Local JVM suite passed. Native run `45518859583` compiled the Kotlin/Native app and XCTest target but all first three UI cases failed at the harness reading switch `value` as `"1"`; the xcresult accessibility tree showed `Selected` with an empty value. The harness now checks `isSelected` and the next CI run is pending; simulator behavior has not passed yet. Physical checks remain pending.
2. Host/platform: route/action audit, account/session, campus read workflows, image/avatar, camera/QR, files/share/calendar/reminders, GPS/background, theme/accessibility, correct iOS update channel. Service writes remain blocked without sandbox.
3. Music catalog/account: source management, multi-source search/browse, library and playlists, account/security/QR, messages, comments/wiki, recognition and listen-together. Implement real actions; live checks require proper test account.
4. Music playback/platform: DSP, modes/queue, lyrics, local files, download/storage, audio output/background, widgets, MCP, settings. Native constraints require usable equivalents, not placeholder copy.
5. Every batch: exact-source checks, JVM tests, native macOS CI, simulator flows, package fixture scan, independently verified unsigned IPA; real iPhone/account acceptance remains a separate gate.
6. Keep fixing through full ledger closure. Do not call a partial build “all aligned”. No full release/merge while required rows remain unaccepted.

## First batch evidence and next acceptance
- JVM report directory: `shared/build/test-results/jvmTest`; 32 tests, zero failures/errors/skips, initial run after playback changes. Re-run after the normal route and accessibility-tag changes also passed (27 seconds).
- Evidence log: `D:/gongzuoqu/andaxin-work/tmp/ios-parity-v0941-20261125/playback-restore/gradle-test-native-tags.log`.
- Device checks: launch after active playback must remain silent/paused; queue/index/position restored; explicit play resumes; preference off starts at zero after relaunch; retain settings across restart; use native Back from My → Music; exercise both the Music tab and pushed music page without duplicate player state.
- Native test target: `iosApp/UITests/MusicParityTests.swift`; eight tests cover My → Music → native Back, paused restore/relaunch and switch persistence, queue reorder/removal, repeat-vs-shuffle independence, and playback preferences. Each case runs from a fresh simulator app install; no real audio or service writes occur. Test queues are injected into simulator launch preferences; no remote playback or service submission is performed.
- The simulator assertions are not physical playback, persisted real-audio, or full-parity acceptance. The initial iOS candidate is 0.9.35 (187). Native compilation passed on run `45518859583`, but its 3 UI cases failed due to the XCTest value-vs-selected observation; corrected tests are awaiting a fresh native run.
- No service writes were submitted. No iPhone or new native CI evidence yet.

## Queue editing batch — implemented, not accepted
- Added an ordinary queue-row menu with up/down/remove actions. Current selection follows its queue occurrence, not the first matching song ID; the final item cannot be removed.
- Shared helpers preserve the active item through every reorder; removing an earlier item keeps position, while removing the current item resets position and selects the next item (or preceding item at the end). Existing playing-state continuation is retained but has not been audio-verified.
- Seven new `MusicQueueEditTest` cases cover last-item/invalid guards, index transitions, duplicate IDs, all valid moves and persisted paused restore. JVM suite: 39 tests, zero failures/errors/skips; log `D:/gongzuoqu/andaxin-work/tmp/ios-parity-v0941-20261125/queue-edit/gradle-test.log`.
- Three additional native tests exercise up/down and saved order after relaunch, removal before current, current removal and disabled single-item actions. Native execution is still pending; this does not establish actual audio behavior or full parity.


## Playback-mode batch — local behavior fixed, native pending
- Separated repeat mode from shuffle and restored Android's independent loop/always-previous preferences. Repeat control now follows Android queue order: off → all → one → off; shuffle remains independent. Media3 1.11.1 bytecode verifies its 3-second previous-track threshold and one-repeat behavior for manual navigation.
- iOS UITests now assert native `XCUIElement.isSelected` for Compose switches/chips rather than reading an empty `value`; the failed first native run's accessibility tree shows the restore switch with the `Selected` trait, while its `value` is empty. This corrected a test-observation error, not yet a passing native result.
- CI runs each of the eight UI tests in a fresh app install and stores a separate `.xcresult`, so persisted NSUserDefaults cannot leak across test cases. Native rerun still pending.
- JVM music suite: 48 tests, zero failures/errors/skips after repeat ordering update. `MusicPlaybackModesTest` covers legacy migration, independent repeat/shuffle cycles, 3-second boundary, invalid positions, manual/automatic single-repeat semantics, stable shuffle navigation and bounds.
- Remaining audio output/physical iPhone checks are not satisfied by simulator tests. No account/service writes were submitted.

## Queue identity and translated-lyrics batch — pending native verification
- All coroutine playback entry points capture a unique queue-occurrence key and request token before dispatch. Duplicate song IDs no longer force the first matching occurrence after URL resolution; removed/replaced targets cannot fall back to another occurrence.
- Translation preference now drives real text rendering below the original lyric, including seek actions. Standard NetEase YRC parsing preserves per-character word starts; unusable rich payloads fall back to usable LRC.
- Latest exact-source JVM suite: 61 tests, zero failures/errors/skips. External log: `D:/gongzuoqu/andaxin-work/tmp/ios-parity-v0941-20261125/queue-translation/gradle-test.log`.
- Isolated lyric fixture expanded from three to five cases with translation shown/hidden. All replacement anchors were checked against LF-normalized candidate sources (Git archive is LF; untouched Windows files can be CRLF). Full committed-source dry-run follows before push.
- Native build/UI, physical audio, live services, and full-format lyric parity remain unaccepted. This batch does not implement all remaining lyric capabilities or claim full app parity.
## Romanization parity batch — pending native verification
- `romalrc` is parsed as phonetic data rather than reused as an original-lyric fallback. Nearest pairs are matched within 1000 ms, one-to-one, and preserve timing/word times/translation.
- The iOS default is romanization on, stored independently of translation and exposed through a dedicated seekable text row above the translation.
- JVM coverage includes timestamp boundary, non-reuse, malformed-safe behavior, independent visibility, YRC preservation and default-on ordering. Isolated lyrics fixtures add romanized-on/romanized-off render cases; XCUITest adds default/independence/persistence checks.
- This batch is pending native compile/UI evidence. Broader lyric format capabilities and physical/live-service acceptance remain unaccepted.

## 音乐页顺序与可见性批次 — 待原生验证
- 新增纯 JVM `moveMusicTabOrder`：上下箭头按目标索引移动单个音乐页标签；越界钳到两端，重复/空白/不存在/原地移动返回 null 不改状态。
- 设置页列出全部页面并提供每页独立 testTag 的上移/下移按钮；下载/云盘/历史可见性开关保留且与顺序分开持久化。
- JVM 测试覆盖上移、下移、越界、无效输入与连续移动组合；XCUITest 覆盖“移动后重启仍生效”和可见性开关往返。原生执行尚未开始，本批不计入已验收。
