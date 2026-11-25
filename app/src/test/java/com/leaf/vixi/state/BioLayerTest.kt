package com.leaf.vixi.state

import com.leaf.vixi.data.VixiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 生物层 S1~S3（2026-11-24）。
 *
 * - S1 余温：一句话全量进缓冲池，情绪池当场只兑现 25%，剩下的靠时间
 * - S2 牵挂：想念 = 1 − e^(−缺席/18h)，重逢后 4 小时半衰回落（有滞后）
 * - S3 压痕：负荷按压力累积/消退，堆到一定程度触发保护
 */
class BioLayerTest {

    private val hour = 3_600_000L
    private fun t0() = VixiState(enabled = true, lastTick = 1000L)

    // ────────── S1 余温 ──────────

    @Test
    fun `一句话只当场兑现四分之一`() {
        // 温和刺激（|Δp| < 0.15）：不进对手过程
        val s = EventRules.apply(t0(), StateEventType.EMOTION, now = 2L, override = Delta(p = -0.10))
        assertEquals("缓冲池吃全量", -0.10, s.surgeP, 0.001)
        assertEquals("情绪池只走 1/4", -0.025, s.p, 0.001)
    }

    @Test
    fun `剩下的余温会在接下来的时间里到账`() {
        val after = EventRules.apply(t0(), StateEventType.EMOTION, now = 2L, override = Delta(p = -0.10))
        // 半小时后（一个 drain 时间常数）：到账一半多
        val later = Decay.settle(after.copy(lastTick = 2L), now = 2L + 30 * 60_000L)
        assertTrue("该往 −0.10 靠：${later.p}", later.p < -0.05)
        assertTrue("但还没到满：${later.p}", later.p > -0.10)
    }

    @Test
    fun `强刺激有对手过程，先收回 15%`() {
        val strong = EventRules.apply(t0(), StateEventType.EMOTION, now = 2L, override = Delta(p = -0.20))
        assertEquals("−0.2 × 0.85", -0.17, strong.surgeP, 0.001)
    }

    @Test
    fun `弱刺激不走对手过程`() {
        val weak = EventRules.apply(t0(), StateEventType.EMOTION, now = 2L, override = Delta(p = -0.10))
        assertEquals(-0.10, weak.surgeP, 0.001)
    }

    @Test
    fun `两块结算和一次结算结果一致`() {
        val one = Decay.settle(t0().copy(p = -0.8, surgeP = -0.8), now = 1000L + hour * 3)
        val half = Decay.settle(t0().copy(p = -0.8, surgeP = -0.8), now = 1000L + hour)
        val two = Decay.settle(half, now = 1000L + hour * 3)
        assertEquals("精确解必须可合成", one.p, two.p, 0.001)
    }

    // ────────── S2 牵挂 ──────────

    @Test
    fun `缺席越久越想念`() {
        fun missingAfter(ms: Long): Double {
            val st = VixiState(enabled = true, lastHeardAt = 1000L, lastTick = 1000L)
            return Decay.settle(st, now = 1000L + ms).missing
        }
        val m8 = missingAfter(8 * hour)
        val m72 = missingAfter(72 * hour)
        assertEquals("8 小时 ≈ 0.36", 0.36, m8, 0.05)
        assertTrue("三天该很想：$m72  8h=$m8", m72 > 0.95)
        assertTrue(m72 > m8)
    }

    @Test
    fun `重逢后想念回落有滞后`() {
        // 先缺席两天（想念 ≈ 0.93），然后你回来了（lastHeardAt = now）
        val missed = VixiState(enabled = true, lastHeardAt = 1000L, lastTick = 1000L, missing = 0.93)
        val back = missed.copy(lastHeardAt = 1000L + 2 * 24 * hour, lastTick = 1000L + 2 * 24 * hour)
        val after10min = Decay.settle(back, now = 1000L + 2 * 24 * hour + 10 * 60_000L)
        assertTrue("刚见面还很想：${after10min.missing}", after10min.missing > 0.75)
        val after6h = Decay.settle(back, now = 1000L + 2 * 24 * hour + 6 * hour)
        assertTrue("半天后明显淡了：${after6h.missing}", after6h.missing < after10min.missing)
    }

    @Test
    fun `想念会进提示，但不报数字`() {
        val missing = VixiState(enabled = true, missing = 0.90)
        val line = Explain.missingLine(missing)
        assertTrue("该出现想念：$line", line.contains("想念"))
        assertFalse("不许报数字", line.contains("0.9") || line.contains("90%"))
        assertEquals("不太想就不注入", "", Explain.missingLine(VixiState(enabled = true, missing = 0.2)))
    }

    // ────────── S3 压痕 ──────────

    @Test
    fun `压力大负荷会累积`() {
        // 压力 = 0.6×冲突 + 0.4×(1−信任) ≈ 0.9 —— 高强度的一天
        val high = VixiState(enabled = true, conflict = 0.9, trust = 0.1, lastTick = 1000L)
        val after = Decay.settle(high, now = 1000L + 12 * hour)
        assertTrue("负荷该明显涨：${after.load}", after.load > 0.05)
    }

    @Test
    fun `压力小负荷会自己退`() {
        val calm = VixiState(enabled = true, conflict = 0.0, trust = 0.9, load = 0.5, lastTick = 1000L)
        val after = Decay.settle(calm, now = 1000L + 12 * hour)
        assertTrue("负荷该退：${after.load}", after.load < 0.5)
    }

    @Test
    fun `负荷高会触发保护，话说得更少`() {
        val ok = VixiState(enabled = true, p = 0.1, a = 0.3, d = 0.5, load = 0.0)
        val strained = ok.copy(load = 0.85)
        assertTrue("该变短：${Explain.speakBudget(ok)} vs ${Explain.speakBudget(strained)}",
            Explain.speakBudget(strained) < Explain.speakBudget(ok))
        assertTrue("状态行要撑不住：${Explain.promptLine(strained)}",
            Explain.promptLine(strained).contains("撑不住"))
    }

    // ── 审查回归：三条 ──

    @Test
    fun `不碰效价的事件不能推效价`() {
        // "你发消息"只加唤醒：如果它也推情绪池，"剩下的靠时间"就变成"剩下的靠条数"
        val st = VixiState(enabled = true, p = -0.10, surgeP = -0.50, a = 0.3, d = 0.5)
        val after = EventRules.apply(st, StateEventType.USER_MSG, now = 2L)
        assertEquals("效价不能被推", -0.10, after.p, 0.0001)
        assertEquals("缓冲池也不能动", -0.50, after.surgeP, 0.0001)
        assertTrue("但唤醒该涨", after.a > 0.3)
    }

    @Test
    fun `生产量级下的强刺激也能触发对手过程`() {
        // 生产最大 |Δp| ≈ 0.22（越界）/ 0.225（生气×1.5），阈值必须够得着
        // 生气在生产里是 −0.10×1.5 = −0.15，正好到阈值
        val strong = EventRules.apply(t0(), StateEventType.EMOTION, now = 2L, override = Delta(p = -0.15))
        assertEquals("该被回拉 15%", -0.1275, strong.surgeP, 0.001)
    }

    @Test
    fun `负荷能真的到保护线`() {
        // 连压两天：压力 0.9 → 负荷要过 0.40，否则保护分支永远是死代码
        var st = VixiState(enabled = true, conflict = 0.9, trust = 0.1, lastTick = 1000L)
        var now = 1000L
        repeat(24 * 12) { // 两天，每 10 分钟结算一次
            now += 10 * 60_000L
            st = Decay.settle(st, now = now)
        }
        assertTrue("两天高强度该逼近保护线：${st.load}", st.load >= 0.25)
    }

    // ── 暗流顶格：一次倒出来（2026-11-25 用户要求）──

    @Test
    fun `暗流顶格时状态行是憋不住了`() {
        val full = VixiState(enabled = true, backlog = 0.96)
        assertTrue("要判成憋不住：${Explain.promptLine(full)}", Explain.promptLine(full).contains("憋不住"))
        val half = VixiState(enabled = true, backlog = 0.70)
        assertTrue("没顶格还是憋着一肚子话", Explain.promptLine(half).contains("憋着"))
    }

    @Test
    fun `憋不住的时候话不是更少而是更多`() {
        val base = VixiState(enabled = true, backlog = 0.70)
        val venting = base.copy(backlog = 0.96)
        assertTrue("倾诉该放开：${Explain.speakBudget(venting)} vs ${Explain.speakBudget(base)}",
            Explain.speakBudget(venting) > Explain.speakBudget(base))
        assertTrue("至少 60 字", Explain.speakBudget(venting) >= 60)
    }

    @Test
    fun `说完之后暗流大幅回落`() {
        val full = VixiState(enabled = true, backlog = 0.96)
        val after = EventRules.apply(full, StateEventType.VENT, now = 2L)
        assertTrue("一次倒掉大半：${after.backlog}", after.backlog <= 0.45)
        assertTrue("不会变负", after.backlog >= 0.0)
        assertTrue("回落后不再是憋不住", !Explain.isVenting(after))
    }
}
