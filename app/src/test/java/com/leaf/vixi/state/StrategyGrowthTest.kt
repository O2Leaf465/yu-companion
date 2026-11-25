package com.leaf.vixi.state

import com.leaf.vixi.data.VixiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 生物层 S4（心动·策略选择）+ S5（入心·慢学习）。
 */
class StrategyGrowthTest {

    // ────────── S4 ──────────

    @Test
    fun `没数据时偏向保守`() {
        val m = Strategy.choose(Strategy.decode("").stats)
        assertEquals("没把握就别往前冲", Strategy.Move.HOLD, m)
    }

    @Test
    fun `靠近一直成功就会先选靠近`() {
        val st = mapOf(
            Strategy.Move.APPROACH to (5 to 5),
            Strategy.Move.HOLD to (3 to 6),
            Strategy.Move.WITHDRAW to (2 to 5),
        )
        assertEquals(Strategy.Move.APPROACH, Strategy.choose(st))
    }

    @Test
    fun `关系很僵时靠近会被压回不动`() {
        val st = mapOf(Strategy.Move.APPROACH to (9 to 10))
        val hostile = VixiState(enabled = true, conflict = 0.6)
        assertEquals(Strategy.Move.HOLD, Strategy.adjust(Strategy.choose(st), hostile))
    }

    @Test
    fun `策略表能原样存回来`() {
        val st = mapOf(
            Strategy.Move.APPROACH to (2 to 3),
            Strategy.Move.WITHDRAW to (1 to 2),
            Strategy.Move.HOLD to (0 to 1),
        )
        val raw = Strategy.encode(st, Strategy.Move.WITHDRAW)
        val back = Strategy.decode(raw)
        assertEquals(2 to 3, back.stats[Strategy.Move.APPROACH])
        assertEquals(0 to 1, back.stats[Strategy.Move.HOLD])
        assertEquals(Strategy.Move.WITHDRAW, back.last)
    }

    @Test
    fun `策略行只给方向不给台词`() {
        val line = Strategy.line(Strategy.Move.WITHDRAW, VixiState(enabled = true))
        assertTrue("要出现岔开：$line", line.contains("岔开"))
        assertTrue("要标明这是做法", line.contains("怎么做"))
    }

    // ────────── S5 ──────────

    @Test
    fun `证据不够时旋钮不动`() {
        var k = Growth.Knobs()
        var ev = emptyMap<Growth.Knob, Growth.Evidence>()
        repeat(2) {
            val (nk, ne) = Growth.step(k, Growth.Signal(1, null, null), ev)
            k = nk; ev = ne
        }
        assertEquals("两次还不够", 1.0, k.express, 0.001)
        val (k3, _) = Growth.step(k, Growth.Signal(1, null, null), ev)
        assertTrue("第三次才挪一步：${k3.express}", k3.express > 1.0)
    }

    @Test
    fun `单次只挪一小步且有界`() {
        var k = Growth.Knobs()
        var ev = emptyMap<Growth.Knob, Growth.Evidence>()
        repeat(3) { val (nk, ne) = Growth.step(k, Growth.Signal(1, null, null), ev); k = nk; ev = ne }
        assertTrue("第一次明显小于上限：${k.express}", k.express < 1.05)
        repeat(60) { val (nk, ne) = Growth.step(k, Growth.Signal(1, null, null), ev); k = nk; ev = ne }
        assertTrue("永远不超过上限：${k.express}", k.express <= Growth.MAX + 0.001)
    }

    @Test
    fun `旋钮真的会影响表达和风险`() {
        val base = VixiState(enabled = true)
        val learned = base.copy(knobs = Growth.encode(Growth.Knobs(express = 1.2, tact = 1.2, bond = 1.2)))
        assertTrue("表达旋钮让话变长", Explain.speakBudget(learned) > Explain.speakBudget(base))
        assertTrue("礼貌旋钮让 risk 变高", Explain.riskOf(learned) > Explain.riskOf(base))
    }

    @Test
    fun `默认关的时候旋钮不会被改`() {
        val s = VixiState(enabled = true)
        assertEquals(false, s.learnOn)
        assertEquals("1.000,1.000,1.000", s.knobs)
    }

    @Test
    fun `旋钮编解码能往返`() {
        val k = Growth.Knobs(0.9, 1.1, 0.85)
        val back = Growth.decode(Growth.encode(k))
        assertEquals(0.9, back.express, 0.001)
        assertEquals(1.1, back.tact, 0.001)
        assertEquals(0.85, back.bond, 0.001)
    }

    // ── 审查回归（证据语义 / 落盘 / 解码健壮性）──

    @Test
    fun `普通聊天不算证据`() {
        // 第一版把每句非夸奖都当 bond 负证据，会把 bond 一路压到底
        // 中等长度（不夸不骂）：三个信号都不算数据
        val sig = Growth.signalOf("今天过得还行吧", praiseOrAffection = false, violated = false, negative = false)
        assertEquals(null, sig.bond)
        assertEquals(null, sig.express)
        assertEquals(null, sig.tact)
    }

    @Test
    fun `摸头和图片不算表达证据`() {
        val pat = Growth.signalOf(
            "（@你 伸手摸了摸@她 的头）", praiseOrAffection = false, violated = false,
            negative = false, isText = false,
        )
        assertEquals("摸头模板不该算数据", null, pat.express)
        assertEquals("也不该算 bond 证据", null, pat.bond)
    }

    @Test
    fun `骂人会记负证据`() {
        val sig = Growth.signalOf("你真蠢", praiseOrAffection = false, violated = false, negative = true)
        assertEquals(0, sig.bond)
    }

    @Test
    fun `证据要连续同向才挪，方向变了清零`() {
        var k = Growth.Knobs()
        var ev = emptyMap<Growth.Knob, Growth.Evidence>()
        // 1,0,1 只在中间那次不挪，第三次与前次同向才继续攒
        listOf(1, 0, 1).forEach { sig ->
            val (nk, ne) = Growth.step(k, Growth.Signal(sig, null, null), ev)
            k = nk; ev = ne
        }
        assertEquals("方向反复不该挪满一步", 1.0, k.express, 0.001)
    }

    @Test
    fun `证据串能往返`() {
        val raw = Growth.encodeEvidence(mapOf(Growth.Knob.EXPRESS to Growth.Evidence(1, 2)))
        val back = Growth.decodeEvidence(raw)
        assertEquals(1, back[Growth.Knob.EXPRESS]?.sign)
        assertEquals(2, back[Growth.Knob.EXPRESS]?.count)
    }

    @Test
    fun `坏策略串不会算出超过一`() {
        val back = Strategy.decode("A5/2,W-1/3,H0/0|X")
        val (w, n) = back.stats[Strategy.Move.APPROACH] ?: (0 to 0)
        assertTrue("胜不能多于总：$w/$n", w <= n)
        assertTrue("成功率不超过 1", Strategy.rate(w, n) <= 1.0)
    }

    @Test
    fun `重置不会抹掉学到的东西`() {
        val st = VixiState(enabled = true, moves = "A2/3,W0/0,H0/0|A", knobs = "1.050,1.000,1.000")
        // reset 在 StateStore 里（要 Context），这里只断言字段能存住
        assertEquals("A2/3,W0/0,H0/0|A", st.moves)
        assertEquals(1.05, Growth.decode(st.knobs).express, 0.001)
    }

    @Test
    fun `旋钮会淡忘回中立`() {
        val drifted = Growth.Knobs(express = 1.20, tact = 1.20, bond = 0.80)
        val after = Growth.relax(drifted, Growth.FORGET_HALF_LIFE_MS)
        assertTrue("一天半衰就该回一半：${after.express}", after.express < 1.15)
        assertTrue("另一个方向也一样：${after.bond}", after.bond > 0.85)
        val long = Growth.relax(drifted, Growth.FORGET_HALF_LIFE_MS * 20)
        assertEquals("最后回中立", 1.0, long.express, 0.01)
    }
}
