package com.leaf.vixi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 请求记录：每次真的发给 API 的 system / user 原文，逐字可查。
 *
 * 这是"她为什么这么回"的唯一真相来源 —— 调试页给你数字，这里给你原文。
 * 只保留最近若干条，只在内存里（退出 App 就没了），避免把提示词写进手机存储。
 */
@Composable
fun PromptLogScreen(vm: ChatViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current

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
                Text("请求记录", color = VixiPalette.text, fontSize = 15.sp)
                Box(Modifier.weight(1f))
                TextAction(
                    label = "复制全部",
                    horizontalPadding = 8,
                    onClick = { clipboard.setText(AnnotatedString(state.requests.joinToString("\n\n") { it.asText() })) },
                )
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(VixiPalette.hairline))


            if (state.requests.isEmpty()) {
                Text(
                    text = "还没有请求。填好 key 后发一条消息，这里就会出现完整提示词。",
                    color = VixiPalette.muted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.requests, key = { it.index }) { record ->
                        RecordCard(record)
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordCard(record: RequestRecord) {
    val clock = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(record.at))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(VixiPalette.her)
            .padding(12.dp)
    ) {
        Text(
            text = "#${record.index}  $clock  ·  in ${record.promptTokens} / out ${record.completionTokens}" +
                "  ·  ${record.elapsedMs}ms",
            color = VixiPalette.accent,
            fontSize = 10.5.sp,
            fontFamily = FontFamily.Monospace,
        )
        record.error?.let {
            SectionLabel("错误")
            Mono(it, VixiPalette.danger)
        }
        // 单独拎出来：这一段就是"她这轮被告知的旧事"，一眼能核对
        SectionLabel(
            if (record.retrieved.isEmpty()) "记忆检索（未命中）"
            else "她看到的旧事（${record.retrieved.size} 条）"
        )
        if (record.recallQuery.isBlank()) {
            Mono("（本轮没触发检索）", VixiPalette.muted)
        } else {
            val rounds = if (record.recallRounds.isEmpty()) "" else
                "\n轮次（${record.recallRounds.size} 轮）：" +
                    record.recallRounds.joinToString(" ｜ ") { "「$it」" }
            Mono("查询串：「${record.recallQuery}」$rounds\n" +
                (if (record.retrieved.isEmpty()) "（没找到相关原文，按无记忆回话）"
                else record.retrieved.joinToString("\n") { "· $it" }), VixiPalette.text)
        }
        SectionLabel("SYSTEM（${record.system.length} 字）")
        Mono(record.system, VixiPalette.text)
        SectionLabel("USER（${record.user.length} 字）")
        Mono(record.user, VixiPalette.text)
        SectionLabel("模型返回（${record.raw.length} 字）")
        Mono(record.raw.ifBlank { "（空）" }, VixiPalette.muted)
        if (record.replies.isNotEmpty()) {
            SectionLabel("切成 ${record.replies.size} 条")
            Mono(record.replies.joinToString("\n") { "[$it]" }, VixiPalette.muted)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = "── $text ──",
        color = VixiPalette.accent.copy(alpha = 0.75f),
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(top = 8.dp, bottom = 3.dp),
    )
}

@Composable
private fun Mono(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text = text,
        color = color,
        fontSize = 10.5.sp,
        lineHeight = 15.sp,
        fontFamily = FontFamily.Monospace,
    )
}

private fun RequestRecord.asText(): String = buildString {
    appendLine("===== 请求 #$index  ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(at))} =====")
    appendLine("in $promptTokens / out $completionTokens  ·  ${elapsedMs}ms  ·  model=${model}")
    error?.let { appendLine("错误：$it") }
    appendLine()
    appendLine("---------- SYSTEM ----------")
    appendLine(system)
    appendLine()
    appendLine("---------- USER ----------")
    appendLine(user)
    appendLine()
    appendLine("---------- 返回 ----------")
    appendLine(raw.ifBlank { "（空）" })
}
