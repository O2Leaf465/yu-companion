package com.leaf.vixi.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leaf.vixi.App
import com.leaf.vixi.data.Character
import com.leaf.vixi.data.Conversation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 会话列表：单聊 / 群聊 + 新建入口。
 *
 * 从聊天页点标题进来。切会话时**会话本身、聊天记录、话题、纠正记录**是一起换的
 * （见 ChatViewModel.loadConversation），所以这里只负责选，不负责搬数据。
 *
 * 头像按角色 id 存，这里点角色头像就能换 —— 群聊里每条气泡靠它区分是谁。
 */
@Composable
fun ConversationsScreen(
    vm: ChatViewModel,
    onBack: () -> Unit,
    onNewCharacter: () -> Unit,
    onNewGroup: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val store = App.instance.images
    val scope = rememberCoroutineScope()

    var cropUri by remember { mutableStateOf<Uri?>(null) }
    var cropFor by remember { mutableStateOf<String?>(null) }
    var pickFor by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }

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
                // 缩放 + 压缩放 IO，别在主线程做
                scope.launch {
                    withContext(Dispatchers.IO) { store.saveAvatarBitmap(bitmap, which) }
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
                Text("会话", color = VixiPalette.text, fontSize = 15.sp)
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(VixiPalette.hairline))

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 14.dp, vertical = 10.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item { SectionTitle("会话") }
                items(state.conversations, key = { it.id }) { conv ->
                    ConversationRow(
                        conv = conv,
                        members = conv.memberIds.mapNotNull { id ->
                            state.characters.firstOrNull { it.id == id }
                        },
                        current = conv.id == state.conversationId,
                        onClick = {
                            if (vm.openConversation(conv.id)) onBack()
                        },
                    )
                }

                item { Spacer(Modifier.height(10.dp)); SectionTitle("角色") }
                items(state.characters, key = { it.id }) { c ->
                    CharacterRow(
                        character = c,
                        tick = tick,
                        onAvatar = {
                            pickFor = c.id
                            picker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onChat = {
                            if (vm.openCharacterChat(c.id)) onBack()
                        },
                    )
                }

                item {
                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextAction(
                            label = "＋ 新建角色",
                            color = VixiPalette.accent,
                            fontSize = 13.sp,
                            horizontalPadding = 12,
                            verticalPadding = 10,
                            onClick = onNewCharacter,
                        )
                        TextAction(
                            label = "＋ 新建群聊",
                            color = VixiPalette.accent,
                            fontSize = 13.sp,
                            horizontalPadding = 12,
                            verticalPadding = 10,
                            onClick = onNewGroup,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "群聊的记忆会在成员的单聊里被检索到（群 → 单聊单向）；" +
                            "两个人的单聊之间永远不互通。",
                        color = VixiPalette.muted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = VixiPalette.muted,
        fontSize = 11.sp,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp),
    )
}

@Composable
private fun ConversationRow(
    conv: Conversation,
    members: List<Character>,
    current: Boolean,
    onClick: () -> Unit,
) {
    val subtitle = if (conv.isGroup) {
        "群聊 · ${members.size} 人 · " + members.joinToString("、") { it.name }
    } else {
        "单聊"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(VixiPalette.her)
            .clickableNoIndication { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 群聊用成员头像叠不下了，这里用一个圆点标出"这是会话"；当前会话高亮
        Text(
            text = if (current) "●" else "○",
            color = if (current) VixiPalette.accent else VixiPalette.muted,
            fontSize = 12.sp,
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = conv.title.ifBlank { "未命名" },
                color = VixiPalette.text,
                fontSize = 14.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                color = VixiPalette.muted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = if (current) "当前" else "进入 ›",
            color = if (current) VixiPalette.accent else VixiPalette.muted,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun CharacterRow(
    character: Character,
    tick: Int,
    onAvatar: () -> Unit,
    onChat: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(VixiPalette.her)
            .clickableNoIndication { onChat() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(
            which = character.id,
            size = 36.dp,
            fallback = character.name.take(1),
            refreshKey = tick,
            onClick = onAvatar,
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(character.name, color = VixiPalette.text, fontSize = 14.5.sp)
            Text(
                text = "点头像换头像 · 点这行进单聊 · 人设 ${character.persona.length} 字",
                color = VixiPalette.muted,
                fontSize = 10.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text("›", color = VixiPalette.muted, fontSize = 13.sp)
    }
}
