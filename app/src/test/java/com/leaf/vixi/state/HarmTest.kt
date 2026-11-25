package com.leaf.vixi.state

import com.leaf.vixi.data.VixiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 误伤（交叉层第十节）：一句话被听成两句话。
 * 核心是**三处独立扣分不能直接相加**，要收敛成一处判定。
 */
class HarmTest {

    @Test
    fun `单条判定保持原样`() {
        val st = VixiState(enabled = true, closeness = 0.20)
        val v = Boundary.violation("老婆", st)
        assertEquals("单条不该被折损", 0.75 * 0.8, v, 0.01)
    }

    @Test
    fun `一句话命中三条也不会直接判死刑`() {
        val st = VixiState(enabled = true, closeness = 0.10)
        val hits = Boundary.check("老婆你必须马上抱抱我", st)
        assertTrue("至少要命中两条：${hits.map { it.name }}", hits.size >= 2)
        val v = Boundary.combine(hits)
        assertTrue("以前相加会顶到 1.0：$v", v < 0.85)
        assertTrue("但也明显越界：$v", v > 0.4)
    }

    @Test
    fun `协同只是小加成，不是相加`() {
        val one = listOf(Boundary.Hit("称谓", 0.60))
        val two = listOf(Boundary.Hit("称谓", 0.60), Boundary.Hit("身体", 0.20))
        assertEquals("单条 = 自己", 0.60, Boundary.combine(one), 0.001)
        assertEquals("两条 = max + 0.05", 0.65, Boundary.combine(two), 0.001)
    }

    @Test
    fun `没有命中就是零`() {
        assertEquals(0.0, Boundary.combine(emptyList()), 0.0)
    }

    @Test
    fun `收敛后仍然会触发反感`() {
        val st = VixiState(enabled = true, closeness = 0.40)
        val hits = Boundary.check("宝宝我回来了", st)
        val v = Boundary.combine(hits)
        assertTrue("轻度但关系没到 → 该反感", Boundary.shouldAvert(hits, st, v))
    }
}
