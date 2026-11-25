package com.leaf.vixi.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 导出目录名的契约：**导出和删除必须算出一个名字**。
 *
 * 这里锁住的是 2026-11-25 找出的那个 P0：
 * `RecordArchive` 当时用自己的 `[^A-Za-z0-9_-] → _` 算目录名，
 * 「鱼」被算成 `__`、「群-群聊」被算成 `______`，和真实目录对不上 →
 * 删会话时 `notes = 0`，整段对话的 md 全留在 `文档/鱼/` 里，用户以为删干净了。
 *
 * 这个测试不是为了覆盖率，是为了让"两边各算一次"这件事再也回不来。
 */
class TopicExporterDirTest {

    @Test
    fun `中文名原样保留`() {
        assertEquals("鱼", TopicExporter.exportDirName("鱼"))
        assertEquals("Nami", TopicExporter.exportDirName("Nami"))
    }

    @Test
    fun `群聊目录名带连字符也不变形`() {
        assertEquals("群-群聊", TopicExporter.exportDirName("群-群聊"))
    }

    @Test
    fun `只去掉文件系统非法字符，其他一律保留`() {
        // 斜杠必须消失（否则会变成多级目录）
        assertEquals("ab", TopicExporter.exportDirName("a/b"))
        assertEquals(false, TopicExporter.exportDirName("a/b").contains("/"))
        // 百分号是合法文件名字符，保留（当年 SQL LIKE 通配符的坑已经改用 `=` 精确匹配解决）
        assertEquals("50%鱼", TopicExporter.exportDirName("50%鱼"))
    }

    @Test
    fun `空名字给空串，交给调用方拒绝`() {
        assertEquals("", TopicExporter.exportDirName(""))
        assertEquals("", TopicExporter.exportDirName("   "))
    }
}
