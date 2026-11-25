package com.leaf.vixi.proactive

import com.leaf.vixi.data.MsgKind
import com.leaf.vixi.data.StoredMessage
import com.leaf.vixi.data.VixiState
import com.leaf.vixi.llm.LlmPrompt
import com.leaf.vixi.state.Explain
import com.leaf.vixi.state.Proactive

/**
 * 「她主动开口」那一次请求的 prompt。
 *
 * 为什么不复用 `PromptBuilder`：那个类的 system 段是给**前台一轮对话**拼的
 * （状态行、需要、硬规则、检索片段、群成员…），它还要求一个 `systemProvider`，
 * 而那个 lambda 在 `ChatViewModel` 里是私有的 —— 后台为了拿到它去改 ChatViewModel
 * 就违反"不要新增对 ChatViewModel 的依赖"。所以这里只搭一个**很小**的请求：
 * 人设 + 最近的几句话 + "现在没人跟你说话，你自己开口"。
 *
 * 省钱的角度也对：一次主动开口输出十几二十个字，system 没必要背整块状态。
 */
internal object ProactivePrompt {

    /** 输出上限：主动开口就该短，给多了模型会写小作文 */
    const val MAX_TOKENS = 120

    /** 带多少条最近记录当上下文 */
    const val HISTORY_LINES = 12

    /** 每条记录截断 + 总长度上限：后台请求不该因为一段长对话变成大 prompt */
    private const val PER_LINE_CHARS = 80
    private const val HISTORY_CHARS = 1500
    private const val PERSONA_CHARS = 2500

    fun build(
        characterName: String,
        persona: String,
        recent: List<StoredMessage>,
        state: VixiState,
        now: Long,
    ): LlmPrompt {
        val hour = Proactive.hourOf(now)
        val system = buildSystem(characterName, persona)
        val user = buildUser(hour = hour, recent = recent, state = state, now = now)
        return LlmPrompt(system = system, user = user, maxTokens = MAX_TOKENS)
    }

    private fun buildSystem(characterName: String, persona: String): String = buildString {
        val body = persona.trim().take(PERSONA_CHARS)
        // 人设里通常已经写过名字；没写才补一句，避免同一个名字在开头重复两遍
        if (characterName.isNotBlank() && !body.contains(characterName)) {
            appendLine("你是「$characterName」。")
            appendLine()
        }
        appendLine(body)
        appendLine()
        appendLine("【这一轮是你在主动开口】")
        appendLine("他刚才没有跟你说话，是你自己想找他说点什么。")
        appendLine("只说一句话（最多两句短的），像平常发消息那样。")
        appendLine("不要解释、不要用括号写动作、不要问「在吗」「人呢」、不要把「主动」「想你」说破。")
        append("只输出你要说的那句话本身。")
    }

    private fun buildUser(hour: Int, recent: List<StoredMessage>, state: VixiState, now: Long): String =
        buildString {
            appendLine("【最近的对话】")
            val history = historyText(recent)
            appendLine(history.ifBlank { "（还没聊过）" })
            appendLine()
            appendLine("【现在】${phaseOf(hour)}（${hour.toString().padStart(2, '0')} 点），安静。")
            val miss = missLine(state, now)
            if (miss.isNotBlank()) {
                appendLine(miss)
                appendLine()
            }
            append("【这次不是回复他】没有人刚说话，你想开口。说一句就好。")
        }

    /**
     * 借用 `Explain` 里现成的那句想念提示。
     *
     * 传的是**副本**：`Explain.missingLine` 按 `state.missing` 判断，
     * 而此刻该看的是"按缺席时长推算出来的想念"（见 `Proactive.effectiveMissing`），
     * 所以把推算值塞进副本再问它 —— 不动真状态，也不在第二个地方写一遍提示词。
     */
    private fun missLine(state: VixiState, now: Long): String {
        if (!state.enabled) return ""
        val projected = state.copy(missing = Proactive.effectiveMissing(state, now))
        return runCatching { Explain.missingLine(projected) }.getOrDefault("")
    }

    private fun phaseOf(hour: Int): String = when (hour) {
        in 5..8 -> "清早"
        in 9..11 -> "上午"
        in 12..13 -> "中午"
        in 14..17 -> "下午"
        in 18..22 -> "晚上"
        else -> "深夜"
    }

    /** 最近几条，按"从旧到新"拼；超长从最旧的开始丢 */
    private fun historyText(recent: List<StoredMessage>): String {
        val lines = recent.takeLast(HISTORY_LINES).mapNotNull(::oneLine)
        val kept = ArrayDeque<String>()
        var budget = HISTORY_CHARS
        for (line in lines.asReversed()) {
            if (line.length > budget) break
            kept.addFirst(line)
            budget -= line.length
        }
        return kept.joinToString("\n")
    }

    private fun oneLine(m: StoredMessage): String? {
        val who = if (m.fromUser) "他" else "你"
        val body = when {
            // 动作事件（"摸了摸她的头"）也进上下文，但要和说出来的话区分开
            m.text.isNotBlank() && m.kind == MsgKind.ACTION -> "（${m.text}）"
            m.text.isNotBlank() -> m.text
            m.images.isNotEmpty() -> "（发了张图）"
            else -> return null
        }
        return "$who：${body.replace("\n", " ").take(PER_LINE_CHARS)}"
    }
}
