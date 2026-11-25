package com.leaf.vixi.memory

import com.leaf.vixi.data.StoredMessage

/**
 * 本地闸门：判断这句话"像不像在问过去的事"。
 *
 * 为什么要它：如果每轮都让模型决定要不要调检索，那就要每轮都带工具定义（每次都花钱），
 * 而且 90% 的闲聊根本不需要翻旧账。先用纯字符串判断（0 成本、0 延迟）挡一道，
 * 只有放行时才带工具、才可能多一次调用。
 *
 * 这是"宁可漏、不要多"的策略：漏判的代价是她这次想不起来，多判的代价是真金白银。
 */
object RecallGate {

    /** 一看就是在问旧事：这些词本身就指向过去 */
    private val STRONG = listOf(
        "上次", "上回", "那次", "那天", "当时", "我说过", "跟你说过",
        "聊过", "提过", "答应过", "约好", "记不记得", "还记得", "什么来着", "有印象",
    )

    /**
     * 弱信号：「记得/之前/后来/昨天」在日常闲聊里出现得太频繁
     * （"我记得你不吃香菜"、"之前也是这样"），单独出现不算，必须配疑问。
     */
    private val WEAK = listOf(
        "之前", "以前", "后来", "昨天", "前天", "前几天", "上周", "上个月", "前阵子", "记得",
    )

    private val QUESTION = listOf("?", "？", "吗", "呢", "什么", "哪", "怎么", "是不是", "有没有")

    fun needsRecall(text: String): Boolean {
        val t = text.trim()
        // 阈值放到 3：「上次呢」「那天呢」这类最短的问句恰恰最该检索
        if (t.length < 3) return false
        if (STRONG.any { t.contains(it) }) return true
        val asks = QUESTION.any { t.contains(it) }
        return asks && WEAK.any { t.contains(it) }
    }

    /**
     * 把问句削成"实质内容"再拿去检索。
     *
     * 为什么必须削：整句当查询时，两字词里绝大部分是「你还/还记/记得/什么/想要/的/吗」这类虚词，
     * 它们把 keyScore 的分母撑到 20，真正的实词（「功能」）只占 1/20 → 命中的反而是
     * "措辞相似的我自己说过的话"，而不是答案。削完只剩「功能」，检索才有意义。
     *
     * 长短语先删，避免「你还记得」被拆成「记得」删剩一半。
     */
    fun extractQuery(text: String): String {
        var t = text.trim()
        // 先删多字虚词（长词优先），再删少量语气助词。
        // 注意：**不能删「能/要/想/过/是/有/会」这类单字** —— 它们是实词的组成部分
        // （「功能」的能、「会议」的会），删了就把查询毁成单个字。
        STOP_PHRASES.forEach { t = t.replace(it, " ") }
        STOP_CHARS.forEach { c -> t = t.replace(c.toString(), " ") }
        return t.replace(Regex("\\s+"), " ").trim().take(40)
    }

    /** 多字虚词：指代、疑问、回忆动词、元词组 */
    private val STOP_PHRASES = listOf(
        "你还记不记得", "你记不记得", "还记不记得", "你还记得", "你还记", "你记得", "记得",
        "我跟你说过", "我跟你说", "我说过", "跟你说过", "我们聊过", "聊过", "提过",
        "我问过你", "我问过", "我问你", "问过你", "问过", "我说", "说过", "问你",
        "对话内容", "对话", "内容", "那个话", "那个", "这个", "那些", "这些",
        "上次", "上回", "那次", "那天", "之前", "以前", "当时", "后来", "刚才",
        "什么", "怎么", "哪些", "是不是", "有没有", "想要", "告诉", "一下",
        "喜欢", "聊过吗", "说过吗",
    )

    /** 只删纯语气/代词字；能/要/想/过/是/有/会/不 一律保留 */
    private val STOP_CHARS = "那我你他她它这那些个的了呢吗吧啊呀哦么什咋就都还也很太"
}

/**
 * 原文检索：在两字词层面打分，不用向量库。
 *
 * 打分 = 关键词命中数 + 时间新近加成；取 top N，并过双阈值
 * （绝对分下限 + 达到最高分的比例），避免"都不太相关"时硬塞噪声。
 */
object Retriever {

    data class Hit(
        val text: String,
        val ts: Long,
        val fromUser: Boolean,
        val score: Double,
        /** 纯关键词命中率（不含时间加成）：噪声过滤主要靠它 */
        val keyScore: Double,
        /** 群聊里是谁说的（角色 id）：片段要带对说话人，否则"谁说的"会张冠李戴 */
        val speakerId: String? = null,
    )

    data class MultiResult(
        val hits: List<Hit>,
        /** 实际跑过的每一轮查询词，调试页要显示出来 */
        val variants: List<String>,
        /** 词不够时补进来的稀有词 */
        val augmented: List<String> = emptyList(),
    )

    /**
     * 多轮检索 + 合并去重（用户要的策略）。
     *
     * 为什么要多轮：一句关键词组合经常"太严"——只要有一个词没命中就被落到阈值下面；
     * 也可能"太松"——把无关的带进来。逐级放宽（去掉动词、再去掉一个词）能覆盖这两种情况，
     * 每一轮各自取"一段对话"，最后合并。
     *
     * 去重规则（用户明确要求"有子集就丢掉少的那个，不反悔"）：
     * 后面某一轮的结果如果被前面保留的结果**完全包含**（消息集合是子集），直接丢弃；
     * 反过来，如果前面保留的其实是后一轮的子集，也把它丢掉换成更全的那段。
     *
     * 全部在本地完成，不增加任何 API 调用。
     */
    fun recallMulti(
        terms: List<String>,
        corpus: List<StoredMessage>,
        sourceText: String = "",
        maxChars: Int = 700,
    ): MultiResult {
        var words = terms.map { it.trim() }.filter { it.length >= 2 }.distinct()
        if (corpus.isEmpty()) return MultiResult(emptyList(), emptyList())

        // 模型经常把关键词连成一串（"多少次笨蛋"）：那样"去动词/去一个词"都做不了，
        // 等于只查了一次。这里先按"语料里真实存在的片段"把它拆开（多少次 / 笨蛋）。
        if (words.size == 1 && words[0].length >= 4) {
            val split = splitByCorpus(words[0], corpus)
            if (split.size >= 2) words = split
        }

        // 还是只有一个词时，从原句里补几个"语料里出现得最少、所以最有信息量"的词
        // 只要求"至少两个字"，**不按词频过滤**（用户明确要求取消词频限制）。
        // 动词**完全不作为检索词**：动词本身没有区分度，拿它查会把一堆不相关的对话捞回来。
        // 名词照常参与 —— 单个名词也要单独查一轮，那往往正是最精确的一次。
        words = words.filter { it.length >= 2 }.filterNot { VERBS.contains(it) }

        // 补词只在"词太少"时做，最多补 1 个；补进来的也不许是动词
        var augmented = emptyList<String>()
        if (words.size < 2 && sourceText.isNotBlank()) {
            augmented = rarestGrams(sourceText, corpus, 1).filterNot { it in words || VERBS.contains(it) }
            words = (words + augmented).distinct()
        }
        if (words.isEmpty()) return MultiResult(emptyList(), emptyList())

        // ① 全部词 ② 逐次去掉一个词 ③ 每个词单独查一轮
        // ③ 是必须的：只有当词表正好两个时，"去掉一个词"才会产生单词查询；
        //    三个词以上时去掉一个仍然是两词组合，单词这一轮就永远没有 —— 而单个**名词**
        //    往往能捞到完全不同的内容（"笨蛋"和"多少次"说的根本不是一件事）。
        //    （动词不会再出现在这里，上面已经整表剔掉了）
        val variants = mutableListOf<List<String>>()
        fun addVariant(v: List<String>) {
            if (v.isEmpty()) return
            val key = v.joinToString(" ")
            if (variants.none { it.joinToString(" ") == key }) variants += v
        }
        addVariant(words)
        if (words.size > 1) {
            words.indices.forEach { i -> addVariant(words.filterIndexed { j, _ -> j != i }) }
        }
        if (words.size > 1) {
            words.forEach { w -> addVariant(listOf(w)) }
        }
        if (variants.isEmpty()) return MultiResult(emptyList(), emptyList())

        data class Seg(val ts: Set<Long>, val hits: List<Hit>)

        // 关键：每一轮**只找前面没覆盖过的内容**。
        // 否则时间加成会让每一轮都选中同一个最近的种子，三段结果互相包含，
        // 被去重规则砍到只剩一段 —— 表现就是"轮次对了，但只回来一次的结果"。
        // 轮数也要封顶：词一多，变体能有 7 轮，合并起来直接把上下文撑爆。
        val used = HashSet<Long>()
        val segs = variants.take(MAX_ROUNDS).mapNotNull { v ->
            val hits = recallOnce(v.joinToString(" "), corpus, excludeTs = used)
            if (hits.isEmpty()) null else Seg(hits.map { it.ts }.toSet(), hits).also {
                used += it.ts
            }
        }
        if (segs.isEmpty()) {
            return MultiResult(emptyList(), variants.map { it.joinToString(" ") }, augmented)
        }

        // 子集去重：A ⊆ B 就丢 A；如果已保留的是新段的子集，则用新段替换它
        val kept = mutableListOf<Seg>()
        for (s in segs) {
            if (kept.any { k -> s.ts.all { it in k.ts } }) continue
            kept.removeAll { k -> k.ts.isNotEmpty() && k.ts.all { it in s.ts } }
            kept += s
        }

        val out = mutableListOf<Hit>()
        val seen = HashSet<Long>()
        var chars = 0
        for (s in kept) {
            for (h in s.hits) {
                if (!seen.add(h.ts)) continue
                if (chars + h.text.length > maxChars) break
                out += h
                chars += h.text.length
            }
            if (chars >= maxChars) break
        }
        return MultiResult(out, variants.map { it.joinToString(" ") }, augmented)
    }

    /**
     * 把"多少次笨蛋"这种连串按语料里真实出现的片段切开。
     *
     * 判据用**文档频率**而不是"在语料里出现过"：连串本身（"多少次笨"）可能正好出现在
     * 用户刚打的那一句话里，用 contains 就会把它当成一个词，切分反而失效。
     * 要求至少 2 条消息里出现过，才能把"用户自己那句话"排除掉。
     */
    private fun splitByCorpus(word: String, corpus: List<StoredMessage>): List<String> {
        fun df(cand: String): Int = corpus.count { it.text.contains(cand) }
        val out = mutableListOf<String>()
        var i = 0
        while (i < word.length - 1) {
            var piece = ""
            for (len in intArrayOf(4, 3, 2)) {
                if (i + len <= word.length) {
                    val cand = word.substring(i, i + len)
                    if (df(cand) >= 2) {
                        piece = cand
                        break
                    }
                }
            }
            if (piece.isNotEmpty()) {
                out += piece
                i += piece.length
            } else {
                i += 1
            }
        }
        return out.distinct().filter { it.length >= 2 }
    }

    /**
     * 从一句话里挑出"语料中最稀有"的几个两字词：稀有 ≈ 信息量大。
     * 用来在模型只给了一个关键词时，把句子拆成可比较的几轮。
     */
    private fun rarestGrams(text: String, corpus: List<StoredMessage>, n: Int): List<String> {
        val q = bigrams(text)
        if (q.isEmpty()) return emptyList()
        val df = HashMap<String, Int>()
        q.forEach { g -> df[g] = 0 }
        corpus.forEach { m ->
            val g = bigrams(m.text)
            q.forEach { if (it in g) df[it] = (df[it] ?: 0) + 1 }
        }
        return df.entries
            .filter { it.value > 0 }
            .sortedBy { it.value }
            .take(n)
            .map { it.key }
    }

    /** 动词表：这些词完全不参与检索（含单字过滤后剩下的双字动词） */
    private const val MAX_ROUNDS = 4

    private val VERBS = listOf(
        "想要", "需要", "觉得", "认为", "希望", "打算", "决定", "要求", "可以", "能够",
        "应该", "记得", "说过", "问过", "聊过", "告诉", "喜欢", "讨厌", "担心", "害怕",
        "知道", "明白", "同意", "拒绝", "帮忙", "开始", "结束", "继续", "准备",
        "选择", "挑选", "商量", "讨论", "睡觉", "吃饭", "喝水", "学习", "上课", "下课",
        "回家", "出门", "回来", "出去", "进来", "打开", "关闭", "购买", "安排", "处理",
        "发现", "感觉", "看起来", "认为", "以为", "相信", "怀疑", "记着", "忘了", "想起",
    )

    /**
     * 单次检索：给定查询串，返回"命中点前后 60 秒内的整段对话"。
     *
     * @param excludeTs 前面轮次已经覆盖过的消息：本轮不再拿它们当种子，
     *                  保证多轮搜到的是**新内容**而不是同一段反复命中
     */
    fun recallOnce(
        query: String,
        corpus: List<StoredMessage>,
        limit: Int = 5,
        excludeTs: Set<Long> = emptySet(),
    ): List<Hit> {
        val q = query.trim()
        if (q.length < 2 || corpus.isEmpty()) return emptyList()

        val pool = if (excludeTs.isEmpty()) corpus else corpus.filter { it.ts !in excludeTs }
        if (pool.isEmpty()) return emptyList()

        val qGrams = bigrams(q)
        if (qGrams.isEmpty()) return emptyList()

        val scored = pool.mapNotNull { msg ->
            val text = msg.text
            if (text.isBlank()) return@mapNotNull null
            val grams = bigrams(text)
            val overlap = qGrams.count { it in grams }
            if (overlap == 0) return@mapNotNull null
            val keyScore = overlap.toDouble() / qGrams.size
            // 时间新近：30 天线性衰减
            val ageDays = ((System.currentTimeMillis() - msg.ts) / 86_400_000.0).coerceAtLeast(0.0)
            val recency = (1.0 - ageDays / 30.0).coerceIn(0.0, 1.0)
            Hit(
                text = text, ts = msg.ts, fromUser = msg.fromUser,
                score = keyScore + recency * 0.3,
                keyScore = keyScore,
                speakerId = msg.speakerId,
            )
        }.sortedByDescending { it.score }

        if (scored.isEmpty()) return emptyList()

        // ── 为什么不能"凑满 3 条" ──
        // 旧规则是 `score < top*0.5 就停`，但相邻几条消息的时间加成几乎相同、关键词命中率也挤在
        // 一个区间里，分数很容易都落在最高分的一半以上 —— 那条线形同虚设，结果永远取满 3 条，
        // 后两条基本是噪声：既花了 token，又可能把她带偏。
        //
        // 新规则：① 关键词命中率有**绝对下限**；② 第 2 名要比第 1 名更接近（0.75），
        // 第 3 名要比第 2 名更接近（0.85）；③ 同一个人 30 秒内的相邻消息只取一条。
        val picked = mutableListOf<Hit>()
        val topKey = scored.first().keyScore
        for (h in scored) {
            // 列表按 score（含时间加成）降序；门槛一律看 keyScore。
            // 全部用 continue —— 用 break 会连带丢掉后面更好的结果。
            if (h.keyScore < 0.2) continue
            if (picked.isNotEmpty()) {
                // 断档也按 keyScore 比：相关不相关是关键词的事，时间只影响排序。
                val need = if (picked.size == 1) topKey * 0.75 else picked.last().keyScore * 0.85
                if (h.keyScore < need) continue
                // 两分钟内的算同一处，别重复取（真正的内容由下面"扩成一段"来补）
                if (picked.any { kotlin.math.abs(it.ts - h.ts) < 120_000 }) continue
            }
            picked += h
            // 种子最多 3 个：再多的边际收益很低，而每段还要连带上下文
            if (picked.size >= minOf(limit, 3)) break
        }

        // 把每个命中"扩成一小段对话"。
        // 窗口从 ±60 秒收到 ±20 秒、每段最多 3 条、只扩前 2 个种子：
        // 之前"3 种子 × ±60 秒"一轮就能带回 40 多条，多轮合并直接把上下文撑爆。
        val ordered = corpus.sortedBy { it.ts }
        val out = mutableListOf<Hit>()
        val added = HashSet<Long>()
        var chars = 0
        val blockChars = 320    // 单轮上限：多轮合并时总量另有上限
        val window = 20_000L
        val maxPerBlock = 3
        for (seed in picked.take(2)) {
            var perBlock = 0
            for (m in ordered) {
                if (kotlin.math.abs(m.ts - seed.ts) > window) continue
                if (m.ts in added) continue
                if (chars + m.text.length > blockChars) break
                out += Hit(
                    text = m.text, ts = m.ts, fromUser = m.fromUser,
                    score = seed.score, keyScore = seed.keyScore,
                    speakerId = m.speakerId,
                )
                added += m.ts
                chars += m.text.length
                perBlock += 1
                if (perBlock >= maxPerBlock) break
            }
            if (chars >= blockChars) break
        }
        return out
    }

    /** 中文没有空格，用两字滑窗当"词"；标点（含中文标点）全部剔掉，否则会造出「吗，」这种噪声 */
    private fun bigrams(s: String): Set<String> {
        val out = HashSet<String>()
        val cleaned = s.replace(Regex("[\\p{P}\\p{S}\\s]"), "")
        if (cleaned.length < 2) return out
        for (i in 0 until cleaned.length - 1) out += cleaned.substring(i, i + 2)
        return out
    }

    /**
     * 找出"这轮新冒出来、当前窗口里没有、但原文库里有"的词。
     *
     * 这是比关键词表可靠得多的触发方式：你说「房梁和绳子」，`房梁` 不在当前话题里，
     * 却在几天前的记录里 —— 那就是在说旧事，直接去查，不用等模型决定。
     * 命中的词直接当查询串用，检索再也不依赖模型的输出格式。
     */
    fun novelQuery(text: String, windowText: String, corpus: List<StoredMessage>): String {
        val q = bigrams(text)
        if (q.isEmpty()) return ""
        val inWindow = bigrams(windowText)
        val novel = q.filter { it !in inWindow }
        if (novel.isEmpty()) return ""

        val archive = HashSet<String>()
        corpus.forEach { m -> archive += bigrams(m.text) }
        // 最多带 2 个，多了会把轮次排满、把噪声带进来
        val hit = novel.filter { it in archive }.take(2)
        return if (hit.isEmpty()) "" else hit.joinToString(" ")
    }
}
