package com.leaf.vixi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leaf.vixi.data.VixiState
import com.leaf.vixi.state.Explain
import java.util.Locale

/**
 * 状态数值编辑器（调试页里弹出的浮层）。
 *
 * 为什么要有：以前想试"委屈"的提示语，得用 adb 改 json 再发消息，一轮三分钟。
 * 现在是拖滑块 → 旁边实时显示**这一轮会注入哪句话** → 点保存，发一条就能看效果。
 *
 * 只改数值：开关和 `lastTick` 不动（一保存就重置结算基准，会让衰减看起来"不生效"）。
 */
@Composable
fun StateEditSheet(
    initial: VixiState,
    onCancel: () -> Unit,
    onApply: (VixiState) -> Unit,
) {
    // 九个可调量。全部用 Double 存，界面按两位小数显示
    var p by remember { mutableStateOf(initial.p) }
    var a by remember { mutableStateOf(initial.a) }
    var d by remember { mutableStateOf(initial.d) }
    var energy by remember { mutableStateOf(initial.energy) }
    var backlog by remember { mutableStateOf(initial.backlog) }
    var conflict by remember { mutableStateOf(initial.conflict) }
    var trust by remember { mutableStateOf(initial.trust) }
    var closeness by remember { mutableStateOf(initial.closeness) }
    var repair by remember { mutableStateOf(initial.repair) }
    var mood by remember { mutableStateOf(initial.mood) }
    // 需要（N1）：加上滑块，否则真机上没法造出"想爆发"的状态，只能 adb 改 json
    var needTouch by remember { mutableStateOf(initial.needTouch) }
    var needRespect by remember { mutableStateOf(initial.needRespect) }
    // 越界（上一轮）：造得出、也清得掉 —— 否则一次越界会一直把 risk 抬高
    var violation by remember { mutableStateOf(initial.violation) }
    // 负荷（生物层·压痕）：真机上要能造出"撑不住"，不然保护分支永远看不到
    var load by remember { mutableStateOf(initial.load) }
    // 生物层新加的量：缓冲池（余温）/ 想念 —— 之前没滑块，真机上根本造不出状态
    var surgeP by remember { mutableStateOf(initial.surgeP) }
    var missing by remember { mutableStateOf(initial.missing) }

    val draft = initial.copy(
        p = p, a = a, d = d, energy = energy, backlog = backlog,
        conflict = conflict, trust = trust, closeness = closeness, repair = repair, mood = mood,
        needTouch = needTouch, needRespect = needRespect, violation = violation, load = load,
        missing = missing,
        surgeP = surgeP, surgeA = a, surgeD = d,
    )
    val preview = remember(draft) { Explain.feeling(draft.copy(enabled = true)) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.62f))
            .clickableNoIndication { onCancel() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                .background(VixiPalette.panel)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .heightIn(max = 640.dp)
                .clickableNoIndication { }
        ) {
            // ── 标题行 ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("直接改状态", color = VixiPalette.text, fontSize = 14.sp)
                Box(Modifier.weight(1f))
                TextAction(label = "取消", horizontalPadding = 10, onClick = onCancel)
                TextAction(
                    label = "保存",
                    horizontalPadding = 10,
                    color = VixiPalette.accent,
                    onClick = { onApply(draft) },
                )
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(VixiPalette.hairline))

            // ── 实时预览：这一轮会注入什么 ──
            Text(
                text = preview.promptLine,
                color = VixiPalette.accent,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )

            // ── 预设：一键进某个状态（调提示语时最省事） ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // 名字 → 组合值（p, a, d, backlog, conflict）
                listOf(
                    "平静" to doubleArrayOf(0.0, 0.3, 0.5, 0.0, 0.0),
                    "开心" to doubleArrayOf(0.6, 0.7, 0.6, 0.0, 0.0),
                    "有点闷" to doubleArrayOf(-0.3, 0.35, 0.5, 0.0, 0.0),
                    "生气" to doubleArrayOf(-0.5, 0.75, 0.6, 0.0, 0.2),
                    "委屈" to doubleArrayOf(-0.5, 0.6, 0.3, 0.0, 0.1),
                    "憋着话" to doubleArrayOf(-0.1, 0.4, 0.5, 0.7, 0.0),
                    "有裂痕" to doubleArrayOf(-0.3, 0.4, 0.4, 0.0, 0.5),
                    "信任低" to doubleArrayOf(0.0, 0.3, 0.5, 0.0, 0.0),
                ).forEach { (label, v) ->
                    PillButton(label) {
                        p = v[0]; a = v[1]; d = v[2]; backlog = v[3]; conflict = v[4]
                        if (label == "信任低") trust = 0.3
                    }
                }
            }

            // ── 滑块 ──
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                SliderRow("效价 p", p, -1.0, 1.0) { p = it }
                SliderRow("唤醒 a", a, 0.0, 1.0) { a = it }
                SliderRow("支配 d", d, 0.0, 1.0) { d = it }
                SliderRow("暗流 backlog", backlog, 0.0, 1.0) { backlog = it }
                SliderRow("冲突 conflict", conflict, 0.0, 1.0) { conflict = it }
                SliderRow("信任 trust", trust, 0.0, 1.0) { trust = it }
                SliderRow("亲近 closeness", closeness, 0.0, 1.0) { closeness = it }
                SliderRow("修复 repair", repair, 0.0, 1.0) { repair = it }
                SliderRow("心境 mood", mood, -1.0, 1.0) { mood = it }
                SliderRow("体力 energy", energy, 0.0, 1.0) { energy = it }
                SliderRow("需要·被摸", needTouch, 0.0, 1.0) { needTouch = it }
                SliderRow("需要·被尊重", needRespect, 0.0, 1.0) { needRespect = it }
                SliderRow("越界（上一轮）", violation, 0.0, 1.0) { violation = it }
                SliderRow("负荷（撑多久）", load, 0.0, 1.0) { load = it }
                SliderRow("缓冲池（余温）", surgeP, -1.0, 1.0) { surgeP = it }
                SliderRow("想念", missing, 0.0, 1.0) { missing = it }
                Text(
                    text = "分寸 risk=${String.format(java.util.Locale.US, "%.2f", Explain.riskOf(draft))} · " +
                        "说多少 ${Explain.speakBudget(draft)} 字",
                    color = VixiPalette.muted,
                    fontSize = 10.5.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
                // 需要的预览：改完立刻能看到会不会注入【想要什么】/【可以主动】
                Text(
                    text = "需要：" + com.leaf.vixi.state.Needs.summary(draft) +
                        "\n注入：" + run {
                            val risk = Explain.riskOf(draft)
                            val burst = com.leaf.vixi.state.Needs.burstLine(draft, risk)
                            val want = if (burst.isBlank()) {
                                com.leaf.vixi.state.Needs.wantLine(draft, risk)
                            } else ""
                            listOf(want, burst).filter { it.isNotBlank() }.joinToString(" ⏎ ")
                                .ifBlank { "（这一轮不会注入需要）" }
                        },
                    color = VixiPalette.muted,
                    fontSize = 10.5.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    text = "保存后建议马上发一条消息看效果；" +
                        "衰减半衰期现在是 ${com.leaf.vixi.state.Decay.DECAY_HALF_LIFE_MS / 1000} 秒，" +
                        "停着不动数值会自己往基线回落。",
                    color = VixiPalette.muted,
                    fontSize = 10.5.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
                )
            }
        }
    }
}

/** 一行滑块：左侧名字、右侧数值（两位小数，够看） */
@Composable
private fun SliderRow(
    label: String,
    value: Double,
    from: Double,
    to: Double,
    onChange: (Double) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                color = VixiPalette.muted,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = String.format(Locale.US, "%.2f", value),
                color = VixiPalette.text,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toDouble()) },
            valueRange = from.toFloat()..to.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = VixiPalette.accent,
                activeTrackColor = VixiPalette.accent,
                inactiveTrackColor = VixiPalette.hairline,
            ),
            modifier = Modifier.fillMaxWidth().height(30.dp),
        )
    }
}

/** 预设按钮：小圆角、点了不弹提示 */
@Composable
private fun PillButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(VixiPalette.ink)
            .clickableNoIndication { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(label, color = VixiPalette.text, fontSize = 11.sp)
    }
}
