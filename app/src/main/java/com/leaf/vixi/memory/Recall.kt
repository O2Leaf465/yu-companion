package com.leaf.vixi.memory

import com.leaf.vixi.data.IndexEntry
import com.leaf.vixi.data.MemoryIndex
import com.leaf.vixi.data.RecallStat
import com.leaf.vixi.data.StoredMessage
import kotlin.math.abs
import kotlin.math.pow

/** 要不要想、想到多少：三档（不是开关） */
enum class RecallMode { OFF, LIGHT, FULL }

/** 一段连续的对话 = 一个"事件"（30 分钟内算同一件） */
data class EventBlock(
    val start: Long,
    val end: Long,
    val messages: List<StoredMessage>,
    val importance: Double,
    val p: Double,
    val label: String,
    val entities: Set<String>,
    /** 这一段里有钉住的条目（承诺/里程碑/自我定义）：不参与软遗忘 */
    val pinned: Boolean = false,
)

/** 打分明细，调试页要能看"为什么想起来这件事" */
data class Scored(
    val event: EventBlock,
    val score: Double,
    val lead: Double,
    val semantic: Double,
    val importance: Double,
    val recency: Double,
    val moodBoost: Double,
    /** 命中的稀有专名（有就几乎一定该被想起） */
    val rareHit: String = "",
    /** 冷层（很久没被提起、重要度也低）：预算上最多取 2 条 */
    val cold: Boolean = false,
)

data class Result(
    val mode: RecallMode,
    val gate: Double,
    /** 展开的（≥0.55）：给原文 */
    val expanded: List<Scored>,
    /** 只提示一句的（0.35–0.55） */
    val hinted: List<Scored>,
    /** 渲染好、可直接进提示词的行 */
    val lines: List<String>,
    /** 调试用一行摘要 */
    val detail: String,
    /** 候选明细（写进 recall.log，用来复查"为什么没命中"） */
    val debug: List<String> = emptyList(),
    /** 参与打分的事件起点（供调用方写"提取抑制"） */
    val candidates: List<Long> = emptyList(),
    /** 本次被选中的事件起点（供调用方写"共召回"） */
    val picked: List<Long> = emptyList(),
)

/**
 * 检索 v2：**三通道 + 事件为单位 + 分档注入**（照 BioCore 的结构，但全本地、无向量、无摘要）。
 *
 * 为什么不是"关键词命中就召回"：
 *  - 像不像只是入场券，**线索**（人名/时间/承诺）才说明"对得上"；
 *  - 想起来多少要分档：≥0.55 展开事件、0.35–0.55 只提一句、<0.35 干脆不提；
 *  - 心情会影响"想起什么"：低落时更容易翻旧账（心境一致性），这是唯一一项目前只在 PAD 上体现的机制。
 *
 * 全部纯规则，**不增加任何 API 调用**。
 */
object Recall {

    /** 事件切分：隔了 30 分钟就是另一件事 */
    private const val EVENT_GAP_MS = 30 * 60 * 1000L

    /** 展开档/提示档的门槛 */
    const val EXPAND_GATE = 0.55
    const val HINT_GATE = 0.35

    /** 每档的预算 */
    const val EXPAND_MAX_MESSAGES = 3
    const val EXPAND_MAX_CHARS = 220
    const val HINT_MAX_CHARS = 60
    const val LIGHT_MAX_CHARS = 80

    /** 时间衰减：一周掉一半 */
    private const val RECENCY_HALF_LIFE_DAYS = 7.0

    /** F3：判断"这个词算不算当前话题"看的最近消息条数 */
    private const val TOPIC_WINDOW_MESSAGES = 30

    /**
     * F3：话题线索给线索通道的权重上限（0.35 = 单独命中最多把 lead 抬到 0.35）。
     * 明显低于稀有专名（专名命中直接 lead=1.0），不能盖过它。
     */
    private const val TOPIC_LEAD_WEIGHT = 0.35

    // ────────── 闸门：要不要想（0..1，不是布尔） ──────────

    /** 明确指向过去 */
    private val STRONG = listOf(
        "上次", "上回", "那次", "那天", "当时", "我说过", "你说过", "跟你说过",
        "聊过", "提过", "记得", "记不记得", "还记得", "什么来着", "有印象", "问过你",
    )

    /** 承诺类：比"上次"更需要认真回想 */
    private val PROMISE = listOf("答应", "说好", "约好", "承诺", "保证")

    /** 过去的时间词：单独出现也算"指向过去"（"上周那件事"要被想起来） */
    private val PAST_TIME = listOf(
        "昨天", "前天", "上周", "上上", "上个月", "去年", "之前", "以前", "前阵子",
        "那次", "那天", "当时", "那会儿", "刚才", "上次",
    )

    /** 指示词：让"那件事"更像具体指代 */
    private val DEMONSTRATIVE = listOf("那件事", "那个", "那件", "那回", "那次", "那天")

    /** 中文连续串 / 英文数字串：只有"整段词"才当专名，不看两字切片 */
    private val CJK_RUN = Regex("[\\u4e00-\\u9fa5]{3,}")
    private val ASCII_TOKEN = Regex("[A-Za-z][A-Za-z0-9_\\-]{1,}")

    /**
     * 句子里有没有"具体的专名"：整段中文串或英文串，**语料里出现过但最近没提过**。
     *
     * 这就是「那我发给你“锟斤拷”那件事呢」该被检索、而「今天吃什么」不该的原因。
     */
    /**
     * 句子里"真正稀有"的专名（三元组 + 英文串）。
     *
     * 稀有 = 只在 ≤2 个事件里出现，且 ≤25% 的事件。
     * 只判断"出现过"是不够的：「上次说 / 次说的 / 那件事」在大量事件里都有，
     * 那样会让一堆无关片段拿到满分线索（这一版踩过）。
     */
    private fun rareTokensOf(query: String, blocks: List<EventBlock>): List<String> {
        val cand = LinkedHashSet<String>()
        CJK_RUN.findAll(query).forEach { m ->
            val s = m.value
            if (s.length >= 3) for (i in 0..s.length - 3) {
                val tri = s.substring(i, i + 3)
                if (tri !in NOVEL_STOP) cand += tri
            }
            if (s.length >= 3 && s !in NOVEL_STOP) cand += s
        }
        ASCII_TOKEN.findAll(query).forEach { if (it.value.length >= 2) cand += it.value }
        if (cand.isEmpty() || blocks.isEmpty()) return emptyList()

        val n = blocks.size.toDouble()
        val textOf = blocks.map { b -> b.messages.joinToString("") { it.text } }
        // 字符级特异性：「那件事」这种词整词稀有，但每个字都很常见；
        // 「锟斤拷」每个字都罕见。用字频卡住，就不用维护一张巨大的虚词表。
        val charDf = HashMap<Char, Int>()
        textOf.forEach { t ->
            t.toCharArray().distinct().forEach { c -> charDf[c] = (charDf[c] ?: 0) + 1 }
        }
        val charLimit = (0.15 * n).coerceAtLeast(2.0)
        return cand.filter { c ->
            val df = textOf.count { it.contains(c) }
            if (df !in 1..2 || df / n > 0.25) return@filter false
            // 英文/数字串不看字频
            if (c.any { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }) return@filter true
            c.all { ch -> (charDf[ch] ?: 0) <= charLimit }
        }
    }

    private fun novelToken(query: String, corpusText: String, recentText: String): Boolean {
        val tokens = mutableSetOf<String>()
        // 中文用**三元组**：整段串太粗（"那我发锟斤拷那件事呢"整串在语料里当然没有），
        // 两字词太碎（"天吃""吃什"几乎句句都有）——三字刚好卡住"锟斤拷"这种专名。
        CJK_RUN.findAll(query).forEach { m ->
            val s = m.value
            if (s.length >= 3) for (i in 0..s.length - 3) tokens += s.substring(i, i + 3)
        }
        // 英文/数字串整体算一个词
        ASCII_TOKEN.findAll(query).forEach { tokens += it.value }
        // 整段串也留一份（"锟斤拷"单独一句时整串正好命中）
        CJK_RUN.findAll(query).forEach { tokens += it.value }
        if (tokens.isEmpty()) return false
        return tokens.any { tok ->
            tok.length >= 3 && tok !in NOVEL_STOP && corpusText.contains(tok) && !recentText.contains(tok)
        }
    }

    /** 太常见的词不算"专名信号" */
    private val NOVEL_STOP = setOf(
        "今天", "现在", "什么", "怎么", "我们", "他们", "你们", "这个", "那个",
        "可以", "就是", "还是", "因为", "所以", "而且", "但是", "如果", "时候",
    )

    /** 引号里的具体指代：中英文引号都算 */
    private val QUOTED = Regex("[\"“”「『'][^\"“”「』']{2,}[\"“”」』']")

    private val QUESTION = listOf("?", "？", "吗", "呢", "什么", "怎么", "哪", "是不是", "有没有")

    /**
     * 要不要检索（0~1）。
     *
     * @param unclosed 当前话题还没聊完（没结尾）→ 略加分
     */
    fun gateScore(
        text: String,
        unclosed: Boolean = false,
        extraEntity: Boolean = false,
        /** 句子里有一个"语料里很稀有"的词（专名/技术词/昵称）→ 也能开门 */
        novelEntity: Boolean = false,
    ): Double {
        val t = text.trim()
        if (t.length < 3) return 0.0
        val strong = STRONG.any { t.contains(it) }
        val promise = PROMISE.any { t.contains(it) }
        val pastTime = PAST_TIME.any { t.contains(it) }
        // 引号里的东西（“锟斤拷”「那个东西」）本身就是一个具体指代
        val quoted = QUOTED.containsMatchIn(t)
        // 完全没有"指向过去"或"具体指代"的信号：不该检索（否则每句话都在翻旧账）
        if (!strong && !promise && !pastTime && !novelEntity && !quoted) return 0.0

        // 基础分按最强的那一类给（同一句里"那次"同时命中多个词表也只算一次）
        var s = if (promise) 0.55 else 0.45
        if (quoted) s += 0.10
        if (DEMONSTRATIVE.any { t.contains(it) }) s += 0.15
        if (extraEntity) s += 0.15
        if (QUESTION.any { t.contains(it) }) s += 0.12
        if (unclosed) s += 0.10
        return s.coerceIn(0.0, 1.0)
    }

    fun modeOf(gate: Double): RecallMode = when {
        gate < 0.30 -> RecallMode.OFF
        gate <= 0.60 -> RecallMode.LIGHT
        else -> RecallMode.FULL
    }

    // ────────── 主流程 ──────────

    /**
     * @param query 这一轮进来的话（所有消息拼一起）
     * @param corpus 语料（已排除当前窗口）
     * @param entries 索引
     * @param mood 当前心境（−1..1）
     * @param unclosed 当前话题还没聊完
     * @param speakerName 渲染"谁说的"
     */
    fun run(
        query: String,
        corpus: List<StoredMessage>,
        entries: List<IndexEntry>,
        mood: Double,
        unclosed: Boolean = false,
        knownNames: List<String> = emptyList(),
        /** 最近窗口的文本：用来判断"这个专名最近没提过"，避免日常词误开闸门 */
        recentText: String = "",
        /** 每段被想起过几次、最后一次什么时候（复习间隔用） */
        recalls: Map<Long, RecallStat> = emptyMap(),
        /** 共召回加成（和"最近被想起过的段落"的关联强度，0~0.03） */
        coBonus: Map<Long, Double> = emptyMap(),
        now: Long = System.currentTimeMillis(),
        /** 这条时间之后的一律不算"旧事"（防止把本轮刚说的话检索回来） */
        excludeAfter: Long = Long.MAX_VALUE,
        speakerName: (StoredMessage) -> String = { if (it.fromUser) "Leaf" else "她" },
    ): Result {
        val entities = MemoryIndex.entities(query, knownNames).toSet()
        // 2026-11-22 修正：回声过滤从"按事件丢"改成"按消息丢"。
        // 原来整段（17:06–17:42 那种连续聊了半小时的）是一个事件，只要里面有本轮那句问话，
        // **整个事件**就被当成回声丢掉 —— 而"傻子"那段正好也在同一个事件里，
        // 于是"还记得我之前叫你傻子吗"什么都想不起来（真机 replay 复现）。
        val usable = corpus.filter { it.ts < excludeAfter }
            .filterNot { isEchoMessage(it, query) }
        val blocks = blocks(usable, entries)

        // 稀有专名（专名命中 = 线索满分，和 BioCore 的"线索通道"一个意思）
        val rareTokens = rareTokensOf(query, blocks)
        // 专名信号用"整段词"判断，不用两字词：
        // 两字词几乎句句都有稀有组合（"天吃""吃什"），会把闸门彻底放开。
        val novel = rareTokens.any { !recentText.contains(it) }

        val qGrams = bigrams(query)
        // ── F1（2026-11-22）──
        // 真机现象：「还记得我说…吗」连问三次，前两次有命中、第三次起 0 命中。
        // 根因是**词频被用来"砍词"**：那些词被自己反复说 → df 抬高 → 过了 0.40 的门槛
        // → 从"实词"里被踢掉 → 语义归零。逻辑反了：一个词因为"你正在说它"而搜不到。
        // 改法：词频**只用来称重（IDF），不再用来砍词**；砍词交给功能词表 + 一个很宽的上限。
        val df = documentFrequency(blocks, qGrams)
        val gate = gateScore(query, unclosed, entities.isNotEmpty(), novel || rareTokens.isNotEmpty())
        val mode = modeOf(gate)
        if (mode == RecallMode.OFF || blocks.isEmpty()) {
            return Result(mode, gate, emptyList(), emptyList(), emptyList(),
                "gate=${fmt(gate)} " + if (mode == RecallMode.OFF) "不检索" else "无事件")
        }

        // IDF：只有"稀有词"才算实词。
        // 「我们/上次/说的/那件事」这类词在大量事件里都出现 → 权重被压到 0，
        // 真正对得上的是「医院」「牙医」这种只在少数事件里出现的词。
        val content = contentTerms(qGrams, df, blocks.size)
        val weights = content.associateWith { g ->
            kotlin.math.ln((blocks.size + 1.0) / ((df[g] ?: 0) + 1.0)) + 0.05
        }
        val weightSum = weights.values.sum().coerceAtLeast(1e-6)

        // ── F3（2026-11-22）──
        // 高频实义词也要算线索：真机上「傻子」今天说了 7 次 → df 高 → 被当虚词丢掉，
        // 结果她想起的是"你愿意当我的…吗"，而不是刚被叫的那几声「傻子」。
        // 做法：查询词若在**最近 30 条**里出现过 ≥2 次，算"话题线索"，给 lead 一点加权
        // （权重明显低于稀有专名，不能盖过它）。
        val recentCorpus = usable.takeLast(TOPIC_WINDOW_MESSAGES).joinToString(" ") { it.text }
        val topicTerms = content.filter { countOccurrences(recentCorpus, it) >= 2 }.toSet()
        val latestStart = blocks.maxOfOrNull { it.start } ?: 0L

        val scored = blocks.map { b ->
            val text = b.messages.joinToString(" ") { it.text }
            val grams = bigrams(text)
            val semantic = weights.entries.sumOf { (g, w) -> if (grams.contains(g)) w else 0.0 } / weightSum
            val rareHit = rareTokens.firstOrNull { text.contains(it) }.orEmpty()
            val lead = if (entities.isEmpty()) 0.0 else {
                val hit = entities.count { e -> b.entities.contains(e) || text.contains(e) }
                hit.toDouble() / entities.size
            }
            // 话题线索：**当前这一段不算**（否则她每轮都在"想起来的"是刚才那几句）
            val topicHit = if (topicTerms.isEmpty() || b.start == latestStart) 0.0 else {
                topicTerms.count { grams.contains(it) }.toDouble() / topicTerms.size
            }
            val recency = 0.5.pow((now - b.start).toDouble() / (RECENCY_HALF_LIFE_DAYS * 86_400_000.0))
            // 心境一致性：心情与事件同向加成，反向略减（低落时更容易翻旧账）
            val moodBoost = 1.0 + 0.25 * (if (b.p * mood >= 0) abs(mood) else -abs(mood))
            // 专名命中把线索拉满：问「锟斤拷那件事」时，含这个词的那段必须先被选中
            val leadFinal = if (rareHit.isNotEmpty()) 1.0
            else (lead + TOPIC_LEAD_WEIGHT * topicHit).coerceAtMost(1.0)
            // 重要度要**随时间衰减**（他的 30 天半衰、下限 0.15），
            // 否则一年前的一次吵架和昨天的吵架一样重。
            val effImp = effectiveImportance(b.importance, now - b.start)
            // 复习到期（按 1/2/4/7/15/30/60 天排）→ 召回分 ×1.15
            val dueBonus = if (recallsDue(b.start, recalls, now)) 1.15 else 1.0
            // 记忆点之间会互相影响（他的第五节）：
            //   被想起过 → 重要度强化（+0.015/次，30 天半衰）
            //   上次没被选中但很像 → 提取抑制（−0.08，7 天半衰）
            val stat = recalls[b.start]
            val boost = stat?.effectiveBoost(now) ?: 0.0
            val suppress = stat?.suppression(now) ?: 0.0
            val score = (0.45 * leadFinal + 0.30 * semantic + 0.15 * effImp + 0.10 * recency) *
                moodBoost * dueBonus + boost - suppress + (coBonus[b.start] ?: 0.0)
            val ageDays = (now - b.start) / 86_400_000.0
            val cold = MemoryIndex.layerOf(ageDays, effImp, stat?.count ?: 0) == "cold"
            Scored(b, score, leadFinal, semantic, b.importance, recency, moodBoost, rareHit, cold)
        }.sortedByDescending { it.score }

        // 分档 + MMR（同一件事只取一次、内容太像的跳过）
        val expanded = mutableListOf<Scored>()
        val hinted = mutableListOf<Scored>()
        val picked = mutableListOf<Scored>()
        for (s in scored) {
            if (expanded.size + hinted.size >= 3) break
            // 层级预算（他的 hot/warm/cold）：冷层最多取 2 条
            if (s.cold && (expanded.count { it.cold } + hinted.count { it.cold }) >= 2) continue
            if (s.score < HINT_GATE) continue
            // 提示档也要"对得上"：只靠重要度+新近堆上来的无关段落不进提示
            if (s.rareHit.isEmpty() && s.lead <= 0.0 && s.semantic < 0.45) continue
            if (picked.any { tooSimilar(it, s) }) continue
            if (mode == RecallMode.LIGHT) {
                if (hinted.size >= 1) break
                hinted += s
                picked += s
                continue
            }
            // 软遗忘：过了 fadeAfterDays 的旧事只给一句提示，不再展开原文
            //（他的"细节先淡、节点留久"；我们不删正文，只是不再整段倒出来）
            val faded = !s.event.pinned &&
                (now - s.event.start) > MemoryIndex.fadeAfterDays(s.event.importance) * 86_400_000L
            // 钉住的（承诺/里程碑/自我定义）门槛降到提示档：想起来就给原文
            val expandGate = if (s.event.pinned) HINT_GATE else EXPAND_GATE
            if (s.score >= expandGate && expanded.size < 2 && !faded) expanded += s else hinted += s
            picked += s
        }

        val lines = mutableListOf<String>()
        expanded.forEach { s ->
            lines += renderBlock(s.event, speakerName, weights, EXPAND_MAX_MESSAGES, EXPAND_MAX_CHARS)
        }
        hinted.forEach { s ->
            val cap = if (mode == RecallMode.LIGHT) LIGHT_MAX_CHARS else HINT_MAX_CHARS
            lines += renderBlock(s.event, speakerName, weights, 1, cap)
        }

        val detail = buildString {
            append("gate=").append(fmt(gate)).append(' ').append(mode.name)
            if (entities.isNotEmpty()) append(" 实体=").append(entities.joinToString("/"))
            append(" 展开=").append(expanded.size).append(" 提示=").append(hinted.size)
            expanded.take(1).forEach {
                append(" | ").append(fmt(it.score))
                append("(线索").append(fmt(it.lead))
                append(" 语义").append(fmt(it.semantic))
                append(" 重要").append(fmt(it.importance))
                append(" 新近").append(fmt(it.recency))
                append(" 心境×").append(fmt(it.moodBoost)).append(')')
            }
        }
        val debug = scored.take(4).map { s ->
            "  ${fmt(s.score)} lead=${fmt(s.lead)} sem=${fmt(s.semantic)} imp=${fmt(s.importance)} " +
                "rare=${s.rareHit.ifBlank { "-" }} 「${s.event.messages.first().text.take(16)}」"
        } + listOf(
            "  content=" + content.joinToString("/") +
                " entities=" + entities.joinToString("/") +
                " recent=" + recentText.take(12),
        )
        return Result(
            mode, gate, expanded, hinted, lines, detail, debug,
            candidates = scored.map { it.event.start },
            picked = (expanded + hinted).map { it.event.start },
        )
    }

    // ────────── 内部：切事件、渲染、相似度 ──────────

    private fun blocks(corpus: List<StoredMessage>, entries: List<IndexEntry>): List<EventBlock> {
        if (corpus.isEmpty()) return emptyList()
        val sorted = corpus.sortedBy { it.ts }
        val out = mutableListOf<EventBlock>()
        var cur = mutableListOf<StoredMessage>()
        var prev = 0L
        sorted.forEach { m ->
            if (prev > 0 && m.ts - prev > EVENT_GAP_MS && cur.isNotEmpty()) {
                out += build(cur, entries)
                cur = mutableListOf()
            }
            cur += m
            prev = m.ts
        }
        if (cur.isNotEmpty()) out += build(cur, entries)
        return out
    }

    private fun build(msgs: List<StoredMessage>, entries: List<IndexEntry>): EventBlock {
        val start = msgs.first().ts
        val end = msgs.last().ts
        val inside = entries.filter { it.ts in start..end }
        val peak = inside.maxByOrNull { it.importance }
        return EventBlock(
            start = start,
            end = end,
            messages = msgs,
            importance = peak?.importance ?: 0.4,
            p = peak?.p ?: 0.0,
            label = peak?.label.orEmpty(),
            entities = inside.flatMap { it.entities }.toSet(),
            pinned = inside.any { it.pinned },
        )
    }

    private fun renderBlock(
        e: EventBlock,
        speakerName: (StoredMessage) -> String,
        weights: Map<String, Double>,
        maxMessages: Int,
        maxChars: Int,
    ): String {
        // 正文预算要**先扣掉时间头**，否则头占满预算时正文一条都塞不进，
        // 真机上就出现过"只返回（11-20 17:16 那次）没有正文"。
        val header = "（${dayStamp(e.start)} 那次）"
        val bodyBudget = (maxChars - header.length - 1).coerceAtLeast(24)

        // 先挑对上的那几条，再把"紧接着的那一句"带上 ——
        // 没有接话的那一句，模型只看到"用户说过什么"，不知道她当时是怎么接的。
        val picked = withReplies(e, pickMessages(e, weights, maxMessages, bodyBudget))
        val body = StringBuilder()
        picked.forEach { m ->
            val line = "${speakerName(m)}：${m.text.replace("\n", " ")}"
            if (body.length + line.length + 1 > bodyBudget) return@forEach
            if (body.isNotEmpty()) body.append('\n')
            body.append(line)
        }
        if (body.isEmpty()) {
            // 一句完整的都放不下：截断一条，保证"有正文"
            val m = picked.lastOrNull() ?: e.messages.last()
            val room = (bodyBudget - speakerName(m).length - 2).coerceAtLeast(12)
            body.append(speakerName(m)).append('：').append(m.text.replace("\n", " ").take(room))
        }
        if (body.isEmpty()) return ""
        return header + "\n" + body
    }

    private val DAY = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)

    private fun dayStamp(ts: Long) = DAY.format(java.util.Date(ts))

    /**
     * 把"紧接着的那一句"带进来看她当时怎么接的。
     *
     * 只挑到"用户说了什么"时，这段旧事对模型没用：它不知道她是敷衍、接住了、还是翻旧账。
     */
    private fun withReplies(e: EventBlock, picked: List<StoredMessage>): List<StoredMessage> {
        if (picked.isEmpty()) return picked
        val out = LinkedHashSet<StoredMessage>()
        picked.forEach { m ->
            out += m
            val i = e.messages.indexOfFirst { it.ts == m.ts }
            if (i >= 0) e.messages.getOrNull(i + 1)?.let { out += it }
        }
        return out.sortedBy { it.ts }
    }

    /**
     * 只有一条原文时，补一条相邻消息。
     *
     * 只有一句"XX：医院在三楼"模型很难用：不知道谁接了什么。
     * 补一条邻居就能看出这是一问一答还是自言自语。
     */
    private fun withNeighbor(e: EventBlock, picked: List<StoredMessage>): List<StoredMessage> {
        if (picked.size >= 2 || e.messages.size < 2) return picked
        val one = picked.first()
        val idx = e.messages.indexOfFirst { it.ts == one.ts && it.text == one.text }
        if (idx < 0) return picked
        val neighbor = e.messages.getOrNull(idx + 1) ?: e.messages.getOrNull(idx - 1) ?: return picked
        return listOf(one, neighbor).sortedBy { it.ts }
    }

    /** 这一段是不是"这一轮问句"的回声（真机上把刚发的话当旧事检索回来过） */
    /**
     * 这一条消息是不是"本轮问句的回声"。
     *
     * 按**消息**判断，不按事件判断：一整段连续聊天通常是同一个事件，
     * 按事件丢会把同一段里真正该被想起的内容一起丢掉（2026-11-22 真机复现）。
     */
    private fun isEchoMessage(m: StoredMessage, query: String): Boolean {
        val q = query.filter { it.isLetterOrDigit() }
        if (q.length < 6) return false
        val t = m.text.filter { it.isLetterOrDigit() }
        if (t.length < 4) return false
        return q.contains(t) || t.contains(q)
    }

    private fun tooSimilar(a: Scored, b: Scored): Boolean {
        val ta = a.event.messages.joinToString(" ") { it.text }
        val tb = b.event.messages.joinToString(" ") { it.text }
        val ga = bigrams(ta)
        val gb = bigrams(tb)
        if (ga.isEmpty() || gb.isEmpty()) return false
        val inter = ga.intersect(gb).size.toDouble()
        val union = ga.union(gb).size.toDouble()
        return union > 0 && inter / union > 0.60
    }

    /** 每个查询词出现在多少个事件里（用来算稀有度） */
    private fun documentFrequency(blocks: List<EventBlock>, query: Set<String>): Map<String, Int> {
        if (query.isEmpty()) return emptyMap()
        val df = HashMap<String, Int>()
        blocks.forEach { b ->
            val set = bigrams(b.messages.joinToString(" ") { it.text })
            query.forEach { q -> if (set.contains(q)) df[q] = (df[q] ?: 0) + 1 }
        }
        return df
    }

    /**
     * 中文功能词（两字组合）。**2026-11-22 加的**：
     * 真机上「还记得我之前叫你傻子吗」的语义分给到了 1.00，命中的却是那段
     * 「还记得我说：你愿意当我的…吗」—— 因为 `还记/记得/得我/之前/叫你` 这几个
     * **功能词组合**只在那一小段里同时出现，df 低，于是被当成了"实词"。
     *
     * 语义通道只能由**内容词**给分；这些词不该出现在分母里。
     */
    private val STOP_BIGRAMS = setOf(
        "还记", "记得", "得我", "之前", "叫你", "你愿",
        "我们", "你们", "他们", "什么", "怎么", "这个", "那个", "哪个",
        "一个", "一些", "一下", "一起", "一样", "时候", "时间", "现在",
        "不是", "就是", "没有", "可以", "觉得", "知道", "真的", "自己",
        "因为", "所以", "但是", "如果", "然后", "还是", "已经", "不会",
        "不能", "可能", "应该", "这样", "那样", "多少", "为什么", "这是",
        "那是", "有点", "好像", "其实", "东西", "事情", "问题", "说什",
        "什么", "么样", "次说", "上次", "那次", "那件", "这件", "说的", "件事",
        "当我的", "我的", "你的", "他的", "她的", "是不", "嗯嗯",
        // 2026-11-22 回放真实语料时补的：这些在真机上被当成了"实词"给语义满分
        "那你", "你还", "那我", "那我", "你可", "你是", "是你", "都是",
        "对了", "而且", "不过", "只是", "这样", "一样", "这样", "好的",
        "我说", "我说", "你说", "说我", "说我", "啊你", "呀你", "么吗",
    )

    /**
     * 只保留"实词"。
     *
     * **2026-11-22 改了判据**：原来用 `df/事件数 ≤ 0.40` 砍词，结果"常见实义词"被砍掉
     * （「傻子」今天说了 7 次 → 被当虚词 → 真正的线索拿不到分），而且"你反复说的词"
     * 会从实词里消失（重复提问第二次起检索失效）。
     * 现在：砍词只交给功能词表（[STOP_BIGRAMS]），词频只负责 IDF 称重；
     * 另外留一个很宽的上限（>80% 的事件都含 → 等于标点，没有区分度）。
     */
    private fun contentTerms(query: Set<String>, df: Map<String, Int>, eventCount: Int): Set<String> {
        val n = eventCount.coerceAtLeast(1).toDouble()
        // 必须在语料里出现过：从没出现过的两字词是切词切出来的半截，不该占分母
        val meaningful = query.filter { it !in STOP_BIGRAMS && (df[it] ?: 0) > 0 }
        // 几乎每条都有的词没有区分度（上限放得很宽，常见实义词要留着）
        val useful = meaningful.filter { (df[it] ?: 0) / n <= 0.80 }
        if (useful.isNotEmpty()) return useful.toSet()
        // 全被过滤（很短的语料）→ 退一步取最稀有的两个，别让检索彻底失效
        return meaningful.sortedBy { df[it] ?: 0 }.take(2).toSet()
    }

    /** 子串出现次数（不重叠） */
    private fun countOccurrences(text: String, sub: String): Int {
        if (sub.isEmpty()) return 0
        var i = text.indexOf(sub)
        var n = 0
        while (i >= 0) {
            n++
            i = text.indexOf(sub, i + sub.length)
        }
        return n
    }

    /**
     * 从一段事件里挑**真正对上的那几条**消息（不是简单取最后三条）。
     *
     * 人回想时想起的是"那句话"，不是"最后三句"。
     */
    private fun pickMessages(
        e: EventBlock,
        weights: Map<String, Double>,
        maxMessages: Int,
        maxChars: Int,
    ): List<StoredMessage> {
        if (weights.isEmpty() || e.messages.size <= 1) {
            return e.messages.asReversed().take(maxMessages).asReversed()
        }
        val scored = e.messages.map { m ->
            val g = bigrams(m.text)
            val hit = weights.entries.sumOf { (w, weight) -> if (g.contains(w)) weight else 0.0 }
            m to hit
        }
        val strong = scored.filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(maxMessages)
            .map { it.first }
        val chosen = (if (strong.isNotEmpty()) strong else scored.map { it.first }.takeLast(maxMessages))
            .sortedBy { it.ts }

        // 字数预算：从最早开始塞，超了就丢
        var used = 0
        val out = mutableListOf<StoredMessage>()
        chosen.forEach { m ->
            val len = m.text.length + 6
            if (used + len <= maxChars) {
                used += len
                out += m
            }
        }
        return if (out.isNotEmpty()) out else chosen.takeLast(1)
    }

    private fun coverage(query: Set<String>, text: Set<String>): Double {
        if (query.isEmpty() || text.isEmpty()) return 0.0
        val hit = query.count { text.contains(it) }
        return (hit.toDouble() / query.size).coerceIn(0.0, 1.0)
    }

    private fun bigrams(s: String): Set<String> {
        val t = s.filter { it.isLetterOrDigit() }
        if (t.length < 2) return if (t.isEmpty()) emptySet() else setOf(t)
        return (0 until t.length - 1).map { t.substring(it, it + 2) }.toSet()
    }

    /** 有效重要度：30 天半衰，下限 0.15（他的规则） */
    fun effectiveImportance(importance: Double, ageMs: Long): Double {
        val halved = importance * 0.5.pow(ageMs.toDouble() / (30 * 86_400_000.0))
        return halved.coerceAtLeast(0.15)
    }

    /** 复习是否到期：按 1/2/4/7/15/30/60 天排，最后一次召回 + 间隔 <= 现在 */
    private fun recallsDue(
        start: Long,
        recalls: Map<Long, RecallStat>,
        now: Long,
    ): Boolean {
        val stat = recalls[start] ?: return true // 从没被想起过：算"到期"
        val last = maxOf(stat.lastAt, start)
        val gap = MemoryIndex.reviewIntervalDays(stat.count) * 86_400_000L
        return now - last >= gap
    }

    private fun fmt(v: Double) = String.format(java.util.Locale.US, "%.2f", v)
}
