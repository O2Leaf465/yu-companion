package com.leaf.vixi.state

import com.leaf.vixi.data.StoredMessage
import com.leaf.vixi.data.VixiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 越界检测（2026-11-22）：用户发现的缺口——"分寸只影响她的回复，从不判断我越不越界"。
 *
 * 五条判据必须都活着，而且都要**乘关系折扣**：
 * 同样一句"老婆"，刚认识就是越界，熟到一定程度就是撒娇。
 */
class BoundaryTest {

    private val now = 1_700_000_000_000L
    private val hour = 3_600_000L

    private fun s(closeness: Double = 0.20, trust: Double = 0.30) =
        VixiState(enabled = true, closeness = closeness, trust = trust)

    /** 她"刚刚"说的话（无视拒绝只看 10 分钟窗口） */
    private fun her(text: String) = StoredMessage(fromUser = false, text = text, ts = now - 60_000L)

    // ────────── 五条判据 ──────────

    @Test
    fun `关系浅时叫老婆算越界，熟了就不算`() {
        assertTrue(Boundary.violation("老婆我回来了", s(closeness = 0.20)) > 0.0)
        assertEquals(0.0, Boundary.violation("老婆我回来了", s(closeness = 0.90)), 0.001)
    }

    @Test
    fun `关系浅时要抱抱算身体越界，半熟之后不算`() {
        assertTrue(Boundary.violation("抱抱", s(closeness = 0.10)) > 0.0)
        assertEquals(0.0, Boundary.violation("抱抱", s(closeness = 0.40)), 0.001)
    }

    @Test
    fun `命令句式在关系浅时算越界`() {
        val near = Boundary.violation("你必须马上回来", s(closeness = 0.20))
        val far = Boundary.violation("你必须马上回来", s(closeness = 0.90))
        assertTrue("近关系要有分：$near", near > 0.0)
        assertTrue("熟人不该被当命令：$far", far < near)
    }

    @Test
    fun `追问隐私算越界`() {
        assertTrue(Boundary.violation("你体重多少", s(closeness = 0.20)) > 0.0)
        assertTrue(Boundary.violation("你工资多少呀", s(closeness = 0.20)) > 0.0)
    }

    @Test
    fun `她说过不要之后还做同一件事，判得最重`() {
        val hits = Boundary.check(
            "抱抱",
            s(closeness = 0.10),
            recent = listOf(her("别这样"), her("不要了")),
        )
        assertTrue("要出现无视拒绝：${hits.map { it.name }}", hits.any { it.name == "无视拒绝" })
        val worst = hits.first { it.name == "无视拒绝" }.amount
        assertTrue("而且它的分量最重：$worst（${hits.map { it.name + it.amount }}）",
            worst == hits.maxOf { it.amount } && worst > 0.5)
    }

    @Test
    fun `陈述句不算命令越界`() {
        assertEquals("「我马上到」不是命令", 0.0, Boundary.violation("我马上到", s()), 0.001)
        assertTrue("「你快点睡」是命令", Boundary.violation("你快点睡", s()) > 0.0)
        assertTrue("但对她说「快点」算", Boundary.violation("快点回我", s()) > 0.0)
    }

    @Test
    fun `否定式命令不算越界`() {
        assertEquals(0.0, Boundary.violation("不用给我", s()), 0.001)
    }

    @Test
    fun `夸她不等于追问隐私`() {
        assertEquals("「我今天发工资了」只是在说事", 0.0, Boundary.violation("我今天发工资了", s()), 0.001)
        assertTrue("问了才算", Boundary.violation("你工资多少呀", s()) > 0.0)
    }

    @Test
    fun `她说的是特别别人不算拒绝`() {
        val hits = Boundary.check(
            "抱抱",
            s(closeness = 0.10),
            recent = listOf(her("今天的特别冷"), her("别人都说好")),
        )
        assertFalse("不该判无视拒绝：${hits.map { it.name }}", hits.any { it.name == "无视拒绝" })
    }

    @Test
    fun `普通寒暄不判越界`() {
        assertEquals(0.0, Boundary.violation("今天好累啊", s()), 0.001)
        assertEquals(0.0, Boundary.violation("在吗", s()), 0.001)
    }

    @Test
    fun `合计不超过 1`() {
        val v = Boundary.violation("老婆你必须马上给我抱抱，你体重多少", s(closeness = 0.0), listOf(her("不要")))
        assertTrue("上限是 1：$v", v <= 1.0)
        assertTrue("而且会明显偏高：$v", v > 0.6)
    }

    // ────────── 该不该"真的反感"（2026-11-23 用户要求）──────────

    @Test
    fun `不熟的时候一句宝宝就该反感`() {
        // 轻度称谓 + 半熟（亲近 0.40）：分数只有 0.35×0.6=0.21，但关系没到 → 也该反感
        val st = s(closeness = 0.40)
        val hits = Boundary.check("宝宝我回来了", st)
        val v = hits.sumOf { it.amount }
        assertTrue("先要判出越界：$v", v > 0)
        assertTrue("关系没熟，轻度称谓也要反感", Boundary.shouldAvert(hits, st, v))
    }

    @Test
    fun `不熟的时候伸手就该反感`() {
        val st = s(closeness = 0.20)
        val hits = Boundary.check("摸摸头", st)
        val v = hits.sumOf { it.amount }
        assertTrue("摸头在关系浅时是越界", v > 0)
        assertTrue("也该直接反感", Boundary.shouldAvert(hits, st, v))
    }

    @Test
    fun `熟人说同样的话不该反感`() {
        val st = s(closeness = 0.80, trust = 0.80)
        val hits = Boundary.check("宝宝我回来了", st)
        assertFalse("熟人之间不算越界", Boundary.shouldAvert(hits, st, hits.sumOf { it.amount }))
    }

    @Test
    fun `命令和隐私要够重才反感`() {
        // 轻度命令（快点）在熟练关系里分数很低 → 不该就摆出反感
        val st = s(closeness = 0.60, trust = 0.60)
        val hits = Boundary.check("你快点睡", st)
        assertFalse("轻度命令别动不动就反感：${hits.map { it.name }}",
            Boundary.shouldAvert(hits, st, hits.sumOf { it.amount }))
    }

    // ────────── 三处用途：risk / 关系量 / 需要 ──────────

    @Test
    fun `越界会让她下一轮更含蓄`() {
        val clean = VixiState(enabled = true, closeness = 0.20, trust = 0.30, violation = 0.0)
        val rude = clean.copy(violation = 0.8)
        assertTrue("越界之后 risk 更高：${Explain.riskOf(rude)} vs ${Explain.riskOf(clean)}",
            Explain.riskOf(rude) > Explain.riskOf(clean))
    }

    @Test
    fun `越界会降亲近涨暗流、并涨被尊重需要`() {
        val st = s(closeness = 0.20)
        val after = EventRules.apply(
            st, StateEventType.BOUNDARY, now = now, magnitude = 0.5,
            override = Delta(closeness = -0.015, backlog = 0.01),
            violation = 0.5,
        )
        assertTrue("亲近要降", after.closeness < st.closeness)
        assertTrue("暗流要涨", after.backlog > st.backlog)
        assertTrue("被尊重要涨", after.needRespect > st.needRespect)
        assertEquals("越界程度落盘，下一轮 risk 才读得到", 0.5, after.violation, 0.001)
    }

    // ────────── PAT 有条件（用户 2026-11-22 点头）──────────

    @Test
    fun `这一轮没越界，violation 必须回零`() {
        // 只写不清会让 risk 被"历史上最后一次越界"永久抬高
        val dirty = VixiState(enabled = true, violation = 0.8)
        val clean = EventRules.apply(
            dirty, StateEventType.BOUNDARY, now = now, magnitude = 0.0,
            override = Delta(), violation = 0.0,
        )
        assertEquals(0.0, clean.violation, 0.001)
        assertTrue("risk 要跟着降回去", Explain.riskOf(clean) < Explain.riskOf(dirty))
    }

    @Test
    fun `关系浅时摸头不是亲近是越界`() {
        val cold = s(closeness = 0.10)
        val after = EventRules.apply(cold, StateEventType.PAT, now = now)
        assertFalse("不该再涨亲近", after.closeness > cold.closeness)
        assertTrue("被尊重要涨", after.needRespect > cold.needRespect)
        assertEquals("而且不解渴", cold.needTouch, after.needTouch, 0.001)
    }

    @Test
    fun `关系够近时摸头照常有效`() {
        val warm = s(closeness = 0.50).copy(needTouch = 0.80)
        val after = EventRules.apply(warm, StateEventType.PAT, now = now)
        assertTrue("该涨亲近", after.closeness > warm.closeness)
        assertTrue("该解渴", after.needTouch < warm.needTouch)
    }

    // ────────── 重复越界要递减（否则亲近被磨到 0）──────────

    @Test
    fun `同样的越界，第二次没那么伤`() {
        val first = Boundary.impact(0.6, repeat = 0)
        val second = Boundary.impact(0.6, repeat = 1)
        val fifth = Boundary.impact(0.6, repeat = 4)
        assertTrue("第二次该更轻：${second.closeness} vs ${first.closeness}",
            second.closeness > first.closeness)
        assertTrue("但不会变成 0：${fifth.closeness}", fifth.closeness < 0.0)
        assertTrue("情绪那一下不递减", kotlin.math.abs(first.p - fifth.p) < 0.0001)
    }

    @Test
    fun `被越界过几次之后，亲近不会再被磨到底`() {
        var st = VixiState(enabled = true, closeness = 0.30, trust = 0.40)
        val t0 = 1_700_000_000_000L
        repeat(8) { i ->
            st = EventRules.apply(
                st, StateEventType.BOUNDARY, now = t0 + i * 60_000L, magnitude = 0.6, violation = 0.6,
            )
        }
        assertTrue("八次之后也该留一点亲近：${st.closeness}", st.closeness > 0.10)
    }
}
