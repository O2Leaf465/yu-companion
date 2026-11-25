package com.leaf.vixi.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.leaf.vixi.data.VixiState
import com.leaf.vixi.state.Decay
import com.leaf.vixi.state.Explain
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 调试页：0 token，一次看全内部状态。
 *
 * 布局原则（改版后）：
 *  - 顶栏只留"返回 + 标题"，其余动作放下面一排胶囊按钮 —— 以前挤到"导出 OB"竖着折行
 *  - 每块信息一张卡片：状态底座 / 上一轮 / 检索 / 原始 dump
 *  - 状态数值可以**直接拖滑块改**，旁边实时显示这一轮会注入的那句话
 *  - 数值只在这里出现，绝不进 prompt、绝不进聊天气泡
 */
@Composable
fun DebugScreen(vm: ChatViewModel, onBack: () -> Unit, onOpenPrompts: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    // 注入标签直接在调试页改（用户要求）
    var showInjection by remember { mutableStateOf(false) }
    // 【想念】【想要什么】【说多少】这些文案单独一个弹窗（用户 2026-11-25 要求可改）
    var showInjectionText by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    var editing by remember { mutableStateOf(false) }
    var showRaw by remember { mutableStateOf(false) }

    // 进调试页就刷新一次状态：外部删过文件、或改过数值，也能立刻看到最新的
    LaunchedEffect(Unit) { vm.refreshStateNow() }

    // 停在这一页时每 2 秒结算一次：半衰期 30 秒，能看到数字肉眼回落（离开这页自动停）
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(2000)
            vm.tickState()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(VixiPalette.ink)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            // ── 顶栏：只放返回和标题，别的都挪走 ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextAction(
                    label = "‹ 返回",
                    color = VixiPalette.accent,
                    fontSize = 14.sp,
                    horizontalPadding = 8,
                    onClick = onBack,
                )
                Text("调试", color = VixiPalette.text, fontSize = 15.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (state.sandbox) "测试环境" else "",
                    color = VixiPalette.danger,
                    fontSize = 10.5.sp,
                )
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(VixiPalette.hairline))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // ── 工具行：横向滚动，永远不折行 ──
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ToolChip("请求记录", VixiPalette.accent, onOpenPrompts)
                    ToolChip("注入标签", VixiPalette.accent) { showInjection = true }
                    ToolChip("注入文案", VixiPalette.accent) { showInjectionText = true }
                    ToolChip("主动测试", VixiPalette.accent) { vm.proactiveTest() }

                    if (showInjection) {
                        // AlertDialog 是独立窗口，放在 Row 里也能正常弹
                        val charId = vm.stateCharIdPublic()
                        InjectionDialog(
                            initial = charId?.let { vm.reactionTable.raw(it) } ?: "",
                            onSave = { text -> charId?.let { vm.reactionTable.save(it, text) } },
                            onReset = { charId?.let { vm.reactionTable.reset(it) } },
                            onClose = { showInjection = false },
                        )
                    }
                    if (showInjectionText) {
                        // 【想念】【想要什么】【说多少】这些文案（用户 2026-11-25 要求可改）
                        val charId = vm.stateCharIdPublic()
                        InjectionDialog(
                            initial = charId?.let { vm.injectionTable.raw(it) } ?: "",
                            title = "注入文案（想念 / 想要什么 / 说多少）",
                            hint = "一行一个：键=文案。删掉某行就回默认。\n" +
                                "可用键：missing.mild · missing.strong · need.touch.close · " +
                                "need.touch.mild · need.belong · need.respect.warm · " +
                                "need.respect.cold · length.short · length.normal · length.long",
                            onSave = { text ->
                                charId?.let { vm.injectionTable.save(it, text) }
                                vm.injectionTable.bind(charId)
                            },
                            onReset = { charId?.let { vm.injectionTable.reset(it) } },
                            onClose = { showInjectionText = false },
                        )
                    }
                    ToolChip(
                        label = if (state.sandbox) "测试环境:开" else "测试环境:关",
                        color = if (state.sandbox) VixiPalette.danger else VixiPalette.muted,
                        onClick = { vm.toggleSandbox() },
                    )
                    if (state.sandbox) {
                        ToolChip("清空测试", VixiPalette.danger) { vm.clearSandbox() }
                    }
                    ToolChip("导出 OB", VixiPalette.accent) { vm.exportToObsidian() }
                    ToolChip("复制 dump", VixiPalette.muted) {
                        clipboard.setText(AnnotatedString(state.debugText))
                    }
                }

                // ── 卡片：状态底座 ──
                StateCard(
                    stateOn = state.stateOn,
                    isGroup = state.isGroup,
                    snapshot = state.stateSnapshot,
                    onToggle = { vm.toggleSelfState() },
                    onReset = { vm.resetSelfState() },
                onToggleLearn = { vm.toggleLearn() },
                onToggleSilence = { vm.toggleSilence() },
                onProactiveCheck = { vm.proactiveCheck() },
                onEdit = { editing = true },
                )

                // ── 卡片：这句话怎么被理解（评价层 P1） ──
                AppraisalCard(state.recentAppraisals)

                // ── 卡片：上一轮 ──
                Card(title = "上一轮") {
                    val t = state.lastTurn
                    if (t.at.isBlank()) {
                        Text(
                            "还没跑过一轮。去设置里填好 baseUrl / model / key，然后发一条消息。",
                            color = VixiPalette.muted,
                            fontSize = 11.5.sp,
                            lineHeight = 17.sp,
                        )
                    } else {
                        KvGrid(
                            listOf(
                                "时间" to t.at,
                                "会话" to t.conversation,
                                "模型" to t.model,
                                "key" to t.keyMask,
                                "历史" to "${t.historyCount} 条（window=${t.historyWindow}）",
                                "新消息" to if (t.newMessages.isEmpty()) "0 条" else
                                    "${t.newMessages.size} 条「${t.newMessages.joinToString(" / ").take(40)}」",
                                "图片" to "${t.imageCount} 张",
                                "user 段" to "${t.userChars} 字 ≈ ${t.userTokens} tok",
                                "system" to "${t.systemChars} 字 ≈ ${t.systemTokens} tok",
                                "人设" to "${t.personaChars} 字",
                                "耗时" to "${t.elapsedMs} ms",
                                "回复" to "${t.replies} 条 · 输出 ${t.completionTokens} tok",
                            ),
                        )
                        if (t.recallInfo.isNotBlank()) {
                            Text(
                                "记忆检索：${t.recallInfo}",
                                color = VixiPalette.muted,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }

                // ── 卡片：原始 dump（默认折叠） ──
                Card(
                    title = "原始 dump",
                    action = {
                        TextAction(
                            label = if (showRaw) "收起" else "展开",
                            horizontalPadding = 8,
                            color = VixiPalette.accent,
                            onClick = { showRaw = !showRaw },
                        )
                    },
                ) {
                    if (showRaw) {
                        Text(
                            text = state.debugText,
                            color = VixiPalette.text.copy(alpha = 0.84f),
                            fontSize = 10.5.sp,
                            lineHeight = 16.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    } else {
                        Text(
                            "完整提示词、返回原文、解析结果都在这。复制 dump 也会复制它。",
                            color = VixiPalette.muted,
                            fontSize = 11.sp,
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }

        // ── 数值编辑器（浮层） ──
        if (editing) {
            val snapshot = state.stateSnapshot
            if (snapshot != null) {
                StateEditSheet(
                    initial = snapshot,
                    onCancel = { editing = false },
                    onApply = {
                        vm.applyStateEdit(it)
                        editing = false
                    },
                )
            }
        }
    }
}

/** 状态卡片：开关 / 编辑 / 重置 + 数值网格 + 注入行 + 最近事件 */
@Composable
private fun StateCard(
    stateOn: Boolean,
    isGroup: Boolean,
    snapshot: VixiState?,
    onToggle: () -> Unit,
    onReset: () -> Unit,
    onEdit: () -> Unit,
    onToggleLearn: () -> Unit,
    onToggleSilence: () -> Unit,
    onProactiveCheck: () -> Unit,
) {
    Card(
        title = if (stateOn) "状态底座：开" else "状态底座：关",
        action = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (stateOn) {
                    TextAction(label = "改数值", horizontalPadding = 10, color = VixiPalette.accent, onClick = onEdit)
                }
                TextAction(
                    label = if (snapshot?.learnOn == true) "学习:开" else "学习:关",
                    horizontalPadding = 10,
                    color = if (snapshot?.learnOn == true) VixiPalette.accent else VixiPalette.muted,
                    onClick = onToggleLearn,
                )
                TextAction(label = "重置", horizontalPadding = 10, onClick = onReset)
                TextAction(label = "主动?", horizontalPadding = 10, onClick = onProactiveCheck)
                TextAction(
                    label = if (stateOn) "开" else "关",
                    horizontalPadding = 10,
                    color = if (stateOn) VixiPalette.accent else VixiPalette.muted,
                    onClick = onToggle,
                )
            }
        },
    ) {
        val s = snapshot
        if (s == null) {
            Text("（状态未装载）", color = VixiPalette.muted, fontSize = 11.5.sp)
            return@Card
        }
        if (!s.enabled) {
            Text(
                "这个角色没开状态底座：数值不动、也不注入。点右边「关」变成「开」。",
                color = VixiPalette.muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
            return@Card
        }

        KvGrid(
            listOf(
                "效价 p" to f2(s.p),
                "唤醒 a" to f2(s.a),
                "支配 d" to f2(s.d),
                "暗流" to f2(s.backlog),
                "冲突" to f2(s.conflict),
                "信任" to f3(s.trust),
                "亲近" to f3(s.closeness),
                "修复" to f2(s.repair),
                "心境" to signed(s.mood),
                "分寸" to Explain.tactWord(s),
                // 害羞只活 3 分钟：过期了别还挂在这儿（和"下轮注入"对不上）
                "上次" to (
                    if (!Explain.isShy(s) && s.lastLabel == com.leaf.vixi.state.EmotionLabel.SHY.zh) {
                        "${s.lastLabel}（已过）"
                    } else {
                        s.lastLabel.ifBlank { "—" }
                    }
                    ),
                // 带上"目标"：白天曲线平，只看当前值会以为体力是死的
                "体力" to "${f2(s.energy)} → ${f2(Decay.energyTarget())}",
                // 下面四个是**派生量**（不是独立状态）：从上面的数值算出来，直接决定"说多少"
                "压力" to f2(Explain.pressure(s)),
                "展开" to f2(Explain.openUp(s)),
                "疲劳" to Explain.fatigueWord(s),
                "说多少" to "${Explain.speakBudget(s)} 字",
                // 需要（N1：先只显示，看清楚数值怎么动）
                "被摸" to f2(s.needTouch),
                "被尊重" to f2(s.needRespect),
                "被在意" to f2(com.leaf.vixi.state.Needs.belong(s)),
                "休息" to f2(com.leaf.vixi.state.Needs.rest(s)),
                "最想要" to (com.leaf.vixi.state.Needs.dominant(s)?.kind?.label ?: "—"),
                // 越界：上一轮你这句话越过的程度（喂给 risk / 关系量 / 被尊重需要）
                "越界" to f2(s.violation),
                // 生物层：余温的缓冲池 / 压痕的负荷 / 牵挂的想念
                "缓冲" to f2(s.surgeP),
                "负荷" to f2(s.load),
                "想念" to f2(s.missing),
                // 主动开口：**实时算一遍**（不发消息、不花钱）。
                // 之前放在按钮里，点完只设 notice，调试页根本看不到 —— 等于没法验证。
                "主动" to com.leaf.vixi.state.Proactive.decide(s, System.currentTimeMillis())
                    .let { "${(it.score * 100).toInt()}% " + if (it.send) "会开口" else "不说" },
                "混合" to com.leaf.vixi.state.EmotionMix.decode(s.emoMix)
                    .joinToString("+") { "${it.label.zh}${(it.w * 100).toInt()}" }
                    .ifBlank { "—" },
                "应对" to (com.leaf.vixi.state.Strategy.decode(s.moves).last?.zh ?: "—"),
                "旋钮" to com.leaf.vixi.state.Growth.encode(com.leaf.vixi.state.Growth.decode(s.knobs))
                    .replace(",", " / ")
                    .let { if (s.learnOn) it else "$it（关）" },
                // 证据进度：攒够 3 次同类才挪一步，看得到 "2/3"
                "证据" to com.leaf.vixi.state.Growth.decodeEvidence(s.evidence)
                    .entries.joinToString(" ") { "${it.key.name.take(3)}${it.value.count}" }
                    .ifBlank { "—" },
                "基线" to "${f2(s.baseP)}/${f2(s.baseA)}/${f2(s.baseD)}",
            ),
        )

        // 下一轮会注入的那句话（改成实时算的：拖完滑块就能看到效果）
        val preview = Explain.promptLine(s)
        val shown = when {
            isGroup -> "（群聊不注入，只更新不注入）"
            else -> preview
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("下轮注入", color = VixiPalette.muted, fontSize = 10.5.sp)
        }
        Text(
            text = shown,
            color = if (isGroup) VixiPalette.muted else VixiPalette.accent,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        )

        // 需要（N2/N3）：下轮会不会注入【想要什么】/【可以主动】，不用发消息就能验
        if (!isGroup) {
            val risk = Explain.riskOf(s)
            val want = com.leaf.vixi.state.Needs.wantLine(s, risk)
            val burst = com.leaf.vixi.state.Needs.burstLine(s, risk)
            val needText = listOf(want, burst).filter { it.isNotBlank() }.joinToString("\n")
            Text(
                text = if (needText.isBlank()) "（不需要注入：最想要的都还没过 0.70，或关系太远只走长度）" else needText,
                color = if (needText.isBlank()) VixiPalette.muted else VixiPalette.accent,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            )
        }

        Text(
            text = "说多少 约 ${Explain.speakBudget(s)} 字（按唤醒/暗流/冲突/信任/底色算）",
            color = VixiPalette.muted,
            fontSize = 10.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            text = "回落 ${Decay.DECAY_HALF_LIFE_MS / 60_000} 分钟半衰 · " +
                "上次结算 " + if (s.lastTick > 0L) clock(s.lastTick) else "（从未）",
            color = VixiPalette.muted,
            fontSize = 10.sp,
            modifier = Modifier.padding(top = 4.dp),
        )

        // 最近事件：最多 4 条，能看出是谁把数值推上去的
        if (s.events.isEmpty()) {
            Text(
                "事件：还没有",
                color = VixiPalette.muted,
                fontSize = 10.5.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        } else {
            Text(
                "最近事件（共 ${s.events.size}）",
                color = VixiPalette.muted,
                fontSize = 10.5.sp,
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
            )
            s.events.takeLast(4).forEach { e ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                    Text(
                        clock(e.ts).takeLast(8),
                        color = VixiPalette.muted,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        " ${e.type} ${e.note} ${e.delta}".trimEnd(),
                        color = VixiPalette.text.copy(alpha = 0.82f),
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }
    }
}

/**
 * 评价层卡片（P1）：这句话被她读成什么样。
 *
 * 四维 + 一个标签 + 强度，全部来自本地规则（`Appraiser` / `Labeler`）。
 * 这一层现在**不影响**数值和提示词，纯粹用来核对"她理解得对不对"。
 */
@Composable
private fun AppraisalCard(items: List<AppraisalUi>) {
    Card(title = "这句话怎么被理解") {
        if (items.isEmpty()) {
            Text(
                "发一条消息后，这里会显示它被读成什么（相关性/利害/应对/归因 → 一个情绪标签）。",
                color = VixiPalette.muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
            return@Card
        }
        // 合并发送时一个窗口可能有好几句：逐条列出标签，四维只看最后一条
        if (items.size > 1) {
            items.forEach { one ->
                Text(
                    "「${one.text.take(16)}」→ ${one.label} ${f2(one.intensity)}",
                    color = VixiPalette.muted,
                    fontSize = 10.5.sp,
                    lineHeight = 15.sp,
                )
            }
            Spacer(Modifier.height(4.dp))
        }
        val ui = items.last()
        val a = ui.appraisal
        Text(
            text = "「${ui.text.take(40)}」",
            color = VixiPalette.text.copy(alpha = 0.86f),
            fontSize = 11.5.sp,
            lineHeight = 16.sp,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(ui.label, color = VixiPalette.accent, fontSize = 13.sp)
            Text(
                "  强度 ${f2(ui.intensity)}",
                color = VixiPalette.muted,
                fontSize = 10.5.sp,
            )
        }
        Spacer(Modifier.height(4.dp))
        KvGrid(
            listOf(
                "相关" to signed(a.relevance),
                "利害" to signed(a.impact),
                "应对" to f2(a.coping),
                "归因" to signed(a.attribution),
            ),
        )
        if (a.events.isNotEmpty()) {
            Text(
                "命中 " + a.events.joinToString("、") { it.key },
                color = VixiPalette.muted,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** 通用卡片：标题行 + 内容 */
@Composable
private fun Card(
    title: String,
    action: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(VixiPalette.panel)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = VixiPalette.text, fontSize = 12.5.sp)
            Spacer(Modifier.weight(1f))
            action?.invoke()
        }
        Spacer(Modifier.height(6.dp))
        content()
    }
}

/** 两列键值对：左边标签右边数值，读起来比一整段文字快 */
@Composable
private fun KvGrid(items: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        items.chunked(2).forEach { pair ->
            Row(modifier = Modifier.fillMaxWidth()) {
                pair.forEach { (k, v) ->
                    Row(modifier = Modifier.weight(1f)) {
                        Text(k, color = VixiPalette.muted, fontSize = 10.5.sp)
                        Text(
                            " $v",
                            color = VixiPalette.text,
                            fontSize = 10.5.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** 工具按钮（胶囊） */
@Composable
private fun ToolChip(
    label: String,
    color: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(VixiPalette.panel)
            .clickableNoIndication { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(label, color = color, fontSize = 11.sp)
    }
}

private fun f2(v: Double) = String.format(Locale.US, "%.2f", v)

/** 带符号显示：+0.80 / -0.50（相关性、利害、归因这些有正负的量） */
private fun signed(v: Double) =
    (if (v >= 0) "+" else "") + String.format(Locale.US, "%.2f", v)
private fun f3(v: Double) = String.format(Locale.US, "%.3f", v)
private fun clock(ts: Long) =
    SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(ts))