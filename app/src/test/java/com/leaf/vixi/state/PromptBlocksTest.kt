package com.leaf.vixi.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 交叉层·铺陈 + 留白（第七、四节）：位置固定、空块丢弃。 */
class PromptBlocksTest {

    @Test
    fun `按位置排序，稳定的在前`() {
        val out = PromptBlocks.assemble(
            listOf(
                PromptBlocks.Block(PromptBlocks.VOLATILE, "状态", "【此刻状态】平静"),
                PromptBlocks.Block(PromptBlocks.STABLE, "人设", "你是Nami"),
            ),
        )
        assertTrue("人设要在前面：$out", out.indexOf("你是Nami") < out.indexOf("【此刻状态】"))
    }

    @Test
    fun `空块直接丢，不留占位`() {
        val out = PromptBlocks.assemble(
            listOf(
                PromptBlocks.Block(PromptBlocks.STABLE, "人设", "你是Nami"),
                PromptBlocks.Block(PromptBlocks.VOLATILE, "需要", ""),
            ),
        )
        assertEquals("你是Nami", out)
    }

    @Test
    fun `同一位置保持插入顺序`() {
        val out = PromptBlocks.assemble(
            listOf(
                PromptBlocks.Block(PromptBlocks.VOLATILE, "a", "第一"),
                PromptBlocks.Block(PromptBlocks.VOLATILE, "b", "第二"),
            ),
        )
        assertEquals("第一\n第二", out)
    }

    @Test
    fun `硬规则永远在最后`() {
        val out = PromptBlocks.assemble(
            listOf(
                PromptBlocks.Block(PromptBlocks.LAST, "硬规则", "【硬规则】别用感叹号"),
                PromptBlocks.Block(PromptBlocks.VOLATILE, "状态", "【此刻状态】平静"),
            ),
        )
        assertTrue("硬规则要最后：$out", out.endsWith("【硬规则】别用感叹号"))
    }
}
