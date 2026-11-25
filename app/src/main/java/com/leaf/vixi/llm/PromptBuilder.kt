package com.leaf.vixi.llm

import com.leaf.vixi.data.MsgKind

/** 一条历史消息。self=true 表示是"她"（非用户）说的；speaker 是具体角色名（群聊用） */
data class Turn(
    val self: Boolean,
    val text: String,
    val kind: MsgKind = MsgKind.TEXT,
    val quote: String? = null,
    val imageCount: Int = 0,
    /** 与气泡同一时间戳，用来精确删除（按文本匹配会删错重复消息） */
    val ts: Long = 0L,
    /** 群聊里的说话人名字；单聊留空，渲染时用 selfName */
    val speaker: String? = null,
) {
    /** 进 prompt 时怎么显示。历史里的图片只能是占位，不可能每次把图都重新发一遍。 */
    val rendered: String
        get() = buildString {
            quote?.let { append("（引用「").append(it.take(60)).append("」）") }
            append(
                when (kind) {
                    MsgKind.IMAGE -> when {
                        imageCount > 1 && text.isNotBlank() -> "[${imageCount}张图片] $text"
                        imageCount > 1 -> "[${imageCount}张图片]"
                        text.isNotBlank() -> "[图片] $text"
                        else -> "[图片]"
                    }
                    MsgKind.ACTION -> com.leaf.vixi.ActionText.forPrompt(text)
                    else -> text
                }
            )
        }
}

/** 这一轮新来的消息 */
data class Incoming(
    val text: String,
    val kind: MsgKind = MsgKind.TEXT,
    /** 图片文件名列表（files/images 下）；一次可以带多张，合并成一次调用 */
    val images: List<String> = emptyList(),
    /** 引用的原文 */
    val quote: String? = null,
    /** 与气泡同一时间戳：用于"只写进历史一次"和精确删除 */
    val ts: Long = 0L,
) {
    val rendered: String
        get() = buildString {
            quote?.let { append("（引用「").append(it.take(60)).append("」）") }
            when {
                kind == MsgKind.IMAGE && images.size > 1 && text.isNotBlank() ->
                    append("[${images.size}张图片] ").append(text)
                kind == MsgKind.IMAGE && images.size > 1 -> append("[${images.size}张图片]")
                kind == MsgKind.IMAGE && text.isNotBlank() -> append("[图片] ").append(text)
                kind == MsgKind.IMAGE -> append("[图片]")
                kind == MsgKind.ACTION -> append(com.leaf.vixi.ActionText.forPrompt(text))
                else -> append(text)
            }
        }
}

data class LlmPrompt(
    val system: String,
    val user: String,
    val maxTokens: Int = 180,
    /** 本轮要一起发过去的图片（base64，不含 data: 前缀）。历史图片不会重复发送。 */
    val images: List<String> = emptyList(),
) {
    /** 粗略 token 估算：中文约 1.5 字/token，用于调试页显示，不参与计费判断 */
    val approxInputTokens: Int get() = ((system.length + user.length) * 0.67).toInt()
}

/**
 * 全 App 最影响成本的一个类。
 *
 * 结构固定为：
 *   [system] 人设 + 输出格式约定                        ← 稳定，缓存命中
 *   [user]   历史 → 新消息 → 摘要 → 纠正 → 现在        ← 从前到后越来越易变
 *
 * 两条铁律：
 *   1. 易变的块绝不允许出现在 system 里（写死时间戳 = 缓存永远不命中，成本 ×50）
 *   2. user 段内部的顺序也必须"稳定在前、易变在后"：某段一变，它后面所有字节的
 *      偏移都会跟着变，缓存前缀从那里断掉。摘要每 10 轮重写一次，所以它必须殿后。
 */
class PromptBuilder(
    private val systemProvider: () -> String,
    private val historyLimit: Int = HISTORY_LIMIT,
) {

    fun build(
        history: List<Turn>,
        incoming: List<Incoming>,
        momentLine: String,
        corrections: List<String> = emptyList(),
        images: List<String> = emptyList(),
        /** 本地检索到的旧原文（每条带说话人前缀），为空则不注入 */
        retrieved: List<String> = emptyList(),
        /** 单聊时"她"的名字；群聊时只作兜底 */
        selfName: String = "鱼",
        /** 群聊成员名（非空即群聊：注入成员表 + 名字前缀格式） */
        members: List<String> = emptyList(),
        /** 本会话带多少条历史：群聊人多话多，降到 12 条 */
        historyLimit: Int = HISTORY_LIMIT,
        /** 此刻状态的一行话（数值翻译来的）；空 = 这个会话没开状态底座 */
        selfStateLine: String = "",
        /** 这一轮说多少（P5-1：由状态算出的字数预算）；空 = 不限 */
        lengthLine: String = "",
        /** 主动性与分寸（P5-2）；空 = 不限 */
        initiativeLine: String = "",
        /** 分寸（P5-3：敢不敢示弱/撒娇/开玩笑）；空 = 不限 */
        tactLine: String = "",
        /**
         * 需要（N2/N3）：【想要什么】+ 爆发那一轮的【可以主动】。
         *
         * 只给"目的 + 怎么说成功率更高"，**不给策略** —— 策略让模型自己推。
         * 位置在【分寸】之后、【硬规则】之前：目的要受分寸约束，硬规则最后兜底。
         */
        needLines: String = "",
        missingLines: String = "",
        moveLines: String = "",
        silenceLines: String = "",
        correctionLines: String = "",
        /**
         * 长期人物事实（【关于她的事实…】）。**放在 user 段最前面**：
         * 它变化很慢、阅读顺序上属于"背景设定"，而且 system 是缓存前缀不能动。
         * 空串 = 这个角色还没有事实，行为和以前一致。
         */
        factLines: String = "",
        /**
         * 她可以做的两个动作（2026-11-25）：引用他刚说的一句 / 发一张图。
         *
         * 只在她确实做得到的时候才非空（贴纸目录里有图才给【可以发的图】）。
         * 空串 = 这个能力不存在，生产行为和加它之前逐字一致。
         */
        actLines: String = "",
        /** 三条硬规则（他模型层那篇：情绪词≤1 / 感叹号≤1 / 连续附和≤2 轮）；空 = 不限 */
        hardRules: String = "",
    ): LlmPrompt {
        val system = systemProvider()
        val isGroup = members.isNotEmpty()

        // —— 稳定性自检（坑：一个时间戳插在前面，成本立刻 ×50 且不报错）——
        // 刻意不抛异常：早期用 require，人设里只要恰好出现当前时间串就会每发一条崩一次。
        // 现在的策略是"发现就带着问题继续跑"，最多这一轮缓存失效，不给用户一个死循环崩溃。
        @Suppress("UNUSED_EXPRESSION")
        momentLine.isNotBlank() && system.contains(momentLine)

        // 2026-11-23 用户要求：**话题内的记录全带上**，不再固定取最近 20 条。
        // 代价是 token 随话题长度增长 —— 调试页的"下轮请求"里能看到实际字数。
        val recent = if (historyLimit >= history.size) history else history.takeLast(historyLimit)

        // ── 前缀缓存铁律（抄自 QQAgent 的实战结论）──
        // 越靠前的内容越必须**稳定**：某段一变，它后面所有字节的偏移都跟着变，
        // 缓存前缀从那里开始直接归零。所以顺序是
        //     已读（只追加，前缀稳定）→ 新消息 → 更早（每 10 轮才变）→ 纠正 → 现在
        // 摘要以前放在最前面，每 10 轮重写一次，等于每 10 轮把整段缓存打掉一次。
        val user = buildString {
            if (isGroup) {
                appendLine("【群成员】" + members.joinToString("、"))
            }
            // 长期事实放在最前面：它是最稳定的背景（变化极慢），
            // 也是整段里最该"先读到"的东西。放在【已读】之前不会打乱前缀稳定性。
            if (factLines.isNotBlank()) appendLine(factLines)
            appendLine("【已读】")
            if (recent.isEmpty()) {
                appendLine("（还没有聊过）")
            } else {
                recent.forEach { t ->
                    // 动作事件不是"说的话"，不加说话人前缀，避免模型以为这是用户的原话
                    if (t.kind == MsgKind.ACTION) appendLine(t.rendered)
                    // 用名字而不是"我/你"：代词在这类转录里谁都可以理解成自己，
                    // 一旦读错就会出现"把自己说过的话当成对方说的"。
                    else append((t.speaker ?: if (t.self) selfName else "Leaf") + "：")
                        .appendLine(t.rendered)
                }
            }

            appendLine("【新消息】")
            incoming.forEach { m ->
                if (m.kind == MsgKind.ACTION) appendLine(m.rendered)
                else appendLine("Leaf：" + m.rendered)
            }

            // 从这里开始是"会变的部分"，全部殿后。
            // 刻意不再注入「上一个话题」和「更早摘要」：前者一般用不上、占 token；
            // 后者是模型自己压的二手话，容易漂移。旧事一律让她自己检索原文。
            if (retrieved.isNotEmpty()) {
                appendLine("【你想起的旧事】")
                retrieved.forEach { appendLine("- $it") }
                appendLine("（更早的原文片段，可能不完整；跟他最新的说法冲突时以他为准。")
                appendLine("就当是你自己想起来的，别说「我查了记录」，也别否认聊过。）")
            }

            if (corrections.isNotEmpty()) {
                appendLine("【他纠正过你（以他最新说法为准，别再搞错）】")
                corrections.takeLast(3).forEach { appendLine("- $it") }
            }

            // 群聊：格式要求放在动态段末尾（不影响缓存前缀）
            if (isGroup) {
                val names = members.joinToString("、")
                val example = (members.firstOrNull() ?: "鱼") + "：随便"
                appendLine("【输出格式（群聊）】每条消息以成员名开头，写成「名字：内容」，例如「$example」。")
                // 真机上踩过的坑：人设里自称「海苔」，模型就回「海苔：…」，本地切不出气泡。
                // 所以这里把可用名字写死，并明确"自称不算"。
                appendLine("可用名字只有：$names。即使角色设定里自称别的名字，这里也必须用这个名字。")
                appendLine("不必每个人都说话，1~3 个人开口就够；可以互相接话、回应上面那句。不要编新名字。")
            }

            // 此刻状态：一行人话（数值翻译来的），放在最靠后的动态段里。
            // 紧跟一句"别复述"：不然模型会把她自己看到的状态念给用户听。
            if (selfStateLine.isNotBlank()) {
                appendLine(selfStateLine)
                appendLine("（这句只影响你怎么说，不要复述、不要提括号里的提示、也不要说「我现在有点…」。）")
            }
            // 易变段交给 PromptBlocks 组装：**空块丢掉、位置写死**。
            // 以前是手工 appendLine，谁加一段都可能插到硬规则后面去。
            val volatileText = com.leaf.vixi.state.PromptBlocks.assemble(
                com.leaf.vixi.state.PromptBlocks.volatile(
                    "长度" to lengthLine,
                    "主动" to initiativeLine,
                    "分寸" to tactLine,
                    "需要" to needLines,
                    // 想念挨着状态行：它改的是"怎么接"，不是"要不要开口"（那是需要）
                    "想念" to missingLines,
                    "应对" to moveLines,
                    // 动作（引用/发图）放在"抽身"之前：它是怎么说话的一部分
                    "动作" to actLines,
                    "抽身" to silenceLines,
                    "改口" to correctionLines,
                ),
            )
            if (volatileText.isNotBlank()) appendLine(volatileText)
            val lastText = com.leaf.vixi.state.PromptBlocks.assemble(
                listOf(com.leaf.vixi.state.PromptBlocks.Block(
                    com.leaf.vixi.state.PromptBlocks.LAST, "硬规则", hardRules,
                )),
            )
            if (lastText.isNotBlank()) appendLine(lastText)
            append("【现在】").appendLine(momentLine)
        }

        // 群聊一轮里最多 3 个人各说 1~2 句，180 token 容易被截在半句上，
        // 而截断的回复照样会被解析成气泡入库 —— 宁可多给一点额度
        return LlmPrompt(
            system = system,
            user = user.trimEnd(),
            images = images,
            maxTokens = if (isGroup) GROUP_MAX_TOKENS else SINGLE_MAX_TOKENS,
        )
    }

    companion object {
        /** 历史窗口：带 20 条原文，更早的靠检索顶上 */
        /** 历史条数上限。`Int.MAX_VALUE` = 话题内的全带上（2026-11-23 起） */
        const val HISTORY_LIMIT = Int.MAX_VALUE

        /** 群聊窗口：人多话密，20 条会把单聊的两三倍内容挤进来 */
        /** 群聊同上：话题内全部（原来 12 条） */
        const val GROUP_HISTORY_LIMIT = Int.MAX_VALUE

        const val SINGLE_MAX_TOKENS = 180
        const val GROUP_MAX_TOKENS = 300
    }
}
