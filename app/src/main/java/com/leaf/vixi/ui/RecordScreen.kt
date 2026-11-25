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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leaf.vixi.data.RecordArchive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 聊天记录：导出 / 导入 / 删除。
 *
 * 页面只做三件事，全部围绕"当前会话"——切会话是聊天页的事，这里不重复。
 * 删除是唯一不可逆的动作，所以它必须先看到一句**说清删什么、留什么**的话再按。
 */
@Composable
fun RecordScreen(vm: ChatViewModel, onBack: () -> Unit) {
    val rec by vm.records.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmImport by remember { mutableStateOf<RecordArchive.Backup?>(null) }
    // 默认**不勾**：用户往往是"先导出一份保险，再删"，默认连备份一起删正好把刚做的那份保险删掉。
    // 每次打开对话框都重置，别让上一次的选择粘住。
    var withBackups by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.loadRecords() }

    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.CHINA) }

    Box(modifier = Modifier.fillMaxSize().background(VixiPalette.ink)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
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
                Text("聊天记录", color = VixiPalette.text, fontSize = 15.sp)
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(VixiPalette.hairline))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // ── 概况 ──
                Card {
                    Text(rec.title.ifBlank { "当前会话" }, color = VixiPalette.text, fontSize = 14.sp)
                    Text(
                        "${rec.messages} 条消息 · ${rec.memories} 条记忆索引",
                        color = VixiPalette.muted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        "OB 笔记：文档/鱼/${rec.subDir.ifBlank { "（未命名）" }}",
                        color = VixiPalette.muted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }

                // ── 导出 ──
                Card(onClick = { if (!rec.busy) vm.exportRecords() }) {
                    Text("导出备份", color = VixiPalette.accent, fontSize = 14.sp)
                    Text(
                        "正文 + 记忆索引 + 复习 + 共召回 + 改口 + 话题，打包成一个 json；" +
                            "同时发一份到 文档/鱼/_备份",
                        color = VixiPalette.muted,
                        fontSize = 11.5.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                // ── 删除（危险） ──
                Card(onClick = { if (!rec.busy) { withBackups = false; confirmDelete = true } }) {
                    Text("删除聊天记录", color = VixiPalette.danger, fontSize = 14.sp)
                    Text(
                        "一并删除这个会话的记忆索引、复习/共召回/改口记录，以及 文档/鱼/${rec.subDir} 里的 OB 笔记",
                        color = VixiPalette.muted,
                        fontSize = 11.5.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                if (!rec.notice.isNullOrBlank()) {
                    Text(rec.notice!!, color = VixiPalette.accent, fontSize = 12.sp)
                }
                if (rec.busy) {
                    Text("处理中…", color = VixiPalette.muted, fontSize = 12.sp)
                }

                // ── 备份列表（点一行 = 导入） ──
                Text(
                    "本机备份（点一行导入到当前会话）",
                    color = VixiPalette.muted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
                if (rec.backups.isEmpty()) {
                    Text("还没有备份", color = VixiPalette.muted, fontSize = 12.sp)
                } else {
                    rec.backups.forEach { b ->
                        Card(onClick = { if (!rec.busy) confirmImport = b }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        b.title.ifBlank { b.file.name },
                                        color = VixiPalette.text,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        "${fmt.format(Date(b.at))} · ${b.count} 条 · ${b.file.name}",
                                        color = VixiPalette.muted,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 2.dp),
                                    )
                                }
                                Text("导入 ›", color = VixiPalette.accent, fontSize = 12.sp)
                            }
                        }
                    }
                }

                Text(
                    "备份放在 App 私有目录 files/backups/，同时拷一份到 文档/鱼/_备份。" +
                        "换手机前把它发给自己就能搬走。",
                    color = VixiPalette.muted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        // ── 删除确认：一句话说清删什么、留什么 ──
        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                containerColor = VixiPalette.panel,
                title = { Text("删除这个会话的聊天记录？", color = VixiPalette.text, fontSize = 15.sp) },
                text = {
                    Column {
                        Text(
                            "会删掉：这个会话的聊天正文、记忆索引、复习/共召回/改口记录、话题分段，" +
                                "以及 文档/鱼/${rec.subDir} 下的导出笔记。" +
                                "会留下：角色、人设、状态（情绪与关系四量）、自我叙事.md、其他会话。" +
                                "删除不可撤销。",
                            color = VixiPalette.muted,
                            fontSize = 12.5.sp,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 6.dp),
                        ) {
                            Checkbox(
                                checked = withBackups,
                                onCheckedChange = { withBackups = it },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = VixiPalette.danger,
                                    uncheckedColor = VixiPalette.muted,
                                ),
                            )
                            Text(
                                "连这个会话的历史备份一起删",
                                color = VixiPalette.muted,
                                fontSize = 12.sp,
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDelete = false
                        vm.deleteRecords(withBackups)
                    }) { Text("删除", color = VixiPalette.danger) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDelete = false }) {
                        Text("取消", color = VixiPalette.muted)
                    }
                },
            )
        }

        // ── 导入确认：覆盖是破坏性的，也要先说清 ──
        confirmImport?.let { b ->
            AlertDialog(
                onDismissRequest = { confirmImport = null },
                containerColor = VixiPalette.panel,
                title = { Text("用备份覆盖当前会话？", color = VixiPalette.text, fontSize = 15.sp) },
                text = {
                    Text(
                        "会删掉：${rec.title} 现在的 ${rec.messages} 条消息和它的记忆索引。" +
                            "会装上：${b.file.name}（${b.count} 条，${fmt.format(Date(b.at))}）。" +
                            "会留下：角色、人设、状态、其他会话。",
                        color = VixiPalette.muted,
                        fontSize = 12.5.sp,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val f = b.file
                        confirmImport = null
                        vm.importRecords(f)
                    }) { Text("覆盖导入", color = VixiPalette.accent) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmImport = null }) {
                        Text("取消", color = VixiPalette.muted)
                    }
                },
            )
        }
    }
}

/** 一张卡片：没给 onClick 就是纯展示 */
@Composable
private fun Card(onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val base = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .background(VixiPalette.her)
    val styled = if (onClick == null) base else base.clickableNoIndication { onClick() }
    Box(modifier = styled.padding(horizontal = 16.dp, vertical = 14.dp)) {
        Column { content() }
    }
}
