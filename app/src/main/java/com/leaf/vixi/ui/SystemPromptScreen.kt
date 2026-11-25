package com.leaf.vixi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leaf.vixi.App

/**
 * 系统提示词：**当前会话实际拼出来的 system**，只读。
 *
 * 为什么改成按会话渲染：单聊只有一份人设，群聊是「每个成员的人设 + 全局格式约定」。
 * 以前这一页固定显示默认角色那一份，群聊时等于在骗人（页面说"每轮实际发给模型的完整 system"）。
 *
 * 可编辑的只有两块：人设（属于角色，去「人设」页）和格式约定（全局，去「格式约定」页）。
 */
@Composable
fun SystemPromptScreen(
    vm: ChatViewModel,
    onBack: () -> Unit,
    onEditPersona: () -> Unit,
    onEditRules: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val engine = remember { App.instance.persona }

    // 人设改过之后这一页要跟着变：state.characters 变化会触发重组
    val system = remember(state.characters, state.conversationId) { vm.systemPreview() }
    val members = state.members

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
                Text("系统提示词", color = VixiPalette.text, fontSize = 15.sp)
                Box(Modifier.weight(1f))
                TextAction(
                    label = "复制",
                    horizontalPadding = 10,
                    onClick = { clipboard.setText(AnnotatedString(system)) },
                )
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(VixiPalette.hairline))

            Text(
                text = "会话「${state.conversationTitle}」· " +
                    (if (members.size > 1) "${members.size} 份人设 + " else "1 份人设 + ") +
                    "格式约定 · ${system.length} 字",
                color = VixiPalette.muted,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            ) {
                TextAction(
                    label = "改人设 ›",
                    color = VixiPalette.accent,
                    fontSize = 11.sp,
                    horizontalPadding = 4,
                    onClick = onEditPersona,
                )
                TextAction(
                    label = "改格式约定（全局）›",
                    color = VixiPalette.accent,
                    fontSize = 11.sp,
                    horizontalPadding = 4,
                    onClick = onEditRules,
                )
            }

            // ── 人物事实（2026-11-25）──
            // 这些不是 system 的一部分，而是**每轮拼在 user 段最前面**的长期设定。
            // 放到这个页面是因为"人设在这里、事实在别处"最容易让人找不到。
            run {
                val factStore = remember { App.instance.facts }
                var showFacts by remember { mutableStateOf(false) }
                var tick by remember { mutableIntStateOf(0) }
                val factsByMember = remember(tick, state.conversationId, members) {
                    members.associate { it.id to factStore.list(it.id).map { f -> f.text } }
                }
                val total = factsByMember.values.sumOf { it.size }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextAction(
                        label = if (showFacts) "人物事实（$total）▴" else "人物事实（$total）▾",
                        color = VixiPalette.accent, fontSize = 11.sp, horizontalPadding = 4,
                        onClick = { showFacts = !showFacts },
                    )
                    Text(
                        text = "每轮都进提示词 · 由她在聊天里自己记",
                        color = VixiPalette.muted, fontSize = 10.sp,
                    )
                }

                if (showFacts) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .padding(horizontal = 12.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (total == 0) {
                            Text(
                                text = "还没有。聊到她的喜好/经历时，她会自己记一条。",
                                color = VixiPalette.muted, fontSize = 10.5.sp,
                            )
                        }
                        members.forEach { m ->
                            (factsByMember[m.id] ?: emptyList()).forEach { text ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = (if (members.size > 1) "${m.name}：" else "· ") + text,
                                        color = VixiPalette.text.copy(alpha = 0.85f),
                                        fontSize = 11.sp,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextAction(
                                        label = "✕", color = VixiPalette.danger,
                                        fontSize = 12.sp, horizontalPadding = 6,
                                        onClick = {
                                            factStore.remove(m.id, text)
                                            tick++
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(VixiPalette.her)
                    .padding(10.dp)
            ) {
                Text(
                    text = system,
                    color = VixiPalette.text.copy(alpha = 0.9f),
                    fontSize = 11.5.sp,
                    lineHeight = 17.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                )
            }
            Text(
                text = "这就是这一轮会原样发出去的 system（规则可直接改，人设按角色改）",
                color = VixiPalette.muted,
                fontSize = 10.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
    }
}
