package com.leaf.vixi.state

import com.leaf.vixi.data.VixiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 时间衰减的回归测试。
 *
 * 关键性质：**结果只取决于"经过了多久"，和"算了多少次"无关** ——
 * 这正是惰性补算能和"每秒算一次"等价的前提。
 */
class DecayTest {

    private val half = Decay.DECAY_HALF_LIFE_MS

    /** 缓冲池 = 情绪池（"已经到账"的状态）。真实状态由 apply() 写入，测试要显式给 */
    private fun base() = VixiState(
        enabled = true, p = 0.8, a = 0.9, d = 0.9, lastTick = 1000L,
        surgeP = 0.8, surgeA = 0.9, surgeD = 0.9,
    )

    @Test
    fun `缓冲池一个半衰期后正好剩一半，情绪池比它慢`() {
        // 生物层·余温：缓冲池是"还没走完的余温"，严格 90 分钟半衰；
        // 情绪池追它（30 分钟时间常数），所以**比纯半衰慢一点**——这就是"余温"。
        val s = Decay.settle(base(), now = 1000L + half)
        assertEquals("缓冲池 0.8 → 0.4", 0.4, s.surgeP, 0.001)
        assertEquals("a 的缓冲池 0.9 → 0.6", 0.6, s.surgeA, 0.001)
        assertTrue("情绪池该比缓冲池高（还没落下去）：${s.p}", s.p > 0.4 && s.p < 0.8)
        assertTrue("a 同理：${s.a}", s.a > 0.6 && s.a < 0.9)
    }

    @Test
    fun `两个半衰期后缓冲池剩四分之一`() {
        val s = Decay.settle(base(), now = 1000L + half * 2)
        assertEquals(0.2, s.surgeP, 0.001)
        assertEquals(0.45, s.surgeA, 0.001)
        assertTrue("情绪池仍在缓冲池之上：${s.p}", s.p > 0.2)
        assertTrue("但已经很低了：${s.p}", s.p < 0.5)
    }

    @Test
    fun `分成多次结算和一次结算结果一致`() {
        val one = Decay.settle(base(), now = 1000L + half * 2)
        val step1 = Decay.settle(base(), now = 1000L + half)
        val step2 = Decay.settle(step1, now = 1000L + half * 2)
        assertEquals(one.p, step2.p, 0.001)
        assertEquals(one.a, step2.a, 0.001)
    }

    @Test
    fun `结算会推进 lastTick`() {
        val s = Decay.settle(base(), now = 9999L)
        assertEquals(9999L, s.lastTick)
    }

    @Test
    fun `时钟没往前走时不动`() {
        val s = Decay.settle(base(), now = 1000L)
        assertEquals(1000L, s.lastTick)
        assertEquals(0.8, s.p, 0.0001)
    }

    @Test
    fun `第一次结算只定基准`() {
        val fresh = VixiState(enabled = true, p = 0.5, lastTick = 0L)
        val s = Decay.settle(fresh, now = 5000L)
        assertEquals(5000L, s.lastTick)
        assertEquals("没有过去可补，数值不动", 0.5, s.p, 0.0001)
    }

    @Test
    fun `未启用时完全不结算`() {
        val off = VixiState(enabled = false, p = 0.9, lastTick = 1000L)
        val s = Decay.settle(off, now = 1000L + half * 5)
        assertEquals(1000L, s.lastTick)
        assertEquals(0.9, s.p, 0.0001)
    }

    @Test
    fun `暗流会自己淡，但比情绪慢得多`() {
        // 2026-11-24 改：原来"不衰减"，结果只涨不降、聊几天就顶死。
        // 现在 10 小时半衰 —— 90 分钟的情绪早没了，暗流还在。
        val s = Decay.settle(
            VixiState(enabled = true, backlog = 0.7, lastTick = 1000L),
            now = 1000L + half * 10,
        )
        // 15 小时 = 1.5 个暗流半衰 → 0.7 × 0.5^1.5 ≈ 0.25
        assertTrue("15 小时该淡掉七成：${s.backlog}", s.backlog < 0.30)
        val short = Decay.settle(
            VixiState(enabled = true, backlog = 0.7, lastTick = 1000L),
            now = 1000L + half,
        )
        // 90 分钟只是 0.15 个半衰 → 0.7 → 0.63（掉了 10%，但还留着大半）
        assertTrue("但 90 分钟时还留着大半：${short.backlog}", short.backlog > 0.60)
    }

    @Test
    fun `冲突按24小时半衰慢慢消`() {
        // 加交叉层时改的：以前 conflict 永不衰减，`conflict ≥ 0.20` 会把"信任回升"永久锁死。
        // 现在 0.40 挂一天变 0.20 —— 她还记得这件事，只是没那么绷了。
        val s = Decay.settle(
            VixiState(enabled = true, conflict = 0.40, lastTick = 1000L),
            now = 1000L + Decay.CONFLICT_HALF_LIFE_MS,
        )
        assertEquals(0.20, s.conflict, 0.01)
    }

    @Test
    fun `冲突不会隔一夜就忘光`() {
        // 24 小时半衰必须是"慢"的：一小时只掉 3% 左右，不能像情绪那样 90 分钟就散
        val s = Decay.settle(
            VixiState(enabled = true, conflict = 0.40, lastTick = 1000L),
            now = 1000L + 60 * 60 * 1000L,
        )
        assertTrue("一小时后应该还在 0.38 以上", s.conflict > 0.38)
    }

    @Test
    fun `回落方向朝向基线而不是零`() {
        // a 的基线是 0.3，从 0.0 起算该往基线走；
        // 余温之后情绪池追着缓冲池走，所以**比缓冲池慢半拍**（缓冲池已到 0.15，a 还在 0.09）
        val s = Decay.settle(
            VixiState(enabled = true, a = 0.0, lastTick = 1000L),
            now = 1000L + half,
        )
        assertEquals("缓冲池先到一半", 0.15, s.surgeA, 0.001)
        assertTrue("情绪池还在后面：${s.a}", s.a > 0.0 && s.a < 0.15)

        // 多给几个半衰期，它会追上缓冲池、一起贴到基线
        val later = Decay.settle(s, now = 1000L + half * 6)
        assertTrue("最终要贴近基线：${later.a}", later.a > 0.25)
    }

    // ────────── 体力：跟着钟点走（2026-11-22 用户反馈"体力没有明显变化"）──────────

    @Test
    fun `体力目标是一条昼夜曲线`() {
        assertEquals(0.22, Decay.energyBaseAt(3.0), 0.01)   // 最困
        assertEquals(0.80, Decay.energyBaseAt(9.0), 0.01)   // 上午
        assertEquals(0.85, Decay.energyBaseAt(15.0), 0.01)  // 下午最好
        assertEquals(0.72, Decay.energyBaseAt(13.0), 0.01)  // 午后低谷
        assertEquals("0 点和 24 点必须接上，否则过整点会跳一格",
            Decay.energyBaseAt(0.0), Decay.energyBaseAt(24.0), 0.001)
        assertEquals("曲线在关键点之间是连续的", 0.625, Decay.energyBaseAt(7.5), 0.02)
    }

    @Test
    fun `下午打开的体力会被拉到高值，深夜会被拉低`() {
        val start = VixiState(enabled = true, energy = 0.80, lastTick = 1000L)
        // 停在下午（15 点）两小时：目标 0.85，应该往上走
        val afternoon = Decay.settle(start, now = 1000L + 2 * 60 * 60 * 1000L, hour = 15.0)
        assertTrue("下午应该精神：${afternoon.energy}", afternoon.energy > 0.80)
        // 停在凌晨 3 点两小时：目标 0.22，应该明显掉下来
        val night = Decay.settle(start, now = 1000L + 2 * 60 * 60 * 1000L, hour = 3.0)
        assertTrue("凌晨应该累下来：${night.energy}", night.energy < 0.55)
    }

    @Test
    fun `体力不会瞬间归零而是按2小时半衰期靠近目标`() {
        val s = Decay.settle(
            VixiState(enabled = true, energy = 0.80, lastTick = 1000L),
            now = 1000L + Decay.ENERGY_HALF_LIFE_MS,
            hour = 3.0,
        )
        // 一个半衰期：离目标（0.22）的距离剩一半 → 0.80 - (0.80-0.22)/2 ≈ 0.51
        assertEquals(0.51, s.energy, 0.02)
    }

    // ── 摸头需要：关系没到"能被摸"就不该涨（2026-11-23 用户指出）──

    @Test
    fun `还不亲近的时候想被摸不会涨`() {
        // closeness < PAT_MIN_CLOSENESS 时摸头算越界；这种状态下还涨 = 死锁
        val st = VixiState(enabled = true, closeness = 0.10, needTouch = 0.40)
        val after = Decay.settle(st.copy(lastTick = 1000L), now = 1000L + 24 * 3600_000L)
        assertEquals("不该涨", 0.40, after.needTouch, 0.001)
    }

    @Test
    fun `关系够近就会慢慢想被摸`() {
        val st = VixiState(enabled = true, closeness = 0.50, needTouch = 0.40)
        val after = Decay.settle(st.copy(lastTick = 1000L), now = 1000L + 6 * 3600_000L)
        // 6 小时 × 0.06 = +0.36 → 0.40 → 0.76
        assertTrue("六小时要从 0.40 涨到 0.76：${after.needTouch}", after.needTouch > 0.70)
    }

    // ── 暗流出闸口（2026-11-24 用户发现它只涨不降）──

    @Test
    fun `暗流会随时间淡下去`() {
        val st = VixiState(enabled = true, backlog = 0.80, lastTick = 1000L)
        val after = Decay.settle(st, now = 1000L + 10 * 3600_000L)
        assertTrue("10 小时该剩一半：${after.backlog}", after.backlog < 0.50)
        assertTrue("不会变成负的", after.backlog >= 0.0)
    }

    @Test
    fun `她说得多，暗流放掉一块`() {
        val st = VixiState(enabled = true, backlog = 0.50)
        val after = EventRules.apply(st, StateEventType.HER_LONG, now = 1000L)
        assertTrue("要说出口就减压：${after.backlog}", after.backlog < 0.50)
    }

    @Test
    fun `主动发问和道歉也能减压`() {
        val st = VixiState(enabled = true, backlog = 0.50)
        assertTrue(EventRules.apply(st, StateEventType.HER_ASK, now = 1000L).backlog < 0.50)
        assertTrue(EventRules.apply(st, StateEventType.APOLOGIZE, now = 1000L).backlog < 0.50)
    }

    @Test
    fun `回得很短还是在加暗流`() {
        val st = VixiState(enabled = true, backlog = 0.20)
        assertTrue(EventRules.apply(st, StateEventType.HER_SHORT, now = 1000L).backlog > 0.20)
    }
}
