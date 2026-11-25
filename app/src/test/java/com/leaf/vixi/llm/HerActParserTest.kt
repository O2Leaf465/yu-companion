package com.leaf.vixi.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 她能引用、能发图（2026-11-25）—— 锁两件事：
 *
 * 1. **标签必须剥干净**：`[引用:…]` / `[图:…]` 不能漏进气泡正文。
 *    漏了用户在聊天里会直接看到 `[图:熊猫头.jpg]` 这种东西。
 * 2. **认不出来就不动手**：没有标签 / 标签写坏 → 原样返回，她不会因此哑掉。
 */
class HerActParserTest {

    @Test
    fun `引用标签被剥出来，正文不留痕迹`() {
        val s = HerActParser.enrich(Spoken(null, "[引用:你不是说要早睡吗] 你自己看看几点了。"))
        assertEquals("你不是说要早睡吗", s.quote)
        assertEquals("你自己看看几点了。", s.text)
    }

    @Test
    fun `剥完剩个孤零零的标点时去掉`() {
        val s = HerActParser.enrich(Spoken(null, "[引用:早点睡]，晚安。"))
        assertEquals("晚安。", s.text)
    }

    @Test
    fun `发图标签单独一行也能认`() {
        val s = HerActParser.enrich(Spoken(null, "[图:熊猫头无语.jpg]"))
        assertEquals("熊猫头无语.jpg", s.image)
        assertEquals("", s.text)
    }

    @Test
    fun `引用和发图可以同时出现`() {
        val s = HerActParser.enrich(Spoken(null, "[引用:在吗] [图:猫猫点头.png] 在的。"))
        assertEquals("在吗", s.quote)
        assertEquals("猫猫点头.png", s.image)
        assertEquals("在的。", s.text)
    }

    @Test
    fun `没有标签时一字不动`() {
        val s = HerActParser.enrich(Spoken(null, "今天有点累，先睡了。"))
        assertNull(s.quote)
        assertNull(s.image)
        assertEquals("今天有点累，先睡了。", s.text)
    }

    @Test
    fun `写坏的标签当作普通文字，不要吞掉正文`() {
        val s = HerActParser.enrich(Spoken(null, "这个[图]我发不出来"))
        assertNull(s.image)
        assertEquals("这个[图]我发不出来", s.text)
    }

    @Test
    fun `全角冒号也认`() {
        val s = HerActParser.enrich(Spoken(null, "[引用：那句原文] 嗯。"))
        assertEquals("那句原文", s.quote)
    }

    @Test
    fun `群聊里带说话人的句子照样能剥`() {
        val list = HerActParser.enrichAll(
            listOf(
                Spoken("Nami", "[引用:你答应过的] 你答应过的事呢？"),
                Spoken("鱼", "我作证。"),
            ),
        )
        assertEquals("Nami", list[0].speaker)
        assertEquals("你答应过的", list[0].quote)
        assertTrue(list[0].text.startsWith("你答应过的事"))
        assertNull(list[1].quote)
    }
}
