package com.leaf.vixi.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `subject` 字段（2026-11-26）：把"她的事"和"他的事"分开。
 *
 * 为什么要改：真机实测 `{"who":"他"}` 这个可选字段，模型**从来不写** ——
 * `facts_user.jsonl` 一直不存在，"关于他"那半永远空着。
 * 改成必填枚举 `subject`（她 / 他 / 成员名），并保留老 `who` 兼容。
 */
class FactSubjectTest {

    @Test
    fun `subject 她`() {
        val p = FactBlock.split("嗯。\n###FACT {\"subject\":\"她\",\"fact\":\"喜欢吃甜食\"}")
        assertEquals("她", p.items[0].who)
        assertEquals("喜欢吃甜食", p.items[0].fact)
    }

    @Test
    fun `subject 他`() {
        val p = FactBlock.split("好。\n###FACT {\"subject\":\"他\",\"fact\":\"不吃香菜\"}")
        assertEquals("他", p.items[0].who)
        assertEquals("不吃香菜", p.items[0].fact)
    }

    @Test
    fun `老写法 who 仍然认`() {
        val p = FactBlock.split("好。\n###FACT {\"who\":\"Nami\",\"fact\":\"怕打雷\"}")
        assertEquals("Nami", p.items[0].who)
    }

    @Test
    fun `subject 为空时回落到 who`() {
        val p = FactBlock.split("好。\n###FACT {\"subject\":\"\",\"who\":\"他\",\"fact\":\"爱喝冰美式\"}")
        assertEquals("他", p.items[0].who)
    }

    @Test
    fun `没有 subject 也不影响正文`() {
        val p = FactBlock.split("好。\n###FACT {\"fact\":\"养了只猫\"}")
        assertNull(p.items[0].who)
        assertEquals("好。", p.body)
    }

    @Test
    fun `多条里可以一半她一半他`() {
        val p = FactBlock.split(
            "行。\n###FACT {\"facts\":[{\"subject\":\"她\",\"fact\":\"怕黑\"},{\"subject\":\"他\",\"fact\":\"上夜班\"}]}",
        )
        assertEquals(2, p.items.size)
        assertEquals("怕黑", p.items[0].fact)
        assertEquals("他", p.items[1].who)
        assertTrue(p.body.startsWith("行。"))
    }
}
