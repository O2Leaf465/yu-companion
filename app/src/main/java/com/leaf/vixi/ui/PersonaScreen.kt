package com.leaf.vixi.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leaf.vixi.App
import com.leaf.vixi.data.Character
import kotlinx.coroutines.launch

/**
 * 人设页：**按会话编辑**。
 *
 * 人设属于角色，不属于 App —— 单聊里就是这一个角色的人设，
 * 群聊里把每个成员的人设分别列出来，一次只改一位。
 *
 * 以前这里固定改"默认角色"，而且字号/来源读的是老的 `persona_override.md`，
 * 改了角色的真实人设这一页根本不变（真机上踩过）。
 *
 * 缓存提醒：人设是缓存前缀的主体，改一次会让下一轮缓存失效一次，之后重新稳定。
 */
@Composable
fun PersonaScreen(
    vm: ChatViewModel,
    onBack: () -> Unit,
    onOpenSystemPrompt: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val engine = remember { App.instance.persona }
    val members = state.members
    val defaultText = remember { engine.defaultPersona }

    var selectedId by remember { mutableStateOf(members.firstOrNull()?.id ?: "c1") }

    /**
     * 草稿 = （哪个角色，改成了什么）。
     *
     * 为什么不是 `remember(selectedId) { mutableStateOf(人设) }`：
     * 会话还在装载时 `members` 是空列表，第一帧拿到的是内置默认人设；
     * 等真实人设到了，`selectedId` 没变 → `remember` 不会重算，编辑框里就一直是默认人设，
     * 这时候按「保存」会把角色的人设冲掉。用"草稿归属角色"判定就不会有这种半截状态。
     */
    var draft by remember { mutableStateOf<Pair<String, String>?>(null) }

    // 选中的 id 在装载完成前可能还不存在（冷启动先拿默认 "c1"，而这个会话可能只有 c2）。
    // 所以当前角色要**回落到第一个成员**，绝不能是 null —— 否则页面显示默认人设且存不进去。
    val current: Character? = members.firstOrNull { it.id == selectedId } ?: members.firstOrNull()
    val currentId = current?.id

    /** 害羞阈值草稿（0~1，越大越不容易害羞）。跟着选中的角色走 */
    var shyDraft by remember(currentId) { mutableStateOf(current?.shy ?: 0.5) }
    val text = draft?.takeIf { it.first == currentId }?.second
        ?: current?.persona
        ?: defaultText
    fun editText(value: String) {
        currentId?.let { draft = it to value }
    }

    var status by remember { mutableStateOf<String?>(null) }

    /** 情绪反应表草稿（每人设一份，存在 files/emotion_map_<id>.txt） */
    var mapOpen by remember { mutableStateOf(false) }
    var mapDraft by remember(currentId) {
        mutableStateOf(currentId?.let { vm.reactionTable.raw(it) } ?: "")
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val customized = text.trim() != defaultText.trim()

    // 从文件导入人设：读进编辑器，由你确认后点「保存」——不直接覆盖，避免选错文件
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val content = PromptFile.read(context, uri)
                if (content.isNullOrBlank()) {
                    status = "这个文件读不出来（不是文本？）"
                } else {
                    editText(content.trim())
                    status = "已载入 ${content.length} 字，确认后点『保存』"
                }
            }
        }
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
                Text("人设", color = VixiPalette.text, fontSize = 15.sp)
                Box(Modifier.weight(1f))
                TextAction(
                    label = "导入",
                    color = VixiPalette.accent,
                    horizontalPadding = 8,
                    onClick = { importLauncher.launch(arrayOf("text/*", "application/octet-stream")) },
                )
                TextAction(
                    label = "还原默认",
                    horizontalPadding = 8,
                    onClick = {
                        val id = currentId
                        if (id == null) {
                            status = "没有可保存的角色"
                        } else if (!vm.resetPersonaOf(id)) {
                            // 写盘失败不能报"已还原"：以前这里是无条件提示，看着成功了其实没存
                            status = "保存失败（写文件出错）"
                        } else {
                            draft = id to defaultText
                            status = "已还原为内置默认"
                        }
                    },
                )
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(VixiPalette.hairline))

            // 群聊：先选改谁。单聊只有一位，就不摆这一排了
            if (members.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "改谁：",
                        color = VixiPalette.muted,
                        fontSize = 11.sp,
                    )
                    members.forEach { c ->
                        val on = c.id == currentId
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (on) VixiPalette.mine else VixiPalette.her)
                                .clickableNoIndication {
                                    selectedId = c.id
                                    status = null
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = c.name,
                                color = if (on) VixiPalette.text else VixiPalette.muted,
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
            }

            Text(
                text = if (members.size > 1) {
                    "这个群有 ${members.size} 位角色，每人一份人设，互不共用"
                } else {
                    "「${current?.name ?: "她"}」的人设 · " +
                        if (customized) "你改过的版本" else "内置默认"
                },
                color = VixiPalette.muted,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(VixiPalette.her)
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = {
                        editText(it)
                        status = null
                    },
                    textStyle = TextStyle(
                        color = VixiPalette.text,
                        fontSize = 12.5.sp,
                        lineHeight = 19.sp,
                    ),
                    cursorBrush = SolidColor(VixiPalette.accent),
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                )
            }

            // 情绪反应表：同一个"委屈"，不同人设该有不同反应（用户要求"标签要便于更改"）
            TextAction(
                label = if (mapOpen) "收起情绪反应表 ▴" else "编辑情绪反应表 ▾",
                horizontalPadding = 0,
                color = VixiPalette.accent,
                onClick = { mapOpen = !mapOpen },
            )
            if (mapOpen) {
                Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Text(
                        text = "格式：主情绪>次情绪=动作|长度乘子。没写的组合自动退回「只用主情绪」。",
                        color = VixiPalette.muted,
                        fontSize = 10.5.sp,
                        lineHeight = 15.sp,
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .padding(top = 6.dp)
                            .background(VixiPalette.panel, RoundedCornerShape(10.dp))
                            .padding(8.dp),
                    ) {
                        androidx.compose.foundation.text.BasicTextField(
                            value = mapDraft,
                            onValueChange = { mapDraft = it },
                            textStyle = TextStyle(
                                color = VixiPalette.text,
                                fontSize = 11.5.sp,
                                lineHeight = 16.sp,
                            ),
                            cursorBrush = SolidColor(VixiPalette.accent),
                            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        TextAction(
                            label = "保存反应表",
                            horizontalPadding = 0,
                            color = VixiPalette.accent,
                            onClick = {
                                val id = currentId
                                if (id == null) {
                                    status = "没有可保存的角色"
                                } else if (vm.reactionTable.save(id, mapDraft)) {
                                    status = "反应表已保存 · 下一轮起生效"
                                } else {
                                    status = "保存失败（写文件出错）"
                                }
                            },
                        )
                        TextAction(
                            label = "还原内置",
                            horizontalPadding = 0,
                            onClick = {
                                val id = currentId ?: return@TextAction
                                vm.reactionTable.reset(id)
                                mapDraft = vm.reactionTable.raw(id)
                                status = "已还原为内置表"
                            },
                        )
                    }
                }
            }

            // 害羞阈值：不同人设该有不同门槛（内向的一被夸就炸，大大咧咧的只会笑）
            Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("害羞阈值", color = VixiPalette.text, fontSize = 13.sp)
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = if (shyDraft >= 0.7) "很难害羞" else if (shyDraft >= 0.4) "普通" else "很容易害羞",
                        color = VixiPalette.muted,
                        fontSize = 11.sp,
                    )
                }
                androidx.compose.material3.Slider(
                    value = shyDraft.toFloat(),
                    onValueChange = { shyDraft = it.toDouble() },
                    valueRange = 0f..1f,
                    colors = androidx.compose.material3.SliderDefaults.colors(
                        thumbColor = VixiPalette.accent,
                        activeTrackColor = VixiPalette.accent,
                        inactiveTrackColor = VixiPalette.hairline,
                    ),
                )
                Text(
                    text = "越大越不容易害羞。存盘后下一轮起生效。",
                    color = VixiPalette.muted,
                    fontSize = 10.5.sp,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${text.length} 字 · 每轮全量注入",
                        color = VixiPalette.muted,
                        fontSize = 11.sp,
                    )
                    status?.let {
                        Text(it, color = VixiPalette.accent, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(2.dp))
                    TextAction(
                        label = "看当前会话的完整 system ›",
                        color = VixiPalette.accent,
                        fontSize = 11.sp,
                        horizontalPadding = 0,
                        verticalPadding = 2,
                        onClick = onOpenSystemPrompt,
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(VixiPalette.mine)
                        .clickableNoIndication {
                            val id = currentId
                            when {
                                id == null -> status = "没有可保存的角色"
                                text.isBlank() -> status = "不能存空的"
                                vm.saveCharacterOf(id, text, shyDraft) -> {
                                    // 存进去之后草稿就过期了，编辑框回到"角色当前人设"
                                    draft = null
                                    status = "已保存 · 下一轮起生效"
                                }
                                else -> status = "保存失败（写文件出错）"
                            }
                        }
                        .padding(horizontal = 26.dp, vertical = 12.dp),
                ) {
                    Text("保存", color = VixiPalette.text, fontSize = 14.sp)
                }
            }
        }
    }
}
