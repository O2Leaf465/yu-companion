package com.leaf.vixi.state

import com.leaf.vixi.data.StateEvent
import com.leaf.vixi.data.VixiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 交叉层（第四层）的回归测试：**记忆 ↔ 状态**之间的那条边，以及它的断点。
 *
 * 这块和前三层不同：前三层各自独立（层里怎么算），这一层全是"边"（谁影响谁）。
 * 所以测试的重点不是精度，而是**闭环不会跑飞**：
 *  1. 想起来真的会推 p，而且推得比"真冲突"轻
 *  2. 五条断点各挡一个方向（上限/档位/裂痕/重复/群聊）
 *  3. 冲突会衰减 —— 否则"想起旧事 → conflict↑ → 信任永不回升"这条链会自我焊死
 */
class CrossLayerTest {

    private fun base(conflict: Double = 0.0, events: List<StateEvent> = emptyList()) =
        VixiState(enabled = true, conflict = conflict, events = events, lastTick = 1000L)

    private fun item(start: Long, p: Double, imp: Double, label: String = "生气") =
        RecallActivation.Item(start = start, p = p, importance = imp, label = label)

    // ────────── 一、会推，而且推得轻 ──────────

    @Test
    fun `系数：一次回想约等于半次真冲突`() {
        // 被骂一句 p −0.10；这里 p −0.2、重要度 0.5 → −0.2×0.5×0.15 = −0.015
        val acts = RecallActivation.plan(listOf(item(1000L, -0.2, 0.5)), base(), now = 2000L)
        assertEquals(1, acts.size)
        assertEquals(-0.015, acts[0].deltaP, 0.0001)
        assertTrue("不能比一次真冲突还重", abs(acts[0].deltaP) < 0.10)
    }

    @Test
    fun `正面的旧事会抬心情`() {
        val acts = RecallActivation.plan(listOf(item(1000L, 0.4, 0.7)), base(), now = 2000L)
        assertTrue(acts[0].deltaP > 0)
        assertEquals(0.042, acts[0].deltaP, 0.0005)
    }

    @Test
    fun `空候选不激活`() {
        assertTrue(RecallActivation.plan(emptyList(), base(), now = 2000L).isEmpty())
    }

    @Test
    fun `增量小到落盘就没的不记事件`() {
        // p −0.01 × 重要度 0.2 × 0.15 = −0.0003，三位小数存不下
        assertTrue(RecallActivation.plan(listOf(item(1000L, -0.01, 0.2)), base(), now = 2000L).isEmpty())
    }

    // ────────── 二、断点 ──────────

    @Test
    fun `断点 上限：同时想起三件旧事也不叠加成灾难`() {
        val items = listOf(
            item(1000L, -0.5, 0.8),
            item(2000L, -0.5, 0.8),
            item(3000L, -0.5, 0.8),
        )
        val acts = RecallActivation.plan(items, base(), now = 4000L)
        val total = acts.sumOf { it.deltaP }
        assertEquals("合计夹在 ±0.05", -0.05, total, 0.0001)
        assertEquals("按比例缩，不丢条目", 3, acts.size)
    }

    @Test
    fun `断点 裂痕：有裂痕不翻旧账`() {
        val acts = RecallActivation.plan(
            listOf(item(1000L, -0.5, 0.8)),
            base(conflict = RecallActivation.RIFT_BLOCK),
            now = 2000L,
        )
        assertTrue("conflict ≥ 0.40 一律不激活", acts.isEmpty())
    }

    @Test
    fun `断点 裂痕：刚好差一点还允许`() {
        val acts = RecallActivation.plan(
            listOf(item(1000L, -0.5, 0.8)),
            base(conflict = RecallActivation.RIFT_BLOCK - 0.01),
            now = 2000L,
        )
        assertEquals(1, acts.size)
    }

    @Test
    fun `断点 重复：同一条 24 小时内再想起减半`() {
        val now = 10L * 24 * 60 * 60 * 1000
        val once = base(
            events = listOf(StateEvent(ts = now - 1000L, type = "recall", note = "想起旧事·1000·生气")),
        )
        val acts = RecallActivation.plan(listOf(item(1000L, -0.5, 0.8)), once, now = now)
        // 原始 −0.06，第二次 ×0.5 = −0.03；再被单轮上限夹也还是 −0.03
        assertEquals(-0.03, acts[0].deltaP, 0.0001)
    }

    @Test
    fun `断点 重复：超过 24 小时恢复全额`() {
        val now = 10L * 24 * 60 * 60 * 1000
        val longAgo = base(
            events = listOf(
                StateEvent(
                    ts = now - RecallActivation.REPEAT_WINDOW_MS - 1000L,
                    type = "recall",
                    note = "想起旧事·1000·生气",
                ),
            ),
        )
        val acts = RecallActivation.plan(listOf(item(1000L, -0.5, 0.8)), longAgo, now = now)
        assertEquals("夹到上限 −0.05", -0.05, acts[0].deltaP, 0.0001)
    }

    @Test
    fun `账本靠事件表反解不用新字段`() {
        val now = 5_000_000L
        val s = base(
            events = listOf(
                StateEvent(ts = now - 100L, type = "recall", note = "想起旧事·777·委屈"),
                StateEvent(ts = now - 100L, type = "recall", note = "想起旧事·888·生气"),
                StateEvent(ts = now - 100L, type = "user_msg", note = "想起旧事·999·假装"),
            ),
        )
        val seen = RecallActivation.recentActivations(s, now)
        assertEquals(1, seen[777L])
        assertEquals(1, seen[888L])
        assertTrue("不是 recall 类型的不算", seen[999L] == null)
    }

    @Test
    fun `note 能原样反解出起点`() {
        val a = RecallActivation.Activation(1795162L, "生气", -0.5, 0.8, -0.05)
        assertEquals(1795162L, RecallActivation.startOf(RecallActivation.noteOf(a)))
    }

    @Test
    fun `摘要给调试页看`() {
        val acts = listOf(
            RecallActivation.Activation(1L, "生气", -0.5, 0.8, -0.03),
            RecallActivation.Activation(2L, "委屈", -0.3, 0.5, -0.01),
        )
        val line = RecallActivation.summary(acts)
        assertTrue(line, line.contains("2 条"))
        assertTrue(line, line.contains("0.04"))
    }

    // ────────── 三、闭环不跑飞 ──────────

    @Test
    fun `想起旧事会让心情变差但不会把心情推到谷底`() {
        // 连续被同一件事推 10 次：有重复衰减 + 上限，p 应该只挪一点点。
        // 注意 note 必须按 noteOf 的格式写 —— 账本就存在事件表里，格式一断断点就静默失效
        // （这条测试第一次写就因为漏了 note 而失败，等于替真机挡了一次）。
        var s = base()
        var lastDelta = 0.0
        val now = 10L * 24 * 60 * 60 * 1000
        repeat(10) { i ->
            val acts = RecallActivation.plan(listOf(item(1000L, -0.9, 1.0)), s, now + i * 60_000L)
            if (acts.isNotEmpty()) {
                lastDelta = acts.sumOf { it.deltaP }
                s = EventRules.apply(
                    s, StateEventType.RECALL,
                    now = now + i * 60_000L,
                    note = RecallActivation.noteOf(acts.first()),
                    override = Delta(p = lastDelta),
                )
            }
        }
        assertTrue("推过了会不会自己停：p=${s.p}", s.p > -0.30)
        assertTrue("但确实被推下来了", s.p < 0.0)
        assertTrue("第十次应该已经很轻：$lastDelta", abs(lastDelta) < 0.01)
    }

    @Test
    fun `冲突不道歉也会自己消到能回升信任`() {
        // 以前 conflict 永不衰减 → `conflict ≥ 0.20` 让信任永远回不来（闭环焊死的那个洞）
        val day = Decay.CONFLICT_HALF_LIFE_MS
        val s = Decay.settle(
            base(conflict = 0.30).copy(repair = 0.5, trust = 0.5),
            now = 1000L + day,
        )
        assertTrue("一天后冲突应该掉到 0.20 以下：${s.conflict}", s.conflict < 0.20)
        assertTrue("信任这才回得来", s.trust > 0.5)
    }
}
