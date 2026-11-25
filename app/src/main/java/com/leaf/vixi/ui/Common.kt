package com.leaf.vixi.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 可点文字。
 *
 * 顺序很重要：clip → clickable → padding。
 * 如果写成 padding → clickable，可点区域就只剩文字本身那一小块，
 * 手指按在文字旁边的留白上会没反应（"保存"点不动就是这么来的）。
 */
@Composable
fun TextAction(
    label: String,
    modifier: Modifier = Modifier,
    color: Color = VixiPalette.muted,
    fontSize: TextUnit = 12.sp,
    horizontalPadding: Int = 10,
    verticalPadding: Int = 6,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickableNoIndication { onClick() }
            .padding(horizontal = horizontalPadding.dp, vertical = verticalPadding.dp),
    ) {
        Text(text = label, color = color, fontSize = fontSize)
    }
}
