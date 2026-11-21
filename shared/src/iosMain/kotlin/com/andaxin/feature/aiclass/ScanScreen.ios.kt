package com.andaxin.feature.aiclass

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import com.andaxin.core.design.Gap
import com.andaxin.core.design.LocalInk
import com.andaxin.core.design.Type
import com.andaxin.shell.Sheet
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFoundation.*
import platform.UIKit.UIView
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_create
import kotlin.coroutines.resume

@OptIn(ExperimentalForeignApi::class)
private class CameraPreviewView(private val preview: AVCaptureVideoPreviewLayer) : UIView() {
    init {
        clipsToBounds = true
        layer.addSublayer(preview)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        preview.frame = bounds
    }
}

@OptIn(ExperimentalForeignApi::class)
@Composable
fun ScanScreen(onBack: () -> Unit, onSignUrl: (String) -> Unit = {}) {
    val ink = LocalInk.current
    var granted by remember { mutableStateOf(AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo) == AVAuthorizationStatusAuthorized) }
    var asked by remember { mutableStateOf(false) }
    var hit by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf<String?>(null) }
    var camera by remember { mutableStateOf<AVCaptureDevice?>(null) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var maxZoom by remember { mutableFloatStateOf(1f) }
    val latestOnSignUrl by rememberUpdatedState(onSignUrl)
    val session = remember { AVCaptureSession() }
    val preview = remember { AVCaptureVideoPreviewLayer(session = session) }
    val cameraQueue = remember { dispatch_queue_create("com.andaxin.scanner", null) }
    val delegate = remember {
        object : NSObject(), AVCaptureMetadataOutputObjectsDelegateProtocol {
            override fun captureOutput(output: AVCaptureOutput, didOutputMetadataObjects: List<*>, fromConnection: AVCaptureConnection) {
                if (hit) return
                val value = (didOutputMetadataObjects.firstOrNull() as? AVMetadataMachineReadableCodeObject)?.stringValue
                if (value.isNullOrBlank()) return
                code = value
                if (value.startsWith("http", ignoreCase = true)) {
                    hit = true
                    dispatch_async(cameraQueue) { session.stopRunning() }
                    latestOnSignUrl(value)
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        if (granted) return@LaunchedEffect
        granted = suspendCancellableCoroutine { continuation ->
            AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { allowed ->
                if (continuation.isActive) continuation.resume(allowed)
            }
        }
        asked = true
    }
    DisposableEffect(session) { onDispose { dispatch_async(cameraQueue) { session.stopRunning() } } }
    fun changeZoom(value: Float) {
        val device = camera ?: return
        val target = value.coerceIn(1f, maxZoom)
        if (device.lockForConfiguration(null)) {
            try {
                device.videoZoomFactor = target.toDouble()
                zoom = target
            } finally {
                device.unlockForConfiguration()
            }
        }
    }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()).padding(horizontal = Gap.screen, vertical = Gap.md),
        verticalArrangement = Arrangement.spacedBy(Gap.lg),
    ) {
        Text("对准课堂二维码，识别后自动进入签到", fontSize = Type.body, lineHeight = Type.bodyLine, color = ink.muted)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(if (maxWidth > 600.dp) 1.65f else 0.95f)
                    .clip(RoundedCornerShape(28.dp)).background(Color.Black)
                    .pointerInput(camera, maxZoom) { detectTransformGestures { _, _, scale, _ -> changeZoom(zoom * scale) } },
            ) {
                if (granted) {
                    UIKitView(
                        factory = {
                            preview.videoGravity = AVLayerVideoGravityResizeAspectFill
                            val view = CameraPreviewView(preview)
                            session.sessionPreset = AVCaptureSessionPresetHigh
                            val device = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo)
                            val input = device?.let { AVCaptureDeviceInput(device = it, error = null) }
                            if (device == null || input == null || !session.canAddInput(input)) {
                                cameraError = "相机暂不可用，请返回后重试"
                            } else {
                                session.addInput(input)
                                camera = device
                                maxZoom = device.maxAvailableVideoZoomFactor.toFloat().coerceAtLeast(1f)
                                val output = AVCaptureMetadataOutput()
                                if (session.canAddOutput(output)) {
                                    session.addOutput(output)
                                    output.setMetadataObjectsDelegate(delegate, queue = dispatch_get_main_queue())
                                    output.metadataObjectTypes = listOf(AVMetadataObjectTypeQRCode)
                                    dispatch_async(cameraQueue) { session.startRunning() }
                                } else cameraError = "相机无法识别二维码，请返回后重试"
                            }
                            view
                        },
                        update = { view -> preview.frame = view.bounds },
                        properties = UIKitInteropProperties(interactionMode = null),
                        modifier = Modifier.fillMaxSize(),
                    )
                    ScanViewfinder()
                    Text(
                        "将二维码放入框内", color = Color.White, fontSize = Type.meta,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = Gap.lg, start = Gap.md, end = Gap.md),
                    )
                    if (cameraError == null && camera != null) {
                        Row(
                            Modifier.align(Alignment.BottomCenter).padding(Gap.md).horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(Gap.sm),
                        ) {
                            val steps = listOf(1f, 2f, 4f, maxZoom).filter { it <= maxZoom }.distinct()
                            steps.forEach { level ->
                                val selected = kotlin.math.abs(zoom - level) < 0.1f
                                Box(
                                    Modifier.clip(CircleShape).background(if (selected) Color.White else Color.Black.copy(alpha = 0.60f))
                                        .clickable(role = Role.Button) { changeZoom(level) }.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                        .padding(horizontal = Gap.sm, vertical = Gap.sm),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(if (level == maxZoom && maxZoom > 4f) "最大" else "${level.toInt()}×", fontSize = Type.meta, color = if (selected) Color.Black else Color.White)
                                }
                            }
                        }
                    }
                }
                val message = cameraError ?: if (!granted) {
                    if (asked) "请在系统设置 → 安大信中允许相机权限" else "正在请求相机权限…"
                } else null
                if (message != null) Text(message, color = Color.White, fontSize = Type.body, lineHeight = Type.bodyLine, modifier = Modifier.align(Alignment.Center).padding(Gap.xl))
            }
        }
        Sheet {
            Text(if (code == null) "等待识别" else "识别结果", fontSize = Type.title, color = ink.strong)
            Spacer(Modifier.height(Gap.sm))
            SelectionContainer {
                Text(code ?: "无需拍照。保持二维码清晰，识别成功后将自动继续。", fontSize = Type.body, lineHeight = Type.bodyLine, color = ink.muted)
            }
        }
        Spacer(Modifier.height(Gap.lg))
    }
}

@Composable
private fun ScanViewfinder() {
    val accent = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxSize()) {
        val side = minOf(size.width * 0.66f, size.height * 0.52f, 240.dp.toPx())
        val x = (size.width - side) / 2f
        val y = (size.height - side) / 2f
        val shade = Color.Black.copy(alpha = 0.32f)
        drawRect(shade, Offset.Zero, Size(size.width, y))
        drawRect(shade, Offset(0f, y + side), Size(size.width, size.height - y - side))
        drawRect(shade, Offset(0f, y), Size(x, side))
        drawRect(shade, Offset(x + side, y), Size(size.width - x - side, side))
        val corner = 24.dp.toPx()
        val width = 3.dp.toPx()
        for ((cx, cy) in listOf(x to y, x + side to y, x to y + side, x + side to y + side)) {
            drawLine(accent, Offset(cx, cy), Offset(cx + if (cx == x) corner else -corner, cy), width)
            drawLine(accent, Offset(cx, cy), Offset(cx, cy + if (cy == y) corner else -corner), width)
        }
        drawLine(Color.White.copy(alpha = 0.35f), Offset(x + corner / 2f, y + side / 2f), Offset(x + side - corner / 2f, y + side / 2f), 1.dp.toPx())
    }
}
