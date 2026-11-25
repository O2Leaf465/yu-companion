package com.leaf.vixi.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * 新建角色：名字 + 头像 + 人设（可粘贴，也可以导入 md）。
 *
 * 头像选完先进裁剪页，确认后**先留在内存里**：角色还没有 id，等点了保存、角色建出来
 * 才能按 id 落盘（files/avatar/c2.jpg）。人设留空就用内置默认。
 */
@Composable
fun NewCharacterScreen(
    vm: ChatViewModel,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf("") }
    var persona by remember { mutableStateOf("") }
    var avatar by remember { mutableStateOf<Bitmap?>(null) }
    var status by remember { mutableStateOf<String?>(null) }

    var cropUri by remember { mutableStateOf<Uri?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) cropUri = uri
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val content = PromptFile.read(context, uri)
                if (content.isNullOrBlank()) {
                    status = "这个文件读不出来（不是文本？）"
                } else {
                    persona = content.trim()
                    status = "已载入 ${content.length} 字"
                }
            }
        }
    }

    val uri = cropUri
    if (uri != null) {
        AvatarCropScreen(
            uri = uri,
            onCancel = { cropUri = null },
            onConfirm = { bitmap ->
                avatar = bitmap
                cropUri = null
            },
        )
        return
    }

    Box(modifier = Modifier.fillMaxSize().background(VixiPalette.ink)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextAction(
                    label = "‹ 返回",
                    color = VixiPalette.accent,
                    fontSize = 14.sp,
                    horizontalPadding = 8,
                    onClick = onBack,
                )
                Text("新建角色", color = VixiPalette.text, fontSize = 15.sp)
                Box(Modifier.weight(1f))
                TextAction(
                    label = "导入人设",
                    color = VixiPalette.accent,
                    horizontalPadding = 8,
                    onClick = { importer.launch(arrayOf("text/*", "application/octet-stream")) },
                )
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(VixiPalette.hairline))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val bmp = avatar
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(VixiPalette.her)
                            .clickableNoIndication {
                                picker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Text("＋", color = VixiPalette.muted, fontSize = 20.sp)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = if (avatar == null) "点头像上传" else "点头像重选",
                            color = VixiPalette.text,
                            fontSize = 13.sp,
                        )
                        Text(
                            text = "头像按角色存，聊天里就靠它区分谁在说话",
                            color = VixiPalette.muted,
                            fontSize = 10.5.sp,
                        )
                    }
                }

                FieldLabel("名字")
                InputBox(
                    value = name,
                    placeholder = "比如：阿岚",
                    onChange = { name = it; status = null },
                    singleLine = true,
                )

                FieldLabel("人设（可留空，留空用内置默认）")
                InputBox(
                    value = persona,
                    placeholder = "他/她是谁、说话方式、在意什么……",
                    onChange = { persona = it; status = null },
                    singleLine = false,
                )

                status?.let { Text(it, color = VixiPalette.accent, fontSize = 11.sp) }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(VixiPalette.mine)
                        .clickableNoIndication {
                            val err = vm.createCharacter(name, persona, avatar)
                            if (err == null) onDone() else status = err
                        }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("保存并开始聊天", color = VixiPalette.text, fontSize = 14.sp)
                }
                Text(
                    text = "保存后会立刻建好和他的单聊并切过去。",
                    color = VixiPalette.muted,
                    fontSize = 10.5.sp,
                )
            }
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text = text, color = VixiPalette.muted, fontSize = 11.sp)
}

@Composable
private fun InputBox(
    value: String,
    placeholder: String,
    onChange: (String) -> Unit,
    singleLine: Boolean,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(VixiPalette.her)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        if (value.isEmpty()) {
            Text(placeholder, color = VixiPalette.muted, fontSize = 13.sp)
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = TextStyle(color = VixiPalette.text, fontSize = 13.sp),
            cursorBrush = SolidColor(VixiPalette.accent),
            maxLines = if (singleLine) 1 else 10,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
