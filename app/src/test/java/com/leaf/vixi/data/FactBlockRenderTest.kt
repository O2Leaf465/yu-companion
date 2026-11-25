package com.leaf.vixi.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 事实注入段的拼装（2026-11-26 提取成纯函数）。
 *
 * 为什么要单测：真机上出现过 "关于我的事实没进提示词" ——
 * 而这条链路以前只能靠发真消息 + 模型配合才能看到。
 * 现在这段是纯函数，两段规则（她的 / 他的）各断言一次。
 */
class FactBlockRenderTest {

    @Test
    fun `只有他的事实时也要出一段`() {
        val text = FactStore.renderBlock(
            group = false,
            hers = emptyList(),
            his = listOf("不吃香菜"),
        )
        assertTrue("必须包含【关于他的事实】", text.contains("【关于他的事实"))
        assertTrue(text.contains("- 不吃香菜"))
        assertTrue("不该出现她的那段", !text.contains("【关于她的事实"))
    }

    @Test
    fun `只有她的事实`() {
        val text = FactStore.renderBlock(
            group = false,
            hers = listOf("" to listOf("喜欢吃甜食")),
            his = emptyList(),
        )
        assertTrue(text.contains("【关于她的事实"))
        assertTrue(text.contains("- 喜欢吃甜食"))
        assertTrue(!text.contains("【关于他的事实"))
    }

    @Test
    fun `两边都有：她在前、他在后`() {
        val text = FactStore.renderBlock(
            group = false,
            hers = listOf("" to listOf("怕黑")),
            his = listOf("上夜班"),
        )
        val iHer = text.indexOf("【关于她的事实")
        val iHis = text.indexOf("【关于他的事实")
        assertTrue("两段都要在", iHer >= 0 && iHis >= 0)
        assertTrue("他的那段排在后面", iHis > iHer)
        assertTrue(text.contains("- 怕黑"))
        assertTrue(text.contains("- 上夜班"))
    }

    @Test
    fun `群聊时她的事实带名字前缀`() {
        val text = FactStore.renderBlock(
            group = true,
            hers = listOf("Nami：" to listOf("怕打雷")),
            his = emptyList(),
        )
        assertTrue(text.contains("【关于她们的事实"))
        assertTrue(text.contains("- Nami：怕打雷"))
    }

    @Test
    fun `两边都空就返回空串（调用方据此不注入）`() {
        assertEquals("", FactStore.renderBlock(false, emptyList(), emptyList()))
    }

    @Test
    fun `最多只注入最近 20 条`() {
        val many = (1..30).map { "事实$it" }
        val text = FactStore.renderBlock(false, emptyList(), many)
        // 只保留最近的 20 条：11~30 在，1~10 不在
        assertTrue(text.contains("事实30"))
        assertTrue(text.contains("事实11"))
        assertTrue("最早的应被截掉", !text.contains("- 事实1\n") && !text.contains("- 事实10\n"))
    }

    @Test
    fun `结尾那句话两边都有`() {
        val text = FactStore.renderBlock(false, emptyList(), listOf("爱喝冰美式"))
        assertTrue(text.contains("别把它们念出来"))
    }
}
