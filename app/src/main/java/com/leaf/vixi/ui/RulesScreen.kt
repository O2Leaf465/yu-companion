package com.leaf.vixi.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leaf.vixi.App
import kotlinx.coroutines.launch

/**
 * 格式约定（全局）：**可导入、可现场编辑**，不分角色、不分会话。
 *
 * 为什么是全局的：它管的是"怎么分条、能不能用 Markdown、记不住时怎么认账、群里怎么带名字"，
 * 属于 App 的机械规则，不属于某个人物。人设按角色走，见「人设」页。
 *
 * 默认值在代码里（`PersonaEngine.FORMAT_RULES`），改过就存 `files/rules_override.md`，
 * 「还原默认」永远能退回去。
 */
@Composable
fun RulesScreen(onBack: () -> Unit) {
    val engine = remember { App.instance.persona }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var rules by remember { mutableStateOf(engine.rulesText) }
    var status by remember { mutableStateOf<String?>(null) }
    var customized by remember { mutableStateOf(engine.isRulesCustomized) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val content = PromptFile.read(context, uri)
                if (content.isNullOrBlank()) {
                    status = "这个文件读不出来（不是文本？）"
                } else {
                    rules = content.trim()
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
                Text("格式约定", color = VixiPalette.text, fontSize = 15.sp)
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
                        engine.resetRules()
                        rules = engine.rulesText
                        customized = false
                        status = "已还原为内置默认"
                    },
                )
                TextAction(
                    label = "保存",
                    color = VixiPalette.accent,
                    horizontalPadding = 8,
                    onClick = {
                        val ok = engine.saveRules(rules)
                        status = if (ok) "已保存 · 下一轮生效" else "保存失败（内容为空？）"
                        if (ok) customized = true
                    },
                )
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(VixiPalette.hairline))

            Text(
                text = "来源：" + (if (customized) "你改过的版本" else "内置默认") +
                    " · 规则 ${rules.length} 字 · **所有会话共用**",
                color = VixiPalette.muted,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            status?.let {
                Text(
                    text = it,
                    color = VixiPalette.accent,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
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
                BasicTextField(
                    value = rules,
                    onValueChange = { rules = it },
                    textStyle = TextStyle(
                        color = VixiPalette.text,
                        fontSize = 11.5.sp,
                        lineHeight = 17.sp,
                        fontFamily = FontFamily.Monospace,
                    ),
                    cursorBrush = SolidColor(VixiPalette.accent),
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                )
            }
            Text(
                text = "群聊的名字前缀格式（「名字：内容」）也在这里；人设去「人设」页改",
                color = VixiPalette.muted,
                fontSize = 10.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
    }
}
