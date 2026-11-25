package com.leaf.vixi.llm

/**
 * 群聊里的一句话 + 说话人名字（null 只在整体兜底时出现，调用方负责补名字）。
 *
 * `quote` / `image` 是她这一句里夹带的动作（2026-11-25 加：她也能引用、也能发图）：
 * 模型按约定写 `[引用:原文]` / `[图:文件名]`，[HerActParser] 把它们剥出来。
 * 两个都为空时行为和以前完全一样。
 */
data class Spoken(
    val speaker: String?,
    val text: String,
    /** 引用的那句话（原文快照） */
    val quote: String? = null,
    /** 要发的那张图（`files/stickers/` 下的文件名） */
    val image: String? = null,
)

/**
 * 群聊回复解析：把模型的一次返回切成「谁说的一句话」。
 *
 * 为什么要在本地解析：群聊仍然是**每轮 1 次调用**（最硬的成本约束），
 * 一次调用里让模型用「名字：内容」分条输出，回来本地切 —— 切不动就退化成一个人说。
 *
 * 三层兜底（设计文档 §3）：
 *   1. 行首名字能对上成员表（含「小鲸鱼」这种带称呼、带【】的写法）→ 归这个成员
 *   2. 对不上 → 归**上一个**说话的人（模型偶尔只给第一行加前缀）
 *   3. 一行都没对上 → 全归会话创建者（fallback）
 *
 * 名字一律用**原名**回传：气泡选头像、prompt 里的说话人前缀都靠它做键。
 */
object GroupReplyParser {

    /** 模型爱加的列表前缀（"- 鱼：…" / "1. 鱼：…"） */
    private val bullet = Regex("""^\s*(?:[-*•·]|\d+[.、)]|\[\d+]|（\d+）)\s*""")

    /** 名字两边的装饰：**鱼** / 【鱼】 / 「鱼」 */
    private val decor = Regex("""^[\s*_`#>「」『』\[\]【】()（）：:]+|[\s*_`#>「」『』\[\]【】]+$""")

    /** 【鱼】内容  这种没有冒号的写法 */
    private val bracketHead = Regex("""^[【\[]\s*([^】\]]{1,12})\s*[】\]]\s*""")

    /** 一句话最多出几条气泡（一个人连续说两句就够，再多是模型没按格式来） */
    private const val PER_SPEAKER_BUBBLES = 2

    /** 整轮气泡上限：群里 5 条已经很长了 */
    private const val MAX_BUBBLES = 5

    fun parse(raw: String, members: List<String>, fallback: String?): List<Spoken> {
        val lines = raw
            .replace("\r\n", "\n")
            .replace('｜', '\n')
            .replace("||", "\n")
            .split('\n')

        data class Seg(val speaker: String, val text: StringBuilder)

        val segs = mutableListOf<Seg>()
        val orphan = StringBuilder()

        lines.forEach { rawLine ->
            val line = bullet.replace(rawLine, "").trim()
            if (line.isEmpty()) return@forEach

            val head = splitName(line, members)
            when {
                head.name != null && head.body.isNotBlank() ->
                    segs += Seg(head.name, StringBuilder(head.body))
                // 认不出来 → 上一段接着说（模型只给第一行加前缀、或者用了人设里的自称，全靠这条）。
                // 名字前缀已经剥掉了，别让「海苔：」这种东西混进气泡正文。
                head.body.isNotBlank() && segs.isNotEmpty() ->
                    segs.last().text.append('\n').append(head.body)
                head.body.isNotBlank() -> orphan.append('\n').append(head.body)
            }
        }

        // 一开头那几句还没认出说话人：没有"上一个人"，按设计归会话创建者。
        // 这一步不能省 —— 模型经常把前两句给同一个人，丢了就等于她没说过那些话。
        val leading = orphan.toString().trim()
        if (leading.isNotEmpty()) {
            val who = fallback ?: members.firstOrNull()
            if (who != null) segs.add(0, Seg(who, StringBuilder(leading)))
        }

        // 一行都没认出来：整段归兜底人，绝不让她的回复凭空消失
        if (segs.isEmpty()) {
            val text = raw.trim()
            val chunks = ReplySplitter.split(text, maxBubbles = PER_SPEAKER_BUBBLES)
            return chunks.map { Spoken(fallback ?: members.firstOrNull(), it) }
        }

        val out = mutableListOf<Spoken>()
        segs.forEach { seg ->
            // 每段复用单聊的清洗 + 长句切分（去 Markdown、去掉列表符号、按标点断开）
            ReplySplitter.split(seg.text.toString(), maxBubbles = PER_SPEAKER_BUBBLES)
                .forEach { out += Spoken(seg.speaker, it) }
        }

        if (out.size <= MAX_BUBBLES) return out

        // 超了也不能丢内容：并回同一个人最后那条，没有再并到最后一条
        val head = out.take(MAX_BUBBLES).toMutableList()
        out.drop(MAX_BUBBLES).forEach { extra ->
            val same = head.indexOfLast { it.speaker == extra.speaker }
            val at = if (same >= 0) same else head.lastIndex
            head[at] = head[at].copy(text = head[at].text + extra.text)
        }
        return head
    }

    /** 切出来的「说话人 + 内容」；name 为空表示这行看不出是谁说的 */
    private data class Head(val name: String?, val body: String)

    private fun splitName(line: String, members: List<String>): Head {
        bracketHead.find(line)?.let { m ->
            val who = matchMember(m.groupValues[1], members)
            if (who != null) {
                return Head(who, line.substring(m.range.last + 1).trim().ifBlank { line })
            }
            // 括号里不是成员名（例如模型输出「【图片】」）→ 整行当内容，别把正文吃掉
            return Head(null, line)
        }

        val sep = line.indexOfFirst { it == '：' || it == ':' }
        if (sep <= 0) return Head(null, line)
        val body = line.substring(sep + 1).trim()
        val who = matchMember(line.take(sep), members)
        if (who != null) return Head(who, body.ifBlank { line })
        // 认不出的前缀：只有**长得像名字**时才剥掉（海苔: 在在在 → 在在在）。
        // 否则整行都是内容 —— 「那就19:30吧」被当成名字前缀会截成「30吧」。
        return if (looksLikeName(line.take(sep))) Head(null, body.ifBlank { line }) else Head(null, line)
    }

    /** 像不像一个"名字"前缀：短、无数字、无空白（时间/价格/正文里出现冒号时都不满足） */
    private fun looksLikeName(candidate: String): Boolean {
        val name = decor.replace(candidate, "").trim()
        if (name.isEmpty() || name.length > 8) return false
        return name.none { it.isDigit() || it.isWhitespace() }
    }

    /**
     * 把候选名字对到成员表上。
     * 先精确，再按"互相包含"（模型会写「小鲸鱼」「鱼（小鲸鱼）」这种称呼），长的优先。
     */
    private fun matchMember(candidate: String, members: List<String>): String? {
        val name = decor.replace(candidate, "").trim()
        if (name.isEmpty() || name.length > 12) return null
        members.firstOrNull { it == name }?.let { return it }
        return members
            .sortedByDescending { it.length }
            .firstOrNull { name.contains(it) || it.contains(name) }
    }
}
