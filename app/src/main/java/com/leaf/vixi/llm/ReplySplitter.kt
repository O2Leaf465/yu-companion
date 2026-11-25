package com.leaf.vixi.llm

/**
 * 把模型返回的一段文本切成"像人连发"的几条气泡。
 *
 * 为什么要切：她要"一句一条"地说话。但为了省 token 只发一次请求，
 * 所以让模型用换行分条，回来本地切 —— 一次调用拿到 3 条消息，
 * 比调 3 次省 2/3 的钱。
 */
object ReplySplitter {

    const val MAX_CHARS = 60

    /**
     * 气泡数上限。**2026-11-23 用户要求取消 3 条的限制**：她一句一条地说话，
     * 该发几条就发几条（模型那头仍然只调一次 API，切条是本地做的，不多花钱）。
     * 用 `Int.MAX_VALUE` 表示不限。
     */
    const val MAX_BUBBLES = Int.MAX_VALUE

    private val bulletPrefix = Regex("""^\s*(?:[-*•·]|\d+[.、)]|\[\d+]|（\d+）)\s*""")
    private val markdownNoise = Regex("""(\*\*|__|`+|^#{1,6}\s*|^>\s*)""", RegexOption.MULTILINE)
    private val numberLeak = Regex("""(好感|亲密度|情绪值|熟度)\s*[:：]?\s*\d+(\.\d+)?""")
    private val endPunct = charArrayOf('。', '！', '？', '…', '，', '、', '；', '.', '!', '?', ',', '~')

    fun split(
        raw: String,
        maxBubbles: Int = MAX_BUBBLES,
        maxChars: Int = MAX_CHARS,
    ): List<String> {
        val cleaned = raw
            .replace(markdownNoise, "")
            .replace(numberLeak, "")
            .replace("\r\n", "\n")
            .replace('｜', '\n')          // 有的模型爱用全角竖线代替换行
            .replace("||", "\n")

        val lines = cleaned.split('\n')
            .map { bulletPrefix.replace(it, "").trim() }
            .map { it.trim('"', '\'', '「', '」', '“', '”', ' ', '\t') }
            .filter { it.isNotEmpty() }
            .filter { line -> line.any { it.isLetterOrDigit() || it in CJK_PUNCT } }
            .filterNot { it.all { c -> c in CJK_PUNCT || c == '.' } }

        if (lines.isEmpty()) return listOf("……")

        // 长句按标点拆开，绝不截断 —— 截断会丢她真正说的话
        val chunks = lines.flatMap { chunk(it, maxChars) }.filter { it.isNotBlank() }
        if (chunks.isEmpty()) return listOf("……")

        // 不限条数时直接全发；设了上限才把多余的并进最后一条（绝不丢她说的话）
        if (maxBubbles >= chunks.size) return chunks
        val head = chunks.take(maxBubbles - 1).toMutableList()
        val rest = chunks.drop(maxBubbles - 1)
        if (rest.isNotEmpty()) {
            // 超出的并进最后一条，而不是丢掉她说的话
            head += rest.joinToString("")
        }

        return head.ifEmpty { listOf("……") }
    }

    /** 在 maxChars 附近找最后一个标点断开；找不到就硬切 */
    private fun chunk(text: String, max: Int): List<String> {
        if (text.length <= max) return listOf(text.trim())
        val out = mutableListOf<String>()
        var rest = text.trim()
        while (rest.length > max) {
            val window = rest.take(max)
            val idx = window.indexOfLast { it in endPunct }
            val cut = if (idx >= max / 2) idx + 1 else max
            out += rest.take(cut).trim()
            rest = rest.drop(cut).trim()
        }
        if (rest.isNotEmpty()) out += rest
        return out
    }

    /** 演出节奏：短句快点、长句慢点，再加一点抖动，别像定时器 */
    fun typingDelayMillis(text: String): Long =
        600L + text.length * 90L + (200..900).random()

    fun betweenBubblesMillis(): Long = (700L..1800L).random()

    private val CJK_PUNCT = charArrayOf(
        '。', '，', '！', '？', '、', '：', '；', '…', '—', '～', '「', '」', '“', '”', '（', '）'
    )
}
