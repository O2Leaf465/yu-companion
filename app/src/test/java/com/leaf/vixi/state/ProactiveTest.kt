package com.leaf.vixi.state

import com.leaf.vixi.data.VixiState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 主动开口（后台版）：触发分与门禁。纯函数，不碰 Android。 */
class ProactiveTest {

    private val hour = 3_600_000L
    private val now = 1_700_000_000_000L

    @Test
    fun `什么都没发生时不会主动开口`() {
        val d = Proactive.decide(VixiState(enabled = true, lastHeardAt = now), now, hour = 14)
        assertFalse("默认状态不该发：${d.score} ${d.reason}", d.send)
    }

    @Test
    fun `很想他又憋着话的时候会开口`() {
        val s = VixiState(
            enabled = true,
            lastHeardAt = now - 3 * 24 * hour,
            missing = 0.95,
            backlog = 0.6,
            needTouch = 0.95,
        )
        val d = Proactive.decide(s, now, hour = 14)
        assertTrue("该发：${d.score} ${d.reason}", d.send)
    }

    @Test
    fun `刚主动过就闭嘴`() {
        val s = VixiState(
            enabled = true,
            lastHeardAt = now - 3 * 24 * hour,
            missing = 0.95,
            backlog = 0.6,
            needTouch = 0.95,
        )
        val hot = Proactive.decide(s, now, hour = 14, history = Proactive.History(lastSentAt = now - 2 * hour))
        val cold = Proactive.decide(s, now, hour = 14)
        assertTrue("冷却该让它更低：${hot.score} vs ${cold.score}", hot.score < cold.score)
    }

    @Test
    fun `只看想念和暗流，需要顶格不再单独触发`() {
        // 需要顶到满、但不想念也不憋着 → 不该开口
        val s = VixiState(
            enabled = true, lastHeardAt = now - 60_000L,
            needTouch = 1.0, needRespect = 1.0, missing = 0.0, backlog = 0.0,
        )
        val d = Proactive.decide(s, now, hour = 14)
        assertFalse("需要不参与开口：${d.score}", d.send)
    }

    @Test
    fun `半夜不发`() {
        val s = VixiState(
            enabled = true, lastHeardAt = now - 3 * 24 * hour,
            missing = 0.95, backlog = 0.9, needTouch = 1.0, needRespect = 1.0,
        )
        val night = Proactive.decide(s, now, hour = 3)
        val day = Proactive.decide(s, now, hour = 14)
        assertTrue("半夜分更低：${night.score} vs ${day.score}", night.score < day.score)
    }

    @Test
    fun `上次主动没被回，第二次更难开口`() {
        val s = VixiState(
            enabled = true, lastHeardAt = now - 3 * 24 * hour,
            missing = 0.95, backlog = 0.6, needTouch = 0.95,
        )
        val ignored = Proactive.decide(s, now, hour = 14, history = Proactive.History(lastSentAt = now - 4 * hour, lastReplied = false))
        val normal = Proactive.decide(s, now, hour = 14, history = Proactive.History(lastSentAt = now - 4 * hour, lastReplied = true))
        assertTrue("被拒该更低：${ignored.score} vs ${normal.score}", ignored.score < normal.score)
    }
}

