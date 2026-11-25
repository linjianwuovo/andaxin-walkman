# 音乐模块上游同步记录

## 2026-11-25

用户要求将内置音乐模块同步到 MeiloX 最新源码。本次固定源码提交后进行同步，避免构建随远端分支漂移。

| 项目 | 仓库 | 本次固定提交 |
| --- | --- | --- |
| MeiloX | `https://github.com/MeiloX/MeiloX` | `a64b25e70e34e12ce04ce340897ad0898a8d2a80` |
| Glasmio | `https://github.com/NEORUAA/Glasmio` | `530faa08fb2a7723b71f1a16c16c5e3a62e3cce5` |

MeiloX 提交时间为 2026-11-25 07:15:05 UTC；Glasmio 提交时间为 2026-11-25 09:00:04 UTC。同步期间再次查询 MeiloX `main`，结果仍为上述提交。这里的“最新”指本次查询时的 `main` 源码，不是较早发布的 1.55.1 Release。

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
- 逐文件核对上游：44 个文件完全一致，5 个文件仅保留已检查的宿主差异；`MusicScreen.kt` 单独检查适配差异并通过宿主编译。没有缺失文件或遗留合并标记。
- `git diff --check` 通过。首次宿主编译发现漏导入 `GlasmioNavigationPhase`，补齐后重编通过。
- 生产输入守卫仅增加 `music/.cxx` 和 `glasmio/glasmio-glass/.cxx` 两个原生构建输出目录。隔离 Git 仓库验证确认：这两个 CMake 缓存可通过，其他未提交源码仍会被拒绝。
- 本地 APK：`app/build/outputs/apk/stable/debug/app-stable-debug.apk`，98,359,811 字节，SHA-256 `abf1f56b3045152492517ee9ada5c4e50891d5351ed5a8e41cba0ad32bac87b9`。
- `adb devices -l` 没有已连接设备。设备上的动画、音频和账号流程仍待验证，构建与单元测试不替代这些验收。

本次同步不改变 iOS 功能验收的固定 Android v0.9.41 基线，也不表示 `shared` 中的 iOS 独立音乐实现已自动获得所有上游能力。iOS 对齐账本中的待验证、缺失及账号/设备阻塞项继续保持未验收状态。未执行真实服务写入，未合并默认分支，未发布版本。
