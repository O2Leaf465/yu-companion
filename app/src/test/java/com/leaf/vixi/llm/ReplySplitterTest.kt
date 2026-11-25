package com.leaf.vixi.llm

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 切条：**默认上限 5 条**（2026-11-25 回调）。
 *
 * 2026-11-23 曾取消上限（"该发几条就发几条"），但真机上出现"一条回复切成十几条、
 * 演出一分半"的体感问题，于是回到 5 条。仍然只调一次 API —— 切条是本地做的。
 *
 * 关键契约：**超出上限的并进最后一条，绝不丢她说的话**。
 */
class ReplySplitterTest {

    @Test
    fun `六句切五条，多的一句并进最后一条`() {
        val raw = "我在\n这是第二句\n第三句\n第四句\n第五句\n第六句"
        val out = ReplySplitter.split(raw)
        assertEquals(5, out.size)
        assertEquals("第四句", out[3])
        // 用**空格**连：直接拼会变成「第五句第六句」，读起来像一句话
        assertEquals("第五句 第六句", out[4])
    }

    @Test
    fun `十句也不丢内容，只是并进最后一条`() {
        val raw = (1..10).joinToString("\n") { "第${it}句" }
        val out = ReplySplitter.split(raw)
        assertEquals(5, out.size)
        // 一句都不能少：拼回去必须还能找到最后一句
        assertEquals(true, out.last().contains("第10句"))
        assertEquals(true, out.joinToString("").contains("第9句"))
    }

    @Test
    fun `设了上限就按下限合并，而且是并进最后一条不是丢掉`() {
        val raw = (1..5).joinToString("\n") { "第${it}句" }
        val out = ReplySplitter.split(raw, maxBubbles = 2)
        assertEquals(2, out.size)
        assertEquals("第2句 第3句 第4句 第5句", out[1])
    }

    @Test
    fun `五句以内原样保留`() {
        val raw = "一\n二\n三\n四"
        assertEquals(4, ReplySplitter.split(raw).size)
    }

    @Test
    fun `空文本还是给个占位`() {
        assertEquals(listOf("……"), ReplySplitter.split(""))
    }
}
