package com.leaf.vixi.data

import android.content.Context
import java.io.File

/**
 * **user 段文案表**（2026-11-26 用户要求：这些标签内容也要能改）。
 *
 * 和另外两张表的分工，别混：
 *  · `emotion_map_<id>.txt` —— 「情绪 → 动作」，决定**语气**（ReactionTable）
 *  · `injection_<id>.txt` —— 【想念】【想要什么】【说多少】的**内容**（InjectionTable）
 *  · `prompt_text.md`（本文件）—— 拼进 user 段的所有**固定标签与说明句**
 *    （【已读】【新消息】【可以引用】【事实字段】…），决定**提示词长什么样**
 *
 * 一行一个 `键=文案`；`{name}` 这种占位符由代码填（见 [fill]）。
 * 没写的键用内置默认 —— **不建这个文件时，发出去的提示词和改造前逐字一致**。
 */
class PromptText(context: Context) {

    private val file = File(context.filesDir, "prompt_text.md")

    fun raw(): String = runCatching {
        file.takeIf { it.exists() }?.readText(Charsets.UTF_8)?.takeIf { it.isNotBlank() }
    }.getOrNull() ?: TEMPLATE

    fun save(text: String): Boolean = runCatching {
        file.parentFile?.mkdirs()
        file.writeText(text, Charsets.UTF_8)
        bind()
    }.isSuccess

    fun reset(): Boolean {
        val deleted = runCatching { file.takeIf { it.exists() }?.delete() ?: false }.getOrDefault(false)
        bind()
        return deleted
    }

    /** 启动/保存后调一次：把覆盖项装进全局，纯函数那边才读得到 */
    fun bind() {
        current = parse(raw())
    }

    companion object {

        /** 内置默认 —— 和抽取之前的字面量**逐字一致** */
        val DEFAULTS: Map<String, String> = linkedMapOf(
            "seg.members" to "【群成员】",
            "seg.read" to "【已读】",
            "seg.empty" to "（还没有聊过）",
            "seg.new" to "【新消息】",
            "seg.recall" to "【你想起的旧事】",
            "seg.recallHint" to "（更早的原文片段，可能不完整；跟他最新的说法冲突时以他为准。就当是你自己想起来的，别说「我查了记录」，也别否认聊过。）",
            "seg.corrections" to "【他纠正过你（以他最新说法为准，别再搞错）】",
            "seg.group1" to "【输出格式（群聊）】每条消息以成员名开头，写成「名字：内容」，例如「{example}」。",
            "seg.group2" to "可用名字只有：{names}。即使角色设定里自称别的名字，这里也必须用这个名字。",
            "seg.group3" to "不必每个人都说话，1~3 个人开口就够；可以互相接话、回应上面那句。不要编新名字。",
            "seg.now" to "【现在】",
            "act.quote" to "【可以引用】**只在需要指回他刚说的某句具体内容时才用**（他答应过什么、他说错了一句、他问的是你之前说过的某一小句）。随便接话、打招呼、顺着聊都不要用；大多数回复里不该出现这个标签。用法：在这条回复**最前面**写 [引用:那句原文的前几个字]。",
            "act.fact" to "【事实字段】这条回复的**最后一行**固定写一个机器尾巴，三选一：① 有关于**她自己**的稳定信息 → ###FACT {\"subject\":\"她\",\"fact\":\"那句话\"}；② 有关于**他**（也就是 Leaf、正在和你聊天的人）的稳定信息 —— 他的作息、工作、喜好、忌讳、生日、家里的事 → ###FACT {\"subject\":\"他\",\"fact\":\"那句话\"}；③ 都没有 → ###FACT null。每轮都要写。subject 只能是 她 / 他（群聊可以写成员名）。fact 只写一句话，**不要照抄这里的示例文字**；临时情绪、当下发生的事都不写。",
            "act.image" to "【可以发的图】{list}（想发就单独占一行写 [图:文件名]，那一行不要再写别的话；不合适就不发）",
            "act.look" to "【你的样子】他问到了你的形象。这一轮随消息附了一张图，那就是你现在的样子：按图里的样子说（发型、穿着、神态），别编、也别说「我看不到自己」。",
            "act.dig" to "【忽然想起】这一轮你脑子里忽然冒出一件旧事（见【你想起的旧事】）。它不是你被问出来的：用「对了…」「我想起来…」这种口气顺带带出来，别反过来问他「你还记得吗」，也别硬拐回那件事。",
            "act.vent" to "【说多少】这一轮可以多说，把憋着的一次说清楚（分几条也行）。",
        )

        val TEMPLATE: String = buildString {
            appendLine("# user 段文案（全局一份）。一行一个 键=文案，改哪行写哪行，删掉就回默认。")
            appendLine("# {name} 这类花括号是占位符，由代码填，别删。")
            appendLine("# 想整行不发？把等号右边清空 —— 空值的行会被丢掉。")
            appendLine()
            DEFAULTS.forEach { (k, v) -> appendLine("$k=$v") }
        }

        private var current: Map<String, String> = emptyMap()

        /** 取文案：覆盖 → 默认 → 空串（空串 = 这一行不发） */
        fun text(key: String): String = current[key] ?: DEFAULTS[key] ?: ""

        /** 填占位符（`{list}` 这种），没给的键原样留着 */
        fun fill(key: String, vararg args: Pair<String, String>): String {
            var s = text(key)
            args.forEach { (k, v) -> s = s.replace("{$k}", v) }
            return s
        }

        private fun parse(text: String): Map<String, String> {
            val out = HashMap<String, String>()
            text.lineSequence().forEach { rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEach
                val i = line.indexOf('=')
                if (i <= 0) return@forEach
                val key = line.substring(0, i).trim()
                // 值**允许为空**（= 显式关掉这一行），所以这里不像 InjectionTable 那样丢掉空值
                if (key.isNotEmpty()) out[key] = line.substring(i + 1).trim()
            }
            return out
        }
    }
}
