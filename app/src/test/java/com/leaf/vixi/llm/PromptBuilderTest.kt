package com.leaf.vixi.llm

import com.leaf.vixi.data.MsgKind
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提示词装配的回归测试。
 *
 * 为什么专门测这个：**这些行全是"可选参数"，漏传一个就静默失效**。
 * 真机上就漏过一次（`hardRules` 拼好了但没传进 `build`，三条硬规则一个字都没进提示词，
 * 而所有状态相关的测试依然全绿）。所以这里按"用户段里到底有没有这几行"来断言。
 */
class PromptBuilderTest {

    private fun build(
        hardRules: String = "",
        selfStateLine: String = "",
        lengthLine: String = "",
        initiativeLine: String = "",
        tactLine: String = "",
    ): String {
        val prompt = PromptBuilder(systemProvider = { "【人设】测试用" }).build(
            history = emptyList(),
            incoming = listOf(Incoming(text = "在吗", kind = MsgKind.TEXT)),
            momentLine = "【现在】11-20 18:00",
            hardRules = hardRules,
            selfStateLine = selfStateLine,
            lengthLine = lengthLine,
            initiativeLine = initiativeLine,
            tactLine = tactLine,
        )
        return prompt.user
    }

    @Test
    fun `硬规则进了用户段`() {
        val user = build(hardRules = "【硬规则】这一轮最多一个情绪词")
        assertTrue("漏传 hardRules 就是这条测出来", user.contains("【硬规则】"))
    }

    @Test
    fun `状态三行都在用户段里`() {
        val user = build(
            selfStateLine = "【此刻状态】平静。",
            lengthLine = "【说多少】约 28 字。",
            initiativeLine = "【怎么接】正常接。",
            tactLine = "【分寸】直说就行。",
        )
        listOf("【此刻状态】", "【说多少】", "【怎么接】", "【分寸】").forEach {
            assertTrue("$it 应该在用户段", user.contains(it))
        }
    }

    @Test
    fun `状态行按稳定在前易变在后的顺序`() {
        val user = build(
            selfStateLine = "【此刻状态】平静。",
            lengthLine = "【说多少】约 28 字。",
            tactLine = "【分寸】直说就行。",
            hardRules = "【硬规则】最多一个感叹号",
        )
        val order = listOf("【此刻状态】", "【说多少】", "【分寸】", "【硬规则】")
            .map { user.indexOf(it) }
        assertTrue("顺序应严格递增：$order", order == order.sorted() && order.none { it < 0 })
    }

    @Test
    fun `不传就不出现`() {
        val user = build()
        assertTrue(!user.contains("【硬规则】") && !user.contains("【分寸】"))
    }
}
