package com.leaf.vixi.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leaf.vixi.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 头像设置：**按当前会话里的角色**列出来。
 *
 * 单聊只有一位；群聊列出身会话里的每一位 —— 头像按角色 id 存（files/avatar/c1.jpg），
 * 聊天里每条气泡就靠它区分谁在说话。
 * （"你自己的头像"那一份已删：气泡旁从来不显示它。）
 * 统一缩到 256px —— 头像在聊天里最大也就 36dp，存原图只是浪费空间和内存。
 */
@Composable
fun AvatarScreen(vm: ChatViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val store = App.instance.images
    val members = state.members

    var tick by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }
    var cropUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var cropFor by remember { mutableStateOf<String?>(null) }
    var pickFor by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            cropUri = uri
            cropFor = pickFor
        }
    }

    val uri = cropUri
    val which = cropFor
    if (uri != null && which != null) {
        AvatarCropScreen(
            uri = uri,
            onCancel = { cropUri = null; cropFor = null },
            onConfirm = { bitmap ->
                // 缩放 + JPEG 压不要在主线框上做
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { store.saveAvatarBitmap(bitmap, which) }
                    status = if (ok) "头像已更新" else "保存失败"
                    tick++
                    cropUri = null
                    cropFor = null
                }
            },
        )
        return
    }

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
                    label = "‹ 返回",
                    color = VixiPalette.accent,
                    fontSize = 14.sp,
                    horizontalPadding = 8,
                    onClick = onBack,
                )
                Text("头像", color = VixiPalette.text, fontSize = 15.sp)
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(VixiPalette.hairline))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = when {
                        members.isEmpty() -> "会话还在装载…"
                        members.size > 1 ->
                            "会话「${state.conversationTitle}」· ${members.size} 位角色，头像分开存"
                        else -> "会话「${state.conversationTitle}」"
                    },
                    color = VixiPalette.muted,
                    fontSize = 11.sp,
                )

                members.forEach { c ->
                    AvatarEditor(
                        title = "${c.name}的头像",
                        hint = if (members.size > 1) {
                            "群聊气泡上就靠它区分谁在说话"
                        } else {
                            "聊天里点她的头像双击可以摸头"
                        },
                        which = c.id,
                        fallback = c.name.take(1),
                        tick = tick,
                        onPick = {
                            pickFor = c.id
                            picker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onClear = {
                            store.clearAvatar(c.id)
                            tick++
                            status = "已清除「${c.name}」的头像"
                        },
                    )
                }

                status?.let { Text(it, color = VixiPalette.accent, fontSize = 12.sp) }

                Text(
                    text = "提示：头像会缩到 256px 再存，换头像不影响聊天记录。",
                    color = VixiPalette.muted,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun AvatarEditor(
    title: String,
    hint: String,
    which: String,
    fallback: String,
    tick: Int,
    onPick: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(VixiPalette.her)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(
            which = which,
            size = 56.dp,
            fallback = fallback,
            refreshKey = tick,
        )
        Column(
            modifier = Modifier.weight(1f).padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(title, color = VixiPalette.text, fontSize = 14.sp)
            Text(hint, color = VixiPalette.muted, fontSize = 11.sp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TextAction(
                label = "更换",
                color = VixiPalette.accent,
                horizontalPadding = 6,
                onClick = onPick,
            )
            TextAction(
                label = "清除",
                horizontalPadding = 6,
                onClick = onClear,
            )
        }
    }
}
