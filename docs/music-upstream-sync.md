# 音乐模块上游同步记录

## 2026-11-25

用户要求将内置音乐模块同步到 MeiloX 最新源码。本次固定源码提交后进行同步，避免构建随远端分支漂移。

| 项目 | 仓库 | 本次固定提交 |
| --- | --- | --- |
| MeiloX | `https://github.com/MeiloX/MeiloX` | `23f2cc7d818294df25f90a55fa1b99c500b5d026` |
| Glasmio | `https://github.com/NEORUAA/Glasmio` | `530faa08fb2a7723b71f1a16c16c5e3a62e3cce5` |

MeiloX 最终固定提交时间为 2026-11-25 14:20:36 UTC；Glasmio 提交时间为 2026-11-25 09:00:04 UTC。初轮同步固定在 `a64b25e70e34e12ce04ce340897ad0898a8d2a80`，收尾复核时发现 `main` 已推进，追加同步了两笔修复。这里的“最新”指本次查询时的 `main` 源码，不是较早发布的 1.55.1 Release。

### 同步范围

- MeiloX 从上次同步的 `cd6a6dcf7f0a73b3965111706d7d7c0add506c75` 更新到上述提交，共 12 个提交、15 个上游文件。
- 上游 `app/src/main/` 的 14 个文件映射至本地 `music/src/main/`；其余 `MainActivity.kt` 改动移植到宿主专用 `MusicScreen.kt`。
- 同步标签页访问状态保留、页面共享背景、半分辨率卡片模糊、播放器封面与菜单渲染优化、播放列表定位和数据源偏好预读。
- 新导航接口依赖 Glasmio 源码；上游自身的 CI 也检出 Glasmio `main`。本地继续采用随仓携带的源码，固定到上述提交，不改成构建时拉取。
- Glasmio 三方合并参考基线为 `1360306181af7ac4002a9ab1ca12d759d7cd105d`。以本地文件 blob 对比历史提交，203 个文件与该基线完全一致；保留其余宿主定制。更新 35 个库源码文件，不引入独立 sample 应用及其设备测试工程。

### 保留的宿主行为

- `:music` 继续作为 Android library，由安大信提供 Activity、应用生命周期与入口；不恢复独立应用启动器或模块更新页面。
- 保留宿主下载入口、登录和深链接桥接。
- `PlayerSheetRenderer.kt` 保留宿主胶囊到播放器的坐标适配。
- `SourceBrowseScreen.kt` 保留内容标题最多两行。
- Glasmio 保留外侧搜索圆钮的选中态交接、底栏图标颜色与尺寸过渡、玻璃设备兼容性处理。
- 宿主入口在创建音乐内容前读取并预置数据源偏好；保活的一级页面保留 ViewModel，离开整个音乐内容时统一清理。
- 页面隐藏时停止放置底栏与定位控件，完整播放器保持独立渲染；保留歌词页请求和宿主封面展开入口。

### 验证

- `:app:assembleStableDebug` 通过，包含音乐 Kotlin、Hilt、Glasmio 和宿主打包；第二轮构建用时 2 分 58 秒。
- 强制重跑 `:shared:jvmTest --rerun`：21 个测试套件、134 项测试，失败、错误、跳过均为 0。
- 强制重跑 `:app:testStableDebugUnitTest --rerun`：6 项测试，失败、错误、跳过均为 0；两组测试合并运行用时 23 秒。
- `:shared:compileKotlinIosSimulatorArm64` 任务通过，本次复用此前已通过的编译结果；不将其计作模拟器运行或设备验收。
- 同步提交时逐文件核对上游：44 个文件完全一致，5 个文件仅保留已检查的宿主差异；`MusicScreen.kt` 单独检查适配差异并通过宿主编译。没有缺失文件或遗留合并标记。
- `git diff --check` 通过。首次宿主编译发现漏导入 `GlasmioNavigationPhase`，补齐后重编通过。
- 生产输入守卫仅增加 `music/.cxx` 和 `glasmio/glasmio-glass/.cxx` 两个原生构建输出目录。隔离 Git 仓库验证确认：这两个 CMake 缓存可通过，其他未提交源码仍会被拒绝。
- 同步提交时生成的 APK（音量条改动前）：`app/build/outputs/apk/stable/debug/app-stable-debug.apk`，98,359,811 字节，SHA-256 `abf1f56b3045152492517ee9ada5c4e50891d5351ed5a8e41cba0ad32bac87b9`。
- 同步提交验证时，`adb devices -l` 没有已连接设备。设备上的动画、音频和账号流程仍待验证，构建与单元测试不替代这些验收。

本次同步不改变 iOS 功能验收的固定 Android v0.9.41 基线，也不表示 `shared` 中的 iOS 独立音乐实现已自动获得所有上游能力。iOS 对齐账本中的待验证、缺失及账号/设备阻塞项继续保持未验收状态。未执行真实服务写入，未合并默认分支，未发布版本。

### 播放器音量条

按用户提供的 Apple Music 参考图，在上一首／播放／下一首下方、底部工具栏上方接入现有 `GlasmioVolumeSlider`，封面、歌词与队列页面共用。没有修改 UI 库控件实现。

- 调整 `AudioManager.STREAM_MUSIC` 系统媒体音量，不改播放器单曲增益；按设备最小／最大档位换算并禁用固定音量设备上的拖动。
- 监听系统设置变化、音频设备变化及回到前台，刷新显示；组件离开组合时移除监听。
- 为新增的 52dp 控件和间距调整初始底部高度预留；后续仍使用实际测量高度。提供简体中文、繁体中文与英文无障碍名称。
- 音量条改动后的 `:app:assembleStableDebug :app:testStableDebugUnitTest` 通过，用时 1 分 34 秒；这是编译与宿主回归证据，未计作设备音量或声音验收。
- 在独立、无账号的 Android 14 AVD 中安装成功。前两轮模拟器进程分别在 SwiftShader 与 ANGLE 后端退出 139，尚未完成播放页位置、拖动及实体键同步的运行验证。故障日志保留在本地证据目录，不能将首次启动截图计作音量条截图。

### iOS 原生失败记录

旧运行 `45627514269`（提交 `eefd3dcac5263b2dce2e3457a8d5870d8c35d767`）在音乐 UI 检查失败，已追加到 `docs/ios-parity/native-test-history.json`。修正滚动方向与可用区域检查、单次上移的预期顺序，并改为比较实际均衡器滑杆增益是否在重启后保留；等待新的 macOS 原生运行验证。未将测试源码修正计作通过。

### 收尾追加同步到 `23f2cc7d`

- 补入 `fc6896631`（失效封面引用恢复）与 `23f2cc7d8`（限制 HDR 加色亮度）。本轮比较包含 24 个上游文件；22 个生产文件映射到音乐库，另 1 个 `MainActivity.kt` 改动移植到宿主 `MusicScreen.kt`。
- 保留音量条及其底部高度预留、胶囊坐标适配、数据源未登录探测保护和标题两行显示。22 个直接映射的生产文件中，17 个与本轮上游完全一致，5 个保留已复核的本地适配。
- 上游独立应用的 `ScriptPluginInstrumentedTest.kt` 变更保存在证据目录。本地嵌入库没有该仪器测试工程及其 Gradle 依赖，未伪装成已导入或已运行的测试。
- 第三轮改用 Windows 原生进程启动模拟器，随后同样失联；音量条位置、手势、硬件键同步仍未完成运行验收。保留安装成功及三轮故障证据，不以构建替代实测。

### 最终源码构建验证

- `:app:assembleStableDebug :app:testStableDebugUnitTest --rerun :shared:jvmTest --rerun :shared:compileKotlinIosSimulatorArm64` 通过，耗时 2 分 23 秒。宿主 6 项、共享逻辑 134 项测试，失败／错误／跳过均为 0。iOS 编译任务复用已有结果，不计作原生执行。
- 调试构建 APK（不作为覆盖安装交付包）：`AndaXin-music-volume-20261125-debug.apk`，101,799,646 字节，SHA-256 `85bf84c5ddc729e537c40b28544dc38b54cd4f5725ed7d04ff78959f4d83b3f4`。这是 Android 调试测试包；音量交互和实际声音仍待设备检查。
- iOS 对齐账本仍为 279 项、0 项已验收。测试包上传采用 Release 草稿，不发布正式版；iOS 流水线继续使用 `publish=false`。

调试证书 SHA-256 与发布的 v0.9.41 不同，不能覆盖现有安装；已核对原发布密钥库证书与 v0.9.41 一致，另行构建相同发布签名的测试包。密钥与口令不进入仓库。

### 2026-11-25 签名测试包与关于页清理

- 源码提交：`eb3fb6b118f77fe8adc3f5483bd0091b669c916b`。删除 Android 与共享关于页中位于更新卡片下方的“边界：不代你提交申请……”说明，更新通道名称及更新操作保留。
- 本轮 `:app:testStableDebugUnitTest --rerun :shared:jvmTest --rerun :shared:compileKotlinIosSimulatorArm64 :app:assembleStableRelease` 通过，耗时 7 分 19 秒；宿主 6 项、共享逻辑 134 项单测，失败／错误／跳过均为 0。Windows 上的 iOS 编译不作为原生运行或界面验收。
- 签名测试 APK：`AndaXin-music-volume-20261125-eb3fb6b1-release-test.apk`，54,200,192 字节。SHA-256：`e0e13dac3c02249bf3c9ef7ebe4532dacef12c5d4ddf25be8d915b28ab72477d`。本地交付目录：`D:/gongzuoqu/交付`。
- 已通过 `apksigner verify`，证书 SHA-256：`40ee7baae7f57b53feb94f2cb0a47e210fec1e458073a2b6bbd2a551e5bcf9ef`，与已发布 v0.9.41 一致；包名 `com.andaxin`、版本 `0.9.41`、versionCode `180`、arm64。临时签名配置已清理，密钥和口令未进入仓库。实际覆盖安装仍待设备检查。
- 源码已通过 GitHub 的 SSH 443 端口推送，并核对远程分支提交。GitHub API 的直连 TLS 连接失败，本机既有代理端口未监听；APK 上传和新的 iOS 工作流派发尚未完成，不能表述为已推送测试包或已启动原生验证。
- 音量条拖动、硬件键同步和实际声音仍待设备验收；完整 iOS 对齐账本保持 279 项、0 项已验收。

### Android 0.9.42 正式发布

- 已发布正式版 `v0.9.42`（versionCode `181`），Release `458344385`，发布时间 `2026-11-25T16:28:37Z`；源码提交 `81414d62baded60748575b15718505ebd153ed90`，工作分支已推送。Android 发布不改变 iOS 对齐基线 v0.9.41。
- 正式附件 `app-stable-release.apk`：54,200,192 字节，SHA-256 `bc3557f0813f4c539cc9277e55084261ca0206e887a37d4196737e56ec484714`；发布证书与 v0.9.41 一致，另附 `.sha256` 和 `verification.json`。
- Release 构建耗时 4 分 42 秒；宿主 6 项、共享逻辑 134 项测试，失败／错误／跳过均为 0。音量拖动、硬件键同步与实际声音仍待设备验收。
- 远程三项附件的 GitHub digest 与本地一致；正式 tag 指向上述提交，latest 为 v0.9.42，公开更新订阅首项为 v0.9.42；公开 APK 下载返回 HTTP 206，ZIP 文件头与总大小均正确。
- 大文件直传两次遇到连接重置，每次重试前均确认草稿附件状态；限速重传成功后才将草稿转为正式版。没有全局代理变更、重复 Release 或密钥入库。
- iOS 原生验证已另行派发 run `45652802198`，`publish=false`，当前记录为 `in_progress`／`None`；不计作功能已验收，279 项账本未改。


## Android 0.9.43 publication — 2026-11-26

- Published stable [v0.9.43](https://github.com/linjianwuovo/andaxin-walkman/releases/tag/v0.9.43) at `2026-11-26T00:57:40Z` from `1eeed1e06ece93177589bf498f1554cc42a08c10`; version `0.9.43` / code `182`. This includes the reference-style thin volume track and speaker icons from `3206a35d46a1358d91eea0834451c76f54a341f4`.
- The [canonical APK](https://github.com/linjianwuovo/andaxin-walkman/releases/download/v0.9.43/app-stable-release.apk) is `54192444` bytes, SHA-256 `ab7e155d24a0f7f19d608cafa27ffabfd63bc73c7b4d14f730a17cd1b1f987cd`. APK, checksum and verification JSON were uploaded and independently matched against GitHub asset sizes and SHA-256 digests before publication.
- Original release signing certificate `40ee7baae7f57b53feb94f2cb0a47e210fec1e458073a2b6bbd2a551e5bcf9ef` matches. The fresh build completed successfully; host `6` and shared `134` tests passed with no failures, errors or skips.
- Public delivery verification: releases Atom HTTP `200`, first entry `v0.9.43`; APK range request HTTP `206`, `bytes 0-63/54192444`, valid ZIP header. The release tag resolves to the exact source commit above.
- Native iOS build `188` remains separate: run [45730577539](https://github.com/linjianwuovo/andaxin-walkman/actions/runs/45730577539), source `3206a35d46a1358d91eea0834451c76f54a341f4`, `publish=false`. No iOS release or full parity acceptance is claimed. Physical volume dragging, hardware-key synchronization and listening remain unverified.

## 2026-11-26 — Android 0.9.44 relative volume release

- Published Android `0.9.44 (183)` from `6592bfc4caf866940430274edfc70be2dce49c05`; canonical asset `app-stable-release.apk`, 54190968 bytes, SHA-256 `8ba3f9a117a8f40262865c6d09d39e28a7e582f91bffdcf51f227546f068bee2`. Published assets, tag source and latest release were independently verified.
- Volume now starts from its current system value and accumulates horizontal drag deltas, including small deltas across quantized volume steps. Touch-down does not map position to volume. Clamping permits immediate reversal at either endpoint. Both platforms use a 6dp/pt track with the reference speaker icons.
- Original release certificate `40ee7baae7f57b53feb94f2cb0a47e210fec1e458073a2b6bbd2a551e5bcf9ef` and package/version/arm64 identity passed verification; 6 host and 134 shared tests passed with no failures, errors or skips. Twenty checks of the exact production accumulator helpers passed. Physical touch routing, hardware-key sync and actual audio remain unverified.
- Public update Atom returned `v0.9.44` first; canonical APK returned HTTP 206 with the expected ZIP header and total size. Source tests/build verification and physical-device limitations are included in the release verification asset.
- iOS `0.9.35 (189)` uses the same relative control and is being validated separately in run `45738127430`, `publish=false`; no new IPA or full-parity acceptance is claimed here.
- Earlier iOS run `45730577539` (build 188) timed out at the music UI step's 25-minute aggregate limit after all twelve individual cases reported zero failures (36 aggregate summary lines). Lyrics and IPA packaging were skipped. Only that aggregate budget was increased to 40 minutes; all twelve cases and failure propagation remain mandatory.

## 2026-11-26 — Android 0.9.45 animated volume release

- Published Android `0.9.45 (184)` from `c8213c26e5a8a0d4a4ff6a71c91d412b2969bd4e`; canonical asset `app-stable-release.apk`, 54199044 bytes, SHA-256 `e6f7802e8a3595fe3a80ea8f88f44d95cd6c8266b6a684c6a069c98f846b4874`. Original signing certificate, remote asset digests, tag source, latest release, public Atom order and HTTP 206 APK range response passed independent verification.
- Both platform controls retain relative horizontal dragging and the 6dp/pt idle track. Press feedback grows the track toward 16dp/pt and 1.05 times width, brightens/enlarges the speaker icons, and adds bounded endpoint stretch/translation with spring release. The visual overflow does not delay immediate reverse movement of the clamped system volume.
- Android and both iOS Kotlin targets compiled; 6 host and 134 shared tests passed with no failures, errors or skips. Thirty-six checks of the exact production Kotlin accumulators passed, including small-delta accumulation, upper/lower limits, immediate reverse, bounded visual overflow and new-gesture reset. No Android device is connected for actual touch/hardware/audio verification.
- iOS `0.9.35 (189)` run `45738127430` passed all twelve native music UI cases, seven lyrics captures and unsigned device packaging. Verified IPA: 17283471 bytes, SHA-256 `aebfee290798245c09da0f8e882ac9d1d62d5830688d15a66a97456dcfccbb1d`; this package has relative dragging and a thicker track, before the new animation.
- Animated iOS build `190` has separate run `45744871942`, `publish=false`. Its native gates and package must complete before delivery; no formal iOS publication or complete parity acceptance is claimed.
- Parity audit confirmed that device-audio import is absent from the inspected iOS entry points, while the fixed Android baseline has a real MediaStore scanner. The discovery ledger now records this as missing rather than unaudited. It still contains 279 items and zero accepted items; widgets, local MCP, script sources and physical/sandbox checks remain open.
