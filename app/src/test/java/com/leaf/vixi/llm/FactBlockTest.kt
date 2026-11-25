package com.leaf.vixi.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 事实尾巴（2026-11-25 用户定稿）：模型每轮最后一行给 `###FACT {...}` 或 `###FACT null`。
 *
 * 两个必须守住的点：
 * 1. **尾巴不许进气泡**（哪怕它是坏 JSON）
 * 2. **没有尾巴也不能把整轮回复弄丢**
 */
class FactBlockTest {

    @Test
    fun `有事实时切出字段，正文干净`() {
        val p = FactBlock.split("那家店我记着呢。\n###FACT {\"fact\":\"喜欢草莓\"}")
        assertEquals("那家店我记着呢。", p.body)
        assertEquals(1, p.items.size)
        assertEquals("喜欢草莓", p.items[0].fact)
    }

    @Test
    fun `null 表示这轮没有事实`() {
        val p = FactBlock.split("今天好累。\n###FACT null")
        assertEquals("今天好累。", p.body)
        assertTrue(p.items.isEmpty())
        assertTrue(p.hasMark)
    }

    @Test
    fun `群聊带 who`() {
        val p = FactBlock.split("我也喜欢。\n###FACT {\"who\":\"Nami\",\"fact\":\"怕打雷\"}")
        assertEquals("Nami", p.items[0].who)
        assertEquals("怕打雷", p.items[0].fact)
    }

    @Test
    fun `坏 JSON 也不许把尾巴漏进气泡`() {
        val p = FactBlock.split("嗯。\n###FACT {\"fact\":")
        assertEquals("嗯。", p.body)
        assertTrue(p.items.isEmpty())
    }

    @Test
    fun `支持一次给多条`() {
        val p = FactBlock.split(
            "好。\n###FACT {\"facts\":[{\"fact\":\"喜欢草莓\"},{\"who\":\"Nami\",\"fact\":\"怕打雷\"}]}",
        )
        assertEquals(2, p.items.size)
        assertEquals("怕打雷", p.items[1].fact)
    }

    @Test
    fun `代码块包着也能解析`() {
        val p = FactBlock.split("好。\n###FACT ```json\n{\"fact\":\"养了只猫\"}\n```")
        assertEquals("养了只猫", p.items[0].fact)
    }

    @Test
    fun `没有尾巴时正文原样返回`() {
        val raw = "今天有点累，先睡了。"
        val p = FactBlock.split(raw)
        assertEquals(raw, p.body)
        assertTrue(p.items.isEmpty())
        assertFalse(p.hasMark)
    }

    @Test
    fun `整轮只有尾巴时给个占位，不返回空串`() {
        val p = FactBlock.split("###FACT {\"fact\":\"喜欢草莓\"}")
        assertEquals("……", p.body)
        assertEquals(1, p.items.size)
    }
}
