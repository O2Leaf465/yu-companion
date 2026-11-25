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
     * 气泡数上限。
     *
     * 2026-11-23 曾按用户要求取消上限（`Int.MAX_VALUE`），但 2026-11-25 真机上
     * 出现"一条回复切成十几条、演出一分半"的体感问题 —— 每泡都要先"打字"再停一下。
     * 现在**回到 5 条**，多的并进最后一条（绝不丢她说的话），单泡演出也同时压短了。
     */
    const val MAX_BUBBLES = 5

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

    /**
     * 演出节奏：短句快点、长句慢点，再加一点抖动，别像定时器。
     *
     * 2026-11-25 压短过一次：原来 `600 + 90×字 + 200~900`，一条 30 字的要 3.3~4.2 秒，
     * 五条叠起来 20 秒的"挤牙膏"。现在 55ms/字、封顶 2.2 秒。
     */
    fun typingDelayMillis(text: String): Long =
        (350L + text.length * 55L + (150..450).random()).coerceAtMost(2200L)

    fun betweenBubblesMillis(): Long = (400L..1100L).random()

    private val CJK_PUNCT = charArrayOf(
        '。', '，', '！', '？', '、', '：', '；', '…', '—', '～', '「', '」', '“', '”', '（', '）'
    )
}
