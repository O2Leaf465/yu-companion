package com.leaf.vixi.state

import com.leaf.vixi.data.PendingEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「哪条事件能把最近情绪标签改掉」的回归测试。
 *
 * 2026-11-23 真机 bug：越界事件已经写进状态（`boundary 越界·身体0.20`），
 * 但状态行还是上一轮的情绪 —— 因为转发时只认 EMOTION，BOUNDARY 的「反感」被丢了。
 */
class StateLabelForwardTest {

    @Test
    fun `越界要能把标签改成反感`() {
        val p = PendingEvent(
            type = StateEventType.BOUNDARY,
            note = "越界·称谓/轻0.35",
            magnitude = 0.35,
            label = EmotionLabel.AVERSION.zh,
        )
        assertEquals("反感", com.leaf.vixi.data.labelToForward(p))
    }

    @Test
    fun `没到反感的越界不改标签`() {
        val p = PendingEvent(
            type = StateEventType.BOUNDARY,
            note = "没越界",
            magnitude = 0.0,
            label = null,
        )
        assertNull(com.leaf.vixi.data.labelToForward(p))
    }

    @Test
    fun `情绪事件照旧从 note 兜底取标签`() {
        val p = PendingEvent(type = StateEventType.EMOTION, note = "害羞·praise·你好好看")
        assertEquals("害羞", com.leaf.vixi.data.labelToForward(p))
    }

    @Test
    fun `别的类型不该改标签`() {
        assertNull(
            com.leaf.vixi.data.labelToForward(
                PendingEvent(type = StateEventType.USER_MSG, note = "你发消息"),
            ),
        )
    }
}
