package com.leaf.vixi.state

/**
 * 交叉层·铺陈（第七节）+ 留白（第四节）+ 去饰（第五节）。
 *
 * 三件事其实是一件事：**提示词里只放真有内容的东西，并且按固定位置排**。
 *
 * - **留白**：空块直接丢（不写"（无）"占位）
 * - **铺陈**：位置是常数（稳定在前、易变在后），不是"我每次记得把这段往后放"
 * - **去饰**：块内部只留一句人话，拼字符串的工作只在 `assemble` 一处
 *
 * 为什么用结构而不是自觉：缓存前缀的纪律靠不住人。
 * 一旦有人在前面插一行每轮都变的东西，成本会 ×50 而且**不报错**。
 */
object PromptBlocks {

    /** 历史之前：几乎不变（人设、格式约定、群成员） */
    const val STABLE = 100

    /** 历史之间：半稳定（摘要、长期偏好） */
    const val SEMI = 150

    /** 历史之后：每轮都变（状态、说多少、需要、分寸…） */
    const val VOLATILE = 190

    /** 最末尾：硬规则兜底（必须最后看到） */
    const val LAST = 200

    data class Block(val order: Int, val title: String, val text: String)

    /** 组装：丢空块 → 按 order 稳定排序（同 order 保持插入顺序）→ 用换行连接 */
    fun assemble(blocks: List<Block>): String =
        blocks.withIndex()
            .filter { it.value.text.isNotBlank() }
            .sortedWith(compareBy({ it.value.order }, { it.index }))
            .joinToString("\n") { it.value.text.trim() }

    /** 一组易变块：把"某一行为空"的删掉，剩下的顺序固定 */
    fun volatile(vararg parts: Pair<String, String>): List<Block> =
        parts.mapIndexed { i, (title, text) -> Block(VOLATILE + i, title, text) }
}
