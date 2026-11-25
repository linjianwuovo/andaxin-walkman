#!/usr/bin/env python3
"""Build and capture iOS lyrics fixtures without modifying the production checkout."""

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import plistlib
import re
import shutil
import signal
import stat
import struct
import subprocess
import sys
import tarfile
import tempfile
import time
import zipfile
import zlib


MARKER = "AX_IOS_LYRICS_VISUAL_V1"
COVER = f"{MARKER}:COVER"
BUNDLE = "com.andaxin.ios"
CASES = {
    "timed-cover": ("decoded", "timed"),
    "mixed-no-cover": ("none", "wrapped"),
    "completed-empty": ("none", "empty"),
    "translated-on": ("none", "translated"),
    "translated-off": ("none", "original"),
    "romanized-on": ("none", "phonetic"),
    "romanized-off": ("none", "phonetic-hidden"),
}
READY_SECONDS = 60
BUILD_SECONDS = 900
IOS = "shared/src/iosMain/kotlin/com/andaxin"
SCENE = "iosApp/Sources/SceneDelegate.swift"
SCREENS = f"{IOS}/shared/IosScreens.kt"
MUSIC = f"{IOS}/feature/music/MusicScreen.ios.kt"
CONTROLLER = f"{IOS}/feature/music/MusicController.ios.kt"
IMAGES = f"{IOS}/shared/IosImages.kt"
HTTP = f"{IOS}/core/net/HttpEngineKtor.kt"
OVERLAYS = (SCENE, SCREENS, MUSIC, CONTROLLER, IMAGES, HTTP)
SEED = "shared/build/bin/iosArm64/releaseFramework/shared.framework"
PLACEHOLDER = "shared/build/xcodeFrameworks/shared.framework"
PROJECT = "iosApp/AndaXin.xcodeproj"


class SmokeError(RuntimeError):
    pass


def require(condition, message):
    if not condition:
        raise SmokeError(message)


RUNTIME_PHASES = {"isolation", "simulator-details", "xcode-version", "framework-seed", "xcodegen", "xcodebuild",
                  "bundle-validation", "simulator-setup", "capture", "capture-cleanup", "simulator-cleanup"}
RUNTIME_OPERATIONS = {"source-isolation", "list-devices", "xcode-version", "framework-seed", "xcodegen", "xcodebuild",
                      "bundle-validation", "prepare", "launch", "readiness", "screenshot", "liveness", "png-validation",
                      "bootstatus", "uninstall", "install", "appearance", "status-bar-override", "status-bar-clear",
                      "terminate", "process-stop", "simulator-cleanup", "validation"}


def runtime_failure(exc, phase, operation):
    selected_phase = getattr(exc, "smoke_phase", phase)
    selected_operation = getattr(exc, "smoke_operation", operation)
    record = {"phase": selected_phase if isinstance(selected_phase, str) and selected_phase in RUNTIME_PHASES else "capture",
              "operation": selected_operation if isinstance(selected_operation, str) and selected_operation in RUNTIME_OPERATIONS else "validation"}
    code = getattr(exc, "returncode", None)
    has_code = type(code) is int and -(1 << 31) <= code < (1 << 32) and code != 0
    if isinstance(exc, subprocess.TimeoutExpired):
        kind = "timeout"
    elif isinstance(exc, OSError):
        kind = "os-error"
    elif has_code:
        kind = "command-exit"
    elif isinstance(exc, subprocess.SubprocessError):
        kind = "subprocess-error"
    elif isinstance(exc, ValueError):
        kind = "value-error"
    elif isinstance(exc, SmokeError):
        kind = "validation"
    else:
        kind = "unexpected-error"
    record["kind"] = kind
    if has_code:
        record["exit_code"] = code
    return record


def runtime_cleanup_failures(exc):
    errors = getattr(exc, "smoke_cleanup_errors", [])
    return [runtime_failure(error, "simulator-cleanup", "simulator-cleanup") for error in errors[:8]]


def cleanup_actions(actions, primary=None):
    errors = []
    for phase, operation, action in actions:
        try:
            action()
        except Exception as exc:
            nested = getattr(exc, "smoke_cleanup_errors", [])
            if nested:
                errors.extend(nested[:8])
            else:
                exc.smoke_phase, exc.smoke_operation = phase, operation
                errors.append(exc)
    if errors:
        existing = getattr(primary, "smoke_cleanup_errors", []) if primary is not None else []
        combined = []
        for error in (*existing, *errors):
            if not any(error is item for item in combined):
                combined.append(error)
        target = primary if primary is not None else errors[0]
        target.smoke_cleanup_errors = combined[:8]
        if primary is None:
            raise target


def digest(data):
    return hashlib.sha256(data).hexdigest()


def json_bytes(data):
    return (json.dumps(data, indent=2, sort_keys=True) + "\n").encode()


def png_chunk(kind, data):
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))


def cover_png():
    size = 48
    rows = bytearray()
    for y in range(size):
        rows.append(0)
        for x in range(size):
            rows.extend((232 - 3*x, 65 + 3*y, 215 - 2*y) if x + y < size else (25 + x, 93 + y, 190))
    return (b"\x89PNG\r\n\x1a\n" + png_chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 2, 0, 0, 0))
            + png_chunk(b"IDAT", zlib.compress(rows, 9)) + png_chunk(b"IEND", b""))


def patch_set(png):
    # Each edit must match exactly once in the actual candidate, never in a fallback UI.
    hex_png = png.hex()
    encoded = " +\n                ".join(f'"{hex_png[i:i+96]}"' for i in range(0, len(hex_png), 96))
    route_parser = r'''        if let i = CommandLine.arguments.firstIndex(of: "-AndaXinOpen"),
           i + 1 < CommandLine.arguments.count {
            pendingOpen = CommandLine.arguments[i + 1]
            NSLog("AndaXin 壳：带 -AndaXinOpen \(pendingOpen!)（CI 专用）")
        }
'''
    return {
        SCENE: [
            (route_parser, ""),
            ("    private var pendingOpen: String?\n", '''    private var pendingOpen: String?
    private var lyricsVisualKey: String?
'''),
            ("        guard let windowScene = scene as? UIWindowScene else { return }\n",
             "        guard let windowScene = scene as? UIWindowScene else { return }\n" + route_parser + f'''        if let key = pendingOpen, key.hasPrefix("{MARKER}:"),
           CommandLine.arguments.contains("-AndaXinGuest") {{
            lyricsVisualKey = key
            pendingOpen = nil
        }}
'''),
            ("        w.makeKeyAndVisible()\n", '''        w.makeKeyAndVisible()

        if let key = lyricsVisualKey {
            g.armIosLyricsVisualFixture(key: key)
            w.overrideUserInterfaceStyle = .light
            g.onGuest { [weak self] in self?.swap(toTabs: true, g) }
            g.browseAsGuest()
            return
        }
'''),
            ('''        let items: [(UIViewController, String, String)] = [
            (g.homeViewController(), "首页", "house"),
            (g.timetableViewController(), "课程表", "calendar"),
            (g.messageViewController(), "消息", "envelope"),
            (g.musicViewController(), "音乐", "music.note"),
            (g.meViewController(), "我的", "person.crop.circle"),
        ]
''', '''        // Keep the native tab shell; only the selected Music root is instantiated for the fixture.
        let live = lyricsVisualKey == nil
        let items: [(UIViewController, String, String)] = [
            (live ? g.homeViewController() : UIViewController(), "首页", "house"),
            (live ? g.timetableViewController() : UIViewController(), "课程表", "calendar"),
            (live ? g.messageViewController() : UIViewController(), "消息", "envelope"),
            (g.musicViewController(), "音乐", "music.note"),
            (live ? g.meViewController() : UIViewController(), "我的", "person.crop.circle"),
        ]
'''),
            ("        tabs.viewControllers = items.map { $0.0 }\n", '''        tabs.viewControllers = items.map { $0.0 }
        if !live { tabs.selectedIndex = 3 }
'''),
            ("        g.watchUnread { [weak self] text in\n", '''        if !live { return tabs }
        g.watchUnread { [weak self] text in
'''),
        ],
        SCREENS: [
            ("fun IosAppGraph.musicViewController(): UIViewController =\n", f'''private var lyricsVisualCase: String? = null

fun IosAppGraph.armIosLyricsVisualFixture(key: String) {{
    val case = key.removePrefix("{MARKER}:")
    require(key != case && case in setOf("timed-cover", "mixed-no-cover", "completed-empty", "translated-on", "translated-off", "romanized-on", "romanized-off"))
    lyricsVisualCase = case
}}

fun IosAppGraph.musicViewController(): UIViewController =
'''),
            ("ComposeUIViewController { IosTopInset { MusicScreen() } }",
             "ComposeUIViewController { IosTopInset { MusicScreen(lyricsVisualCase) } }"),
        ],
        MUSIC: [
            ("import androidx.compose.ui.unit.dp\n", '''import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.unit.dp
'''),
            ("import kotlinx.coroutines.flow.collectLatest\n", "import kotlinx.coroutines.flow.collectLatest\nimport kotlinx.coroutines.flow.first\n"),
            ("fun MusicScreen() {\n    val controller = remember { MusicController.shared }", '''fun MusicScreen(lyricsVisualCase: String? = null) {
    if (lyricsVisualCase != null) {
        val fixture = remember(lyricsVisualCase) { MusicController.lyricsVisualFixture(lyricsVisualCase) }
        PlayerPanel(fixture, {}, {}, {}, {}, lyricsVisualCase)
        return
    }
    val controller = remember { MusicController.shared }'''),
            ('''    onLogout: () -> Unit,
) {
    val song = controller.current ?: return
    var showLyrics by remember { mutableStateOf(controller.lyricsVisible) }
    val artwork = rememberAlbumBitmap(song.cover)
''', f'''    onLogout: () -> Unit,
    lyricsVisualCase: String? = null,
) {{
    val song = controller.current ?: return
    var showLyrics by remember {{ mutableStateOf(controller.lyricsVisible) }}
    val artwork = rememberAlbumBitmap(song.cover)
    var panelLaidOut by remember {{ mutableStateOf(false) }}
    var lyricLayout by remember {{ mutableStateOf<String?>(null) }}
    var followSettled by remember {{ mutableStateOf(false) }}
    LaunchedEffect(lyricsVisualCase, showLyrics, panelLaidOut, lyricLayout, followSettled, artwork) {{
        val case = lyricsVisualCase ?: return@LaunchedEffect
        val layout = lyricLayout ?: return@LaunchedEffect
        val needsCover = case == "timed-cover"
        if (!showLyrics || !panelLaidOut || !followSettled || (artwork != null) != needsCover) return@LaunchedEffect
        repeat(2) {{ withFrameNanos {{ }} }}
        val cover = if (artwork != null) "decoded" else "none"
        platform.Foundation.NSLog("{MARKER}:READY:$case:$cover:$layout")
    }}
'''),
            ('''    Box(Modifier.fillMaxSize()) {
        if (showLyrics) LyricsBackdrop(artwork, Modifier.matchParentSize())
''', '''    val visualProbe = if (lyricsVisualCase == null) Modifier else Modifier.onGloballyPositioned {
        panelLaidOut = it.size.width > 0 && it.size.height > 0
    }
    Box(Modifier.fillMaxSize().then(visualProbe)) {
        if (showLyrics) LyricsBackdrop(artwork, Modifier.matchParentSize())
'''),
            ("                key(song.id, lines) { KaraokeLyricsView(controller, lines, lyricModifier) }", f'''                key(song.id, lines) {{
                    val probeLayout: ((String) -> Unit)? =
                        if (lyricsVisualCase == null) null else ({{ layout: String -> lyricLayout = layout }})
                    val probeSettled: (() -> Unit)? =
                        if (lyricsVisualCase == null) null else ({{ ->
                            if (!followSettled) {{
                                platform.Foundation.NSLog("{MARKER}:FOLLOW_SETTLED:$lyricsVisualCase")
                                followSettled = true
                            }}
                        }})
                    KaraokeLyricsView(
                        controller, lines, lyricModifier,
                        onFixtureLayout = probeLayout,
                        onFixtureFollowSettled = probeSettled,
                    )
                }}'''),
            ("private fun KaraokeLyricsView(controller: MusicController, lines: List<LyricLine>, modifier: Modifier) {", '''private fun KaraokeLyricsView(
    controller: MusicController,
    lines: List<LyricLine>,
    modifier: Modifier,
    onFixtureLayout: ((String) -> Unit)? = null,
    onFixtureFollowSettled: (() -> Unit)? = null,
) {'''),
            ('''            Text(hint, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.78f), textAlign = TextAlign.Center)
''', '''            Text(hint, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.78f), textAlign = TextAlign.Center,
                onTextLayout = {
                    if (it.size.width > 0 && it.size.height > 0) {
                        onFixtureLayout?.invoke("empty")
                        onFixtureFollowSettled?.invoke()
                    }
                })
'''),
            ('''        if (!dragging && !browsing) state.animateScrollToItem(activeIndex.coerceAtLeast(0))
''', '''        if (!dragging && !browsing) {
            state.animateScrollToItem(activeIndex.coerceAtLeast(0))
            if (onFixtureFollowSettled != null) {
                snapshotFlow { state.isScrollInProgress }.first { !it }
                onFixtureFollowSettled()
            }
        }
'''),
            ('''                        text = if (active) karaokeLine(line, controller.position, color) else AnnotatedString(line.text),
''', '''                    text = if (active) karaokeLine(line, controller.position, color) else AnnotatedString(line.text),
                    onTextLayout = { result ->
                        if (active && onFixtureLayout != null && result.size.width > 0 && result.size.height > 0) {
                            val elapsed = (controller.position * 1000).toLong() - line.timeMs
                            if (line.wordTimes.size == line.text.length && line.wordTimes.firstOrNull()?.let { elapsed > it } == true &&
                                line.wordTimes.lastOrNull()?.let { elapsed < it } == true) onFixtureLayout("timed")
                            else if (line.phonetic.isNotBlank() && !controller.romanizeLyrics) onFixtureLayout("phonetic-hidden")
                            else if (line.phonetic.isNotBlank()) Unit
                            else if (line.translation.isNotBlank()) {
                                if (!controller.translateLyrics) onFixtureLayout("original")
                            } else if (result.lineCount > 1) onFixtureLayout("wrapped")
                        }
                    },
'''),
            ("                            text = phonetic,\n", """                            text = phonetic,
                            onTextLayout = { result ->
                                if (active && onFixtureLayout != null && result.size.width > 0 && result.size.height > 0) {
                                    onFixtureLayout("phonetic")
                                }
                            },
"""),
            ("                            text = translation,\n", '''                            text = translation,
                            onTextLayout = { result ->
                                if (active && onFixtureLayout != null && result.size.width > 0 && result.size.height > 0) {
                                    onFixtureLayout("translated")
                                }
                            },
'''),
        ],
        CONTROLLER: [
            ("    private val engine: HttpEngine = createHttpEngine(HttpEngineConfig()),\n", '''    private val engine: HttpEngine = createHttpEngine(HttpEngineConfig()),
    private val lyricsVisualOnly: Boolean = false,
'''),
            ("        val shared: MusicController by lazy { MusicController() }\n", f'''        val shared: MusicController by lazy {{ MusicController() }}

        internal fun lyricsVisualFixture(case: String): MusicController = MusicController(lyricsVisualOnly = true).apply {{
            val cover = case == "timed-cover"
            val mixed = case == "mixed-no-cover"
            val translated = case == "translated-on" || case == "translated-off"
            val romanized = case == "romanized-on" || case == "romanized-off"
            require(cover || mixed || translated || romanized || case == "completed-empty")
            val song = MusicSong(
                id = if (cover) 9101L else if (mixed) 9102L else if (translated || romanized) 9104L else 9103L,
                name = if (cover) "纸灯沿着风走" else if (mixed) "The folded atlas · 折页地图" else if (translated || romanized) "A lantern over the river" else "静默温室",
                artist = "Glass Orchard · 玻璃果园",
                album = "Paper Observatory",
                cover = if (cover) "{COVER}" else "",
                duration = if (cover) 182_000L else if (mixed) 241_000L else 156_000L,
            )
            current = song
            position = if (cover) 12.4 else if (mixed) 49.5 else if (translated || romanized) 12.4 else 22.0
            duration = song.duration / 1000.0
            lyricsVisible = true
            musicU = null
            playbackMode = MusicPlaybackMode(MUSIC_REPEAT_OFF, false)
            lyricFontSize = 28
            lyricColor = if (mixed) "#FFF3AF" else ""
            translateLyrics = case == "translated-on"
            romanizeLyrics = case == "romanized-on"
            lyrics = when {{
                translated || romanized -> listOf(
                    LyricLine(6_000L, "Leave the window open", translation = "让窗留给风", phonetic = if (romanized) "liú chuāng" else ""),
                    LyricLine(10_000L, "A lantern over the river", translation = "河面上，一盏纸灯", phonetic = if (romanized) "hé miàn" else ""),
                    LyricLine(17_000L, "The stars find their way home", translation = "星光找到了回家的路", phonetic = if (romanized) "xīng guāng" else ""),
                    LyricLine(24_000L, "Tomorrow begins on a blank page", translation = "明天从一张白纸升起", phonetic = if (romanized) "míng tiān" else ""),
                )
                cover -> {{
                    val line = "纸灯沿着风走，照见河上的星"
                    listOf(
                        LyricLine(6_000L, "今夜我把窗留给风"),
                        LyricLine(10_000L, line, List(line.length) {{ it * 380L }}),
                        LyricLine(17_000L, "河的回声穿过空桥"),
                        LyricLine(24_000L, "明天从一张白纸升起"),
                        LyricLine(31_000L, "留一粒星在口袋里"),
                    )
                }}
                mixed -> listOf(
                    LyricLine(39_000L, "A small compass / 小小的罗盘"),
                    LyricLine(48_000L, "把天光折进地图的第三页，then carry the folded atlas past the sleepy harbor. نجمة صغيرة تسكن فوق الورق — עוד שביל קטן אל המים。每一条线都通向未命名的晨雾。"),
                    LyricLine(63_000L, "海面把字迹写得很轻 · the shoreline keeps the margin"),
                    LyricLine(72_000L, "שקט בין הגלים · Let the blank margin breathe"),
                )
                else -> emptyList()
            }}
        }}
'''),
            ("    private val player = MusicPlayer()\n", "    private val player by lazy { MusicPlayer() }\n"),
            ("    private val nowPlaying = NowPlaying(\n", "    private val nowPlaying by lazy { NowPlaying(\n"),
            ('''        onSeek = { seconds -> seekTo(seconds) },
    )

    init {
''', '''        onSeek = { seconds -> seekTo(seconds) },
    ) }

    init {
        if (!lyricsVisualOnly) startPlaybackRestore()
    }

    private fun startPlaybackRestore() {
'''),
            *[(f"    fun {signature} {{\n", f"    fun {signature} {{\n        if (lyricsVisualOnly) return\n") for signature in (
                "reloadLyrics()", "toggle()", "previous()", "seekTo(seconds: Double)", "cyclePlayMode()",
            )],
            ("    fun next() = advanceQueue(automatic = false)\n",
             "    fun next() { if (!lyricsVisualOnly) advanceQueue(automatic = false) }\n"),
        ],
        IMAGES: [
            ("        val bytes = fetch(url) ?: return null\n", f'''        if (url != "{COVER}") return null
        val hex = {encoded}
        val bytes = ByteArray(hex.length / 2) {{ index -> hex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }}
'''),
            ("        val bmp = decodeBytes(bytes) ?: return null\n",
             f'''        val bmp = checkNotNull(decodeBytes(bytes)) {{ "{MARKER}:DECODE_FAILED" }}
'''),
        ],
        HTTP: [
            ("actual fun createHttpEngine(config: HttpEngineConfig): HttpEngine = KtorEngine(config)\n", f'''actual fun createHttpEngine(config: HttpEngineConfig): HttpEngine = object : HttpEngine {{
    override suspend fun execute(call: HttpCall, timeoutMs: Long?): HttpReply =
        HttpReply(status = 503, headers = emptyList(), bodyText = "{MARKER}:OFFLINE")
}}
'''),
        ],
    }


def apply_overlay(checkout):
    png = cover_png()
    hashes = {}
    pending = {}
    for name, patches in patch_set(png).items():
        file = checkout / name
        require(file.is_file() and not file.is_symlink(), f"Missing overlay source: {name}")
        before = file.read_bytes()
        text = before.decode("utf-8")
        for i, (anchor, replacement) in enumerate(patches, 1):
            count = text.count(anchor)
            require(count == 1, f"{name}: anchor {i} expected once, found {count}: {anchor.splitlines()[0]}")
            text = text.replace(anchor, replacement, 1)
        after = text.encode("utf-8")
        require(before != after, f"No overlay change: {name}")
        pending[file] = after
        hashes[name] = {"source_sha256": digest(before), "overlay_sha256": digest(after)}
    require(set(hashes) == set(OVERLAYS), "Overlay file allowlist changed")
    for file, after in pending.items():
        file.write_bytes(after)
    return {"overlay_sources": hashes, "overlay_sha256": digest(json_bytes(hashes)), "cover_sha256": digest(png)}


def run(argv, cwd, timeout=30):
    result = subprocess.run(argv, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
    if result.returncode != 0:
        error = SmokeError(f"Checked command failed (exit {result.returncode})")
        error.returncode = result.returncode
        raise error
    return result.stdout


def git(repo, executable, *args):
    return run([executable, *args], repo)


def git_paths(output):
    return [os.fsdecode(p) for p in output.split(b"\0") if p]


def git_blob(data):
    return hashlib.sha1(f"blob {len(data)}\0".encode() + data).hexdigest()


def production_snapshot(repo, sha, executable):
    require(re.fullmatch(r"[0-9a-f]{40}", sha), "Expected a full lowercase commit SHA")
    head = git(repo, executable, "rev-parse", "HEAD").decode().strip()
    require(head == sha, f"Production HEAD {head} does not match expected SHA {sha}")
    require(not git(repo, executable, "diff", "--cached", "--name-only", "-z", sha, "--"),
            "Production Git index differs from the expected commit")
    inputs = {}
    for record in git_paths(git(repo, executable, "ls-tree", "-rz", sha)):
        meta, name = record.split("\t", 1)
        mode, kind, oid = meta.split(" ")
        file = repo / name
        require(kind == "blob" and mode in ("100644", "100755"), f"Unsupported production input: {name}")
        require(file.is_file() and file.resolve() == file, f"Missing or linked production input: {name}")
        actual_mode = file.stat().st_mode
        if os.name != "nt":
            executable_bit = bool(actual_mode & stat.S_IXUSR)
            require(name == "gradlew" or executable_bit == (mode == "100755"),
                    f"Unexpected executable mode: {name}")
        data = file.read_bytes()
        # Git for Windows may check out committed LF files with CRLF. The snapshot still hashes raw bytes.
        require(git_blob(data) == oid or (os.name == "nt" and git_blob(data.replace(b"\r\n", b"\n")) == oid),
                f"Modified production input: {name}")
        inputs[name] = digest(data)
    generated = {
        "iosApp/Info.plist",
        "iosApp/simulator-launch.png", "iosApp/simulator-later.png", "iosApp/simulator-guest.png",
        "iosApp/simulator-open-sport.png",
        "simlog.txt", "console.txt", "console2.txt", "console3.txt",
        "AndaXin-unsigned.ipa", "ipa_list.txt", "shared-framework.zip",
    }
    outputs = {".gradle", ".kotlin", "build", "build-device", "Payload", PROJECT,
               "shared/build", "app/build", "music/build", "glasmio/.gradle", "glasmio/.kotlin", "glasmio/build",
               "music/.cxx", "glasmio/glasmio-glass/.cxx"}
    outputs.update(f"glasmio/{module}/build" for module in
                   ("glasmio-core", "glasmio-glass", "glasmio-ui", "glasmio-scaffold", "glasmio-icons"))
    # Inspect ignored files too: XcodeGen and Gradle do not consult .gitignore when finding inputs.
    def walk_error(exc):
        raise exc

    for directory, dirs, files in os.walk(repo, followlinks=False, onerror=walk_error):
        rel = Path(directory).relative_to(repo)
        for name in dirs[:]:
            path = (rel / name).as_posix()
            if path == ".git" or path in outputs:
                require(not (repo / path).is_symlink(), f"Linked production output directory: {path}")
                dirs.remove(name)
            else:
                require(not (repo / path).is_symlink(), f"Added linked production input: {path}")
        for name in files:
            path = (rel / name).as_posix()
            if path in inputs or path == ".git":
                continue
            if path in generated:
                require(not (repo / path).is_symlink(), f"Linked generated file: {path}")
                continue
            raise SmokeError(f"Added production input: {path}")
    project = repo / PROJECT
    generated_inputs = [repo / "iosApp/Info.plist", project / "project.pbxproj", *project.rglob("*.xcscheme")]
    for file in generated_inputs:
        name = file.relative_to(repo).as_posix()
        if file.is_file() and name not in inputs:
            require(file.resolve() == file, f"Linked generated production input: {name}")
            inputs[f"{name} [generated]"] = digest(file.read_bytes())
    return {"sha": sha, "files": len(inputs), "input_sha256": digest(json_bytes(inputs))}


def archive_checkout(repo, sha, executable, work):
    checkout = work / "checkout"
    checkout.mkdir()
    archive = work / "candidate.tar"
    with archive.open("wb") as output:
        subprocess.run([executable, "-c", "core.autocrlf=false", "archive", "--format=tar", sha],
                       cwd=repo, stdout=output,
                       stderr=subprocess.PIPE, check=True, timeout=60)
    with tarfile.open(archive) as source:
        members = source.getmembers()
        for entry in members:
            path = PurePosixPath(entry.name)
            require(not path.is_absolute() and ".." not in path.parts and (entry.isdir() or entry.isfile()),
                    f"Unexpected archive entry: {entry.name}")
        source.extractall(checkout, members=members, filter="data")
    archive.unlink()
    return checkout


def isolated_fixture(repo, sha, executable, temp, before, action):
    try:
        with tempfile.TemporaryDirectory(prefix="ios-lyrics-fixture-", dir=temp) as directory:
            work = Path(directory)
            checkout = archive_checkout(repo, sha, executable, work)
            manifest = apply_overlay(checkout)
            return action(work, checkout, manifest)
    finally:
        require(production_snapshot(repo, sha, executable) == before, "Production input hashes changed during fixture run")


def copy_seed(repo, checkout):
    source = repo / SEED
    target = checkout / PLACEHOLDER
    require(not source.is_symlink() and (source / "shared").is_file() and (source / "shared").stat().st_size > 0,
            "Missing pristine production device framework placeholder")
    require(not target.exists(), "Archive unexpectedly contains the framework placeholder")
    target.parent.mkdir(parents=True)
    require(source.resolve() == source, "Linked production framework placeholder")
    for path in source.rglob("*"):
        if path.is_symlink():
            require(source in path.resolve().parents, f"Framework placeholder has an external link: {path.name}")
    shutil.copytree(source, target, symlinks=True)
    (checkout / "gradlew").chmod((checkout / "gradlew").stat().st_mode | stat.S_IXUSR)


def stop(process):
    if process.poll() is None:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
    process.wait(timeout=5)


def bounded_build(argv, cwd, output, timeout, env=None):
    with output.open("wb") as log:
        process = subprocess.Popen(argv, cwd=cwd, env=env, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        try:
            require(process.wait(timeout=timeout) == 0, f"{argv[0]} failed (exit {process.returncode})")
        except subprocess.TimeoutExpired as exc:
            raise SmokeError(f"{argv[0]} timed out after {timeout} seconds") from exc
        finally:
            stop(process)


DIAGNOSTIC_KINDS = (
    (r"unresolved reference|cannot find .+ in scope|has no member|use of unresolved identifier", "unresolved symbol"),
    (r"no such module|missing required module|module .+ not found", "missing module"),
    (r"type mismatch|argument type mismatch|cannot convert value|type .+ does not conform", "type mismatch"),
    (r"cannot infer|not enough information to infer|type of expression is ambiguous", "type inference failed"),
    (r"unexpected token|expecting |expected |syntax error", "syntax error"),
    (r"too many arguments|no value passed for|incorrect argument label|missing argument", "argument mismatch"),
    (r"undefined symbols|linker command failed|library not found", "linker error"),
    (r"unable to find a device matching|no devices are eligible", "simulator destination unavailable"),
    (r"could not resolve|could not get resource|could not get |could not download", "dependency resolution failed"),
)


def build_diagnostics(log):
    if not log.is_file():
        return []
    with log.open("rb") as source:
        source.seek(max(0, log.stat().st_size - 256 * 1024))
        tail = source.read(256 * 1024).decode("utf-8", errors="replace")
    paths = "|".join(re.escape(path) for path in (*OVERLAYS, "iosApp/project.yml", "shared/build.gradle.kts"))
    location = re.compile(r"(?:^|[/\s])(?P<file>" + paths + r"):(?P<line>[0-9]{1,6})(?::(?P<column>[0-9]{1,6}))?[:\s]")
    records = []
    # Emit only known source names, coordinates, and categories, never the compiler's free text.
    for line in tail.splitlines():
        if len(line) > 4096:
            continue
        match = location.search(line)
        message = line[match.end():] if match else line
        if not re.match(r"\s*(?:e:|fatal error:|error:|ld:|xcodebuild: error:|could not )", line, re.IGNORECASE) and not (
            match and re.match(r"\s*(?:fatal )?error:", message, re.IGNORECASE)
        ):
            continue
        kind = next((name for pattern, name in DIAGNOSTIC_KINDS if re.search(pattern, message, re.IGNORECASE)), None)
        if match:
            record = {"file": match["file"], "line": int(match["line"]), "kind": kind or "source compile error"}
            if match["column"]:
                record["column"] = int(match["column"])
        elif kind:
            record = {"kind": kind}
        else:
            continue
        if record not in records:
            records.append(record)
        if len(records) == 20:
            break
    return records


def inspect_png(path):
    require(path.is_file(), "Screenshot command produced no PNG")
    data = path.read_bytes()
    require(data.startswith(b"\x89PNG\r\n\x1a\n"), "Invalid screenshot PNG signature")
    width = height = idat = 0
    offset = 8
    while offset + 12 <= len(data):
        length, kind = struct.unpack_from(">I4s", data, offset)
        end = offset + 12 + length
        require(end <= len(data), "Truncated screenshot PNG chunk")
        payload = data[offset+8:end-4]
        require(zlib.crc32(kind + payload) == struct.unpack_from(">I", data, end-4)[0], "Screenshot PNG checksum failed")
        if offset == 8:
            require(kind == b"IHDR" and length == 13, "Screenshot is missing its PNG header")
            width, height = struct.unpack_from(">II", payload)
        if kind == b"IDAT":
            idat += length
        if kind == b"IEND":
            require(length == 0 and end == len(data) and idat > 0 and 0 < width < height,
                    "Screenshot is not a complete portrait PNG")
            return {"width": width, "height": height, "sha256": digest(data)}
        offset = end
    raise SmokeError("Screenshot PNG has no IEND")


def fixture_markers(log):
    pattern = rb"(?<![\w:])" + MARKER.encode() + rb":(?:FOLLOW_SETTLED:[a-z-]+|READY:[a-z-]+:[a-z]+:[a-z-]+)(?=\s|$)"
    return [match.decode("ascii") for match in re.findall(pattern, log)]


def wait_ready(case, log, alive, timeout=READY_SECONDS):
    expected = f"{MARKER}:READY:{case}:{':'.join(CASES[case])}"
    follow = f"{MARKER}:FOLLOW_SETTLED:{case}"
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        content = log.read_bytes()
        require(alive(), "Fixture app exited before a screenshot")
        require(f"{MARKER}:DECODE_FAILED".encode() not in content, "Local fixture cover decode failed")
        settled = ready = False
        for marker in fixture_markers(content):
            require(marker in {follow, expected}, "Wrong case or visual layout in fixture readiness marker")
            if marker == follow:
                settled = True
            else:
                require(settled, "Fixture announced readiness before initial lyric follow settled")
                ready = True
        if ready:
            return expected
        time.sleep(min(0.1, max(0, deadline - time.monotonic())))
    raise SmokeError(f"Timed out awaiting actual layout for {case}")


class Simulator:
    def __init__(self, cwd, udid):
        require(re.fullmatch(r"[0-9A-Fa-f]{8}(?:-[0-9A-Fa-f]{4}){3}-[0-9A-Fa-f]{12}", udid), "Invalid SIM_UDID")
        self.cwd = cwd
        self.udid = udid

    def sim(self, command, *args, timeout=30):
        operation = command if command in ("bootstatus", "uninstall", "install", "terminate") else "validation"
        if command == "status_bar":
            operation = {"override": "status-bar-override", "clear": "status-bar-clear"}.get(args[0] if args else None, "validation")
        elif command == "ui":
            operation = "appearance"
        elif command == "io":
            operation = "screenshot"
        argv = ["xcrun", "simctl", command, self.udid, *args]
        try:
            result = subprocess.run(argv, cwd=self.cwd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
        except Exception as exc:
            exc.smoke_operation = operation
            raise
        if result.returncode != 0:
            detail = " ".join((result.stdout + result.stderr).decode("utf-8", errors="replace").split())[:400]
            suffix = f": {detail}" if detail else ""
            error = SmokeError(f"xcrun simctl {command} failed (exit {result.returncode}){suffix}")
            error.returncode = result.returncode
            error.smoke_operation = operation
            raise error
        return result.stdout

    def details(self):
        listing = json.loads(run(["xcrun", "simctl", "list", "devices", "available", "--json"], self.cwd))
        matches = [(runtime, item) for runtime, entries in listing["devices"].items()
                   for item in entries if item["udid"].lower() == self.udid.lower()]
        require(len(matches) == 1, "Selected simulator is unavailable")
        runtime, item = matches[0]
        return {"udid": self.udid, "model": item["name"], "runtime": runtime}

    def uninstall_if_present(self):
        try:
            result = subprocess.run(
                ["xcrun", "simctl", "uninstall", self.udid, BUNDLE],
                cwd=self.cwd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30,
            )
        except Exception as exc:
            exc.smoke_operation = "uninstall"
            raise
        if result.returncode == 0:
            return
        # simctl returns 117 when the bundle is not installed; tolerate only that case.
        output = (result.stdout + result.stderr).decode("utf-8", errors="replace")
        if result.returncode == 117 and re.search(r"(not installed|no such app|application .* not found)", output, re.IGNORECASE):
            return
        detail = " ".join(output.split())[:400]
        suffix = f": {detail}" if detail else ""
        error = SmokeError(f"xcrun simctl uninstall failed (exit {result.returncode}){suffix}")
        error.returncode = result.returncode
        error.smoke_operation = "uninstall"
        raise error

    def prepare(self, app):
        self.sim("bootstatus", "-b", timeout=120)
        # Clear any app data from the generic smoke on precisely this simulator.
        self.uninstall_if_present()
        self.sim("install", str(app))
        self.sim("ui", "appearance", "light")
        self.sim("status_bar", "override", "--time", "9:41", "--dataNetwork", "wifi", "--wifiMode", "active",
                 "--wifiBars", "3", "--cellularMode", "active", "--cellularBars", "4", "--batteryState", "charged",
                 "--batteryLevel", "100")

    def launch(self, case, output):
        return subprocess.Popen(["xcrun", "simctl", "launch", "--console", "--terminate-running-process", self.udid,
                                 BUNDLE, "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN",
                                 "-AndaXinGuest", "-AndaXinOpen", f"{MARKER}:{case}"],
                                cwd=self.cwd, stdout=output, stderr=subprocess.STDOUT, start_new_session=True)

    def screenshot(self, path):
        self.sim("io", "screenshot", str(path))

    def terminate(self):
        self.sim("terminate", BUNDLE)

    def cleanup(self):
        cleanup_actions([
            ("simulator-cleanup", "status-bar-clear", lambda: self.sim("status_bar", "clear")),
            ("simulator-cleanup", "uninstall", self.uninstall_if_present),
        ])


def capture(sim, case, work, artifacts, timeout=READY_SECONDS):
    console = work / f"{case}.raw"
    image = work / f"{case}.png"
    report = {"case": case, "status": "failed"}
    operation = "launch"
    try:
        with console.open("wb") as output:
            process = sim.launch(case, output)
            primary = None
            try:
                operation = "readiness"
                report["ready"] = wait_ready(case, console, lambda: process.poll() is None, timeout)
                operation = "screenshot"
                sim.screenshot(image)
                operation = "liveness"
                require(process.poll() is None, "Fixture app exited during screenshot")
                operation = "png-validation"
                report["png"] = inspect_png(image)
                shutil.copyfile(image, artifacts / image.name)
            except Exception as exc:
                primary = exc
                exc.smoke_phase = "capture"
                if not getattr(exc, "smoke_operation", None):
                    exc.smoke_operation = operation
                raise
            finally:
                cleanup_actions([
                    ("capture-cleanup", "terminate", lambda: sim.terminate() if process.poll() is None else None),
                    ("capture-cleanup", "process-stop", lambda: stop(process)),
                ], primary)
        report["status"] = "passed"
        return report
    except Exception as exc:
        report["failure"] = runtime_failure(exc, "capture", operation)
        report["error"] = report["failure"]["kind"]
        cleanup = runtime_cleanup_failures(exc)
        if cleanup:
            report["cleanup_failures"] = cleanup
        exc.smoke_phase = report["failure"]["phase"]
        exc.smoke_operation = report["failure"]["operation"]
        raise
    finally:
        # Only fixture protocol records (never the raw app console) leave the disposable directory.
        markers = fixture_markers(console.read_bytes()) if console.is_file() else []
        allowed = {f"{MARKER}:READY:{key}:{':'.join(stages)}" for key, stages in CASES.items()}
        allowed.update(f"{MARKER}:FOLLOW_SETTLED:{key}" for key in CASES)
        report["observed_markers"] = list(dict.fromkeys(marker for marker in markers if marker in allowed))
        (artifacts / f"{case}.log").write_bytes(json_bytes(report))


def collect(repo, sha, executable, temp, artifacts, udid):
    before = production_snapshot(repo, sha, executable)
    evidence = {"production_before": before, "cases": []}
    phase, operation = "isolation", "source-isolation"
    artifacts.mkdir()

    def build(phase, argv, cwd, log, timeout, env=None):
        try:
            bounded_build(argv, cwd, log, timeout, env)
        except (SmokeError, OSError, subprocess.SubprocessError):
            records = build_diagnostics(log)
            report = {"phase": phase, "diagnostics": records}
            (artifacts / "build-diagnostics.json").write_bytes(json_bytes(report))
            print(json_bytes(report).decode(), end="", file=sys.stderr)
            raise SmokeError(f"Isolated {phase} failed; see sanitized build diagnostics") from None

    def build_and_capture(work, checkout, manifest):
        nonlocal phase, operation
        evidence.update(manifest)
        phase, operation = "simulator-details", "list-devices"
        sim = Simulator(checkout, udid)
        evidence["simulator"] = sim.details()
        phase, operation = "xcode-version", "xcode-version"
        evidence["xcode"] = run(["xcodebuild", "-version"], checkout).decode().strip()
        phase, operation = "framework-seed", "framework-seed"
        copy_seed(repo, checkout)
        phase, operation = "xcodegen", "xcodegen"
        build("xcodegen", ["xcodegen", "-s", "project.yml"], checkout / "iosApp", work / "xcodegen.raw", 60)
        derived = work / "DerivedData"
        env = dict(os.environ)
        env["GRADLE_OPTS"] = f'{env.get("GRADLE_OPTS", "")} -Dorg.gradle.daemon=false -Dorg.gradle.caching=false'
        phase, operation = "xcodebuild", "xcodebuild"
        build("xcodebuild", ["xcodebuild", "-project", PROJECT, "-scheme", "AndaXin", "-configuration", "Debug",
                             "-destination", f"platform=iOS Simulator,id={udid}", "-derivedDataPath", str(derived),
                             "CODE_SIGNING_ALLOWED=NO", "build"], checkout, work / "xcodebuild.raw", BUILD_SECONDS, env)
        phase, operation = "bundle-validation", "bundle-validation"
        app = derived / "Build/Products/Debug-iphonesimulator/AndaXin.app"
        with (app / "Info.plist").open("rb") as source:
            require(plistlib.load(source).get("CFBundleIdentifier") == BUNDLE, "Unexpected fixture bundle identifier")
        primary = None
        try:
            phase, operation = "simulator-setup", "prepare"
            sim.prepare(app)
            for case in CASES:
                phase, operation = "capture", "launch"
                evidence["cases"].append(capture(sim, case, work, artifacts))
        except Exception as exc:
            primary = exc
            raise
        finally:
            cleanup_actions([("simulator-cleanup", "simulator-cleanup", sim.cleanup)], primary)

    try:
        isolated_fixture(repo, sha, executable, temp, before, build_and_capture)
        evidence["production_after"] = before
        evidence["status"] = "passed"
    except Exception as exc:
        evidence["status"] = "failed"
        kinds = (SmokeError, subprocess.TimeoutExpired, subprocess.CalledProcessError, OSError, ValueError, subprocess.SubprocessError)
        evidence["failure_type"] = next((kind.__name__ for kind in kinds if isinstance(exc, kind)), "Exception")
        evidence["failure"] = runtime_failure(exc, phase, operation)
        cleanup = runtime_cleanup_failures(exc)
        if cleanup:
            evidence["cleanup_failures"] = cleanup
        exc.smoke_phase = evidence["failure"]["phase"]
        exc.smoke_operation = evidence["failure"]["operation"]
        raise
    finally:
        (artifacts / "manifest.json").write_bytes(json_bytes(evidence))


def scan_stream(stream, label):
    tokens = (MARKER.encode(), MARKER.encode("utf-16-le"), MARKER.encode("utf-16-be"),
              b"armIosLyricsVisualFixture", b"lyricsVisualFixture")
    carry = b""
    while block := stream.read(1 << 20):
        data = carry + block
        require(not any(token in data for token in tokens), f"Fixture marker leaked into production package: {label}")
        carry = data[-max(map(len, tokens)):]


def scan_package(app, ipa):
    require(app.is_dir() and ipa.is_file(), "Production package or built app missing")
    for path in app.rglob("*"):
        require(MARKER not in path.name, f"Fixture file leaked into production app: {path.name}")
        if path.is_file():
            with path.open("rb") as stream:
                scan_stream(stream, str(path.relative_to(app)))
    with zipfile.ZipFile(ipa) as package:
        for name in package.namelist():
            require(MARKER not in name, f"Fixture file leaked into IPA: {name}")
            if not name.endswith("/"):
                with package.open(name) as stream:
                    scan_stream(stream, name)
    print("Production app and IPA contain no lyrics fixture markers")


def outside(path, repo):
    require(path != repo and repo not in path.parents, f"Fixture output must be outside the production checkout: {path.name}")
    return path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    parser.add_argument("--sha", default=os.environ.get("GITHUB_SHA", ""))
    parser.add_argument("--git", default="git")
    sub = parser.add_subparsers(dest="command", required=True)
    guard = sub.add_parser("guard", help="verify clean production inputs; write or compare hashes")
    guard.add_argument("mode", choices=("write", "compare"))
    guard.add_argument("snapshot", type=Path)
    for command in ("dry-run", "capture"):
        part = sub.add_parser(command)
        part.add_argument("--temp", required=True, type=Path)
        if command == "capture":
            part.add_argument("--artifacts", required=True, type=Path)
            part.add_argument("--udid", default=os.environ.get("SIM_UDID", ""))
    package = sub.add_parser("scan-package")
    package.add_argument("app", type=Path)
    package.add_argument("ipa", type=Path)
    args = parser.parse_args()
    repo = args.repo.resolve()
    try:
        if args.command == "scan-package":
            scan_package(args.app, args.ipa)
        elif args.command == "guard":
            snapshot = outside(args.snapshot.resolve(), repo)
            current = production_snapshot(repo, args.sha, args.git)
            if args.mode == "write":
                snapshot.write_bytes(json_bytes(current))
            else:
                require(json.loads(snapshot.read_bytes()) == current, "Production input hashes differ from baseline")
            print(json_bytes(current).decode(), end="")
        elif args.command == "dry-run":
            temp = outside(args.temp.resolve(), repo)
            before = production_snapshot(repo, args.sha, args.git)
            report = isolated_fixture(repo, args.sha, args.git, temp, before, lambda _, __, manifest: manifest)
            print(json_bytes({"production_unchanged": before, **report}).decode(), end="")
        else:
            require(sys.platform == "darwin", "Native capture requires macOS; use dry-run for source checks")
            collect(repo, args.sha, args.git, outside(args.temp.resolve(), repo), outside(args.artifacts.resolve(), repo), args.udid)
    except Exception as exc:
        if args.command == "capture":
            failure = {"failure": runtime_failure(exc, "capture", "validation")}
            cleanup = runtime_cleanup_failures(exc)
            if cleanup:
                failure["cleanup_failures"] = cleanup
            print("iOS lyrics visual validation: " + json.dumps(failure, sort_keys=True), file=sys.stderr)
        else:
            print(f"iOS lyrics visual validation: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
