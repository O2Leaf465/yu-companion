package com.leaf.vixi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 注入文案编辑弹窗（2026-11-25）。
 *
 * 两个用途共用同一个弹窗，所以标题和格式提示必须由调用方给：
 *  · 「注入标签」→ `files/emotion_map_<角色id>.txt`（情绪→动作 + `_模板=`）
 *  · 「注入文案」→ `files/injection_<角色id>.txt`（【想念】【想要什么】【说多少】）
 *
 * 早先标题写死成"情绪反应表"，第二个入口点开来标题是错的（真机上验出来的）。
 */
@Composable
fun InjectionDialog(
    initial: String,
    onSave: (String) -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
    title: String = "注入标签（情绪反应表）",
    hint: String = "格式：主情绪>次情绪=动作|长度乘子\n" +
        "注入那行的写法：_模板=【此刻状态】{main}{second}（{act}）。",
) {
    var text by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title, fontSize = 15.sp) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = hint,
                    color = VixiPalette.muted,
                    fontSize = 10.5.sp,
                    lineHeight = 15.sp,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .padding(top = 8.dp)
                        .background(VixiPalette.panel, RoundedCornerShape(10.dp))
                        .padding(8.dp),
                ) {
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        textStyle = TextStyle(
                            color = VixiPalette.text,
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp,
                        ),
                        cursorBrush = SolidColor(VixiPalette.accent),
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onReset(); onClose() }) { Text("还原内置", fontSize = 13.sp) }
                TextButton(onClick = { onSave(text); onClose() }) { Text("保存", fontSize = 13.sp) }
            }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text("取消", fontSize = 13.sp) }
        },
    )
}
