package com.leaf.vixi.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.Image
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.leaf.vixi.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 圆形头像。没设过就显示一个字占位。
 *
 * doubleTap 用来做"摸摸头"：先给触感反馈 + 缩放动画，再触发业务动作。
 * 动画做在这里而不是调用方，是为了让"点下去有反应"这件事不依赖网络。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun Avatar(
    which: String,
    size: Dp,
    fallback: String,
    modifier: Modifier = Modifier,
    refreshKey: Int = 0,
    onDoubleTap: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val store = App.instance.images
    val file = remember(which) { store.avatarFile(which) }
    val stamp = refreshKey to file.lastModified()

    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        initialValue = null,
        key1 = which,
        key2 = stamp,
    ) {
        value = withContext(Dispatchers.IO) {
            store.decode(file, 256)?.asImageBitmap()
        }
    }

    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val scale = remember { Animatable(1f) }

    val interaction = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .clip(CircleShape)
            .background(VixiPalette.her)
            .then(
                if (onDoubleTap != null || onClick != null) {
                    Modifier.combinedClickable(
                        indication = null,
                        interactionSource = interaction,
                        onClick = { onClick?.invoke() },
                        onDoubleClick = onDoubleTap?.let { action ->
                            {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                scope.launch {
                                    scale.animateTo(0.82f, tween(90))
                                    scale.animateTo(
                                        1f,
                                        spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium),
                                    )
                                }
                                action()
                            }
                        },
                    )
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = fallback.take(1),
                color = VixiPalette.muted,
                fontSize = (size.value * 0.42f).sp,
            )
        }
    }
}
