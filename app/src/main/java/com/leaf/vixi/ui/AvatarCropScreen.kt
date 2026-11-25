package com.leaf.vixi.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leaf.vixi.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 头像裁剪：正方形取景框，可拖动、可缩放。
 *
 * 为什么不给拖动加边界：拖动越界时取景框会露出空白，确认时由 clamp 兜住，
 * 裁剪结果一定落在图片内部 —— 宁可允许拖出去一点，也不要手指一滑就卡住。
 */
@Composable
fun AvatarCropScreen(
    uri: Uri,
    onCancel: () -> Unit,
    onConfirm: (Bitmap) -> Unit,
) {
    val store = App.instance.images
    var source by remember { mutableStateOf<Bitmap?>(null) }
    var userScale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var working by remember { mutableStateOf(false) }

    LaunchedEffect(uri) {
        source = withContext(Dispatchers.IO) { store.decode(uri, 1200) }
    }

    val density = LocalDensity.current
    val boxDp = 320.dp
    val boxPx = with(density) { boxDp.toPx() }

    // 裁剪中按系统返回 = 取消，而不是退回设置页把裁剪丢掉
    androidx.activity.compose.BackHandler(enabled = true) { onCancel() }

    Box(modifier = Modifier.fillMaxSize().background(VixiPalette.ink)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextAction(
                    label = "取消",
                    color = VixiPalette.muted,
                    fontSize = 14.sp,
                    horizontalPadding = 8,
                    onClick = onCancel,
                )
                Text("裁剪头像", color = VixiPalette.text, fontSize = 15.sp)
            }

            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                val bmp = source
                if (bmp == null) {
                    Box(
                        modifier = Modifier.size(boxDp).clip(RoundedCornerShape(12.dp))
                            .background(VixiPalette.her),
                        contentAlignment = Alignment.Center,
                    ) { Text("加载中…", color = VixiPalette.muted, fontSize = 12.sp) }
                } else {
                    Canvas(
                        modifier = Modifier
                            .size(boxDp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(VixiPalette.hairline)
                            .clipToBounds()
                            .pointerInput(Unit) {
                                detectTransformGestures { _, panChange, zoomChange, _ ->
                                    userScale = (userScale * zoomChange).coerceIn(1f, 4f)
                                    pan += panChange
                                }
                            }
                    ) {
                        val image = bmp.asImageBitmap()
                        val base = max(size.width / bmp.width, size.height / bmp.height)
                        val k = base * userScale
                        val dstW = (bmp.width * k).roundToInt()
                        val dstH = (bmp.height * k).roundToInt()
                        val left = ((size.width - dstW) / 2f + pan.x).roundToInt()
                        val top = ((size.height - dstH) / 2f + pan.y).roundToInt()
                        drawImage(
                            image = image,
                            srcOffset = IntOffset.Zero,
                            srcSize = IntSize(bmp.width, bmp.height),
                            dstOffset = IntOffset(left, top),
                            dstSize = IntSize(dstW, dstH),
                        )
                    }
                }

                Text(
                    text = "拖动调整位置，双指缩放",
                    color = VixiPalette.muted,
                    fontSize = 11.sp,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextAction(
                        label = "重新选图",
                        color = VixiPalette.muted,
                        fontSize = 13.sp,
                        horizontalPadding = 14,
                        verticalPadding = 10,
                        onClick = onCancel,
                    )
                    TextAction(
                        label = if (working) "处理中…" else "用这张",
                        color = VixiPalette.accent,
                        fontSize = 13.sp,
                        horizontalPadding = 18,
                        verticalPadding = 10,
                        onClick = {
                            val image = source ?: return@TextAction
                            working = true
                            // 视口是方框，反推它落在原图上的区域
                            val base = max(boxPx / image.width, boxPx / image.height)
                            val k = base * userScale
                            val srcSide = boxPx / k
                            val dstW = image.width * k
                            val dstH = image.height * k
                            val left = (boxPx - dstW) / 2f + pan.x
                            val top = (boxPx - dstH) / 2f + pan.y
                            val cx = (boxPx / 2f - left) / k
                            val cy = (boxPx / 2f - top) / k

                            var x = (cx - srcSide / 2f).roundToInt()
                            var y = (cy - srcSide / 2f).roundToInt()
                            var side = srcSide.roundToInt()
                            side = side.coerceAtMost(minOf(image.width, image.height)).coerceAtLeast(8)
                            x = x.coerceIn(0, (image.width - side).coerceAtLeast(0))
                            y = y.coerceIn(0, (image.height - side).coerceAtLeast(0))

                            val cropped = runCatching {
                                Bitmap.createBitmap(image, x, y, side, side)
                            }.getOrNull()
                            working = false
                            if (cropped != null) onConfirm(cropped) else onCancel()
                        },
                    )
                }
            }
        }
    }
}
