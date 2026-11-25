package com.leaf.vixi.state

import com.leaf.vixi.data.VixiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 心境层（P4）的回归测试。
 *
 * 要保住的三条性质：
 *  1. 心境是**慢**的：一次情绪事件只挪一小步，不会跟着一句话跳
 *  2. 心境会**回落**：没有新消息时朝平静走，半衰期 6 小时量级
 *  3. 修复要**经过时间**才变成信任：道歉只记修复量，信任在 Decay 里慢慢回升
 */
class MoodTest {

    private fun base() = VixiState(enabled = true, p = 0.0, a = 0.3, d = 0.5, lastTick = 1000L)

    // ────────── 心境追赶瞬时情绪 ──────────

    @Test
    fun `一次情绪事件只挪一小步`() {
        // p 掉到 −0.5 之后，心境只走 10%
        val s = EventRules.apply(
            base(),
            StateEventType.EMOTION,
            now = 2000L,
            override = Delta(p = -0.5),
            emotionLabel = "生气",
        )
        // 余温之后：−0.5 先过**对手过程**（强刺激回拉 15% → −0.425），情绪池再兑现 25%
        assertEquals(-0.106, s.p, 0.001)
        // 心境跟的是**缓冲池**（她真正被推到哪），再只挪 10% → −0.5 × 0.1 = −0.05
        // 心境跟缓冲池（被打到 −0.425），再只挪 10% → −0.042
        assertEquals("心境只挪 10%", -0.042, s.mood, 0.001)
    }

    @Test
    fun `连续情绪事件心境才慢慢追上来`() {
        var s = base()
        repeat(10) {
            s = EventRules.apply(
                s,
                StateEventType.EMOTION,
                now = 2000L,
                override = Delta(p = -0.5),
                emotionLabel = "生气",
            )
        }
        // 10 次之后应该接近瞬时值（p 被夹到 −1，心境跟着往下走）
        assertTrue("心境应该明显变负", s.mood < -0.15)
    }

    @Test
    fun `上次情绪标签会落盘`() {
        val s = EventRules.apply(
            base(),
            StateEventType.EMOTION,
            now = 2000L,
            override = Delta(),
            emotionLabel = "委屈",
        )
        assertEquals("委屈", s.lastLabel)
        assertEquals(2000L, s.lastLabelAt)
    }

    // ────────── 心境回落 ──────────

    @Test
    fun `一个半衰期后心境只剩一半`() {
        val s = Decay.settle(
            VixiState(enabled = true, mood = -0.4, lastTick = 1000L),
            now = 1000L + Decay.MOOD_HALF_LIFE_MS,
        )
        assertEquals(-0.2, s.mood, 0.01)
    }

    @Test
    fun `心境比瞬时情绪慢得多`() {
        val s = VixiState(enabled = true, p = -0.8, mood = -0.8, lastTick = 1000L)
        // 别写死"某个时长掉多少"：半衰期以后还要调（30 秒 → 90 分钟已经改过一次）。
        // 只断言相对速度——同一段时间里，心境走的距离明显小于瞬时效价。
        val after = Decay.settle(s, now = 1000L + Decay.DECAY_HALF_LIFE_MS)
        val dp = kotlin.math.abs(after.p - s.p)
        val dm = kotlin.math.abs(after.mood - s.mood)
        assertTrue("瞬时应明显回落", dp > 0.2)
        // 余温之后瞬时的回落本身也变慢了（两池），所以放宽成"明显慢于瞬时"
        assertTrue("心境该明显慢于瞬时：dm=$dm dp=$dp", dm < dp * 0.7)
    }

    // ────────── 修复 → 信任 ──────────

    @Test
    fun `道歉只记修复量不直接涨信任`() {
        val s = EventRules.apply(
            base(),
            StateEventType.APOLOGIZE,
            now = 2000L,
            override = Delta(conflict = -0.10, repair = 0.06),
        )
        assertEquals(0.06, s.repair, 0.001)
        assertEquals("信任靠 Decay 慢慢回升", base().trust, s.trust, 0.0001)
    }

    @Test
    fun `修复量够且冲突下来后信任才回升`() {
        val withRift = VixiState(enabled = true, repair = 0.5, conflict = 0.10, trust = 0.5, lastTick = 1000L)
        val s = Decay.settle(withRift, now = 1000L + Decay.REPAIR_HALF_LIFE_MS)
        assertTrue("信任应该回升一点", s.trust > 0.5)
        assertTrue("修复量被消耗", s.repair < 0.5)
    }

    @Test
    fun `冲突还在时不给信任回升`() {
        val broken = VixiState(enabled = true, repair = 0.5, conflict = 0.60, trust = 0.5, lastTick = 1000L)
        val s = Decay.settle(broken, now = 1000L + Decay.REPAIR_HALF_LIFE_MS)
        assertEquals("没和解就不该回升", 0.5, s.trust, 0.0001)
    }

    @Test
    fun `裂痕还在的时候修复量冻结不衰减`() {
        // conflict 0.5 半衰 24h，经过 3h（= repair 一个半衰）后仍 ≈0.46，远高于 0.20 门槛
        // → 修复量必须**冻着**，否则真吵架时道歉拿到的修复会先过期、永远涨不了信任
        val s = Decay.settle(
            VixiState(enabled = true, repair = 0.8, conflict = 0.5, lastTick = 1000L),
            now = 1000L + Decay.REPAIR_HALF_LIFE_MS,
        )
        assertEquals("裂痕没消之前不能耗掉修复量", 0.8, s.repair, 0.02)
    }

    @Test
    fun `裂痕消了之后修复量照常衰减`() {
        val s = Decay.settle(
            VixiState(enabled = true, repair = 0.8, conflict = 0.1, lastTick = 1000L),
            now = 1000L + Decay.REPAIR_HALF_LIFE_MS,
        )
        assertEquals("门开了就按 3h 半衰耗掉一半", 0.4, s.repair, 0.02)
    }

    // ────────── 注入 ──────────

    @Test
    fun `底色偏沉时注入行会多一句`() {
        val line = Explain.promptLine(VixiState(enabled = true, mood = -0.3))
        assertTrue("应该带上底色", line.contains("底色偏沉"))
    }

    @Test
    fun `底色正常时不加多余的话`() {
        val line = Explain.promptLine(VixiState(enabled = true, mood = 0.0))
        assertTrue("不该出现底色字样", !line.contains("底色"))
        assertTrue(line.length <= 60)
    }

    // ────────── 审查补的：幂等性与单次道歉 ──────────

    @Test
    fun `信任回升和结算频率无关`() {
        // 同一段时间：一次结算 vs 每 2 秒结算，结果必须一致（以前放大约 14 倍）
        val start = VixiState(enabled = true, repair = 0.5, conflict = 0.0, trust = 0.5, lastTick = 1000L)
        val end = 1000L + Decay.REPAIR_HALF_LIFE_MS

        val oneShot = Decay.settle(start, now = end)

        // 每 20 分钟结算一次（步长再小就会被"三位小数"的存储精度吃掉，见类注释）
        var stepped = start
        var t = 1000L
        val step = 20 * 60 * 1000L
        while (t < end) {
            t = (t + step).coerceAtMost(end)
            stepped = Decay.settle(stepped, now = t)
        }

        assertEquals("信任回升必须与结算频率无关", oneShot.trust, stepped.trust, 0.02)
        assertEquals(oneShot.repair, stepped.repair, 0.02)
    }

    @Test
    fun `一次道歉也能让信任慢慢回升`() {
        // 一次道歉 repair 只有 0.06；以前门槛 0.30，等于永远不触发
        val start = VixiState(enabled = true, repair = 0.06, conflict = 0.05, trust = 0.5, lastTick = 1000L)
        val s = Decay.settle(start, now = 1000L + Decay.REPAIR_HALF_LIFE_MS)
        assertTrue("一次道歉也该有回升", s.trust > 0.5)
    }

    @Test
    fun `信任回升有上限不会一直涨`() {
        val start = VixiState(enabled = true, repair = 0.5, conflict = 0.0, trust = 0.5, lastTick = 1000L)
        val s = Decay.settle(start, now = 1000L + Decay.REPAIR_HALF_LIFE_MS * 20)
        assertTrue("最多涨到 repair 的一半左右", s.trust <= 0.5 + 0.26)
    }

    @Test
    fun `心境只在效价被推动时挪`() {
        // USER_MSG 只改唤醒，不推动效价 → 心境不该动
        val s = EventRules.apply(
            VixiState(enabled = true, mood = 0.0),
            StateEventType.USER_MSG,
            now = 1L,
        )
        assertEquals(0.0, s.mood, 0.0001)
    }

    @Test
    fun `心境朝基线回落`() {
        // baseP 不是 0 时，心境也该回到 baseP（否则和 p 分叉）
        val s = Decay.settle(
            VixiState(enabled = true, baseP = 0.3, mood = -0.5, lastTick = 1000L),
            now = 1000L + Decay.MOOD_HALF_LIFE_MS * 10,
        )
        assertEquals(0.3, s.mood, 0.02)
    }

    @Test
    fun `情绪强且底色沉时两句话都在`() {
        val s = VixiState(enabled = true, p = -0.5, a = 0.7, d = 0.6, mood = -0.4)
        val line = Explain.promptLine(s)
        assertTrue("情绪词还在", line.contains("生气"))
        assertTrue("底色也在", line.contains("底色偏沉"))
    }
}