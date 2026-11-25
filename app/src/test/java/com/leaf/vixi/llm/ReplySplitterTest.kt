package com.leaf.vixi.llm

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 切条：2026-11-23 起**不再限定 3 条**（用户要求"该发几条就发几条"）。
 * 仍然只调一次 API —— 切条是本地做的，不额外花钱。
 */
class ReplySplitterTest {

    @Test
    fun `六句就切六条，不再并成三条`() {
        val raw = "我在\n这是第二句\n第三句\n第四句\n第五句\n第六句"
        val out = ReplySplitter.split(raw)
        assertEquals(6, out.size)
        assertEquals("第六句", out.last())
    }

    @Test
    fun `十句也不丢`() {
        val raw = (1..10).joinToString("\n") { "第${it}句" }
        assertEquals(10, ReplySplitter.split(raw).size)
    }

    @Test
    fun `设了上限才合并，而且是并进最后一条不是丢掉`() {
        val raw = (1..5).joinToString("\n") { "第${it}句" }
        val out = ReplySplitter.split(raw, maxBubbles = 2)
        assertEquals(2, out.size)
        assertEquals("第2句第3句第4句第5句", out[1])
    }

    @Test
    fun `空文本还是给个占位`() {
        assertEquals(listOf("……"), ReplySplitter.split(""))
    }
}
