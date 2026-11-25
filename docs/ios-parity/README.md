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
1. Playback restore and normal music entry: keep queue/position paused at startup; preference defaults on; reject non-finite persisted positions; explicit play resumes; My → Music uses normal route and native Back. Local JVM music suite: 32 tests pass, including 7 new restore tests. Native compile/simulator/physical checks pending.
2. Host/platform: route/action audit, account/session, campus read workflows, image/avatar, camera/QR, files/share/calendar/reminders, GPS/background, theme/accessibility, correct iOS update channel. Service writes remain blocked without sandbox.
3. Music catalog/account: source management, multi-source search/browse, library and playlists, account/security/QR, messages, comments/wiki, recognition and listen-together. Implement real actions; live checks require proper test account.
4. Music playback/platform: DSP, modes/queue, lyrics, local files, download/storage, audio output/background, widgets, MCP, settings. Native constraints require usable equivalents, not placeholder copy.
5. Every batch: exact-source checks, JVM tests, native macOS CI, simulator flows, package fixture scan, independently verified unsigned IPA; real iPhone/account acceptance remains a separate gate.
6. Keep fixing through full ledger closure. Do not call a partial build “all aligned”. No full release/merge while required rows remain unaccepted.

## First batch evidence and next acceptance
- JVM report directory: `shared/build/test-results/jvmTest`; 32 tests, zero failures/errors/skips, initial run after playback changes. Re-run after the normal route and accessibility-tag changes also passed (27 seconds).
- Evidence log: `D:/gongzuoqu/andaxin-work/tmp/ios-parity-v0941-20261125/playback-restore/gradle-test-native-tags.log`.
- Device checks: launch after active playback must remain silent/paused; queue/index/position restored; explicit play resumes; preference off starts at zero after relaunch; retain settings across restart; use native Back from My → Music; exercise both the Music tab and pushed music page without duplicate player state.
- Native test target: `iosApp/UITests/MusicParityTests.swift`; three tests exercise My → Music → native Back, the legacy playing flag across relaunches, and the persisted restore-position switch. Test queues are injected into simulator launch preferences; no remote playback or service submission is performed.
- The simulator assertions are not physical playback, persisted real-audio, or full-parity acceptance. The initial iOS candidate is 0.9.35 (187); macOS compilation and the new UI suite are still pending.
- No service writes were submitted. No iPhone or new native CI evidence yet.
