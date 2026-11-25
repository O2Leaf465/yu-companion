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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leaf.vixi.App
import com.leaf.vixi.llm.LlmClient
import com.leaf.vixi.llm.LlmException
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    vm: ChatViewModel,
    onBack: () -> Unit,
    onOpenPersona: () -> Unit,
    onOpenSystemPrompt: () -> Unit,
    onOpenAvatar: () -> Unit,
    onOpenRules: () -> Unit,
    onOpenRecords: () -> Unit = {},
    onOpenStickers: () -> Unit = {},
) {
    val settings = remember { App.instance.settings }
    val chatState by vm.state.collectAsStateWithLifecycle()
    // 人设属于角色，属于**当前会话**：单聊一位、群聊每人一份，这里照实显示
    val members = chatState.members
    val personaLine = if (members.size > 1) {
        "群聊 ${members.size} 位：" + members.joinToString(" · ") { "${it.name} ${it.persona.length} 字" }
    } else {
        members.firstOrNull()?.let { "${it.name} · ${it.persona.length} 字 · 点进去改" }
            ?: "点进去改"
    }

    var baseUrl by remember { mutableStateOf(settings.baseUrl) }
    var model by remember { mutableStateOf(settings.model) }
    var apiKey by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var saveTick by remember { mutableStateOf(0) }
    val keyBroken = remember(saveTick) { settings.keyState == com.leaf.vixi.data.SettingsStore.KeyState.Broken }
    val scope = rememberCoroutineScope()
    val llm = remember { LlmClient(settings) }

    fun save() {
        settings.baseUrl = baseUrl
        settings.model = model
        if (apiKey.isNotBlank()) settings.apiKey = apiKey
        apiKey = ""
        saveTick++
        result = if (settings.isConfigured()) "已保存" else "还缺 Key"
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = VixiPalette.text,
        unfocusedTextColor = VixiPalette.text,
        focusedBorderColor = VixiPalette.accent,
        unfocusedBorderColor = VixiPalette.hairline,
        focusedLabelColor = VixiPalette.accent,
        unfocusedLabelColor = VixiPalette.muted,
        cursorColor = VixiPalette.accent,
    )

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
                Text("设置", color = VixiPalette.text, fontSize = 15.sp)
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(VixiPalette.hairline))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(VixiPalette.her)
                        .clickableNoIndication { onOpenPersona() }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("人设", color = VixiPalette.text, fontSize = 14.sp)
                            Text(
                                text = personaLine,
                                color = VixiPalette.muted,
                                fontSize = 11.sp,
                            )
                        }
                        Text("›", color = VixiPalette.muted, fontSize = 16.sp)
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(VixiPalette.her)
                        .clickableNoIndication { onOpenSystemPrompt() }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("系统提示词", color = VixiPalette.text, fontSize = 14.sp)
                            Text(
                                text = "当前会话实际发给模型的完整 system · 只读",
                                color = VixiPalette.muted,
                                fontSize = 11.sp,
                            )
                        }
                        Text("›", color = VixiPalette.muted, fontSize = 16.sp)
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(VixiPalette.her)
                        .clickableNoIndication { onOpenRules() }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("格式约定", color = VixiPalette.text, fontSize = 14.sp)
                            Text(
                                text = "所有会话共用：分条、群聊「名字：内容」、记忆边界",
                                color = VixiPalette.muted,
                                fontSize = 11.sp,
                            )
                        }
                        Text("›", color = VixiPalette.muted, fontSize = 16.sp)
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(VixiPalette.her)
                        .clickableNoIndication { onOpenAvatar() }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("头像", color = VixiPalette.text, fontSize = 14.sp)
                            Text(
                                text = if (members.size > 1) {
                                    "群聊成员头像分开存，气泡上靠它区分谁在说话"
                                } else {
                                    "她的头像可上传；双击她的头像 = 摸摸头"
                                },
                                color = VixiPalette.muted,
                                fontSize = 11.sp,
                            )
                        }
                        Text("›", color = VixiPalette.muted, fontSize = 16.sp)
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(VixiPalette.her)
                        .clickableNoIndication { onOpenStickers() }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("贴纸（她能发的图）", color = VixiPalette.text, fontSize = 14.sp)
                            Text(
                                text = "从相册加图 / 改名 / 删除；文件名就是她看到的图名",
                                color = VixiPalette.muted,
                                fontSize = 11.sp,
                            )
                        }
                        Text("›", color = VixiPalette.muted, fontSize = 16.sp)
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(VixiPalette.her)
                        .clickableNoIndication { onOpenRecords() }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("聊天记录", color = VixiPalette.text, fontSize = 14.sp)
                            Text(
                                text = "导出备份 / 从备份导入 / 删除这个会话的记录与记忆（含 OB 笔记）",
                                color = VixiPalette.muted,
                                fontSize = 11.sp,
                            )
                        }
                        Text("›", color = VixiPalette.muted, fontSize = 16.sp)
                    }
                }

                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "DeepSeek 填 https://api.deepseek.com；OpenAI 兼容中转一般带 /v1",
                    color = VixiPalette.muted,
                    fontSize = 11.sp,
                )

                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("模型名") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    shape = RoundedCornerShape(12.dp),
                    colors = fieldColors,
                    placeholder = {
                        Text(
                            if (settings.apiKey.isBlank()) "sk-..." else "已保存 ${settings.maskedKey()}",
                            fontSize = 13.sp,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Key 用 AndroidKeyStore 加密后存本地，不进代码、不进备份。留空则不改。",
                    color = VixiPalette.muted,
                    fontSize = 11.sp,
                )

                if (keyBroken) {
                    Text(
                        "上次存的 Key 现在解不开（系统密钥被清了）。不是你的错，也不是它被删了 —— 重新填一次并保存即可。",
                        color = VixiPalette.danger,
                        fontSize = 11.sp,
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(VixiPalette.mine)
                        .clickableNoIndication { save() }
                        .padding(vertical = 15.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("保存", color = VixiPalette.text, fontSize = 14.sp)
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(VixiPalette.her)
                        .clickableNoIndication(enabled = !testing) {
                            save()
                            if (!settings.isConfigured()) {
                                result = "先填 Key 再测"
                            } else {
                                testing = true
                                result = "测试中…"
                                scope.launch {
                                    result = try {
                                        llm.ping()
                                    } catch (e: LlmException) {
                                        e.message
                                    } catch (e: Exception) {
                                        "网络不通：${e.message?.take(60)}"
                                    }
                                    testing = false
                                }
                            }
                        }
                        .padding(vertical = 15.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (testing) "测试中…" else "测试连接",
                        color = VixiPalette.text,
                        fontSize = 14.sp,
                    )
                }

                result?.let {
                    Text(it, color = VixiPalette.accent, fontSize = 12.sp)
                }
            }
        }
    }
}
