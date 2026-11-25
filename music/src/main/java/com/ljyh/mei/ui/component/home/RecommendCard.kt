package com.ljyh.mei.ui.component.home

import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.toBitmap
import com.kyant.capsule.ContinuousRoundedRectangle
import com.ljyh.mei.constants.RecommendCardCornerRadius
import com.ljyh.mei.constants.RecommendCardHeight
import com.ljyh.mei.constants.RecommendCardWidth
import io.github.neoruaa.glasmio.glass.createProgressiveBackdropBlurRenderEffect
import com.ljyh.mei.utils.largeImage
import kotlin.math.ceil
import kotlin.math.max

private val RecommendCardShape = ContinuousRoundedRectangle(RecommendCardCornerRadius)

@Composable
fun RecommendCard(
    cover: String,
    title: String? = null,
    extInfo: CardExtInfo,
    showPlay: Boolean = false,
    isLoading: Boolean = false,
    cardWidth: Dp = RecommendCardWidth,
    cardHeight: Dp = RecommendCardHeight,
    onClick: () -> Unit = {},
) {
    val context = LocalContext.current
    val coverModel = cover.takeIf { it.isNotBlank() }?.largeImage()
    val coverRequest = remember(context, coverModel) {
        coverModel?.let {
            ImageRequest.Builder(context)
                .data(it)
                // The extension uses the decoded bitmap directly; reveal both without a cover-only fade.
                .crossfade(false)
                .allowHardware(false)
                .build()
        }
    }
    val currentCoverModel = rememberUpdatedState(coverModel)
    var loadedBitmap by remember(coverModel) { mutableStateOf<Bitmap?>(null) }
    val clearArtworkHeight = cardWidth
    val reflectionHeight = cardHeight - clearArtworkHeight
    val reflectionLayerHeight = cardHeight / 3

    Box(
        modifier = Modifier
            .size(cardWidth, cardHeight)
            // Cache the static squircle-clipped card (cover, blur strip, text, border) in a GPU
            // layer so scrolling, nav transitions, and player morphs never re-upload 731x975 SW path masks.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .clip(RecommendCardShape)
            .homeArtworkBorder(RecommendCardShape)
            .background(Color.DarkGray)
            .clickable(enabled = !isLoading, onClick = onClick),
    ) {
        HomeCoverImage(
            model = coverRequest,
            modifier = Modifier.size(cardWidth, clearArtworkHeight),
            onLoading = {
                if (currentCoverModel.value == coverModel) {
                    loadedBitmap = null
                }
            },
            onSuccess = { state ->
                if (currentCoverModel.value == coverModel) {
                    loadedBitmap = state.result.image.toBitmap()
                }
            },
            onError = {
                if (currentCoverModel.value == coverModel) {
                    loadedBitmap = null
                }
            },
        )
        loadedBitmap?.let { bitmap ->
            RecommendationArtworkExtension(
                bitmap,
                clearArtworkHeight,
                reflectionHeight,
                Modifier.align(Alignment.BottomStart).size(cardWidth, reflectionLayerHeight),
            )
        }
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(reflectionLayerHeight)
                .drawWithCache {
                    val layerTop = cardHeight.roundToPx() - size.height
                    val scrim = Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.30f)),
                        startY = cardHeight.toPx() * 2f / 3f - layerTop,
                        endY = size.height,
                    )
                    onDrawBehind { drawRect(scrim) }
                },
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(
                    start = 16.dp,
                    end = if (isLoading || showPlay) 56.dp else 16.dp,
                    bottom = 16.dp,
                ),
        ) {
            RecommendationDescription(extInfo)
            Spacer(Modifier.height(2.dp))
            Text(
                text = title.orEmpty(),
                color = Color.White,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).size(28.dp),
                color = Color.White,
                strokeWidth = 2.dp,
            )
        } else if (showPlay) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.PlaylistPlay,
                contentDescription = "Play",
                tint = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).size(28.dp),
            )
        }
    }
}

private class ArtworkExtensionCache {
    var bitmap: Bitmap? = null
    var size: IntSize = IntSize.Zero
    var density: Float = Float.NaN
    var clearHeight: Dp = Dp.Unspecified
    var reflectionHeight: Dp = Dp.Unspecified
    var direction: LayoutDirection? = null
}

@Composable
private fun RecommendationArtworkExtension(
    bitmap: Bitmap,
    clearArtworkHeight: Dp,
    reflectionHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val cardHeight = clearArtworkHeight + reflectionHeight
    val paint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.5f) })
        }
    }
    val sourceLayer = rememberGraphicsLayer()
    val cachedLayer = rememberGraphicsLayer()
    val cache = remember { ArtworkExtensionCache() }
    // Cache the static local blur + mask strip at half resolution (preserving the exact
    // 150px-capped Gaussian sigma) so tab switches and scrolling cut progressive blur work ~8x.
    Box(
        modifier.drawBehind {
            val layerSize = IntSize(
                ceil(size.width).toInt().coerceAtLeast(1),
                ceil(size.height).toInt().coerceAtLeast(1),
            )
            val blurSize = IntSize(
                ceil(size.width * 0.5f).toInt().coerceAtLeast(1),
                ceil(size.height * 0.5f).toInt().coerceAtLeast(1),
            )
            val needsRecord = cache.bitmap !== bitmap ||
                cache.size != layerSize ||
                cache.density != density ||
                cache.clearHeight != clearArtworkHeight ||
                cache.reflectionHeight != reflectionHeight ||
                cache.direction != layoutDirection
            if (needsRecord) {
                val drawSize = size
                val blurDrawSize = Size(blurSize.width.toFloat(), blurSize.height.toFloat())
                val scaleX = blurDrawSize.width / drawSize.width
                val scaleY = blurDrawSize.height / drawSize.height
                val clearHeight = clearArtworkHeight.roundToPx().toFloat()
                val cardHeightPx = cardHeight.roundToPx().toFloat()
                val layerTop = cardHeightPx - drawSize.height
                val scale = max(drawSize.width / bitmap.width, clearHeight / bitmap.height)
                val width = bitmap.width * scale
                val height = bitmap.height * scale
                val left = (drawSize.width - width) / 2f
                val top = (clearHeight - height) / 2f
                val artworkBounds = RectF(left, top, left + width, top + height)
                val edgeY = kotlin.math.floor((clearHeight - top) / scale - 0.5f)
                    .toInt().coerceIn(0, bitmap.height - 1)
                sourceLayer.clip = true
                sourceLayer.renderEffect = createProgressiveBackdropBlurRenderEffect(
                    size = blurDrawSize,
                    maxRadius = 100.dp.toPx().coerceAtMost(150f) * scaleY,
                    startYFraction = (cardHeight.toPx() * 2f / 3f - layerTop) / drawSize.height,
                    endYFraction = 1f,
                    startIntensity = 0f,
                    endIntensity = 1f,
                )
                sourceLayer.record(this, layoutDirection, blurSize) {
                    drawIntoCanvas { canvas ->
                        val nativeCanvas = canvas.nativeCanvas
                        val save = nativeCanvas.save()
                        nativeCanvas.scale(scaleX, scaleY)
                        nativeCanvas.translate(0f, -layerTop)

                        // The blur transition samples the same center-cropped cover as the visible artwork.
                        val transitionSave = nativeCanvas.save()
                        nativeCanvas.clipRect(0f, layerTop, drawSize.width, clearHeight)
                        nativeCanvas.drawBitmap(bitmap, null, artworkBounds, paint)
                        nativeCanvas.restoreToCount(transitionSave)

                        // Extend the visible cover's bottom row while preserving its horizontal crop.
                        nativeCanvas.clipRect(0f, clearHeight, drawSize.width, cardHeightPx)
                        nativeCanvas.drawBitmap(
                            bitmap,
                            Rect(0, edgeY, bitmap.width, edgeY + 1),
                            RectF(left, clearHeight, left + width, cardHeightPx),
                            paint,
                        )
                        nativeCanvas.restoreToCount(save)
                    }
                }
                val mask = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Black),
                    startY = (cardHeight.toPx() * 2f / 3f - layerTop) * scaleY,
                    endY = (clearArtworkHeight.toPx() - layerTop) * scaleY,
                )
                cachedLayer.clip = true
                cachedLayer.compositingStrategy =
                    androidx.compose.ui.graphics.layer.CompositingStrategy.Offscreen
                cachedLayer.record(this, layoutDirection, blurSize) {
                    drawLayer(sourceLayer)
                    drawRect(mask, blendMode = BlendMode.DstIn)
                }
                cache.bitmap = bitmap
                cache.size = layerSize
                cache.density = density
                cache.clearHeight = clearArtworkHeight
                cache.reflectionHeight = reflectionHeight
                cache.direction = layoutDirection
            }
            scale(
                scaleX = size.width / blurSize.width.toFloat(),
                scaleY = size.height / blurSize.height.toFloat(),
                pivot = Offset.Zero,
            ) {
                drawLayer(cachedLayer)
            }
        },
    )
}

@Composable
private fun RecommendationDescription(extInfo: CardExtInfo) {
    val iconModel = extInfo.icon?.takeIf { it.isNotBlank() }
    val currentIconModel = rememberUpdatedState(iconModel)
    var iconTerminal by remember(iconModel) { mutableStateOf(iconModel == null) }
    var iconSucceeded by remember(iconModel) { mutableStateOf(false) }
    val color = Color.White.copy(alpha = 0.8f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (iconModel != null && (!iconTerminal || iconSucceeded)) {
            AsyncImage(
                model = iconModel,
                modifier = Modifier.size(16.dp).clip(ContinuousRoundedRectangle(4.dp)),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = ColorFilter.tint(color),
                onLoading = {
                    if (currentIconModel.value == iconModel) {
                        iconTerminal = false
                        iconSucceeded = false
                    }
                },
                onSuccess = {
                    if (currentIconModel.value == iconModel) {
                        iconTerminal = true
                        iconSucceeded = true
                    }
                },
                onError = {
                    if (currentIconModel.value == iconModel) {
                        iconTerminal = true
                        iconSucceeded = false
                    }
                },
            )
            if (iconSucceeded) Spacer(Modifier.width(4.dp))
        }
        Text(
            text = extInfo.text,
            color = color,
            fontSize = 14.sp,
            lineHeight = 18.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

data class CardExtInfo(val icon: String? = null, val text: String)
