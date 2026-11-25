package com.leaf.vixi.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leaf.vixi.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 贴纸库（2026-11-25 用户要求：**放到界面上来，方便编辑**）。
 *
 * 这里是"她能发的图"的唯一入口。文件名就是提示词里列给她看的名字，
 * 所以改名等于改她的说法（「猫猫点头.jpg」比「sticker_01.jpg」有用得多）。
 *
 * 三个动作：从相册添加 / 改名 / 删除。删掉最后一张 = 这个能力自动关闭
 * （提示词里不再出现【可以发的图】）。
 */
@Composable
fun StickerScreen(onBack: () -> Unit) {
    val store = App.instance.stickers

    var tick by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    val list = remember(tick) { store.names() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) { adding = false; return@rememberLauncherForActivityResult }
        scope.launch {
            val got = withContext(Dispatchers.IO) { store.addFromUri(uri) }
            status = if (got != null) "已加入：$got" else "这张图读不出来"
            adding = false
            tick++
        }
    }

    // 改名弹窗
    renaming?.let { old ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("改个名字", fontSize = 15.sp) },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        text = "她看到的就是这个名字，起个有画面的（如「猫猫点头」）。",
                        color = VixiPalette.muted, fontSize = 10.5.sp, lineHeight = 15.sp,
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                            .background(VixiPalette.panel, RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        BasicTextField(
                            value = newName,
                            onValueChange = { newName = it.take(12) },
                            textStyle = TextStyle(color = VixiPalette.text, fontSize = 14.sp),
                            cursorBrush = SolidColor(VixiPalette.accent),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val ok = store.rename(old, newName)
                    status = if (ok) "改好了：$newName" else "改不了（重名或原名已不在）"
                    renaming = null
                    tick++
                }) { Text("保存", fontSize = 13.sp) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("取消", fontSize = 13.sp) } },
        )
    }

    Box(modifier = Modifier.fillMaxSize().background(VixiPalette.ink)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextAction(
                    label = "‹ 返回", color = VixiPalette.accent, fontSize = 14.sp,
                    horizontalPadding = 8, onClick = onBack,
                )
                Text("贴纸（她能发的图）", color = VixiPalette.text, fontSize = 15.sp)
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(VixiPalette.hairline))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "她自己会挑要不要发。这里放什么，她就能发什么 —— " +
                        "删到最后一张，这个能力就自动关掉。",
                    color = VixiPalette.muted, fontSize = 11.sp, lineHeight = 16.sp,
                )

                TextAction(
                    label = if (adding) "正在选图…" else "＋ 从相册添加",
                    color = VixiPalette.accent, fontSize = 14.sp, horizontalPadding = 4,
                    onClick = {
                        adding = true
                        picker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                )

                status?.let {
                    Text(it, color = VixiPalette.muted, fontSize = 11.sp)
                }

                if (list.isEmpty()) {
                    Text(
                        text = "还没有贴纸。加几张之后，她就会在合适的时候发图。",
                        color = VixiPalette.muted, fontSize = 12.sp,
                    )
                }

                list.forEach { name ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(VixiPalette.panel, RoundedCornerShape(10.dp))
                            .clickable { newName = name.substringBeforeLast('.'); renaming = name }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = name,
                            color = VixiPalette.text,
                            fontSize = 13.5.sp,
                            modifier = Modifier.weight(1f),
                        )
                        TextAction(
                            label = "改名", color = VixiPalette.muted, fontSize = 12.sp,
                            horizontalPadding = 8,
                            onClick = { newName = name.substringBeforeLast('.'); renaming = name },
                        )
                        TextAction(
                            label = "删除", color = VixiPalette.danger, fontSize = 12.sp,
                            horizontalPadding = 8,
                            onClick = {
                                val ok = store.delete(name)
                                status = if (ok) "删了：$name" else "删不掉"
                                tick++
                            },
                        )
                    }
                }

                Text(
                    text = "存放位置：files/stickers/（文件名 = 她看到的图名）",
                    color = VixiPalette.muted, fontSize = 10.sp,
                )
            }
        }
    }
}
