package com.leaf.vixi.state

import com.leaf.vixi.data.StateEvent
import com.leaf.vixi.data.VixiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 害羞（2026-11-22，与用户商定）：
 *
 *  1. 触发源复用评价层（心动 / 开心），不新造词表
 *  2. **意外度**才是主角：天天被夸不害羞，偶尔认真夸一次才害羞
 *  3. 阈值来自角色（`Character.shy`，越大越不容易害羞）
 *  4. 表现是"想躲"：绕开话题、话变短、别用感叹号
 */
class ShynessTest {

    private val now = 1_700_000_000_000L
    private val hour = 3_600_000L

    private fun s(closeness: Double = 0.50, events: List<StateEvent> = emptyList()) =
        VixiState(enabled = true, closeness = closeness, events = events)

    /** 生产里夸奖**不会**落成独立事件，而是 `emotion` 事件的 note（`心动·praise·原文`） */
    private fun praises(n: Int) = (1..n).map {
        StateEvent(ts = now - it * 60_000L, type = "emotion", note = "心动·praise·你好好看")
    }

    // ────────── 触发 ──────────

    @Test
    fun `被夸会害羞`() {
        val v = Shyness.evaluate(EmotionLabel.TENDER, 0.8, 0.0, s(), shyBias = 0.3)
        assertTrue("应该害羞：$v", v >= Shyness.THRESHOLD)
    }

    @Test
    fun `被骂不会害羞`() {
        assertEquals(0.0, Shyness.evaluate(EmotionLabel.ANGER, 0.9, 0.0, s(), 0.3), 0.001)
        assertEquals(0.0, Shyness.evaluate(EmotionLabel.HURT, 0.9, 0.0, s(), 0.3), 0.001)
    }

    @Test
    fun `天天被夸就不害羞了`() {
        // 最近 20 条事件里 16 条都是夸 → 密度 0.8 → 意外度接近 0
        val dense = s(events = praises(16))
        val once = Shyness.evaluate(EmotionLabel.TENDER, 0.8, 0.0, s(), 0.3)
        val often = Shyness.evaluate(EmotionLabel.TENDER, 0.8, 0.0, dense, 0.3)
        assertTrue("被夸麻了就该没感觉：$often vs $once", often < once)
        assertTrue("而且应该低于门槛", often < Shyness.THRESHOLD)
    }

    @Test
    fun `突然变重的一句会加分`() {
        // 跳变只在"已经有点常见"时才有意义：给 8 条夸做背景
        val st = s(events = praises(8))
        val plain = Shyness.evaluate(EmotionLabel.TENDER, 0.8, 0.0, st, 0.3)
        val jumped = Shyness.evaluate(EmotionLabel.TENDER, 0.8, 0.2, st, 0.3)
        assertTrue("夸奖强度跳变要更害羞：$jumped vs $plain", jumped > plain)
    }

    @Test
    fun `人设阈值能压住害羞`() {
        // shyBias=1.0 表示"几乎不害羞"
        assertEquals(0.0, Shyness.evaluate(EmotionLabel.TENDER, 0.9, 0.0, s(), 1.0), 0.001)
        // 0.0 表示"很容易害羞"
        val easy = Shyness.evaluate(EmotionLabel.TENDER, 0.8, 0.0, s(), 0.0)
        val normal = Shyness.evaluate(EmotionLabel.TENDER, 0.8, 0.0, s(), 0.5)
        assertTrue(easy > normal)
    }

    // ────────── 表现：绕开 / 话短 / 别用感叹号 ──────────

    @Test
    fun `害羞时状态行是说躲开，不是委屈那种收着`() {
        val shy = VixiState(
            enabled = true, lastLabel = EmotionLabel.SHY.zh, lastLabelAt = now - 60_000L,
        )
        val line = Explain.promptLine(shy, now)
        assertTrue("要出现害羞：$line", line.contains("害羞"))
        assertTrue("要让她岔开话题：$line", line.contains("岔开"))
    }

    @Test
    fun `害羞过几分钟就自己散了`() {
        val old = VixiState(
            enabled = true, lastLabel = EmotionLabel.SHY.zh,
            lastLabelAt = now - Explain.SHY_TTL_MS - 60_000L,
        )
        assertFalse(Explain.isShy(old, now))
        assertFalse("过期了就不该再说害羞", Explain.promptLine(old, now).contains("害羞"))
    }

    @Test
    fun `害羞会把话压短`() {
        val base = VixiState(enabled = true)
        val shy = base.copy(lastLabel = EmotionLabel.SHY.zh, lastLabelAt = now - 60_000L)
        assertTrue("害羞时更短", Explain.speakBudget(shy, now) < Explain.speakBudget(base, now))
    }

    @Test
    fun `害羞时硬规则改成别用感叹号`() {
        val shy = VixiState(enabled = true, lastLabel = EmotionLabel.SHY.zh, lastLabelAt = now - 60_000L)
        val line = Explain.hardRules(shy, now)
        assertTrue("要禁感叹号：$line", line.contains("别用感叹号"))
        assertTrue("平时不该禁", Explain.hardRules(VixiState(enabled = true), now).contains("最多一个感叹号"))
    }

    @Test
    fun `越界到厌恶时状态行明显带厌恶，而且不许撒娇`() {
        val averse = VixiState(
            enabled = true, violation = 0.60,
            lastLabel = EmotionLabel.AVERSION.zh, lastLabelAt = now - 60_000L,
        )
        val line = Explain.promptLine(averse, now)
        assertTrue("要出现厌恶：$line", line.contains("厌恶"))
        assertTrue("而且不许撒娇/解释：${Explain.hardRules(averse, now)}",
            Explain.hardRules(averse, now).contains("别撒娇"))
    }

    @Test
    fun `轻度反感不叫厌恶`() {
        val mild = VixiState(
            enabled = true, violation = 0.20,
            lastLabel = EmotionLabel.AVERSION.zh, lastLabelAt = now - 60_000L,
        )
        val line = Explain.promptLine(mild, now)
        assertTrue("轻度是反感：$line", line.contains("反感"))
        assertFalse("还不该说厌恶", line.contains("厌恶"))
    }

    @Test
    fun `害羞要真的教她怎么卡，而不是只说"有点断"`() {
        val shy = VixiState(enabled = true, lastLabel = EmotionLabel.SHY.zh, lastLabelAt = now - 60_000L)
        val rules = Explain.hardRules(shy, now)
        assertTrue("要给具体形状（重复字/省略号）：$rules",
            rules.contains("重复") && rules.contains("省略号"))
        assertTrue("要限量，别整段结巴：$rules", rules.contains("两处"))
    }

    @Test
    fun `害羞的增量让支配度下降（想躲）`() {
        val d = EmotionDelta.of(EmotionLabel.SHY, 1.0)
        assertTrue("想躲 → d 降", d.d < 0)
        assertTrue("心跳快 → a 升", d.a > 0)
        assertTrue("嘴上说不要人却更近", d.closeness > 0)
    }
}
