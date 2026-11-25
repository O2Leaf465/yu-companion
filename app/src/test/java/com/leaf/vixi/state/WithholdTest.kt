package com.leaf.vixi.state

import com.leaf.vixi.data.VixiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 交叉层·缄口（X2）：知道，也可以不说；瞒下去转成暗流。 */
class WithholdTest {

    @Test
    fun `不敏感的话直接说`() {
        assertEquals(
            Withhold.Act.TELL,
            Withhold.decide(VixiState(enabled = true, closeness = 0.2), "今天好累啊"),
        )
    }

    @Test
    fun `很敏感又不够熟就瞒`() {
        val act = Withhold.decide(VixiState(enabled = true, closeness = 0.20), "你前任是怎么样的人")
        assertEquals(Withhold.Act.WITHHOLD, act)
    }

    @Test
    fun `有点敏感、关系一般就软化`() {
        val act = Withhold.decide(VixiState(enabled = true, closeness = 0.65), "你工资多少")
        assertEquals(Withhold.Act.REFRAME, act)
    }

    @Test
    fun `很熟了就可以说`() {
        val act = Withhold.decide(VixiState(enabled = true, closeness = 0.90), "你前任是怎么样的人")
        assertEquals(Withhold.Act.TELL, act)
    }

    @Test
    fun `群聊里更收着`() {
        val st = VixiState(enabled = true, closeness = 0.60)
        assertEquals(Withhold.Act.REFRAME, Withhold.decide(st, "你工资多少"))
        assertEquals("公开场合该更守", Withhold.Act.WITHHOLD, Withhold.decide(st, "你工资多少", isGroup = true))
    }

    @Test
    fun `瞒下去会涨暗流，说出来不涨`() {
        val st = VixiState(enabled = true, backlog = 0.20)
        // ① 记"动作"（这一轮要瞒）——本身不加暗流，下一轮提示词要读到它
        val act = EventRules.apply(st, StateEventType.WITHHOLD, now = 2L, note = Withhold.Act.WITHHOLD.zh)
        assertEquals("动作本身不加暗流", 0.20, act.backlog, 0.001)
        assertEquals(Withhold.Act.WITHHOLD.zh, act.withhold)

        // ② 代价只在回复真的落库后才记（和爆发同一条纪律）
        val paid = EventRules.apply(act, StateEventType.WITHHOLD_COST, now = 3L, note = Withhold.Act.WITHHOLD.zh)
        assertTrue("瞒着要有代价：${paid.backlog}", paid.backlog > 0.20)
        val told = EventRules.apply(st, StateEventType.WITHHOLD_COST, now = 3L, note = Withhold.Act.TELL.zh)
        assertEquals("说出来不付代价", 0.20, told.backlog, 0.001)
    }

    @Test
    fun `提示行只给做法`() {
        val st = VixiState(enabled = true, withhold = Withhold.Act.WITHHOLD.zh)
        val line = Explain.mergedLine(st, rendered = null)
        assertTrue("要出现绕开：$line", line.contains("绕开") || line.contains("别提"))
        assertTrue("不该解释为什么", !line.contains("敏感"))
    }
}
