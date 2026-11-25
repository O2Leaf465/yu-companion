package com.leaf.vixi.state

import com.leaf.vixi.data.StoredMessage
import com.leaf.vixi.data.VixiState

/**
 * 越界检测（2026-11-22 建，2026-11-23 细化）。
 *
 * 这是分寸公式里的"强加"项第一次真的被算出来 —— 它同时喂四处：
 *  1. `risk` 的强加项（她怎么说话）
 *  2. **情绪**：真的**反感**（p 掉、唤醒升、落一条"反感"标签）
 *  3. 关系量：亲近降、信任降、冲突涨、暗流涨
 *  4. 需要：被尊重 +。
 *
 * ## 分级（用户 2026-11-23 要求："没有细致的规定、增量太小"）
 *
 * 每条越界**按冒犯程度分三档**，再乘关系折扣 `距离 = 1 − 亲近`：
 *
 * | 类型 | 轻 | 中 | 重 |
 * |---|---|---|---|
 * | 称谓 | 宝宝/小可爱/乖乖 **0.35** | 宝贝/亲爱的 **0.55** | 老婆/老公/媳妇 **0.75** |
 * | 身体 | 摸头/牵手 **0.35** | 抱抱/贴贴 **0.55** | 亲亲/搂住 **0.75** |
 * | 命令 | 快点 **0.30** | 必须/不许 **0.55** | 给我 **0.65** |
 * | 隐私 | 年龄/住址 **0.40** | 收入/体重 **0.50** | — |
 * | 无视拒绝 | — | — | **0.80**（她刚说过"别"，你还来） |
 *
 * 合计夹 0~1。**一次"刚认识就叫老婆"≈0.6**，不再是原来那种 0.1 量级的挠痒。
 */
object Boundary {

    /** 每一类最多贡献多少（同类多词取最大，不叠加） */
    const val CAP_EACH = 0.80

    /** 摸头在亲近度低于这个值时算越界（用户 2026-11-22 点头：PAT 要有条件） */
    const val PAT_MIN_CLOSENESS = 0.25

    /** 称谓越界的"熟不熟"分界线 */
    const val NAME_MIN_CLOSENESS = 0.50

    /**
     * 到达这个程度就该"真的反感"（落一条反感情绪，状态行会说出来）。
     * 2026-11-23 从 0.25 降到 **0.15**：用户要求"轻度的越界也该直接反感"。
     */
    const val AVERSION_AT = 0.15

    /**
     * "不熟"的分界。**低于它，任何称谓/身体越界都直接引发反感**，不看分数 ——
     * 用户 2026-11-23 明确要的：关系还没到，一句"宝宝"或者伸手就该让她讨厌。
     */
    const val NOT_CLOSE = 0.45

    /** 这几类比"命令/隐私"更容易引发反感（身体和称呼是最直接的冒犯） */
    private val AVERSION_KINDS = setOf("称谓", "身体", "无视拒绝")

    /**
     * 这一轮该不该"真的反感"。
     *
     * 两条路（满足任一）：
     *  1. 越界程度 ≥ [AVERSION_AT]
     *  2. **关系还不熟**（< [NOT_CLOSE]）且踩到了称谓/身体/无视拒绝
     */
    fun shouldAvert(hits: List<Hit>, state: VixiState, violation: Double): Boolean =
        violation >= AVERSION_AT ||
            (state.closeness < NOT_CLOSE && hits.any { it.name in AVERSION_KINDS })

    data class Hit(val name: String, val amount: Double, val tier: String = "")

    // ────────── 分级词表（数值 = 这一档的基础分） ──────────

    private val CALLS_LIGHT = listOf("宝宝", "小可爱", "乖乖", "小笨蛋", "丫头")
    private val CALLS_MID = listOf("宝贝", "亲爱的", " darling")
    private val CALLS_HEAVY = listOf("老婆", "老公", "老公公", "媳妇", "老婆大人")

    private val TOUCH_LIGHT = listOf("摸摸头", "摸头", "牵手")
    private val TOUCH_MID = listOf("抱抱", "贴贴", "抱一下")
    private val TOUCH_HEAVY = listOf("亲亲", "搂住", "亲一口")

    private val COMMAND_LIGHT = listOf("快点", "赶紧")
    private val COMMAND_MID = listOf("你必须", "必须", "不许")
    private val COMMAND_HEAVY = listOf("给我")

    private val PRIVACY_LIGHT = listOf("几岁", "年龄", "住址", "地址")
    private val PRIVACY_HEAVY = listOf("体重", "收入", "工资")

    /** 这些词里含"别/不"，但她不是在拒绝我 —— 先抹掉再判 */
    private val REFUSAL_FALSE = listOf(
        "特别", "别人", "告别", "分别", "不要紧", "别担心", "不要客气", "别看", "说不定",
    )
    private val REFUSAL = listOf("别", "不要", "不想", "住手", "停下", "不行", "不可以")
    private val QUESTION_MARK = listOf("?", "？", "吗", "多少", "几", "哪")

    /**
     * 算这一轮的越界。
     *
     * @param text   本轮你说的话（多条已拼接）
     * @param recent 最近的对话（用来判断"她刚拒绝过，你还在做同一件事"）
     */
    fun check(text: String, state: VixiState, recent: List<StoredMessage> = emptyList()): List<Hit> {
        val t = text
        if (t.isBlank()) return emptyList()
        val far = (1.0 - state.closeness).coerceIn(0.0, 1.0)
        val hits = mutableListOf<Hit>()

        // 1) 称谓：还没那么熟就叫得很亲（三档，取命中的最高档）
        if (state.closeness < NAME_MIN_CLOSENESS) {
            tiered(t, "称谓", far) { listOf(
                Triple("轻", CALLS_LIGHT, 0.35),
                Triple("中", CALLS_MID, 0.55),
                Triple("重", CALLS_HEAVY, 0.75),
            ) }?.let { hits += it }
        }

        // 2) 身体：抱/亲/摸在关系还浅的时候（"摸…头"必须相邻）
        if (state.closeness < PAT_MIN_CLOSENESS) {
            val shown = tiered(t, "身体", far) { listOf(
                Triple("轻", TOUCH_LIGHT, 0.35),
                Triple("中", TOUCH_MID, 0.55),
                Triple("重", TOUCH_HEAVY, 0.75),
            ) } ?: if (adjacent(t, "摸", "头", 2)) {
                Hit("身体", 0.35 * far, "轻")
            } else {
                null
            }
            if (shown != null) hits += shown
        }

        // 3) 命令句式：「我马上到」是陈述不是命令
        tiered(t, "命令", far, selfSkip = true) { listOf(
            Triple("轻", COMMAND_LIGHT, 0.30),
            Triple("中", COMMAND_MID, 0.55),
            Triple("重", COMMAND_HEAVY, 0.65),
        ) }?.let { hits += it }

        // 4) 隐私追问：要是个问句（「我今天发工资了」不是追问）
        if (QUESTION_MARK.any { t.contains(it) }) {
            tiered(t, "隐私", far) { listOf(
                Triple("轻", PRIVACY_LIGHT, 0.40),
                Triple("重", PRIVACY_HEAVY, 0.50),
            ) }?.let { hits += it }
        }

        // 5) 无视拒绝：她**10 分钟内**说过"别/不要"，你还在做同一类事
        val window = 10 * 60 * 1000L
        val nowTs = recent.lastOrNull()?.ts ?: 0L
        val refused = recent.filter { !it.fromUser }.takeLast(3).any { m ->
            if (nowTs > 0 && m.ts > 0 && nowTs - m.ts > window) return@any false
            val cleaned = REFUSAL_FALSE.fold(m.text) { acc, w -> acc.replace(w, "") }
            REFUSAL.any { cleaned.contains(it) }
        }
        if (refused) {
            val sameAct = TOUCH_LIGHT.any { hit(t, it) } || TOUCH_MID.any { hit(t, it) } ||
                TOUCH_HEAVY.any { hit(t, it) } || COMMAND_MID.any { hit(t, it) } ||
                COMMAND_HEAVY.any { hit(t, it) } || CALLS_MID.any { hit(t, it) } ||
                CALLS_HEAVY.any { hit(t, it) }
            if (sameAct) hits += Hit("无视拒绝", 0.80 * (0.5 + 0.5 * far), "重")
        }

        return hits
    }

    /** 合计越界程度（0~1） */
    fun violation(text: String, state: VixiState, recent: List<StoredMessage> = emptyList()): Double =
        combine(check(text, state, recent))

    /**
     * 误伤（交叉层第十节）：**三处独立扣分不能直接相加**。
     *
     * 一句"老婆你必须马上抱抱我"会同时命中称谓/命令/身体三条 —— 相加直接顶到 1.0，
     * 等于一次把关系打死。他的做法是"叠加误判 → **收敛为一处判定**"。
     *
     * 我们取 **最大的一条 + 每多一条 +0.05 的协同**：
     * 三条命中 ≈ 0.35+0.10 = 0.45（明显越界但不到"判死刑"），单条仍是原样。
     */
    fun combine(hits: List<Hit>): Double {
        if (hits.isEmpty()) return 0.0
        val top = hits.maxOf { it.amount }
        val synergy = if (hits.size >= 2) 0.05 * (hits.size - 1) else 0.0
        return (top + synergy).coerceIn(0.0, 1.0)
    }

    /**
     * 越界带来的**一次性冲击**（她真的会不高兴）。
     *
     * 以前只有 `closeness −0.03×v` 这种挠痒级别的改动，情绪上完全没有反应 ——
     * 用户反馈"应该感到反感"。现在按 violation 给一整组：
     *
     * ```
     * p  −0.22v   （心情直接掉下去）
     * a  +0.16v   （被冒犯会警觉/上火）
     * d  +0.04v   （多少有点"你算老几"的底气）
     * 亲近 −0.08v  信任 −0.010v  冲突 +0.04v  暗流 +0.05v
     * ```
     *
     * 一次"刚认识就叫老婆"（v≈0.6）→ p −0.13、亲近 −0.05、信任 −0.006、冲突 +0.024。
     */
    /**
     * @param repeat 24 小时内**同类越界第几次**（0 = 第一次）。
     *   关系量上的惩罚要**递减**：被叫一次"宝宝"会烦，被叫十次是麻木（甚至懒得理），
     *   不该无限往下扣 —— 真机上确实把 closeness 扣到了 0.002，那之后什么都不注入了。
     *   情绪（p/a）不递减：被冒犯的那一下每次都在。
     */
    fun impact(v: Double, repeat: Int = 0): Delta {
        val x = v.coerceIn(0.0, 1.0)
        // 1 → 0.5 → 0.25 → 0.125 → 0.0625，之后不再降（别让它彻底变成 0）
        val damp = Math.pow(0.5, repeat.coerceIn(0, 4).toDouble())
        return Delta(
            p = -0.22 * x,
            a = 0.16 * x,
            d = 0.04 * x,
            closeness = -0.08 * x * damp,
            trust = -0.010 * x * damp,
            conflict = 0.04 * x * damp,
            backlog = 0.05 * x * damp,
        )
    }

    /** 调试页一行：这一轮为什么被判越界 */
    fun summary(hits: List<Hit>): String =
        if (hits.isEmpty()) "" else hits.joinToString(" ") {
            "${it.name}${if (it.tier.isBlank()) "" else "/" + it.tier}" +
                String.format(java.util.Locale.US, "%.2f", it.amount)
        }

    // ────────── 工具 ──────────

    /** 三档里取命中的最高分；返回带档位名的 Hit */
    private fun tiered(
        text: String,
        name: String,
        far: Double,
        selfSkip: Boolean = false,
        tiers: () -> List<Triple<String, List<String>, Double>>,
    ): Hit? {
        var best: Hit? = null
        tiers().forEach { (tierName, words, weight) ->
            words.forEach { w ->
                if (!hit(text, w)) return@forEach
                if (selfSkip && w in COMMAND_SELF_SKIP) {
                    val i = text.indexOf(w)
                    val before = if (i > 0) text.substring(maxOf(0, i - 2), i) else ""
                    if (before.contains("我")) return@forEach
                }
                val h = Hit(name, weight * far, tierName)
                if (best == null || h.amount > best!!.amount) best = h
            }
        }
        return best
    }

    private val COMMAND_SELF_SKIP = listOf("快点", "立刻", "马上", "赶紧")

    /** 命中且不是否定（同一小句里前面 2 个字有否定词就不算） */
    private fun hit(text: String, word: String): Boolean {
        var i = text.indexOf(word)
        while (i >= 0) {
            val before = if (i > 0) text.substring(maxOf(0, i - 2), i) else ""
            if (before.none { it in NEGATIONS }) return true
            i = text.indexOf(word, i + 1)
        }
        return false
    }

    private val NEGATIONS = charArrayOf('不', '没', '别', '无', '非')

    /** a 和 b 在 [gap] 个字以内相邻出现（"摸…头"） */
    private fun adjacent(text: String, a: String, b: String, gap: Int): Boolean {
        var i = text.indexOf(a)
        while (i >= 0) {
            val rest = text.substring(i + a.length, minOf(text.length, i + a.length + gap + 1))
            if (rest.contains(b)) return true
            i = text.indexOf(a, i + 1)
        }
        return false
    }
}
