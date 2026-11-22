package com.andaxin.feature.aiclass

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import io.github.neoruaa.glasmio.icons.GlasmioIcon
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.andaxin.shell.pressClick
import kotlin.math.roundToInt
import com.andaxin.core.design.Gap
import com.andaxin.core.design.GlassBackButton
import androidx.compose.foundation.layout.statusBarsPadding
import com.andaxin.core.design.Type
import com.andaxin.core.design.LocalInk
import com.andaxin.core.design.Radius
import com.andaxin.shell.Sheet
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 扫码签到：只负责"看见码、把码里的内容念出来"。
 *
 * 扫到 http 那条就直接把码交给 `QrSignScreen` 去开（官方那条签到本身就是网页带着会话完成的），
 * 不再让人多点一次 —— 上课那几分钟经不起多按一次。
 * 底下那一格始终显示码的原文，出问题时那就是唯一的凭据（那一串里带 token，只在这台机子上看得见）。
 */
@Composable
fun ScanScreen(onBack: () -> Unit, onSignUrl: (String) -> Unit = {}) {
    val context = LocalContext.current
    val ink = LocalInk.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var denied by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf<String?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        denied = !ok
    }

    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }

    Column(
        Modifier.fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Gap.screen, vertical = Gap.sm),
        verticalArrangement = Arrangement.spacedBy(Gap.lg),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Gap.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassBackButton(onBack)
            Column(Modifier.weight(1f)) {
                Text("扫码签到", fontSize = Type.display, fontWeight = FontWeight.SemiBold, color = ink.strong)
                Spacer(Modifier.height(Gap.xxs))
                Text("对准课堂二维码，识别后自动进入签到", fontSize = Type.meta, lineHeight = Type.metaLine, color = ink.muted)
            }
        }

        BoxWithConstraints(Modifier.fillMaxWidth()) {
            Box(
                Modifier.fillMaxWidth()
                    .aspectRatio(if (maxWidth > 600.dp) 1.65f else 0.95f)
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color(0xFF10151F)),
                contentAlignment = Alignment.Center,
            ) {
                if (granted) {
                    CameraPreview(onCode = { raw ->
                        code = raw
                        if (raw.startsWith("http", ignoreCase = true)) onSignUrl(raw)
                    })
                } else {
                    Column(
                        Modifier.padding(Gap.xl),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Gap.md),
                    ) {
                        GlasmioIcon("camera.viewfinder", contentDescription = null, tint = Color.White, size = 36.dp)
                        Text(
                            if (denied) "需要相机权限" else "正在请求相机权限",
                            fontSize = Type.title,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            textAlign = TextAlign.Center,
                        )
                        Text("允许相机访问后，即可扫描课堂二维码", fontSize = Type.meta, lineHeight = Type.metaLine, color = Color.White.copy(alpha = 0.72f), textAlign = TextAlign.Center)
                        if (denied) {
                            Text(
                                "重新授权",
                                fontSize = Type.body,
                                fontWeight = FontWeight.Medium,
                                color = Color.White,
                                modifier = Modifier.clip(Radius.pill)
                                    .background(Color.White.copy(alpha = 0.16f))
                                    .pressClick { ask.launch(Manifest.permission.CAMERA) }
                                    .heightIn(min = 48.dp)
                                    .padding(horizontal = Gap.lg, vertical = Gap.md),
                            )
                        }
                    }
                }
            }
        }

        Sheet {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Gap.md),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    Modifier.size(40.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), Radius.field),
                    contentAlignment = Alignment.Center,
                ) {
                    GlasmioIcon("camera.viewfinder", contentDescription = null, tint = MaterialTheme.colorScheme.primary, size = 22.dp)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Gap.xs)) {
                    Text(if (code == null) "等待识别" else "识别结果", fontSize = Type.title, fontWeight = FontWeight.SemiBold, color = ink.strong)
                    if (code == null) {
                        Text("无需拍照。保持二维码清晰，识别成功后将自动继续。", fontSize = Type.meta, lineHeight = Type.metaLine, color = ink.muted)
                    } else {
                        SelectionContainer {
                            Text(code.orEmpty(), fontSize = Type.body, lineHeight = Type.bodyLine, color = ink.strong)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(Gap.sm))
    }
}

@Composable
private fun ScanViewfinder() {
    val accent = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxSize()) {
        val side = minOf(size.width * 0.66f, size.height * 0.52f, 240.dp.toPx())
        val left = (size.width - side) / 2f
        val top = (size.height - side) / 2f - 20.dp.toPx()
        val right = left + side
        val bottom = top + side
        val dim = Color.Black.copy(alpha = 0.34f)
        drawRect(dim, size = Size(size.width, top))
        drawRect(dim, Offset(0f, bottom), Size(size.width, size.height - bottom))
        drawRect(dim, Offset(0f, top), Size(left, side))
        drawRect(dim, Offset(right, top), Size(size.width - right, side))
        val length = 24.dp.toPx()
        val stroke = 3.dp.toPx()
        listOf(
            Offset(left, top) to Offset(left + length, top),
            Offset(left, top) to Offset(left, top + length),
            Offset(right, top) to Offset(right - length, top),
            Offset(right, top) to Offset(right, top + length),
            Offset(left, bottom) to Offset(left + length, bottom),
            Offset(left, bottom) to Offset(left, bottom - length),
            Offset(right, bottom) to Offset(right - length, bottom),
            Offset(right, bottom) to Offset(right, bottom - length),
        ).forEach { (start, end) -> drawLine(accent, start, end, strokeWidth = stroke, cap = StrokeCap.Round) }
        drawLine(Color.White.copy(alpha = 0.28f), Offset(left + 12.dp.toPx(), (top + bottom) / 2f), Offset(right - 12.dp.toPx(), (top + bottom) / 2f), strokeWidth = 1.dp.toPx())
    }
}

@Composable
private fun CameraPreview(onCode: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val executor = remember { Executors.newSingleThreadExecutor() }
    // 绑上相机之后才有：变焦要往它身上设
    var camera by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }
    // 当前缩放比。捏合的时候由监听器写，按丸子的时候由下面那排写
    var zoom by remember { mutableStateOf(1f) }
    var baseZoom by remember { mutableStateOf(1f) }
    /*
     * 扫码器**不能在 remember {} 里造**。10-20 01:42 米11 上就是这么炸的：
     * ML Kit 的 `getClient()` 一旦初始化不了，异常是抛在组合阶段的，整条主线程直接 FATAL，
     * 界面上连一句"扫码起不来"都来不及显示。现在等相机服务真的拿到了再建，
     * 建不起来就把原因显示在黑底那一块，取景框还在，App 还在。
     */
    var scanner by remember { mutableStateOf<BarcodeScanner?>(null) }
    var trouble by remember { mutableStateOf<String?>(null) }

    val scale = remember {
        android.view.ScaleGestureDetector(context, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: android.view.ScaleGestureDetector): Boolean {
                // 起手时读一次相机现在的值：上一发捏到哪儿、这次就从哪儿接着捏，
                // 自己记一份会跟相机不同步（切后台再回来它自己会复位）
                baseZoom = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
                return true
            }

            override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                val cam = camera ?: return false
                val st = cam.cameraInfo.zoomState.value ?: return false
                val to = (baseZoom * detector.scaleFactor).coerceIn(st.minZoomRatio, st.maxZoomRatio)
                cam.cameraControl.setZoomRatio(to)
                zoom = to
                return true
            }
        })
    }

    /** 设一档缩放；相机还没绑上就什么都不做（那一排丸子此时也不该出现）。 */
    fun setZoom(z: Float) {
        val cam = camera ?: return
        val st = cam.cameraInfo.zoomState.value ?: return
        val to = z.coerceIn(st.minZoomRatio, st.maxZoomRatio)
        cam.cameraControl.setZoomRatio(to)
        zoom = to
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { scanner?.close() }
            scanner = null
            executor.shutdown()
        }
    }

    LaunchedEffect(Unit) {
        val provider = cameraProvider(context)
        if (provider == null) {
            trouble = "拿不到相机服务（CameraX 没起来）"
            return@LaunchedEffect
        }
        val client = runCatching {
            BarcodeScanning.getClient(
                BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                    .build(),
            )
        }.getOrElse {
            // 别写成"这台机器不支持"：10-20 那次真凶是我们自己的 R8 把 ML Kit 内部类优化坏了，
            // 跟设备没关系。文案只说没起来，把原话带出来就行，剩下的交给 mapping.txt 反查。
            trouble = "二维码识别没起来：${it.message ?: it.javaClass.simpleName}"
            return@LaunchedEffect
        }
        scanner = client
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { use ->
                use.setAnalyzer(executor) { proxy ->
                    val image = proxy.image
                    val sc = scanner
                    if (image == null || sc == null) {
                        proxy.close()
                        return@setAnalyzer
                    }
                    // 识别本身也可能抛（帧已回收、模型没就绪），抛了就关帧继续等下一张
                    runCatching {
                        sc.process(InputImage.fromMediaImage(image, proxy.imageInfo.rotationDegrees))
                            .addOnSuccessListener { list -> list.firstOrNull()?.rawValue?.let(onCode) }
                            .addOnCompleteListener { proxy.close() }
                    }.onFailure { proxy.close() }
                }
            }
        runCatching {
            provider.unbindAll()
            camera = provider.bindToLifecycle(
                lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis,
            )
        }.onFailure { trouble = "相机打不开：${it.message ?: it.javaClass.simpleName}" }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = {
                previewView.apply {
                    // 捏合要落在预览这一层上。PreviewView 自己不吃触摸，
                    // 所以这里返回 true 把这一路事件收走不影响别的
                    isClickable = true
                    setOnTouchListener { _, ev -> scale.onTouchEvent(ev) }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (trouble == null) {
            ScanViewfinder()
            Text(
                "将二维码放入框内",
                fontSize = Type.meta,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.TopCenter)
                    .padding(top = Gap.lg)
                    .background(Color.Black.copy(alpha = 0.4f), Radius.pill)
                    .padding(horizontal = Gap.lg, vertical = Gap.sm),
            )
            val maxZoom = camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f
            if (maxZoom > 1.2f) {
                Row(
                    Modifier.align(Alignment.BottomCenter)
                        .padding(horizontal = Gap.md, vertical = Gap.lg)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(Gap.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val steps = (listOf(1f, 2f, 4f) + maxZoom).distinct().filter { it <= maxZoom }
                    steps.forEach { z ->
                        val selected = kotlin.math.abs(zoom - z) < 0.06f
                        Text(
                            text = if (z == maxZoom && z > 4f) "最大" else "${z.roundToInt()}×",
                            fontSize = Type.meta,
                            fontWeight = FontWeight.SemiBold,
                            color = if (selected) Color(0xFF10151F) else Color.White,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.clip(CircleShape)
                                .background(if (selected) Color.White else Color.Black.copy(alpha = 0.5f))
                                .pressClick { setZoom(z) }
                                .heightIn(min = 48.dp)
                                .widthIn(min = 48.dp)
                                .padding(horizontal = Gap.md, vertical = Gap.md),
                        )
                    }
                }
            }
        }
        trouble?.let { why ->
            Column(
                Modifier.align(Alignment.Center)
                    .padding(Gap.lg)
                    .background(Color.Black.copy(alpha = 0.85f), Radius.card)
                    .padding(Gap.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Gap.sm),
            ) {
                Text("相机暂不可用", fontSize = Type.title, fontWeight = FontWeight.SemiBold, color = Color.White)
                Text(why, fontSize = Type.meta, lineHeight = Type.metaLine, color = Color.White.copy(alpha = 0.78f), textAlign = TextAlign.Center)
            }
        }
    }
}

private suspend fun cameraProvider(context: Context): ProcessCameraProvider? =
    suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            { cont.resume(runCatching { future.get() }.getOrNull(), null) },
            ContextCompat.getMainExecutor(context),
        )
    }
