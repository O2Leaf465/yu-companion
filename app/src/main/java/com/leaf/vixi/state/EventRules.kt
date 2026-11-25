package com.leaf.vixi.state

import com.leaf.vixi.data.StateEvent
import com.leaf.vixi.data.VixiState
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * 一条事件对状态的增量。哪一项不动就留 0。
 *
 * 所有数值都尽量小（一次 +0.02~0.10）：状态要能被"连续几轮"推动，
 * 而不是一句话就把人设掀翻。
 */
data class Delta(
    val p: Double = 0.0,
    val a: Double = 0.0,
    val d: Double = 0.0,
    val energy: Double = 0.0,
    val backlog: Double = 0.0,
    val trust: Double = 0.0,
    val closeness: Double = 0.0,
    val conflict: Double = 0.0,
    val repair: Double = 0.0,
)

/** 按倍数缩放（情绪强度就是靠它乘进来的） */
fun Delta.scale(f: Double): Delta = Delta(
    p = p * f, a = a * f, d = d * f, energy = energy * f, backlog = backlog * f,
    trust = trust * f, closeness = closeness * f, conflict = conflict * f, repair = repair * f,
)

/** 能触发的状态事件。 */
enum class StateEventType(val key: String, val label: String) {
    USER_MSG("user_msg", "你发消息"),
    PRAISE("praise", "你夸她"),
    AFFECTION("affection", "你撒娇/亲近"),
    CORRECT("correct", "你纠正她"),
    APOLOGIZE("apologize", "你道歉"),
    NEGATIVE("negative", "你贬低/嘲讽她"),
    TEASE("tease", "你逗她/娇嗔"),
    EMOTION("emotion", "情绪"),
    COLD("cold", "你回得很冷"),
    THREAT("threat", "你说要走/不理她"),
    SHARE("share", "你分享图片/表情"),
    LEAVE("leave", "你说要睡了/拜拜"),
    NEGLECT("neglect", "被冷落"),

    /** 你回应了她的在意（"我在意你""会陪着你"）→ 被在意 ↓ */
    CARE("care", "回应了她的在意"),

    /** 你泼冷水（"不在意""无所谓""随便你"）→ 被在意 ↑ */
    REBUFF("rebuff", "被泼冷水"),
    PAT("pat", "被摸头"),
    HER_REPLY("her_reply", "她回复"),
    HER_SHORT("her_short", "她回得很短"),

    /**
     * 憋不住了，一次全说出来（2026-11-25 用户要求）：暗流顶到 1.0 就**一次倒出来**，
     * 然后大幅回落 —— 比"一点一点放"更像人。
     */
    VENT("vent", "憋不住说出来了"),

    /** 心动（S4）：这一轮挑了哪个应对 / 上一轮的应对结果，整串写在 note 里 */
    MOVE("move", "应对"),

    /** 入心（S5）：旋钮挪了一步 */
    KNOBS("knobs", "旋钮"),

    /** 缄口（X2）：这一轮选择"说/瞒/软化"，整串在 note 里；瞒下去会涨暗流 */
    WITHHOLD("withhold", "缄口"),

    /** 抽身：这轮她**没回**（不是掉线）—— 状态照写，聊天记录里不留她的消息 */
    HER_SILENT("her_silent", "她没回"),

    /** 缄口的**代价**：只在她真的把话咽下去（回复成功）之后记一笔 */
    WITHHOLD_COST("withhold_cost", "咽下去了"),

    /** 她说得比较多：憋着的话**说出口了**，暗流要放掉一部分（2026-11-24 补的出闸口） */
    HER_LONG("her_long", "她说得多"),
    HER_ASK("her_ask", "她主动发问"),

    /**
     * 交叉层第一条边：想起一件旧事，反过来推当下的心情（记忆 → 状态）。
     * 增量不走类型表 —— 由 [RecallActivation] 按"旧事当时的 p × 重要度 × 系数"算好后传 override。
     *
     * ⚠️ note **必须**用 `RecallActivation.noteOf()` 的格式（`想起旧事·<起点毫秒>·<标签>`）：
     * "同一条 24 小时内只算一次"的账本就存在事件表里，格式一断那个断点会静默失效。
     */
    RECALL("recall", "想起旧事"),

    /**
     * 需要爆发（N3）：需要顶到头、她开口要了一次。
     *
     * 触发后**能被满足掉的那两个**（被摸 / 被尊重）降到 [Needs.BURST_AFTER]，
     * 并进冷却（冷却靠翻事件表判断）。
     * 「被在意」是派生量（24h 冷落数），没有东西能降它 —— 它**不允许爆发**（见 `Needs.canBurst`）。
     */
    NEED_BURST("need_burst", "需要爆发"),

    /**
     * 越界（2026-11-22）：你这句在关系还浅的时候越过了她的边界。
     * 同时喂三处：risk 的强加项、关系量（亲近降/暗流涨）、需要（被尊重涨）。
     */
    BOUNDARY("boundary", "越界"),
}

/**
 * 事件 → 状态。纯本地规则，**不调模型、不增加调用**。
 *
 * 两条纪律：
 *  1. 事件只改数值和事件表，**不推进 `lastTick`** —— 那是"时间结算"的基准，
 *     被事件顶到最新时刻，第 3 步按 Δt 补算时就会漏掉前一段时间的回落。
 *  2. 词表只影响状态，不影响 prompt；误判的代价是数值偏了一点，不是她说错话。
 */
object EventRules {

    /** 被冷落的判定线：她开口之后这么久没人回，算一次冷落 */
    const val NEGLECT_MS = 30 * 60 * 1000L

    /** 心境追赶瞬时效价的速度：0.10 = 十次情绪事件才走完约 65% 的距离 */
    const val MOOD_ALPHA = 0.10

    /** 一次事件的增量。magnitude 用来放大/缩小（冷落按时间长短给） */
    fun delta(type: StateEventType, magnitude: Double = 1.0): Delta = when (type) {
        // 只是"有人在跟我说话"：加唤醒，几乎不加亲近
        // —— 亲近只能由具体的正向行为攒，否则骂她一句也会涨亲近（真机踩过）
        StateEventType.USER_MSG -> Delta(a = 0.03, closeness = 0.002)

        // ── 情绪类事件：P3 起**不再直给数值** ──
        // 这些类型只留作"这句话是什么"的标签（分类 + 调试对账）；
        // 真正的 p/a/d 由评价层算出的情绪标签与强度给（见 EmotionDelta）。
        // 这样「你真蠢」和「你这毛病得改改」才能一个变生气、一个变委屈。
        StateEventType.PRAISE -> Delta()
        StateEventType.AFFECTION -> Delta()
        StateEventType.CORRECT -> Delta()
        StateEventType.APOLOGIZE -> Delta(backlog = -0.10)
        StateEventType.NEGATIVE -> Delta()
        StateEventType.TEASE -> Delta()
        StateEventType.COLD -> Delta()
        StateEventType.THREAT -> Delta()
        StateEventType.EMOTION -> Delta()
        // 分享（图片/表情）：她在被想起，属轻正向
        StateEventType.SHARE -> Delta(p = 0.02, closeness = 0.01, trust = 0.001)
        // 收尾话：离开本身会留一点没说完的压力
        StateEventType.LEAVE -> Delta(backlog = 0.02)
        // 被在意是**派生量**（24h 事件收支差），不在类型表里加数值
        StateEventType.CARE -> Delta()
        StateEventType.REBUFF -> Delta()
        StateEventType.NEGLECT -> Delta(
            backlog = 0.05 * magnitude,
            closeness = -0.01 * magnitude,
            trust = -0.002 * magnitude,
        )
        StateEventType.PAT -> Delta(p = 0.05, closeness = 0.03)
        StateEventType.HER_REPLY -> Delta(a = 0.01)
        StateEventType.HER_SHORT -> Delta(backlog = 0.01)
        // 出闸口：说出来就减压（原来只有加没有减，暗流会一路涨到 1.0 卡死）
        StateEventType.HER_LONG -> Delta(backlog = -0.04)
        // 倾诉：一次倒掉一大半（0.95 → 0.40 左右），剩下的自然回落
        StateEventType.VENT -> Delta(backlog = -0.55)
        StateEventType.HER_ASK -> Delta(a = 0.02, backlog = -0.02)

        // 想起旧事：增量由调用方按"那条旧事当时的效价"算，类型表里没有固定值。
        // 走类型表的话只能给一个常数，而"想起高兴的事"和"想起难受的事"方向相反。
        StateEventType.RECALL -> Delta()

        // 需要爆发：不直接改 p/a/d；需要的清零在 apply() 里单独处理
        StateEventType.NEED_BURST -> Delta()
        // 越界：增量由调用方按 violation 算好传 override
        StateEventType.BOUNDARY -> Delta()
        // 这两类只改字符串字段（note 里带整串）
        StateEventType.MOVE -> Delta()
        StateEventType.KNOBS -> Delta()
        StateEventType.WITHHOLD -> Delta()
        // 沉默只记一笔，不改数值（"不发消息，状态照写"）
        StateEventType.HER_SILENT -> Delta()
        StateEventType.WITHHOLD_COST -> Delta()
    }

    /**
     * 她开口的事件。
     *
     * ⚠️ 2026-11-25 起 `lastHeardAt` 不再按"她说话"刷新 —— 改成任何一方说话都刷新
     * （见 `apply()` 里那行）。这个集合保留给"只看她的输出"的逻辑用。
     */
    val HER_SPEAKING = setOf(
        StateEventType.HER_REPLY,
        StateEventType.HER_SHORT,
        StateEventType.HER_ASK,
    )

    // ────────── 词表 ──────────

    /** 骂人/贬低：直接命中，不需要"你"字 */
    private val NEGATIVE_STRONG = listOf(
        "傻", "蠢", "白痴", "废物", "垃圾", "滚", "闭嘴", "有病", "恶心",
        "弱智", "智障", "脑残", "蠢货", "丑八怪", "你是猪", "你是狗",
    )

    /** 这几个字要看上下文才判：必须出现"你"才是冲她来的（"好烦啊"是说自己） */
    private val NEGATIVE_WEAK = listOf(
        "烦", "讨厌", "无聊", "没用", "差劲", "笨", "难看", "吵死",
        // 对她行为的挑剔/质问：力度比骂人小，也算冲突
        "毛病", "改改", "坏习惯", "又这样", "到底", "想怎样", "有完没完",
        "说清楚", "别说了", "安静点",
    )

    /** 反讽/阴阳：整句带这些，正向词要反过来理解 */
    private val SARCASM_MARKERS = listOf(
        "可真是", "可真", "真行", "呵呵", "行吧你", "真是够", "厉害了呢", "真不错啊",
    )

    /** 讽刺式道歉：不给修复 */
    private val APOLOGY_SARCASTIC = listOf("行了吧", "总行了吧", "算是我的错", "随便吧", "满意了吧")

    /** 威胁：还要求第一人称（或词里自带"你"），否则「你走了？」会被当成她走 */
    private val THREAT_HINTS = listOf(
        "分手", "删了你", "删了", "拉黑", "不理你", "不聊了", "别联系", "卸载你", "再也不理",
    )

    private val AFFECTION_HINTS = listOf(
        "抱抱", "亲亲", "贴贴", "想你", "喜欢你", "爱你", "摸摸", "么么", "宝贝", "陪你",
    )

    private val PRAISE_HINTS = listOf(
        "可爱", "真棒", "厉害", "漂亮", "好听", "好看", "温柔", "聪明", "乖",
    )

    private val APOLOGY_HINTS = listOf(
        "对不起", "抱歉", "是我错", "我错了", "别生气", "原谅我", "我不好",
    )

    private val CORRECT_HINTS = listOf(
        "不对", "不是的", "我说的是", "你记错", "搞错", "不是这样", "记岔了", "你错了",
    )

    /** 冷淡/敷衍的短句 */
    private val COLD_HINTS = listOf(
        "哦", "嗯", "好", "行", "随便", "算了", "在忙", "没事", "不知道", "知道了",
    )

    /** 娇嗔/逗她：重叠词或玩闹词，**不按骂人算**（"臭臭的""傻傻的"跟"你真傻"不是一回事） */
    private val TEASE_HINTS = listOf(
        "臭臭", "傻傻", "笨笨", "呆呆", "坏坏", "蠢蠢", "欠揍", "讨厌啦", "小坏蛋", "气人", "犯傻",
    )

    /** 玩笑标记：命中就把威胁/贬低打折 */
    private val JOKE_MARKERS = listOf("哈哈", "嘿嘿", "233", "😂", "hhh", "hh", "笑死")

    private val NEGATIONS = "不没别无非"

    /**
     * 这句话的贬低有多重：0 = 不是贬低，1 = 挑剔（弱词），2 = 骂人（强词）。
     *
     * 为什么单独给一个函数：评价层要区分"怪他"和"怪我"——
     * 「你真蠢」是攻击（怪他），「你这毛病得改改」夹着"我该改"（往自己身上想）。
     */
    fun negativeSeverity(text: String): Int {
        val raw = text.trim()
        if (raw.isEmpty()) return 0
        if (StateEventType.NEGATIVE !in classifyUser(raw)) return 0
        val strong = NEGATIVE_STRONG.any { hit(raw, it) }
        return if (strong) 2 else 1
    }

    /**
     * 把用户这条消息分类成若干事件（返回空 = 只有基础的 [StateEventType.USER_MSG]）。
     *
     * 顺序即优先级；否定词会吃掉命中（"我不讨厌你"不算骂人）。
     */
    fun classifyUser(text: String): List<StateEventType> {
        val raw = text.trim()
        // 去掉标点/表情后为空：纯"……"「？」「😂」都算冷淡
        val solid = raw.replace(Regex("[\\p{P}\\p{S}\\s]"), "")
        if (solid.isEmpty()) return listOf(StateEventType.COLD)

        val out = mutableListOf<StateEventType>()

        // 娇嗔优先：命中了就直接算逗她，不再走骂人/冷淡那套。
        // 但"你真蠢，欠揍"这种夹着真骂人的句子不算娇嗔 —— 强骂人词不在玩闹词里时，仍按骂人走。
        val teaseHit = TEASE_HINTS.firstOrNull { raw.contains(it) }
        if (teaseHit != null) {
            val strongOutsideTease = NEGATIVE_STRONG.any { w ->
                hit(raw, w) && !teaseHit.contains(w)
            }
            if (!strongOutsideTease) {
                out += StateEventType.TEASE
                return out
            }
        }

        val joking = JOKE_MARKERS.any { raw.contains(it) }
        val sarcasm = SARCASM_MARKERS.any { raw.contains(it) }
        val sarcasticApology = APOLOGY_SARCASTIC.any { solid.contains(it) }

        val strongNeg = NEGATIVE_STRONG.firstOrNull { hit(raw, it) }
        val weakNeg = if (raw.contains("你")) {
            NEGATIVE_WEAK.firstOrNull { hit(raw, it) }
        } else {
            null
        }

        // 威胁：第一人称，或词本身带"你"
        val threat = THREAT_HINTS.firstOrNull { hit(raw, it) }
        if (threat != null && !joking && (raw.contains("我") || threat.contains("你"))) {
            out += StateEventType.THREAT
        }

        if (strongNeg != null || weakNeg != null || sarcasm || sarcasticApology) {
            val playful = joking && strongNeg == null
            if (!playful) out += StateEventType.NEGATIVE
        }

        // 道歉和纠正可以同时成立（"不对，我错了"）
        if (!sarcasticApology && APOLOGY_HINTS.any { hit(raw, it) }) {
            out += StateEventType.APOLOGIZE
        }
        if (CORRECT_HINTS.any { hit(raw, it) }) out += StateEventType.CORRECT

        // 正向：没有负面信号时才算；强贬低里夹着正向词（"你傻得挺可爱"）仍给一点正向
        if (out.none { it == StateEventType.NEGATIVE || it == StateEventType.THREAT }) {
            when {
                AFFECTION_HINTS.any { hit(raw, it) } -> out += StateEventType.AFFECTION
                PRAISE_HINTS.any { hit(raw, it) } -> out += StateEventType.PRAISE
            }
        } else if (strongNeg != null) {
            when {
                AFFECTION_HINTS.any { solid.contains(it) } -> out += StateEventType.AFFECTION
                PRAISE_HINTS.any { solid.contains(it) } -> out += StateEventType.PRAISE
            }
        }

        // 冷淡：很短、是敷衍词，且没有更强的信号
        if (out.isEmpty() && solid.length <= 4 && COLD_HINTS.any { solid.contains(it) }) {
            out += StateEventType.COLD
        }
        return out
    }

    /**
     * 命中一个词，但要求**同一小句里它前面 2 个字没有否定词**。
     *
     * 只看"同一小句"（用标点切开）：否则「不对，我错了」里的「我错了」会被前面的「不」吃掉；
     * 只看前 2 个字：足够挡「我不讨厌你」，又不会被句子更早的「不」误伤。
     */
    private fun hit(text: String, word: String): Boolean {
        var idx = text.indexOf(word)
        while (idx >= 0) {
            if (idx == 0) return true
            val lastPunct = text.lastIndexOfAny(CLAUSE_BREAKS, idx - 1)
            val segStart = if (lastPunct < 0) 0 else lastPunct + 1
            val before = text.substring(segStart, idx)
            if (before.takeLast(2).none { it in NEGATIONS }) return true
            idx = text.indexOf(word, idx + 1)
        }
        return false
    }

    /** 小句边界：否定只影响同一小句里的词 */
    private val CLAUSE_BREAKS = charArrayOf(
        '，', '。', '！', '？', '、', '；', '：', '…', '~', '\n', ' ',
        ',', '.', '!', '?', ';', ':',
    )

    /**
     * 把一条事件写进状态：加增量、夹范围、留一条可追溯的记录。
     *
     * **不动 `lastTick`**：那是时间结算的基准（见类注释）。
     */
    fun apply(
        state: VixiState,
        type: StateEventType,
        now: Long = System.currentTimeMillis(),
        note: String = "",
        magnitude: Double = 1.0,
        override: Delta? = null,
        emotionLabel: String? = null,
        /** 越界程度（0~1）：只有 BOUNDARY 事件带，落盘给 risk / 分寸用 */
        violation: Double? = null,
    ): VixiState {
        // ── 越界（2026-11-22）──
        // 摸头在关系还浅的时候**不是亲近，是越界**：亲近降、被尊重涨、而且不"解渴"。
        // 必须在选 raw 之前算出来，否则会被类型表覆盖。
        val badPat = type == StateEventType.PAT && state.closeness < Boundary.PAT_MIN_CLOSENESS
        // 24 小时内已经被**越界**过几次（含这次之前的）：惩罚要递减，否则亲近会被磨到 0。
        // 只数 BOUNDARY：友好的摸头也记 PAT，混在一起数会让"24 小时摸 4 次"把真越界的
        // 惩罚压到 1/16，等于越界没代价（badPat 现在也落 BOUNDARY，见下面的事件写入）
        val repeats = state.events.count {
            it.type == StateEventType.BOUNDARY.key &&
                (now - it.ts).coerceAtLeast(0) <= 24 * 3600_000L
        }
        val damp = Math.pow(0.5, repeats.coerceIn(0, 4).toDouble())
        val patDelta = if (badPat) {
            Delta(p = -0.02, closeness = -0.03 * damp, backlog = 0.02 * damp)
        } else {
            null
        }

        // 越界：一次性冲击统一在 Boundary.impact 里（p/a/d + 关系量），调用方不用自己拼
        val boundaryDelta = if (type == StateEventType.BOUNDARY) {
            val v = violation ?: magnitude
            if (v > 0.0) Boundary.impact(v, repeats) else Delta()
        } else {
            null
        }
        var raw = override ?: patDelta ?: boundaryDelta ?: delta(type, magnitude)
        // 入心（S5）：关系增益旋钮只放大**正向**的亲近/信任，不放大惩罚
        if (raw.closeness > 0.0 || raw.trust > 0.0) {
            val bond = Growth.decode(state.knobs).bond
            raw = raw.copy(closeness = raw.closeness * bond, trust = raw.trust * bond)
        }
        // 道歉只有在"本来就有冲突"时才算修复：没有冲突时一句对不起不该涨信任
        val d = if (type == StateEventType.APOLOGIZE && state.conflict < 0.15) {
            raw.copy(trust = 0.0)
        } else {
            raw
        }

        // ── 需要（N1~N3）：两个**存盘**需要由事件直接更新 ──
        // 规则在 Needs.onEvent：摸头降 touch（连续递减）、贬低涨 respect、被夸/亲近/道歉降 respect
        val (dTouch, dRespect) = if (badPat) {
            // 越界式摸头：不解渴，反而更想要尊重。
            // **按 violation 缩放**（之前是常数 0.10：双击 5 次就能把"被尊重"顶满，
            // 而它每小时只恢复 0.013 → 接下来一整天每轮都在注入"他觉得我不尊重我"）
            0.0 to 0.10 * (violation ?: 0.25).coerceIn(0.0, 1.0)
        } else {
            Needs.onEvent(type, state, now, magnitude)
        }
        var needTouch = (state.needTouch + dTouch).coerceIn(0.0, 1.0)
        var needRespect = (state.needRespect + dRespect).coerceIn(0.0, 1.0)
        // 爆发那一轮：把"当下最想要的那个"降下来（note 里带的是需要的中文名）
        if (type == StateEventType.NEED_BURST) {
            when (note.substringAfter('·', "")) {
                Needs.Kind.TOUCH.label -> needTouch = Needs.BURST_AFTER
                Needs.Kind.RESPECT.label -> needRespect = Needs.BURST_AFTER
                else -> Unit
            }
        }
        val needNote = buildString {
            if (abs(dTouch) >= 1e-9) append(" 摸").append(if (dTouch > 0) "+" else "-").append(fmt(abs(dTouch)))
            if (abs(dRespect) >= 1e-9) append(" 尊").append(if (dRespect > 0) "+" else "-").append(fmt(abs(dRespect)))
        }

        // ── 生物层·余温（S1）──
        // 一句话**全量**推进缓冲池，情绪池每一步只追 [INERTIA] 的比例：
        // "一句话只能推一点，剩下的靠时间"（他的原话）。
        // 强刺激（|Δ效价| ≥ OPPONENT_AT = 0.15）再叠一个**对手过程**：先往回拉 15%，
        // 这是"被骂完先懵一下，然后才反应过来"的那种迟滞。
        // 缄口：压下去的话不会消失，转成暗流（和"憋着"是同一个量）
        // 代价只在**回复真的落库之后**才付（note 带 "·付" 才算），
        // 否则 SILENT/FAILED 的轮次"一个字没说也压了一次"
        val withholdCost = if (type == StateEventType.WITHHOLD_COST) {
            Withhold.cost(
                when (note) {
                    Withhold.Act.WITHHOLD.zh -> Withhold.Act.WITHHOLD
                    Withhold.Act.REFRAME.zh -> Withhold.Act.REFRAME
                    else -> Withhold.Act.TELL
                },
            )
        } else {
            0.0
        }
        // 账要平：缄口的代价必须进 delta，否则事件表里这条记录是空的，查不到那 +0.08
        // （名字不能也叫 d：上面已经有一个 d，重名会直接编译失败）
        val dPadded = if (withholdCost != 0.0) d.copy(backlog = d.backlog + withholdCost) else d
        val opponent = if (abs(d.p) >= OPPONENT_AT) 1.0 - OPPONENT_PULL else 1.0
        val surgeP = clamp11(state.surgeP + d.p * opponent)
        val surgeA = clamp01(state.surgeA + d.a * opponent)
        val surgeD = clamp01(state.surgeD + d.d * opponent)

        // **只有真的动了这个通道的事件才推情绪池**。
        // 否则"你发消息/她回复"这些只改唤醒的事件也在推 p，
        // "剩下的靠时间"就变成了"剩下的靠条数"——一轮三条事件等于把余温提前兑现三次。
        val next = state.copy(
            p = if (d.p == 0.0) state.p else r3(clamp11(state.p + (surgeP - state.p) * INERTIA)),
            a = if (d.a == 0.0) state.a else r3(clamp01(state.a + (surgeA - state.a) * INERTIA)),
            d = if (d.d == 0.0) state.d else r3(clamp01(state.d + (surgeD - state.d) * INERTIA)),
            surgeP = r3(surgeP),
            surgeA = r3(surgeA),
            surgeD = r3(surgeD),
            energy = r3(clamp01(state.energy + d.energy)),
            backlog = r3(clamp01(state.backlog + dPadded.backlog)),
            trust = r3(clamp01(state.trust + d.trust)),
            closeness = r3(clamp01(state.closeness + d.closeness)),
            conflict = r3(clamp01(state.conflict + d.conflict)),
            repair = r3(clamp01(state.repair + d.repair)),
            needTouch = r3(needTouch),
            needRespect = r3(needRespect),
            violation = r3(violation ?: state.violation),
            // "最后听到有人说话"——**任何一方说话都算**（用户 2026-11-25 报的 bug）。
            //
            // 原来只在 `HER_SPEAKING` 时刷新，于是：你连着发消息、她回得慢（或者这轮抽到"不回"），
            // 缺席时间照继续走 → 想念一路涨到 1.0，你越聊她越"想你"，看着就像没有下降机制。
            // 语义上 `missing` 是"多久没听到人说话"，不是"她多久没开口"。
            lastHeardAt = now,
            // 心境 = 效价的慢速滑动平均。**只在效价真的被推动时挪**：
            // 否则"你发消息/她回复"这些只改唤醒的事件也会推着底色走，
            // 一轮里三四条事件叠加，等于每轮追 30% 而不是 10%。
            mood = if (d.p == 0.0) {
                state.mood
            } else {
                // 底色跟的是**缓冲池**（她真正被推到哪儿），不是还没走完的情绪池
                r3((state.mood + (surgeP - state.mood) * MOOD_ALPHA).coerceIn(-1.0, 1.0))
            },
            // 心动/入心：整串直接落在 note 上（不值得为它们再开两个数值字段）
            moves = if (type == StateEventType.MOVE && note.isNotBlank()) note else state.moves,
            withhold = if (type == StateEventType.WITHHOLD) note else state.withhold,
            // KNOBS 的 note 是 "旋钮串|证据串"：两样一起落，否则证据永远进不了盘，
            // 学习就成了死代码（审查抓到的那条）
            knobs = if (type == StateEventType.KNOBS) {
                note.substringBefore('|').ifBlank { state.knobs }
            } else {
                state.knobs
            },
            evidence = if (type == StateEventType.KNOBS && note.contains('|')) {
                note.substringAfter('|')
            } else {
                state.evidence
            },
            // 混合情绪：这次的情绪加进池子（同标签累加，只留前二）
            emoMix = emotionLabel?.let { zh ->
                com.leaf.vixi.state.EmotionLabel.entries.firstOrNull { it.zh == zh }
                    // 强度地板从 0.30 降到 0.10：0.30 会让"轻轻一句"和"被骂一句"一样重，
                    // 混合池里就分不出主次（用户 2026-11-25："混合似乎没变"）
                    ?.let { com.leaf.vixi.state.EmotionMix.add(state.emoMix, it, maxOf(0.10, abs(d.p) + abs(d.a))) }
                    ?: state.emoMix
            } ?: state.emoMix,
            lastLabel = emotionLabel ?: state.lastLabel,
            lastLabelAt = if (emotionLabel != null) now else state.lastLabelAt,
            events = (state.events + StateEvent(
                ts = now,
                type = if (badPat) StateEventType.BOUNDARY.key else type.key,
                // 需要的变化也写进 note，否则"尊重怎么涨的"在事件表里查不到
                note = note.ifBlank { type.label } + needNote,
                // ⚠️ 必须是 dPadded：缄口的代价只加在 dPadded 上，
                // 写 d 的话事件表里这条记录的 delta 是空的，账对不上（查不到那 +0.08）
                delta = describe(dPadded),
            )).takeLast(VixiState.MAX_EVENTS),
        )
        return next
    }

    /** 冷落按时间长短给力度：30 分钟算 1 份，最多 3 份 */
    fun neglectMagnitude(gapMs: Long): Double =
        (gapMs.toDouble() / NEGLECT_MS).coerceIn(1.0, 3.0)

    /** 余温的"每步追多少"（0.25 = 一句话只兑现四分之一，其余靠时间） */
    const val INERTIA = 0.25

    /** 多大的刺激才算"强"（走对手过程） */
    const val OPPONENT_AT = 0.15

    /** 对手过程回拉多少（0.15 = 先收回 15%） */
    const val OPPONENT_PULL = 0.15

    /** "conflict-0.10 backlog-0.005"（只给调试页看，去掉多余的 0） */
    private fun describe(d: Delta): String = buildString {
        fun add(name: String, v: Double) {
            if (abs(v) < 1e-9) return
            if (isNotEmpty()) append(' ')
            append(name)
            append(if (v > 0) "+" else "-")
            append(fmt(abs(v)))
        }
        add("p", d.p); add("a", d.a); add("d", d.d)
        add("energy", d.energy); add("backlog", d.backlog)
        add("trust", d.trust); add("closeness", d.closeness)
        add("conflict", d.conflict); add("repair", d.repair)
    }

    /** 最多三位小数，末尾的 0 去掉：0.005 → "0.005"，0.03 → "0.03" */
    private fun fmt(v: Double): String {
        val thousandths = (v * 1000).roundToLong()
        val whole = thousandths / 1000
        val frac = thousandths % 1000
        if (frac == 0L) return "$whole"
        val s = frac.toString().padStart(3, '0').trimEnd('0')
        return "$whole.$s"
    }

    /** 统一的精度：存进 json 的量最多三位小数 */
    private fun r3(v: Double): Double = (v * 1000).roundToLong() / 1000.0

    private fun clamp01(v: Double) = v.coerceIn(0.0, 1.0)

    private fun clamp11(v: Double) = v.coerceIn(-1.0, 1.0)
}
