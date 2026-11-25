package com.leaf.vixi.state

import com.leaf.vixi.data.VixiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P5-1：说多少。
 *
 * 这一层只做一件事，但必须可靠：**同一句输入，在不同状态下要说不同长度的话**。
 * 所以测试基本都是"两个状态比大小"，而不是盯着某个具体数字。
 */
class SpeakingTest {

    private fun s(
        a: Double = 0.3,
        conflict: Double = 0.0,
        trust: Double = 0.5,
        backlog: Double = 0.0,
        mood: Double = 0.0,
        closeness: Double = 0.3,
        energy: Double = 0.80,
    ) = VixiState(
        enabled = true, a = a, conflict = conflict, trust = trust,
        backlog = backlog, mood = mood, closeness = closeness, energy = energy,
    )

    // ────────── 疲劳 / 压力 / 展开：派生量第一次露出来（2026-11-22）──────────

    @Test
    fun `累了就说得更短`() {
        val fresh = Explain.speakBudget(s(energy = 0.80))
        val tired = Explain.speakBudget(s(energy = 0.30))
        assertTrue("累的时候应该更短：$tired < $fresh", tired < fresh)
    }

    @Test
    fun `很累时压到最低档以上但明显变短`() {
        val very = Explain.speakBudget(s(energy = 0.10))
        assertTrue("不能低于下限", very >= Explain.BUDGET_MIN)
        assertTrue("要明显短于平常", very < Explain.speakBudget(s()) * 0.8)
    }

    @Test
    fun `累了会在说多少那一行说出来`() {
        val line = Explain.lengthLine(s(energy = 0.30))
        assertTrue("得让她知道自己累了：$line", line.contains("累"))
        assertTrue("精神时不该提累", !Explain.lengthLine(s(energy = 0.85)).contains("有点累了"))
    }

    @Test
    fun `压力是冲突加不信任并夹在0和1`() {
        // 完全不冲突、完全信任 → 0
        assertEquals(0.0, Explain.pressure(s(conflict = 0.0, trust = 1.0)), 0.001)
        // 满了：0.6×1 + 0.4×1 = 1.0，不许越界
        assertEquals(1.0, Explain.pressure(s(conflict = 1.0, trust = 0.0)), 0.001)
        // 只有冲突：0.6×1 + 0.4×0.5 = 0.8
        assertEquals(0.8, Explain.pressure(s(conflict = 1.0, trust = 0.5)), 0.001)
    }

    @Test
    fun `展开会因冲突大幅打折`() {
        assertTrue(
            "吵架时不该因为唤醒高就多说",
            Explain.openUp(s(a = 0.9, conflict = 0.5)) < Explain.openUp(s(a = 0.9, conflict = 0.0)),
        )
    }

    @Test
    fun `平静时是一个中等长度`() {
        val b = Explain.speakBudget(s())
        assertTrue("不该太短", b > Explain.BUDGET_TIGHT)
        assertTrue("也不该太长", b < Explain.BUDGET_NORMAL + 30)
    }

    @Test
    fun `兴奋时比平静说得多`() {
        assertTrue(Explain.speakBudget(s(a = 0.8)) > Explain.speakBudget(s()))
    }

    @Test
    fun `有裂痕时会收着说`() {
        assertTrue(Explain.speakBudget(s(conflict = 0.7)) < Explain.speakBudget(s()))
    }

    @Test
    fun `不信任时少交底`() {
        assertTrue(Explain.speakBudget(s(trust = 0.1)) < Explain.speakBudget(s(trust = 0.9)))
    }

    @Test
    fun `憋着话时句子更短`() {
        assertTrue(Explain.speakBudget(s(backlog = 0.8)) < Explain.speakBudget(s()))
    }

    @Test
    fun `底色松会多说一句`() {
        assertTrue(Explain.speakBudget(s(mood = 0.6)) > Explain.speakBudget(s(mood = -0.6)))
    }

    @Test
    fun `极端状态也不会超出上下限`() {
        val worst = VixiState(
            enabled = true, a = 0.0, conflict = 1.0, trust = 0.0, backlog = 1.0, mood = -1.0,
        )
        val best = VixiState(
            enabled = true, a = 1.0, conflict = 0.0, trust = 1.0, backlog = 0.0, mood = 1.0,
        )
        assertTrue(Explain.speakBudget(worst) >= Explain.BUDGET_MIN)
        assertTrue(Explain.speakBudget(best) <= Explain.BUDGET_MAX)
    }

    @Test
    fun `长度行带字数且只有一句`() {
        val line = Explain.lengthLine(s())
        assertTrue(line.startsWith("【说多少】"))
        assertTrue("要给出量级", line.contains("字"))
        assertTrue("不能换行", !line.contains("\n"))
    }

    @Test
    fun `短状态用短措辞`() {
        // 暗流 1.0 属于"憋不住"，会改成让她一次说完（那是另一条规则），所以这里用 0.7
        val line = Explain.lengthLine(s(conflict = 1.0, trust = 0.0, backlog = 0.7, a = 0.0))
        assertTrue("该说很短：$line", line.contains("很短"))
    }

    @Test
    fun `长状态用长措辞`() {
        val line = Explain.lengthLine(s(a = 1.0, trust = 1.0, mood = 1.0))
        assertTrue("该允许说几句", line.contains("多"))
    }

    @Test
    fun `未启用时不产出长度行`() {
        assertEquals("", Explain.lengthLine(VixiState(enabled = false)))
    }

    // ────────── P5-2：主动性与分寸 ──────────

    @Test
    fun `有裂痕时别主动找他`() {
        val line = Explain.initiativeLine(s(conflict = 0.5))
        assertTrue("该让他先开口", line.contains("先别主动"))
    }

    @Test
    fun `有话憋着时别追问`() {
        val line = Explain.initiativeLine(s(backlog = 0.7))
        assertTrue(line.contains("别主动追问"))
    }

    @Test
    fun `不信任时先接话别交底`() {
        val line = Explain.initiativeLine(s(trust = 0.2))
        assertTrue(line.contains("别主动提自己的事"))
    }

    @Test
    fun `底色松且兴奋时可以主动`() {
        val line = Explain.initiativeLine(s(mood = 0.4, a = 0.7))
        assertTrue(line.contains("可以主动"))
    }

    @Test
    fun `信任够且没裂痕才允许提旧事`() {
        val ok = Explain.initiativeLine(s(trust = 0.7, conflict = 0.0, backlog = 0.1))
        assertTrue("可以提一句", ok.contains("可以自然提一句"))
        val no = Explain.initiativeLine(s(trust = 0.30, conflict = 0.0))  // 门檻 0.35
        assertTrue("别翻旧账", no.contains("别主动翻旧事"))
    }

    @Test
    fun `主动性行也是单行且有前缀`() {
        val line = Explain.initiativeLine(s())
        assertTrue(line.startsWith("【怎么接】"))
        assertTrue(!line.contains("\n"))
    }

    @Test
    fun `日常聊天的字数预算不超过五十字`() {
        // 用户反馈：几十个字不符合日常聊天习惯
        assertTrue("平静状态下不该超过 45 字", Explain.speakBudget(s()) <= 45)
        assertTrue("最兴奋也不该超过一百", Explain.speakBudget(s(a = 1.0, trust = 1.0, mood = 1.0)) <= 100)
    }

    // ────────── P5-3：分寸 ──────────

    @Test
    fun `有裂痕时不能套近乎`() {
        val line = Explain.tactLine(s(conflict = 0.5, trust = 0.8, closeness = 0.8))
        assertTrue(line.contains("别套近乎"))
    }

    @Test
    fun `不信任时不能示弱`() {
        val line = Explain.tactLine(s(trust = 0.2))
        assertTrue(line.contains("别示弱"))
        assertTrue(line.contains("别主动暴露软处"))
    }

    @Test
    fun `亲近且信任才能撒娇开玩笑`() {
        val line = Explain.tactLine(s(trust = 0.8, closeness = 0.8))
        assertTrue(line.contains("可以撒娇"))
    }

    @Test
    fun `底色沉时玩笑收着`() {
        val line = Explain.tactLine(s(mood = -0.4))
        assertTrue(line.contains("玩笑收着点"))
    }

    @Test
    fun `信任够且没裂痕才可以说难受`() {
        assertTrue(Explain.tactLine(s(trust = 0.6)).contains("可以说一点自己的难受"))
        assertTrue(Explain.tactLine(s(trust = 0.6, conflict = 0.3)).contains("别主动暴露软处"))
    }

    @Test
    fun `分寸是一个词概括`() {
        assertEquals("收着", Explain.tactWord(s(conflict = 0.5)))
        assertEquals("委婉", Explain.tactWord(s(trust = 0.2)))
        assertEquals("亲近", Explain.tactWord(s(trust = 0.8, closeness = 0.8)))
    }

    @Test
    fun `分寸行单行且带前缀`() {
        val line = Explain.tactLine(s())
        assertTrue(line.startsWith("【分寸】"))
        assertTrue(!line.contains("\n"))
    }

    // ────────── 审查补的几条 ──────────

    @Test
    fun `生气不该比平静说得多`() {
        val calm = Explain.speakBudget(s(a = 0.30, conflict = 0.0))
        val angry = Explain.speakBudget(s(a = 0.70, conflict = 0.2, trust = 0.5))
        assertTrue("生气时更要收着说（$angry vs $calm）", angry <= calm + 2)
    }

    @Test
    fun `最短也不会短到像敷衍`() {
        val tight = VixiState(
            enabled = true, a = 0.75, conflict = 0.9, trust = 0.0, backlog = 0.9, mood = -1.0,
        )
        assertTrue("下限提到 12 了", Explain.speakBudget(tight) >= Explain.BUDGET_MIN)
        assertTrue(Explain.BUDGET_MIN >= 12)
    }

    @Test
    fun `分寸的行和词必须一致`() {
        val cases = listOf(
            s(trust = 0.2, closeness = 0.3),
            s(trust = 0.8, closeness = 0.8),
            s(trust = 0.5, closeness = 0.3),
            s(conflict = 0.5),
        )
        cases.forEach { st ->
            val word = Explain.tactWord(st)
            val line = Explain.tactLine(st)
            val expect = when (word) {
                "收着" -> "别套近乎"
                "很含蓄" -> "含蓄一点"
                "委婉" -> "委婉一点"
                "直接" -> "可以直接一点"
                else -> "直说就行"
            }
            assertTrue("词=$word 行=$line 应该对得上", line.contains(expect))
        }
    }

    @Test
    fun `亲近档里不再说玩笑收着`() {
        val line = Explain.tactLine(s(trust = 0.9, closeness = 0.9, mood = -0.4))
        assertTrue("已经很亲近了，不该同时说可以撒娇和玩笑收着", !line.contains("玩笑收着点"))
    }
}