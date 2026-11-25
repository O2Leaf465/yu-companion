package com.leaf.vixi.state

import com.leaf.vixi.data.ReactionTable
import com.leaf.vixi.data.VixiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 混合情绪（2026-11-25）：前二合并 + 可编辑反应表 + 合并层。
 */
class EmotionMixTest {

    private val hour = 3_600_000L

    @Test
    fun `同一情绪反复出现会累积，但每次先把旧情绪打折`() {
        var raw = EmotionMix.add("", EmotionLabel.HURT, 0.3)
        raw = EmotionMix.add(raw, EmotionLabel.HURT, 0.2)
        // 0.3 × 0.8 + 0.2 = 0.44（2026-11-25 之前是纯累加的 0.5；
        // 纯累加 + 等比衰减会让池子里的相对大小永远不变，混合看起来就是死的）
        assertEquals(0.44, EmotionMix.decode(raw).first().w, 0.001)
    }

    @Test
    fun `后来的情绪能把老情绪顶掉`() {
        // 连受四次委屈（旧版：HURT 会一路累加到顶，别的情绪永远挤不进来）
        var raw = ""
        repeat(4) { raw = EmotionMix.add(raw, EmotionLabel.HURT, 0.9) }
        assertEquals(EmotionLabel.HURT, EmotionMix.decode(raw).first().label)

        // 接下来六句开心 —— 应该能反超（这就是"混合会变"的核心）
        repeat(6) { raw = EmotionMix.add(raw, EmotionLabel.JOY, 0.9) }
        assertEquals(EmotionLabel.JOY, EmotionMix.decode(raw).first().label)
    }

    @Test
    fun `最多只留两个`() {
        var raw = ""
        raw = EmotionMix.add(raw, EmotionLabel.HURT, 0.5)
        raw = EmotionMix.add(raw, EmotionLabel.ANGER, 0.4)
        raw = EmotionMix.add(raw, EmotionLabel.SHY, 0.3)
        assertEquals(2, EmotionMix.decode(raw).size)
        assertEquals("留下权重最大的两个", EmotionLabel.HURT, EmotionMix.decode(raw)[0].label)
    }

    @Test
    fun `池子会随时间变淡`() {
        val raw = EmotionMix.add("", EmotionLabel.ANGER, 0.9)
        val after = EmotionMix.decay(raw, EmotionMix.HALF_LIFE_MS)
        assertEquals(0.45, EmotionMix.decode(after).first().w, 0.01)
    }

    @Test
    fun `次情绪权重太小时不算混合`() {
        val s = VixiState(enabled = true, emoMix = "委屈:0.60,生气:0.10")
        assertEquals(EmotionLabel.HURT, EmotionMix.dominant(s))
        assertNull("10% 不足以算混着", EmotionMix.secondary(s))
        val s2 = VixiState(enabled = true, emoMix = "委屈:0.60,生气:0.35")
        assertEquals(EmotionLabel.ANGER, EmotionMix.secondary(s2))
    }

    @Test
    fun `没有池子时退回 lastLabel`() {
        assertEquals(EmotionLabel.ANGER, EmotionMix.dominant(VixiState(enabled = true, lastLabel = "生气")))
    }

    // ── 反应表 ──

    @Test
    fun `表能解析注释和长度乘子`() {
        val t = ReactionTable.parse(
            """
            # 注释
            委屈=话说一半|0.7
            委屈>生气=想怼回去，但更多是委屈|0.75
            """.trimIndent(),
        )
        assertEquals("话说一半", t["委屈"]?.text)
        assertEquals(0.7, t["委屈"]?.mul ?: 0.0, 0.001)
        assertEquals(0.75, t["委屈>生气"]?.mul ?: 0.0, 0.001)
    }

    @Test
    fun `坏行被忽略但不会崩`() {
        val t = ReactionTable.parse("没有等号\n=空键\n委屈=")
        assertTrue("坏行全丢", t.isEmpty())
    }

    @Test
    fun `长度用加权几何平均`() {
        // 纯委屈 0.70
        val one = ReactionTable.smooth(1.0, listOf(EmotionMix.Pair(EmotionLabel.HURT, 1.0)))
        assertEquals(0.70, one, 0.01)
        // 委屈 0.6 + 生气 0.3：介于 0.70 和 1.05 之间，且更靠近委屈
        val two = ReactionTable.smooth(
            1.0,
            listOf(EmotionMix.Pair(EmotionLabel.HURT, 0.6), EmotionMix.Pair(EmotionLabel.ANGER, 0.3)),
        )
        assertTrue("该在两者之间：$two", two > 0.70 && two < 1.05)
        assertTrue("且更靠近委屈：$two", two < 0.95)
    }

    // ── 合并层 ──

    @Test
    fun `合并层不会让状态行和长度行打架`() {
        // 负荷高（原来 = 撑不住/话少） + 暗流顶格（原来 = 憋不住/多说）
        val s = VixiState(enabled = true, load = 0.85, backlog = 0.97, p = 0.0, a = 0.3, d = 0.5)
        val line = Explain.mergedLine(s, rendered = null)
        assertTrue("顶格时只有一个方向：$line", line.contains("多说") && !line.contains("话少一点"))
    }

    @Test
    fun `有表就用表里的动作`() {
        val s = VixiState(enabled = true, emoMix = "委屈:0.60,生气:0.35")
        val rendered = ReactionTable.Rendered(
            main = EmotionLabel.HURT,
            second = EmotionLabel.ANGER,
            text = "想怼回去，但更多是委屈",
            lengthMul = 0.75,
        )
        val line = Explain.mergedLine(s, rendered)
        assertTrue("要用表里的话：$line", line.contains("想怼回去"))
        assertTrue("要说出混着的那一层：$line", line.contains("生气"))
    }
}
