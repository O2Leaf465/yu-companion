package com.leaf.vixi.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 一条状态事件：谁在什么时候，把哪个量改了多少。
 *
 * 状态必须能追溯到事件（调试页要能回答"她为什么是这个状态"），
 * 所以每次改动都留一条，而不是只存最终数值。
 */
data class StateEvent(
    val ts: Long,
    val type: String,
    /** 给人看的一句备注，例如「用户道歉」「她的回复只有 2 个字」 */
    val note: String = "",
    /** 改了哪些量，形如 "conflict-0.10 p+0.05"（只在调试页出现） */
    val delta: String = "",
)

/**
 * 一个角色的状态。**按角色存，不按会话存**：同一个人单聊和群聊共用一份。
 *
 * 第一版字段刻意少：够支撑"情绪 + 体力 + 暗流 + 关系四量"就行，
 * 之后要加的量都放 events 里做推导，不要每加一个功能就往这里塞字段。
 */
data class VixiState(
    /** 是否启用。没启用的角色整份状态不参与任何计算与注入（鱼现在就是关的） */
    val enabled: Boolean = false,
    /** 效价：-1 很糟 … +1 很好 */
    val p: Double = 0.0,
    /** 唤醒：0 平静 … 1 激动（生气和兴奋都高） */
    val a: Double = 0.30,
    /** 支配：0 被动（委屈/顺从）… 1 主动（理直气壮） */
    val d: Double = 0.50,
    /** 长期底色：衰减的回归目标，单次事件不会改它 */
    val baseP: Double = 0.0,
    val baseA: Double = 0.30,
    val baseD: Double = 0.50,
    /** 体力 0…1 */
    val energy: Double = 0.80,
    /** 暗流：想说但没说出口的累积量，只增不减（靠表达或原谅才清） */
    val backlog: Double = 0.0,
    val trust: Double = 0.30,
    val closeness: Double = 0.20,
    val conflict: Double = 0.0,
    val repair: Double = 0.0,
    /** 最近的事件，最多留 [MAX_EVENTS] 条 */
    val events: List<StateEvent> = emptyList(),
    /** 上次算衰减的时间戳：读状态时用它补算，不用后台定时器。**只有"结算"能推进它** */
    val lastTick: Long = 0L,
    /** 她最后一次开口的时间：冷落判定的起点（按角色存，群里说的也算） */
    val lastHeardAt: Long = 0L,
    /** 心境：效价的慢速滑动平均（−1..1），比瞬时情绪慢得多，是"底色" */
    val mood: Double = 0.0,
    /** 最近一次情绪标签（落盘：重启后调试页和心境都要能对上） */
    val lastLabel: String = "",
    val lastLabelAt: Long = 0L,
    /** 需要·被摸（皮肤饥渴）：随时间涨，摸头降。见 `state/Needs.kt` */
    val needTouch: Double = 0.35,
    /** 需要·被尊重：被贬低涨；长时间不辱骂缓慢恢复、被夸加速恢复 */
    val needRespect: Double = 0.15,
    /**
     * 缓冲池（生物层·余温）：一句话**全量**先落在这里，情绪池每步只追 25%。
     *
     * 为什么要两池：一句话落在不在意的人身上只是一阵风；落在她身上，要先过"在意"这道加重，
     * 然后只能推一点，剩下的靠时间自己走完（他的原话："不是那句话重，是她在意，才重"）。
     */
    // 默认**等于当前情绪**（"已经到账、没有余温"的状态）。
    // 如果默认 0，代码里 new 出来的状态会被缓冲池往回拽到 0。
    val surgeP: Double = p,
    val surgeA: Double = a,
    val surgeD: Double = d,

    /**
     * 负荷（生物层·压痕，0~1）：短期压力消化不掉的部分沉在这里。
     * 负荷越高，恢复越慢、越容易累，最后触发"保护"——话说得更短、只说没事。
     */
    val load: Double = 0.0,

    /**
     * 想念（生物层·牵挂，0~1）：`1 − e^(−缺席/18h)`，重逢后按 4 小时半衰回落（有滞后）。
     */
    val missing: Double = 0.0,

    /** 抽身（不回）开关：默认开；关掉她就永远会回 */
    val silenceOn: Boolean = true,

    /**
     * 「晚点回」（DELAY）单独开关：默认**关**。
     *
     * 用户 2026-11-25 实测后要分开：不回（SILENT）可以留，但"隔 15 秒才回"在真机上
     * 只会让人觉得 App 卡住了（有排队消息时不拖，但单发一条仍会静默 15 秒）。
     * 默认关掉，想要那种"她慢半拍"的体感再去调试页打开。
     */
    val delayOn: Boolean = false,

    /** 上一轮的"缄口"动作（说/瞒/软化），只驱动提示行和暗流代价 */
    val withhold: String = "",

    /** 混合情绪池："委屈:0.55,生气:0.30"（见 `EmotionMix`） */
    val emoMix: String = "",

    /** 生物层·心动（S4）：三个应对的胜负计数 + 上一轮挑的那个（见 `Strategy.encode`） */
    val moves: String = "",

    /** 生物层·入心（S5）的开关：**默认关**，没数据时不动 */
    val learnOn: Boolean = false,

    /** 三个旋钮（表达强度/礼貌阈值/关系增益），"1,1,1" = 没学过 */
    val knobs: String = "1.000,1.000,1.000",

    /** 每种信号的连续证据数（同一个方向攒够 3 次才挪一步） */
    val evidence: String = "",

    /**
     * 上一轮的越界程度（0~1）。存盘是为了让"分寸"到处都能读到同一个数 ——
     * 调试页、PromptBuilder、需要层用的都是它，不各算一遍。
     */
    val violation: Double = 0.0,
) {
    companion object {
        const val MAX_EVENTS = 400
    }
}

/** 待写入的一条事件（批量提交用：一次读、一次写，避免一条消息触发 5 次整份读写） */
data class PendingEvent(
    val type: com.leaf.vixi.state.StateEventType,
    val note: String = "",
    val magnitude: Double = 1.0,
    val now: Long = System.currentTimeMillis(),
    /** 显式增量：情绪事件由"标签+强度"算好后直接给，不走类型表 */
    val delta: com.leaf.vixi.state.Delta? = null,
    /** 情绪标签（只有 EMOTION 事件带，用来更新心境和落盘） */
    val label: String? = null,
    /** 越界程度（只有 BOUNDARY 事件带，落盘给 risk 用） */
    val violation: Double? = null,
)

/**
 * 状态文件的读写。路径 `files/state/<角色id>.json` —— 一个角色一份。
 *
 * 为什么不用数据库：量太小（一个人一个文件、几十个字段），
 * 而且出问题时能直接 `run-as cat` 看，比查表快。
 */
class StateStore(context: Context) {

    private val dir = File(context.filesDir, "state").apply { mkdirs() }
    private val lock = Any()

    private fun file(characterId: String) = File(dir, "$characterId.json")

    fun load(characterId: String): VixiState = synchronized(lock) {
        val f = file(characterId)
        if (!f.exists()) return VixiState()
        runCatching { parse(f.readText(Charsets.UTF_8)) }.getOrDefault(VixiState())
    }

    fun save(characterId: String, state: VixiState) {
        synchronized(lock) {
            runCatching {
                // 原子写：导出/调试线程可能同时在读
                val tmp = File(dir, "$characterId.json.tmp")
                tmp.writeText(encode(state).toString(), Charsets.UTF_8)
                if (!tmp.renameTo(file(characterId))) {
                    tmp.copyTo(file(characterId), overwrite = true)
                    tmp.delete()
                }
            }
        }
    }

    /**
     * 改一份状态：读 → 改 → 写，返回改完的。
     *
     * 整段在一个锁里：load 和 save 各自加锁时，中间有一个窗口，
     * 第 3 步把结算挪到 io 线程就会和主线程写丢事件。
     */
    fun update(characterId: String, block: (VixiState) -> VixiState): VixiState = synchronized(lock) {
        val next = block(load(characterId))
        save(characterId, next)
        next
    }

    /**
     * 批量提交事件：**一次读、一次写**。
     *
     * 一条消息会产生 2~5 条事件（你发消息 + 夸奖 + 表扬…），
     * 每条都整份读写会让真机掉帧（事件表上限 200 条 ≈ 24KB）。
     */
    fun applyEvents(characterId: String, batch: List<PendingEvent>): VixiState =
        synchronized(lock) {
            var s = load(characterId)
            if (batch.isEmpty()) return s
            batch.forEach { p ->
                val label = labelToForward(p)
                s = com.leaf.vixi.state.EventRules.apply(
                    s, p.type, p.now, p.note, p.magnitude, p.delta, label, p.violation,
                )
            }
            save(characterId, s)
            s
        }

    /**
     * 开关。**只在某个角色身上装新系统**就靠它：
     * 默认全员关闭，谁开了谁才参与计算和注入（现在只有 Nami 开）。
     */
    fun setEnabled(characterId: String, on: Boolean): VixiState =
        update(characterId) {
            val now = System.currentTimeMillis()
            // 开状态那一刻算"刚听见过她"：否则关了一周再开，
            // 第一轮就注入【想念】"好几天没好好说话了"，而那几天状态根本没在跑。
            it.copy(enabled = on, lastTick = now, lastHeardAt = if (on) now else it.lastHeardAt)
        }

    /**
     * 先补算时间衰减，再返回。
     *
     * 读状态的所有入口都应该走它（每轮请求前、打开调试页、重置后）——
     * 这样"隔了三小时再来"和"每秒算一次"得到同一个结果，而且不用启后台任务。
     */
    fun settle(characterId: String, now: Long = System.currentTimeMillis()): VixiState =
        synchronized(lock) {
            val before = load(characterId)
            val after = com.leaf.vixi.state.Decay.settle(before, now)
            // 数值只挪了千分之一以下就不写盘：调试页每 2 秒结算一次，
            // 否则会连着写上一两分钟（每次都是整份 json）。
            if (meaningfulChange(before, after)) save(characterId, after)
            after
        }

    /** 变化是否值得写盘（避免高频 tick 把整份文件反复重写） */
    private fun meaningfulChange(a: VixiState, b: VixiState): Boolean {
        if (a.events != b.events) return true
        if (a.lastLabel != b.lastLabel) return true
        fun moved(x: Double, y: Double) = kotlin.math.abs(x - y) > 1e-4
        // 需要走得比别慢：touch 每小时 +0.06，用 1e-4 会让调试页每 6 秒整份写盘一次
        fun movedNeed(x: Double, y: Double) = kotlin.math.abs(x - y) > 1e-3
        return moved(a.p, b.p) || moved(a.a, b.a) || moved(a.d, b.d) || moved(a.energy, b.energy) ||
            moved(a.mood, b.mood) || moved(a.repair, b.repair) || moved(a.trust, b.trust) ||
            moved(a.closeness, b.closeness) || moved(a.conflict, b.conflict) ||
            moved(a.backlog, b.backlog) ||
            movedNeed(a.needTouch, b.needTouch) || movedNeed(a.needRespect, b.needRespect) ||
            movedNeed(a.violation, b.violation) || movedNeed(a.surgeP, b.surgeP) ||
            movedNeed(a.surgeA, b.surgeA) || movedNeed(a.surgeD, b.surgeD) ||
            movedNeed(a.load, b.load) || movedNeed(a.missing, b.missing)
    }

    /**
     * 调试用：把数值和事件清回默认，**保留开关状态**。
     * 没有它，调规则的时候只能删文件（删完开关也丢了，得再点一次）。
     */
    fun reset(characterId: String): VixiState {
        val old = load(characterId)
        val fresh = VixiState(
            enabled = old.enabled,
            lastTick = System.currentTimeMillis(),
            // 学到的别清零：旋钮、策略统计、学习开关
            moves = old.moves,
            knobs = old.knobs,
            evidence = old.evidence,
            learnOn = old.learnOn,
        )
        save(characterId, fresh)
        return fresh
    }

    fun addEvent(characterId: String, event: StateEvent, apply: (VixiState) -> VixiState = { it }) {
        update(characterId) { s ->
            val merged = (s.events + event).takeLast(VixiState.MAX_EVENTS)
            apply(s.copy(events = merged))
        }
    }

    private fun encode(s: VixiState): JSONObject = JSONObject().apply {
        put("enabled", s.enabled)
        put("p", s.p); put("a", s.a); put("d", s.d)
        put("baseP", s.baseP); put("baseA", s.baseA); put("baseD", s.baseD)
        put("energy", s.energy)
        put("backlog", s.backlog)
        put("trust", s.trust); put("closeness", s.closeness)
        put("conflict", s.conflict); put("repair", s.repair)
        put("lastTick", s.lastTick)
        put("lastHeardAt", s.lastHeardAt)
        put("mood", s.mood)
        put("lastLabel", s.lastLabel)
        put("lastLabelAt", s.lastLabelAt)
        put("needTouch", s.needTouch)
        put("needRespect", s.needRespect)
        put("violation", s.violation)
        put("surgeP", s.surgeP)
        put("surgeA", s.surgeA)
        put("surgeD", s.surgeD)
        put("load", s.load)
        put("missing", s.missing)
        put("emoMix", s.emoMix)
        put("silenceOn", s.silenceOn)
        put("delayOn", s.delayOn)
        put("withhold", s.withhold)
        put("moves", s.moves)
        put("learnOn", s.learnOn)
        put("knobs", s.knobs)
        put("evidence", s.evidence)
        put("events", JSONArray().apply {
            s.events.forEach { e ->
                put(
                    JSONObject()
                        .put("ts", e.ts)
                        .put("type", e.type)
                        .put("note", e.note)
                        .put("delta", e.delta)
                )
            }
        })
    }

    private fun parse(text: String): VixiState {
        if (text.isBlank()) return VixiState()
        val o = JSONObject(text)
        val events = mutableListOf<StateEvent>()
        o.optJSONArray("events")?.let { arr ->
            (0 until arr.length()).forEach { i ->
                arr.optJSONObject(i)?.let { e ->
                    events += StateEvent(
                        ts = e.optLong("ts"),
                        type = e.optString("type"),
                        note = e.optString("note"),
                        delta = e.optString("delta"),
                    )
                }
            }
        }
        return VixiState(
            enabled = o.optBoolean("enabled", false),
            p = o.optDouble("p", 0.0),
            a = o.optDouble("a", 0.30),
            d = o.optDouble("d", 0.50),
            baseP = o.optDouble("baseP", 0.0),
            baseA = o.optDouble("baseA", 0.30),
            baseD = o.optDouble("baseD", 0.50),
            energy = o.optDouble("energy", 0.80),
            backlog = o.optDouble("backlog", 0.0),
            trust = o.optDouble("trust", 0.30),
            closeness = o.optDouble("closeness", 0.20),
            conflict = o.optDouble("conflict", 0.0),
            repair = o.optDouble("repair", 0.0),
            events = events.takeLast(VixiState.MAX_EVENTS),
            lastTick = o.optLong("lastTick", 0L),
            lastHeardAt = o.optLong("lastHeardAt", 0L),
            mood = o.optDouble("mood", 0.0),
            lastLabel = o.optString("lastLabel", ""),
            lastLabelAt = o.optLong("lastLabelAt", 0L),
            needTouch = o.optDouble("needTouch", 0.35),
            needRespect = o.optDouble("needRespect", 0.15),
            violation = o.optDouble("violation", 0.0),
            // 旧文件没有缓冲池：**初始成当前情绪**，否则缓冲池是 0 会把情绪往基线下面拽
            surgeP = (if (o.has("surgeP")) o.optDouble("surgeP", 0.0) else o.optDouble("p", 0.0))
                .coerceIn(-1.0, 1.0),
            surgeA = (if (o.has("surgeA")) o.optDouble("surgeA", 0.0) else o.optDouble("a", 0.3))
                .coerceIn(0.0, 1.0),
            surgeD = (if (o.has("surgeD")) o.optDouble("surgeD", 0.0) else o.optDouble("d", 0.5))
                .coerceIn(0.0, 1.0),
            load = o.optDouble("load", 0.0).coerceIn(0.0, 1.0),
            missing = o.optDouble("missing", 0.0).coerceIn(0.0, 1.0),
            emoMix = o.optString("emoMix", ""),
            silenceOn = o.optBoolean("silenceOn", true),
            // 老状态文件里没有这个键 → 默认**关**（用户实测后要的：晚回像卡死）
            delayOn = o.optBoolean("delayOn", false),
            withhold = o.optString("withhold", ""),
            moves = o.optString("moves", ""),
            learnOn = o.optBoolean("learnOn", false),
            knobs = o.optString("knobs", "1.000,1.000,1.000"),
            evidence = o.optString("evidence", ""),
        )
    }
}

/**
 * 这条事件该不该更新"最近情绪标签"，以及用什么词。
 *
 * 提成纯函数是有原因的：2026-11-23 真机上「越界」事件写进去了，状态行却还是上一轮的情绪 ——
 * 因为这里只给 EMOTION 转发 label，BOUNDARY 的「反感」被丢掉了。
 */
internal fun labelToForward(p: PendingEvent): String? = when (p.type) {
    com.leaf.vixi.state.StateEventType.EMOTION ->
        p.label ?: p.note.substringBefore('·').takeIf { it.isNotBlank() }
    com.leaf.vixi.state.StateEventType.BOUNDARY -> p.label
    else -> null
}
