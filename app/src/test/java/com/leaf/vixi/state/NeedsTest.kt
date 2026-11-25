package com.leaf.vixi.state

import com.leaf.vixi.data.VixiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 需要（N1~N4）的回归测试。
 *
 * 这几条性质必须保住：
 *  1. 每个需要都**在文本里能涨能降**（涨得自然、降得靠真事）
 *  2. **respect 的恢复路径**（用户定的）：长时间不辱骂缓慢恢复 + 被夸加速恢复
 *  3. 分寸是音量旋钮：关系远的时候"想要"只暗示、不直说，甚至完全不说
 *  4. 爆发只给一次，且会进冷却（不然她会连着爆）
 */
class NeedsTest {

    private val now = 1_700_000_000_000L
    private val hour = 3_600_000L

    private fun s(
        energy: Double = 0.80,
        touch: Double = 0.35,
        respect: Double = 0.15,
        closeness: Double = 0.20,
        trust: Double = 0.30,
        conflict: Double = 0.0,
        events: List<com.leaf.vixi.data.StateEvent> = emptyList(),
    ) = VixiState(
        enabled = true, energy = energy, needTouch = touch, needRespect = respect,
        closeness = closeness, trust = trust, conflict = conflict, events = events,
    )

    // ────────── N1：四个量怎么算 ──────────

    @Test
    fun `休息就是体力的反面`() {
        assertEquals(0.30, Needs.rest(s(energy = 0.70)), 0.001)
        assertEquals(0.80, Needs.rest(s(energy = 0.20)), 0.001)
    }

    @Test
    fun `没人碰就会慢慢想被碰`() {
        // 一小时 +0.06（但**关系得够近**：closeness < 0.25 时摸头算越界，这个需要不涨）
        val after = Decay.settle(
            s(touch = 0.30, closeness = 0.50).copy(lastTick = now), now = now + hour, hour = 12.0,
        )
        assertEquals(0.36, after.needTouch, 0.01)
    }

    @Test
    fun `不亲近的时候这个需要不涨`() {
        val after = Decay.settle(
            s(touch = 0.30, closeness = 0.10).copy(lastTick = now), now = now + hour, hour = 12.0,
        )
        assertEquals("关系没到，涨了就是死锁", 0.30, after.needTouch, 0.01)
    }

    @Test
    fun `被摸会降，而且连续摸头收益递减`() {
        // 关系要够近：closeness < 0.25 的摸头现在按"越界"处理（见 BoundaryTest）
        val a = EventRules.apply(s(touch = 0.90, closeness = 0.60), StateEventType.PAT, now = now)
        val b = EventRules.apply(a, StateEventType.PAT, now = now + 60_000L)
        val drop1 = 0.90 - a.needTouch
        val drop2 = a.needTouch - b.needTouch
        assertTrue("第一次要降得明显", drop1 > 0.15)
        assertTrue("第二次降得更少（连续递减）：$drop1 vs $drop2", drop2 < drop1)
    }

    @Test
    fun `隔久了再摸，收益回满`() {
        val first = EventRules.apply(s(touch = 0.90, closeness = 0.60), StateEventType.PAT, now = now)
        // 11 分钟后再摸：超了 streak 窗口，按"新的一轮"算
        val again = EventRules.apply(first, StateEventType.PAT, now = now + 11 * 60_000L)
        val drop2 = first.needTouch - again.needTouch
        assertTrue("应该回到接近第一次的量：$drop2", drop2 > 0.15)
    }

    @Test
    fun `被在意由最近的冷落决定`() {
        val cold = (1..3).map {
            com.leaf.vixi.data.StateEvent(ts = now - it * hour, type = "neglect", note = "隔了很久")
        }
        assertTrue("冷落越多越想要", Needs.belong(s(events = cold), now) > Needs.belong(s(), now))
        assertTrue("没冷落时不该顶到高值", Needs.belong(s(), now) < Needs.HIGH)
    }

    // ────────── respect 的恢复路径（用户 2026-11-22 定的口径）──────────

    @Test
    fun `被贬低会让尊重需要涨`() {
        val after = EventRules.apply(s(), StateEventType.NEGATIVE, now = now)
        assertEquals(0.40, after.needRespect, 0.01)
    }

    @Test
    fun `长时间不辱骂会缓慢恢复`() {
        // 6 小时恢复 0.08
        val after = Decay.settle(s(respect = 0.50).copy(lastTick = now), now = now + 6 * hour, hour = 12.0)
        assertEquals(0.42, after.needRespect, 0.01)
        assertTrue("一夜下来能明显回落", after.needRespect < 0.50)
    }

    @Test
    fun `被夸会加速恢复`() {
        val praised = EventRules.apply(s(respect = 0.60), StateEventType.PRAISE, now = now)
        assertEquals(0.50, praised.needRespect, 0.01)
        assertTrue("夸一句降得比等一小时还多", 0.60 - praised.needRespect > Needs.RESPECT_RECOVER_PER_HOUR)
    }

    @Test
    fun `被纠正也算不被尊重`() {
        val a = EventRules.apply(s(), StateEventType.CORRECT, now = now)
        assertTrue("纠正会小幅涨", a.needRespect > 0.15)
    }

    // ────────── N2：注入（分寸是音量旋钮）──────────
    // 注意：注入测试一律用**真实** riskOf（2026-11-22 审查指出：手填 risk 会和真机矛盾）

    @Test
    fun `最想要只在过了门槛才算`() {
        // 摸头需要只在关系够近时成立（closeness ≥ 0.25），夹具给 0.5
        assertTrue(Needs.dominant(s(touch = 0.60, closeness = 0.50), now) == null)
        assertEquals(Needs.Kind.TOUCH, Needs.dominant(s(touch = 0.80, closeness = 0.50), now)?.kind)
    }

    @Test
    fun `亲近时可以直说想被摸头`() {
        val st = s(touch = 0.85, closeness = 0.90, trust = 0.90)
        val risk = Explain.riskOf(st)
        assertEquals("这个夹具应该是亲近档", Explain.TACT_CLOSE, Explain.tactBand(st))
        val line = Needs.wantLine(st, risk, now)
        assertTrue("应该说可以直接说：$line", line.contains("可以直接说"))
        assertTrue(line.contains("想被摸头"))
    }

    @Test
    fun `关系一般时只能暗示`() {
        // 关系一般：closeness .40 / trust .30 → risk ≈ 0.47（委婉档），正好落在"暗示"那档
        val st = s(touch = 0.85, closeness = 0.40)
        val risk = Explain.riskOf(st)
        assertEquals(Explain.TACT_MILD, Explain.tactBand(st))
        val line = Needs.wantLine(st, risk, now)
        assertTrue("应该说只能暗示：$line", line.contains("只能暗示"))
    }

    @Test
    fun `关系太远就干脆不注入`() {
        val st = s(touch = 0.95, closeness = 0.05, trust = 0.10)
        val risk = Explain.riskOf(st)
        assertEquals("应该进含蓄档", Explain.TACT_INDIRECT, Explain.tactBand(st))
        assertEquals("", Needs.wantLine(st, risk, now))
    }

    @Test
    fun `需要注入里不许出现数值`() {
        val st = s(touch = 0.95, closeness = 0.9, trust = 0.9)
        val line = Needs.wantLine(st, Explain.riskOf(st), now)
        assertTrue("不能把分数递给模型：$line", !line.contains("/10") && !line.contains("0.9"))
        assertTrue("也不能提分数这两个字", !line.contains("分数"))
    }

    @Test
    fun `累不会注入，只影响长度`() {
        val st = s(energy = 0.05)
        assertEquals("", Needs.wantLine(st, Explain.riskOf(st), now))
        assertTrue("但长度要变短", Explain.speakBudget(st) < Explain.speakBudget(s()))
    }

    @Test
    fun `被尊重不满足时她可以要求放尊重`() {
        // 默认关系是委婉档，但"要求尊重"不该被卡住（审查指出过：卡住就永不触发）
        val st = s(respect = 0.85)
        val line = Needs.wantLine(st, Explain.riskOf(st), now)
        assertTrue("要出现尊重二字：$line", line.contains("尊重"))
        assertTrue("可以要求，也可以冷下来", line.contains("要求") || line.contains("冷下来"))
    }

    @Test
    fun `关系很远时被尊重只要求冷下来不强求直说`() {
        val st = s(respect = 0.85, closeness = 0.05, trust = 0.10)
        val line = Needs.wantLine(st, Explain.riskOf(st), now)
        assertTrue("含蓄档应该走冷下来：$line", line.contains("冷下来"))
    }

    // ────────── N3：爆发（只给一次 + 冷却）──────────

    @Test
    fun `顶到头且关系不僵才允许爆发`() {
        assertFalse("没到顶不给", Needs.canBurst(s(touch = 0.80, closeness = 0.9, trust = 0.9), risk = 0.1, now = now))
        assertTrue(Needs.canBurst(s(touch = 0.95, closeness = 0.9, trust = 0.9), risk = 0.1, now = now))
        assertFalse("关系太僵说不出口", Needs.canBurst(s(touch = 0.95), risk = 0.50, now = now))
    }

    @Test
    fun `爆发后那个需要降下来并进冷却`() {
        val hot = s(touch = 0.95, closeness = 0.9, trust = 0.9)
        val after = EventRules.apply(hot, StateEventType.NEED_BURST, now = now, note = "需要爆发·被摸")
        assertEquals(Needs.BURST_AFTER, after.needTouch, 0.001)
        assertTrue("刚爆完不许再爆", Needs.burstCooling(after, now + hour))
        assertFalse("六小时以后可以再爆", Needs.burstCooling(after, now + 7 * hour))
        assertEquals("", Needs.burstLine(after, risk = 0.1, now = now + hour))
    }

    @Test
    fun `爆发不给累的人`() {
        assertFalse(
            "休息顶到 1.0 也不爆发",
            Needs.canBurst(s(energy = 0.0, closeness = 0.9, trust = 0.9), risk = 0.1, now = now),
        )
    }

    @Test
    fun `被在意顶到满也不爆发（它没法被满足掉）`() {
        // 3 次冷落 → belong = 0.35+0.66 = 1.0；但它是派生量，没有东西能把它降下来，
        // 允许爆发就会每 6 小时重复一次，"只给一次"不成立
        val cold = (1..3).map {
            com.leaf.vixi.data.StateEvent(ts = now - it * hour, type = "neglect", note = "隔了很久")
        }
        val st = s(closeness = 0.9, trust = 0.9, events = cold)
        assertEquals(1.0, Needs.belong(st, now), 0.01)
        assertFalse(Needs.canBurst(st, risk = 0.1, now = now))
    }

    // ────────── N4：憋着的需要把话压短 ──────────

    @Test
    fun `想说不能说的时候话更短`() {
        val near = Explain.speakBudget(s(touch = 0.95, closeness = 0.90, trust = 0.90))
        val far = Explain.speakBudget(s(touch = 0.95, closeness = 0.05, trust = 0.05))
        assertTrue("关系远时更憋：$far vs $near", far < near)
    }

    @Test
    fun `没有需要时不改变长度`() {
        val plain = Explain.speakBudget(s(touch = 0.0, respect = 0.0))
        val neutral = Explain.speakBudget(VixiState(enabled = true))
        assertTrue("低需要不该压长度：$plain vs $neutral", kotlin.math.abs(plain - neutral) <= 6)
    }

    @Test
    fun `闲置一天也不该把平静态压短`() {
        // 审查指出过：起点设在 0.50 时，十小时没被摸会让平静态从 28 字掉到 23 字（−18%），
        // 而对方什么都没做 —— 那是把"没摸头"当成长期惩罚。
        // 起点提到 0.75 后：短时间闲置几乎不该有变化，只有真的"憋到满"才压到 −20%。
        val fresh = Explain.speakBudget(s(touch = 0.35))
        val eightHours = Explain.speakBudget(s(touch = 0.35 + 8 * Needs.TOUCH_GROW_PER_HOUR))
        assertTrue("八小时闲置最多差 2 字：${fresh - eightHours}", fresh - eightHours <= 2)

        val saturated = Explain.speakBudget(s(touch = 1.0))
        assertTrue("憋到满也不该超过 20%：${fresh - saturated}", fresh - saturated <= (fresh * 0.20).toInt() + 1)
    }

    @Test
    fun `关系没到的时候，想被摸不算最想要`() {
        val st = s(touch = 1.0, respect = 0.80, closeness = 0.10)
        assertEquals("要不到的不该顶在最前面", Needs.Kind.RESPECT, Needs.dominant(st, now)?.kind)
    }

    @Test
    fun `关系没到的时候不注入想被摸`() {
        val st = s(touch = 1.0, closeness = 0.10, trust = 0.10)
        assertEquals("", Needs.wantLine(st, Explain.riskOf(st), now))
        assertFalse("爆发也不许", Needs.canBurst(st, risk = 0.1, now = now))
    }

    @Test
    fun `关系没到的时候想要摸头不压长度`() {
        val far = s(touch = 1.0, closeness = 0.10)
        assertEquals("要不到的需要不该让她少说话", 1.0, Needs.lengthFactor(far, Explain.riskOf(far)), 0.001)
    }

    // ── 被在意：有涨有降（2026-11-24 用户发现它只涨不降）──

    private fun ev(type: String, at: Long) = com.leaf.vixi.data.StateEvent(ts = at, type = type, note = "")

    @Test
    fun `回应了她的在意，被在意要降`() {
        val cold = s(closeness = 0.5, events = (1..3).map { ev("neglect", now - it * hour) })
        val cared = cold.copy(events = cold.events + (1..2).map { ev("care", now - it * 60_000L) })
        assertTrue("原来 ${Needs.belong(cold, now)}，现在 ${Needs.belong(cared, now)}",
            Needs.belong(cared, now) < Needs.belong(cold, now))
    }

    @Test
    fun `说不在意，被在意要涨`() {
        val base = s(closeness = 0.5)
        val rebuffed = base.copy(events = listOf(ev("rebuff", now - 60_000L)))
        assertTrue("要涨", Needs.belong(rebuffed, now) > Needs.belong(base, now))
    }

    @Test
    fun `不但在意要认得出来，不在意也要认得出来`() {
        assertEquals(1, Needs.careSignal("我在意你"))
        assertEquals(1, Needs.careSignal("我会陪着你的"))
        assertEquals(-1, Needs.careSignal("不在意"))
        assertEquals(-1, Needs.careSignal("无所谓"))
        assertEquals(0, Needs.careSignal("今天好累"))
        assertEquals("否定前缀不算在意", -1, Needs.careSignal("我不在意"))
    }

    @Test
    fun `被在意不会掉到负的也不会超过一`() {
        val many = s(closeness = 0.5, events = (1..10).map { ev("care", now - it * 60_000L) })
        assertTrue(Needs.belong(many, now) >= 0.0)
        val rebuffs = s(closeness = 0.5, events = (1..10).map { ev("rebuff", now - it * 60_000L) })
        assertTrue(Needs.belong(rebuffs, now) <= 1.0)
    }
}
