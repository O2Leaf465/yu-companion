package com.leaf.vixi.state

import com.leaf.vixi.data.VixiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 交叉层·抽身（第十二节）：四选一，带随机，沉默也记账。 */
class SilenceTest {

    @Test
    fun `状态好的时候基本都回`() {
        val s = VixiState(enabled = true, energy = 0.9, load = 0.0, conflict = 0.0, backlog = 0.2)
        val w = Silence.weights(s, hour = 14)
        assertTrue("回的权重该最高：${w.respond}", w.respond > w.silent)
        // 三个随机点里至少两个是"回"
        val acts = listOf(0.1, 0.5, 0.9).map { Silence.decide(s, 14, it) }
        assertTrue("多数情况要回：$acts", acts.count { it == Silence.Act.RESPOND } >= 2)
    }

    @Test
    fun `累垮了又吵着架就不回得多`() {
        val s = VixiState(enabled = true, energy = 0.1, load = 0.9, conflict = 0.9, backlog = 0.0)
        val w = Silence.weights(s, hour = 14)
        assertTrue("不回的权重该上来：${w.silent} vs ${w.respond}", w.silent > 0.3)
        assertTrue("回的权重该被压低：${w.respond}", w.respond < 0.5)
    }

    @Test
    fun `憋着话会往收尾偏`() {
        val calm = Silence.weights(VixiState(enabled = true, backlog = 0.1), 14)
        val full = Silence.weights(VixiState(enabled = true, backlog = 0.95), 14)
        assertTrue("暗流高更容易收尾：${full.endTopic} vs ${calm.endTopic}", full.endTopic > calm.endTopic)
    }

    @Test
    fun `随机数决定结果，但不会越界`() {
        val s = VixiState(enabled = true, energy = 0.4, load = 0.4, conflict = 0.3, backlog = 0.5)
        listOf(0.0, 0.25, 0.5, 0.75, 0.999).forEach {
            val act = Silence.decide(s, 14, it)
            assertTrue("必须是四选一：$act", act in Silence.Act.entries)
        }
    }

    @Test
    fun `状态没开就永远回`() {
        assertEquals(Silence.Act.RESPOND, Silence.decide(VixiState(enabled = false), 3, 0.99))
    }

    @Test
    fun `收尾有提示行，沉默没有`() {
        assertTrue(Silence.line(Silence.Act.END_TOPIC).contains("收住"))
        assertTrue(Silence.line(Silence.Act.SILENT).isBlank())
    }

    @Test
    fun `沉默会记账但不动数值`() {
        val before = VixiState(enabled = true, backlog = 0.3)
        val after = EventRules.apply(before, StateEventType.HER_SILENT, now = 2L)
        assertEquals("不发消息不改数值", 0.3, after.backlog, 0.001)
        assertTrue("但事件表里要留一笔", after.events.any { it.type == "her_silent" })
    }
}
