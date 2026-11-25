package com.leaf.vixi.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * 一条消息的**记忆索引**：只有元数据，正文永远回 `chat_*.jsonl` 取。
 *
 * 为什么不存正文、不写摘要：
 *  - 原文是唯一真相（"总结不是真相"），索引只回答"哪一段值得想"；
 *  - 索引很小，可以随时按新规则重算，不担心污染历史。
 */
data class IndexEntry(
    val ts: Long,
    val topicId: Long,
    val fromUser: Boolean,
    val speakerId: String?,
    val importance: Double,
    val label: String,
    val a: Double,
    val p: Double,
    val d: Double,
    val trust: Double,
    val closeness: Double,
    val conflict: Double,
    val backlog: Double,
    val mood: Double,
    val entities: List<String> = emptyList(),
    /** 来源：user 直说 / her 她说的 / infer 补出来的历史（他的"来源"字段） */
    val source: String = "user",
    /** 置信：不同来源可信度不同，召回与"不确定"判断都要用 */
    val confidence: Double = 1.0,
    /** 钉住：自我定义 / 里程碑 / 承诺，不参与淘汰（他的"钉住"） */
    val pinned: Boolean = false,
    /** 冲突标记：这条被后来的说法覆盖过（版本链的另一半） */
    val superseded: Boolean = false,
)

/**
 * 一段的"记忆状态"：
 *  - count/lastAt：被想起过几次、最后一次（复习间隔用）
 *  - boost/boostAt：重要度强化（被想起 +0.015，同样带 30 天衰减）
 *  - suppressedAt：提取抑制（没被选中但很相似的条目被暂时压住，7 天半衰）
 */
data class RecallStat(
    val count: Int,
    val lastAt: Long = 0L,
    val boost: Double = 0.0,
    val boostAt: Long = 0L,
    val suppressedAt: Long = 0L,
) {
    /** 强化值的当前有效量（30 天半衰） */
    fun effectiveBoost(now: Long): Double {
        if (boost <= 0.0 || boostAt <= 0L) return 0.0
        return boost * Math.pow(0.5, (now - boostAt).toDouble() / (30 * 86_400_000.0))
    }

    /** 抑制的当前强度（7 天半衰） */
    fun suppression(now: Long): Double {
        if (suppressedAt <= 0L) return 0.0
        val decay = Math.pow(0.5, (now - suppressedAt).toDouble() / (7 * 86_400_000.0))
        return 0.08 * decay
    }
}

/** 一个话题聚成的记忆点（给可视化/检索用） */
data class TopicMemory(
    val topicId: Long,
    val startTs: Long,
    val endTs: Long,
    val importance: Double,
    val label: String,
    val a: Double,
    val p: Double,
    val d: Double,
    val trust: Double,
    val closeness: Double,
    val conflict: Double,
    val mood: Double,
    val messageCount: Int,
    val title: String = "",
    /** 这一段里有没有"钉住"的条目（承诺/里程碑/自我定义） */
    val pinned: Boolean = false,
)

/**
 * 记忆索引存储：`files/mem/index_<会话>.jsonl`，一行一条。
 *
 * 重要度公式（第一章，用户确认用这版）：
 *   importance = 0.25 + 0.35*唤醒 + 0.25*|效价| + 0.15*(1-信任)
 * 助手自己说的话再打九折（SELF_WEIGHT）。
 */
class MemoryIndex(context: Context) {

    private val base = File(context.filesDir, "mem")
    private var file: File? = null

    fun open(conversationId: String) {
        base.mkdirs()
        file = File(base, "index_${sanitize(conversationId)}.jsonl")
    }

    fun append(entry: IndexEntry) {
        val f = file ?: return
        runCatching { f.appendText(encode(entry).toString() + "\n", Charsets.UTF_8) }
    }

    /**
     * 删掉某条消息的索引（删单条消息时用）。
     *
     * 不删的后果：被删掉的那句还留在索引里，如果它是承诺/里程碑（`pinned`），
     * 整段事件会被永久钉住 —— 永不软遗忘、展开门槛降到 0.35。
     * "删除"不等于"忘记"，用户看到的就是"我明明删了它还记得"。
     */
    fun remove(ts: Long) {
        val f = file ?: return
        if (!f.exists()) return
        runCatching {
            val keep = f.readLines(Charsets.UTF_8)
                .filter { it.isNotBlank() }
                .filter { line ->
                    val e = runCatching { decode(JSONObject(line)) }.getOrNull()
                    e == null || e.ts != ts
                }
            f.writeText(keep.joinToString("") { it + "\n" }, Charsets.UTF_8)
        }
    }

    fun read(): List<IndexEntry> {
        val f = file ?: return emptyList()
        if (!f.exists()) return emptyList()
        return runCatching {
            f.readLines(Charsets.UTF_8)
                .filter { it.isNotBlank() }
                .mapNotNull { line -> runCatching { decode(JSONObject(line)) }.getOrNull() }
        }.getOrDefault(emptyList())
    }

    /** 提取抑制：这次没被选中的相似条目，先压住（7 天半衰） */
    fun suppress(eventStart: Long, now: Long = System.currentTimeMillis()) = runCatching {
        val f = File(base, (file?.name ?: return@runCatching Unit)
            .replace("index_", "recalls_").replace(".jsonl", ".json"))
        val o = if (f.exists()) JSONObject(f.readText(Charsets.UTF_8)) else JSONObject()
        val old = o.opt(eventStart.toString()) as? JSONObject ?: JSONObject()
        old.put("sp", now)
        o.put(eventStart.toString(), old)
        f.writeText(o.toString(), Charsets.UTF_8)
    }

    /** 共召回关联：这两段这次一起被想起 → 记一笔（30 天半衰，用时间戳近似） */
    fun recordCoRecall(a: Long, b: Long, now: Long = System.currentTimeMillis()) = runCatching {
        if (a == b) return@runCatching
        val f = File(base, (file?.name ?: return@runCatching Unit)
            .replace("index_", "co_").replace(".jsonl", ".json"))
        f.parentFile?.mkdirs()
        val o = if (f.exists()) JSONObject(f.readText(Charsets.UTF_8)) else JSONObject()
        val key = "${minOf(a, b)}|${maxOf(a, b)}"
        val prev = o.optJSONObject(key)
        val n = (prev?.optInt("n", 0) ?: 0) + 1
        o.put(key, JSONObject().apply {
            put("n", n)
            put("at", now)
        })
        f.writeText(o.toString(), Charsets.UTF_8)
    }

    /** 共召回加成：这段和"这次想起的那几段"有多熟（0~0.03） */
    fun coRecallBonus(start: Long, picked: List<Long>, now: Long = System.currentTimeMillis()): Double =
        runCatching {
            val f = File(base, (file?.name ?: return@runCatching 0.0)
                .replace("index_", "co_").replace(".jsonl", ".json"))
            if (!f.exists()) return@runCatching 0.0
            val o = JSONObject(f.readText(Charsets.UTF_8))
            picked.count { p ->
                val key = "${minOf(start, p)}|${maxOf(start, p)}"
                val rec = o.optJSONObject(key) ?: return@count false
                val age = now - rec.optLong("at", 0L)
                val decay = Math.pow(0.5, age.toDouble() / (30 * 86_400_000.0))
                rec.optInt("n", 0) * decay >= 2.0
            } * 0.015
        }.getOrDefault(0.0)

    /**
     * 共召回加成表：和"最近 7 天被想起过的段落"有强关联的候选，各加一点分（0~0.03）。
     *
     * 他的原话是"常一起出现就越来越容易一起被想起"。检索是纯本地的，
     * 这里拿"最近被想起的那几段"当参照（而不是当次结果——当次结果还在算分）。
     */
    fun coRecallBonuses(recalls: Map<Long, RecallStat>, now: Long): Map<Long, Double> =
        runCatching {
            val f = File(base, (file?.name ?: return@runCatching emptyMap())
                .replace("index_", "co_").replace(".jsonl", ".json"))
            if (!f.exists()) return@runCatching emptyMap()
            val o = JSONObject(f.readText(Charsets.UTF_8))
            val recent = recalls.filterValues { it.lastAt > 0L && now - it.lastAt <= 7 * 86_400_000L }.keys
            if (recent.isEmpty()) return@runCatching emptyMap()
            val out = HashMap<Long, Double>()
            o.keys().asSequence().forEach { key ->
                val sep = key.indexOf('|')
                if (sep <= 0) return@forEach
                val a = key.substring(0, sep).toLongOrNull() ?: return@forEach
                val b = key.substring(sep + 1).toLongOrNull() ?: return@forEach
                val rec = o.optJSONObject(key) ?: return@forEach
                val decay = Math.pow(0.5, (now - rec.optLong("at", 0L)).toDouble() / (30 * 86_400_000.0))
                val strength = rec.optInt("n", 0) * decay
                if (strength < 2.0) return@forEach
                val add = (strength * 0.01).coerceAtMost(0.03)
                if (a in recent) out[b] = (out[b] ?: 0.0) + add
                if (b in recent) out[a] = (out[a] ?: 0.0) + add
            }
            out
        }.getOrDefault(emptyMap())

    /**
     * 索引裁剪（他的"条目截断"）：超过 [MAX_ENTRIES] 条时，按"重要度低 + 最旧"丢一部分。
     *
     * **只裁索引，不删正文** —— 原文是唯一真相；索引只是"哪一段值得想"的目录，
     * 目录可以变短，正文一行不动。
     */
    fun pruneIfNeeded() {
        val f = file ?: return
        if (!f.exists()) return
        runCatching {
            val lines = f.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
            if (lines.size <= MAX_ENTRIES) return
            val keep = lines
                .mapNotNull { line -> runCatching { JSONObject(line) to decode(JSONObject(line)) }.getOrNull() }
                // 钉住的永远不裁（承诺/里程碑），其余按重要度保留
                .sortedWith(compareByDescending<Pair<JSONObject, IndexEntry>> { it.second.pinned }
                    .thenByDescending { it.second.importance })
                .take(MAX_ENTRIES)
                .sortedBy { it.second.ts }
                .map { it.first.toString() }
            f.writeText(keep.joinToString("\n") + "\n", Charsets.UTF_8)
        }
    }
    /** 每段的召回次数（他的"复习"）：单独存一个小 json，不改写 jsonl */
    private val recallsFile: File by lazy { File(base, "recalls_${file?.name ?: "v1"}") }

    fun recalls(): Map<Long, RecallStat> = runCatching {
        val f = File(base, (file?.name ?: return@runCatching emptyMap())
            .replace("index_", "recalls_").replace(".jsonl", ".json"))
        if (!f.exists()) return@runCatching emptyMap()
        val o = JSONObject(f.readText(Charsets.UTF_8))
        o.keys().asSequence().associate { key ->
            val v = o.opt(key)
            // 兼容旧格式：直接存 int 时当作 count，lastAt 记 0
            if (v is Number) key.toLong() to RecallStat(v.toInt(), 0L)
            else key.toLong() to RecallStat(
                count = (v as JSONObject).optInt("n", 0),
                lastAt = (v as JSONObject).optLong("at", 0L),
                boost = (v as JSONObject).optDouble("b", 0.0),
                boostAt = (v as JSONObject).optLong("bt", 0L),
                suppressedAt = (v as JSONObject).optLong("sp", 0L),
            )
        }
    }.getOrDefault(emptyMap())

    /** 这段被想起来了：计数 +1，并记下"最后一次被想起"的时间 */
    fun bumpRecall(eventStart: Long, now: Long = System.currentTimeMillis()) = runCatching {
        val f = File(base, (file?.name ?: return@runCatching Unit)
            .replace("index_", "recalls_").replace(".jsonl", ".json"))
        f.parentFile?.mkdirs()
        val o = if (f.exists()) JSONObject(f.readText(Charsets.UTF_8)) else JSONObject()
        val old = o.opt(eventStart.toString())
        val count = when (old) {
            is Number -> old.toInt()
            is JSONObject -> old.optInt("n", 0)
            else -> 0
        }
        val prevBoost = when (old) {
            is JSONObject -> old.optDouble("b", 0.0)
            else -> 0.0
        }
        val prevBoostAt = (old as? JSONObject)?.optLong("bt", 0L) ?: 0L
        val prevSp = (old as? JSONObject)?.optLong("sp", 0L) ?: 0L
        o.put(eventStart.toString(), JSONObject().apply {
            put("n", count + 1)
            put("at", now)
            // 被想起一次 +0.015（他的"重要度强化"），带上衰减后的旧值
            val carried = if (prevBoostAt > 0L) {
                prevBoost * Math.pow(0.5, (now - prevBoostAt).toDouble() / (30 * 86_400_000.0))
            } else {
                prevBoost
            }
            put("b", (carried + 0.015).coerceAtMost(0.15))
            put("bt", now)
            if (prevSp > 0L) put("sp", prevSp)
        })
        f.writeText(o.toString(), Charsets.UTF_8)
    }
    fun clear() = runCatching { file?.delete() }

    private fun encode(e: IndexEntry): JSONObject = JSONObject().apply {
        put("ts", e.ts)
        put("topic", e.topicId)
        put("user", e.fromUser)
        e.speakerId?.let { put("speaker", it) }
        put("imp", e.importance)
        put("label", e.label)
        put("a", e.a); put("p", e.p); put("d", e.d)
        put("trust", e.trust); put("closeness", e.closeness)
        put("conflict", e.conflict); put("backlog", e.backlog)
        put("mood", e.mood)
        if (e.entities.isNotEmpty()) put("ent", JSONArray(e.entities))
        put("src", e.source)
        put("conf", e.confidence)
        if (e.pinned) put("pin", true)
        if (e.superseded) put("sup", true)
    }

    private fun decode(o: JSONObject): IndexEntry = IndexEntry(
        ts = o.optLong("ts"),
        topicId = o.optLong("topic"),
        fromUser = o.optBoolean("user"),
        speakerId = o.optString("speaker", "").ifBlank { null },
        importance = o.optDouble("imp", 0.0),
        label = o.optString("label", ""),
        a = o.optDouble("a", 0.3),
        p = o.optDouble("p", 0.0),
        d = o.optDouble("d", 0.5),
        trust = o.optDouble("trust", 0.5),
        closeness = o.optDouble("closeness", 0.3),
        conflict = o.optDouble("conflict", 0.0),
        backlog = o.optDouble("backlog", 0.0),
        mood = o.optDouble("mood", 0.0),
        entities = o.optJSONArray("ent")?.let { arr ->
            (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
        }.orEmpty(),
        source = o.optString("src", "user"),
        confidence = o.optDouble("conf", 1.0),
        pinned = o.optBoolean("pin", false),
        superseded = o.optBoolean("sup", false),
    )

    private fun sanitize(s: String) = s.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "v1" }

    companion object {
        const val SELF_WEIGHT = 0.9

        /** 索引最多留多少条（正文不删） */
        const val MAX_ENTRIES = 6000

        fun importance(a: Double, p: Double, trust: Double, fromUser: Boolean = true): Double {
            val raw = 0.25 + 0.35 * a + 0.25 * abs(p) + 0.15 * (1.0 - trust)
            val weighted = if (fromUser) raw else raw * SELF_WEIGHT
            return (weighted.coerceIn(0.0, 1.0) * 1000).roundToLong() / 1000.0
        }

        /** 承诺 / 里程碑 / 自我定义：不参与淘汰（他的"钉住"） */
        private val PIN_HINTS = listOf(
            "答应", "说好", "约好", "承诺", "保证", "第一次", "纪念", "生日",
            "我叫", "我的名字", "永远", "绝不",
        )

        fun isPinned(text: String): Boolean = PIN_HINTS.any { text.contains(it) }

        /** 置信：用户直说 1.0 / 她说的 0.85 / 补出来的历史 0.60 */
        fun confidenceOf(fromUser: Boolean, backfilled: Boolean = false): Double = when {
            backfilled -> 0.60
            fromUser -> 1.00
            else -> 0.85
        }

        /** 复习间隔（他的 1/2/4/7/15/30/60 天）：召回越多，下次越晚 */
        fun reviewIntervalDays(recalls: Int): Int = when (recalls.coerceIn(0, 6)) {
            0 -> 1; 1 -> 2; 2 -> 4; 3 -> 7; 4 -> 15; 5 -> 30; else -> 60
        }

        /** 层级（他的 hot/warm/cold）：近 + 重要 + 常被召回 = hot */
        fun layerOf(ageDays: Double, importance: Double, recalls: Int): String = when {
            ageDays <= 3 || importance >= 0.75 || recalls >= 3 -> "hot"
            ageDays <= 30 || importance >= 0.55 || recalls >= 1 -> "warm"
            else -> "cold"
        }

        /**
         * 软遗忘窗口：多久之后"只给提示、不再展开原文"。
         *
         * **和原文不同**：他的规则是"高中心度事件细节先淡、摘要留"，我们没有摘要，
         * 所以这里反过来——**越重要留得越久**（14 + 30×重要度 天，约 17~41 天）；
         * 钉住的（承诺/里程碑/自我定义）永不淡出。
         */
        fun fadeAfterDays(importance: Double, pinned: Boolean = false): Int {
            if (pinned) return 3650
            return (14 + 30 * importance).toInt().coerceIn(14, 44)
        }

        private val TIME_WORDS = listOf(
            "今天", "昨天", "明天", "前天", "后天", "上周", "这周", "下周", "上个月", "这个月",
            "去年", "今年", "早上", "上午", "中午", "下午", "晚上", "深夜", "凌晨",
            "周一", "周二", "周三", "周四", "周五", "周六", "周日", "周末",
        )

        fun entities(text: String, knownNames: List<String> = emptyList()): List<String> {
            if (text.isBlank()) return emptyList()
            val out = LinkedHashSet<String>()
            knownNames.filter { it.isNotBlank() && text.contains(it) }.forEach { out += it }
            TIME_WORDS.filter { text.contains(it) }.forEach { out += it }
            Regex("\\d{1,4}").findAll(text).take(3).forEach { out += it.value }
            return out.take(6).toList()
        }

        fun byTopic(entries: List<IndexEntry>): List<TopicMemory> =
            entries.groupBy { it.topicId }
                .map { (topicId, list) -> aggregate(topicId, list) }
                .sortedBy { it.startTs }

        /** 与 TopicExporter.segments() 同一套切法，canvas 才能引用到真实文件 */
        fun byRanges(entries: List<IndexEntry>, ranges: List<Pair<Long, Long>>): List<TopicMemory> =
            ranges.mapNotNull { (start, end) ->
                val list = entries.filter { it.ts in start..end }
                if (list.isEmpty()) null else aggregate(start, list)
            }

        private fun aggregate(topicId: Long, list: List<IndexEntry>): TopicMemory {
            val sorted = list.sortedBy { it.ts }
            val peak = sorted.maxByOrNull { it.importance } ?: sorted.last()
            return TopicMemory(
                topicId = topicId,
                startTs = sorted.first().ts,
                endTs = sorted.last().ts,
                importance = peak.importance,
                label = peak.label,
                a = peak.a, p = peak.p, d = peak.d,
                trust = peak.trust, closeness = peak.closeness,
                conflict = peak.conflict, mood = peak.mood,
                messageCount = sorted.size,
                pinned = sorted.any { it.pinned },
            )
        }
    }
}
