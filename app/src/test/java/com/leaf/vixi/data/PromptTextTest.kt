package com.leaf.vixi.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * user 段文案表（2026-11-26）。
 *
 * 两个必须守住的点：
 * 1. **不建文件时和抽取之前逐字一致** —— 否则"可编辑"这件事本身就会改掉她的手感
 * 2. **占位符要能填**（`{names}` / `{example}` / `{list}`），填不上的会原样漏进提示词
 */
class PromptTextTest {

    @Test
    fun `默认值和抽取前一致`() {
        assertEquals("【已读】", PromptText.text("seg.read"))
        assertEquals("【新消息】", PromptText.text("seg.new"))
        assertEquals("【现在】", PromptText.text("seg.now"))
    }

    @Test
    fun `未知键返回空串（这一行不发）`() {
        assertEquals("", PromptText.text("no.such.key"))
    }

    @Test
    fun `群聊格式里的名字占位符会被填`() {
        val s = PromptText.fill("seg.group2", "names" to "鱼、Nami")
        assertTrue(s.contains("鱼、Nami"))
        assertTrue("占位符不该留下来", !s.contains("{names}"))
    }

    @Test
    fun `群聊示例占位符会被填`() {
        val s = PromptText.fill("seg.group1", "example" to "Nami：随便")
        assertTrue(s.contains("Nami：随便"))
        assertTrue(!s.contains("{example}"))
    }

    @Test
    fun `可以发的图那行能填贴纸列表`() {
        val s = PromptText.fill("act.image", "list" to "表情1.jpg、表情2.jpg")
        assertTrue(s.contains("表情1.jpg、表情2.jpg"))
        assertTrue(!s.contains("{list}"))
    }

    @Test
    fun `事实字段的默认文案里带着 subject 枚举`() {
        val s = PromptText.text("act.fact")
        assertTrue("必须提 subject（否则模型不写归属）", s.contains("subject"))
        assertTrue("必须给出 null 这个选项", s.contains("###FACT null"))
    }

    @Test
    fun `每一条默认文案都非空（防止模板里漏键）`() {
        PromptText.DEFAULTS.forEach { (k, v) ->
            assertTrue("键 $k 的默认值是空的", v.isNotBlank())
        }
    }

    @Test
    fun `模板里列全了所有键`() {
        PromptText.DEFAULTS.keys.forEach { k ->
            assertTrue("模板里没有 $k", PromptText.TEMPLATE.contains("$k="))
        }
    }
}
