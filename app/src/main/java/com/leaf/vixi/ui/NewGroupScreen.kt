package com.leaf.vixi.ui

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 新建群聊：勾选已有角色（≥2）+ 群名。
 *
 * 群聊仍然**每轮 1 次调用**：一次请求里每个角色的人设一起进 system，
 * 模型按「名字：内容」多行输出，回本地按名字切气泡（群成员表就是切分依据）。
 */
@Composable
fun NewGroupScreen(
    vm: ChatViewModel,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var title by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var status by remember { mutableStateOf<String?>(null) }

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
                Text("新建群聊", color = VixiPalette.text, fontSize = 15.sp)
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(VixiPalette.hairline))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("群名（可留空）", color = VixiPalette.muted, fontSize = 11.sp)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(VixiPalette.her)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    if (title.isEmpty()) {
                        Text("比如：小群", color = VixiPalette.muted, fontSize = 13.sp)
                    }
                    BasicTextField(
                        value = title,
                        onValueChange = { title = it; status = null },
                        textStyle = TextStyle(color = VixiPalette.text, fontSize = 13.sp),
                        cursorBrush = SolidColor(VixiPalette.accent),
                        maxLines = 1,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    text = "选成员（至少 2 个，已选 ${selected.size} 个）",
                    color = VixiPalette.muted,
                    fontSize = 11.sp,
                )

                state.characters.forEach { c ->
                    val on = c.id in selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(VixiPalette.her)
                            .clickableNoIndication {
                                selected = if (on) selected - c.id else selected + c.id
                                status = null
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(
                            which = c.id,
                            size = 32.dp,
                            fallback = c.name.take(1),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(c.name, color = VixiPalette.text, fontSize = 14.sp)
                            Text(
                                text = "人设 ${c.persona.length} 字",
                                color = VixiPalette.muted,
                                fontSize = 10.5.sp,
                            )
                        }
                        Text(
                            text = if (on) "✓" else "○",
                            color = if (on) VixiPalette.accent else VixiPalette.muted,
                            fontSize = 15.sp,
                        )
                    }
                }

                if (state.characters.size < 2) {
                    Text(
                        text = "角色不够两个：先回上一层「＋ 新建角色」建一个人。",
                        color = VixiPalette.danger,
                        fontSize = 11.sp,
                    )
                }

                status?.let { Text(it, color = VixiPalette.danger, fontSize = 11.sp) }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(VixiPalette.mine)
                        .clickableNoIndication {
                            val err = vm.createGroup(title, selected.toList())
                            if (err == null) onDone() else status = err
                        }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("创建群聊", color = VixiPalette.text, fontSize = 14.sp)
                }
                Text(
                    text = "群里每个人说的话会带名字和各自头像；不必每人一句，允许互相接话。",
                    color = VixiPalette.muted,
                    fontSize = 10.5.sp,
                )
            }
        }
    }
}
