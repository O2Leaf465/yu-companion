package com.leaf.vixi.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 状态事件分类的回归测试。
 *
 * 为什么要有：词表是最容易被"以为写对了"的部分 ——
 * 一句"我不讨厌你"被判成骂人，就会让状态朝反方向走，而真机上几乎看不出来。
 *
 * 跑：`.\gradlew.bat :app:testDebugUnitTest`
 */
class EventRulesTest {

    private fun types(text: String) = EventRules.classifyUser(text)

    // ────────── 正向 ──────────

    @Test
    fun `夸奖算 praise`() {
        assertEquals(listOf(StateEventType.PRAISE), types("你好可爱"))
    }

    @Test
    fun `撒娇算 affection`() {
        assertEquals(listOf(StateEventType.AFFECTION), types("抱抱"))
    }

    @Test
    fun `亲密词不会被当成骂人`() {
        assertTrue(StateEventType.NEGATIVE !in types("喜欢你"))
    }

    // ────────── 负向 ──────────

    @Test
    fun `强贬低算 negative`() {
        assertTrue(StateEventType.NEGATIVE in types("你真蠢"))
    }

    @Test
    fun `猪狗也算贬低`() {
        assertTrue(StateEventType.NEGATIVE in types("你是猪吧"))
    }

    @Test
    fun `挑剔行为算 negative`() {
        assertTrue(StateEventType.NEGATIVE in types("你这毛病得改改"))
    }

    @Test
    fun `弱词必须带你才判`() {
        // "烦"是说自己，不是骂她
        assertTrue(StateEventType.NEGATIVE !in types("好烦啊"))
        assertTrue(StateEventType.NEGATIVE in types("你很烦"))
    }

    @Test
    fun `否定词吃掉命中`() {
        assertTrue(StateEventType.NEGATIVE !in types("我不讨厌你"))
    }

    @Test
    fun `反讽算 negative 而不是 praise`() {
        val t = types("你可真聪明")
        assertTrue(StateEventType.NEGATIVE in t)
        assertTrue(StateEventType.PRAISE !in t)
    }

    @Test
    fun `强贬低夹正向词仍给一点正`() {
        val t = types("你傻得挺可爱")
        assertTrue(StateEventType.NEGATIVE in t)
        assertTrue("半开玩笑的夸应该还有正向", StateEventType.PRAISE in t || StateEventType.AFFECTION in t)
    }

    // ────────── 威胁 ──────────

    @Test
    fun `第一人称威胁算 threat`() {
        assertTrue(StateEventType.THREAT in types("我不理你了"))
    }

    @Test
    fun `问对方走不走不算威胁`() {
        assertTrue(StateEventType.THREAT !in types("你走了？"))
    }

    @Test
    fun `玩笑式威胁不打折也不记`() {
        assertTrue(StateEventType.THREAT !in types("我走了哈哈"))
    }

    // ────────── 道歉 / 纠正 ──────────

    @Test
    fun `普通道歉算 apologize`() {
        assertEquals(listOf(StateEventType.APOLOGIZE), types("对不起"))
    }

    @Test
    fun `讽刺道歉不给修复`() {
        val t = types("对不起行了吧")
        assertTrue(StateEventType.APOLOGIZE !in t)
        assertTrue(StateEventType.NEGATIVE in t)
    }

    @Test
    fun `纠正和道歉可以同时成立`() {
        val t = types("不对，我错了")
        assertTrue(StateEventType.CORRECT in t)
        assertTrue(StateEventType.APOLOGIZE in t)
    }

    // ────────── 冷淡 / 纯标点 ──────────

    @Test
    fun `敷衍短句算 cold`() {
        assertEquals(listOf(StateEventType.COLD), types("哦"))
    }

    @Test
    fun `纯标点算 cold`() {
        assertEquals(listOf(StateEventType.COLD), types("……"))
        assertEquals(listOf(StateEventType.COLD), types("？"))
    }

    @Test
    fun `普通短句不算 cold`() {
        assertTrue(StateEventType.COLD !in types("在吗"))
    }

    // ────────── 数值规则 ──────────

    @Test
    fun `你发消息几乎不加亲近`() {
        val d = EventRules.delta(StateEventType.USER_MSG)
        assertTrue("user_msg 不该加明显亲近度", d.closeness < 0.01)
    }

    @Test
    fun `情绪类事件不再直给数值`() {
        // P3 起：p/a/d 由"情绪标签 + 强度"给（EmotionDelta），类型表只留作分类
        listOf(
            StateEventType.PRAISE,
            StateEventType.AFFECTION,
            StateEventType.NEGATIVE,
            StateEventType.COLD,
            StateEventType.THREAT,
            StateEventType.CORRECT,
            StateEventType.APOLOGIZE,
            StateEventType.TEASE,
            StateEventType.EMOTION,
        ).forEach { type ->
            val d = EventRules.delta(type)
            assertEquals("$type 不该再有固定增量", 0.0, d.p + d.a + d.d, 0.0001)
        }
    }

    @Test
    fun `显式增量可以覆盖类型表`() {
        // 生物层·余温之后：增量**全量进缓冲池**，情绪池当场只兑现 25%
        val after = EventRules.apply(
            com.leaf.vixi.data.VixiState(enabled = true),
            StateEventType.EMOTION,
            now = 1L,
            override = Delta(p = -0.2),
        )
        assertEquals("缓冲池吃全量，但强刺激先回拉 15%", -0.17, after.surgeP, 0.0001)
        assertEquals("情绪池只兑现四分之一（r3 到三位小数）", -0.042, after.p, 0.0001)
    }

    @Test
    fun `事件不推进 lastTick`() {
        val before = com.leaf.vixi.data.VixiState(enabled = true, lastTick = 1000L)
        val after = EventRules.apply(before, StateEventType.USER_MSG, now = 9999L)
        assertEquals("事件不能改结算基准", 1000L, after.lastTick)
    }

    @Test
    fun `她的回复会刷新最后听到她的时间`() {
        val before = com.leaf.vixi.data.VixiState(enabled = true)
        val after = EventRules.apply(before, StateEventType.HER_REPLY, now = 5000L)
        assertEquals(5000L, after.lastHeardAt)
    }

    @Test
    fun `冷落按时间倍数放大且封顶三倍`() {
        assertEquals(1.0, EventRules.neglectMagnitude(30 * 60_000L), 0.001)
        assertEquals(3.0, EventRules.neglectMagnitude(24 * 60 * 60_000L), 0.001)
    }

    @Test
    fun `数值最多三位小数`() {
        val s = EventRules.apply(
            com.leaf.vixi.data.VixiState(enabled = true, p = 0.0, a = 0.30, d = 0.50),
            StateEventType.USER_MSG,
            now = 1L,
        )
        // 0.3 + 0.03 在 double 里是 0.32999999999999996，必须被收成 0.33。
        // 余温之后当场只兑现 25%：0.3 → 0.3075（缓冲池 0.33）
        assertEquals(0.33, s.surgeA, 0.0000001)
        assertEquals(0.308, s.a, 0.0001)
    }
}
