package com.leaf.vixi.state

import com.leaf.vixi.data.VixiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 翻译层的回归测试：数值 → 一句话。
 *
 * 重点不是措辞，而是**边界**：同样一个数值不该在两条相邻规则之间来回跳、
 * 每轮只能给一个情绪词、未启用时不产出任何东西。
 */
class ExplainTest {

    @Test
    fun `平静时给平静`() {
        assertEquals("平静", Explain.feeling(VixiState(enabled = true)).word)
    }

    @Test
    fun `低效价高唤醒高支配是生气`() {
        val f = Explain.feeling(VixiState(enabled = true, p = -0.4, a = 0.7, d = 0.6))
        assertEquals("有点生气", f.word)
    }

    @Test
    fun `低效价高唤醒低支配是委屈`() {
        val f = Explain.feeling(VixiState(enabled = true, p = -0.4, a = 0.6, d = 0.3))
        assertEquals("有点委屈", f.word)
    }

    @Test
    fun `低效价低唤醒是闷`() {
        val f = Explain.feeling(VixiState(enabled = true, p = -0.3, a = 0.35, d = 0.5))
        assertEquals("有点闷", f.word)
    }

    @Test
    fun `高效价高唤醒是兴奋`() {
        val f = Explain.feeling(VixiState(enabled = true, p = 0.5, a = 0.7, d = 0.5))
        assertEquals("有点兴奋", f.word)
    }

    @Test
    fun `暗流加冲突优先于情绪词`() {
        // 明明很生气，但"憋着一肚子话"更影响这一轮怎么回
        val f = Explain.feeling(VixiState(enabled = true, p = -0.5, a = 0.8, d = 0.7, backlog = 0.7, conflict = 0.5))
        assertEquals("憋着一肚子话", f.word)
    }

    @Test
    fun `体力低不会单独产出情绪词`() {
        // energy 没有事件来源（只有很慢的回归），所以它不该决定她怎么说
        assertEquals("平静", Explain.feeling(VixiState(enabled = true, energy = 0.2)).word)
    }

    @Test
    fun `d 恒为 0_5 时永远不会是委屈`() {
        // d 的默认值就是 0.5；没有事件改它之前，负面+高唤醒只可能是生气
        val f = Explain.feeling(VixiState(enabled = true, p = -0.5, a = 0.8, d = 0.5))
        assertEquals("有点生气", f.word)
    }

    @Test
    fun `被贬低到支配度低才变成委屈`() {
        val f = Explain.feeling(VixiState(enabled = true, p = -0.5, a = 0.8, d = 0.35))
        assertEquals("有点委屈", f.word)
    }

    @Test
    fun `信任低时给不冷不热`() {
        // 门槛 2026-11-22 随默认信任（0.30）下调到 0.20，这里要低于它
        val f = Explain.feeling(VixiState(enabled = true, trust = 0.15))
        assertEquals("不冷不热", f.word)
    }

    @Test
    fun `暗流高单独就能触发憋着`() {
        val f = Explain.feeling(VixiState(enabled = true, backlog = 0.6))
        assertEquals("憋着一肚子话", f.word)
    }

    @Test
    fun `未启用时不产出注入行`() {
        assertEquals("", Explain.promptLine(VixiState(enabled = false, p = -0.4, a = 0.7)))
    }

    @Test
    fun `注入行只有一句且带前缀`() {
        val line = Explain.promptLine(VixiState(enabled = true, p = -0.4, a = 0.7, d = 0.6))
        assertTrue(line.startsWith("【此刻状态】"))
        assertTrue("只能是一行", !line.contains("\n"))
        assertTrue("别写太长，60 字以内", line.length <= 60)
    }

    @Test
    fun `注入行不出现原始数值`() {
        val line = Explain.promptLine(VixiState(enabled = true, p = -0.4, a = 0.7, d = 0.6, backlog = 0.2))
        assertTrue("数值不能进提示词", !line.contains("0."))
    }
}
