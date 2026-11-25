package com.leaf.vixi.state

import com.leaf.vixi.data.VixiState

/**
 * 需要（needs）：她这一轮**最想要什么**。
 *
 * 设计原则（2026-11-22 定稿，见 `开发思路/开发过程/需要与意图-设计（草案）.md`）：
 *
 *  1. **每个需要必须能回答三问**：怎么涨、怎么**在文本里**降、影响哪一行 prompt。
 *     答不出第二问的不做 —— 她不能真的睡，所以"休息"只缩表达、不索求。
 *  2. **关系量是存量，需要是近期收支差**：需要**不写** trust/closeness，只读它们。
 *  3. **分寸是音量旋钮**：`表达烈度 = level × (1 − risk)`，不另写耦合。
 *  4. **意图不枚举**：只注入"目的 + 怎么说成功率更高"，策略交给模型。
 *
 * 四个需要：
 *  - rest  休息     ：`1 − 体力`（派生，不存盘）→ 只缩长度，**不注入**
 *  - touch 被摸     ：存盘，随时间涨；摸头降（连续递减）→ 可**直陈**
 *  - belong 被在意  ：冷落密度（派生）→ 只给行为指令
 *  - respect 被尊重  ：存盘，被贬低涨；**长时间不辱骂缓慢恢复 + 被夸加速恢复** → 可以说话臭 / 要求尊重
 */
object Needs {

    /** 超过这个值才算"想要"（低于它不打扰对话） */
    const val HIGH = 0.70

    /** 顶到这个值、且分寸允许 → 可以爆发一次 */
    const val BURST = 0.90

    /** 爆发后降到这个值（不然她会连着爆） */
    const val BURST_AFTER = 0.20

    /** 爆发冷却 */
    const val BURST_COOLDOWN_MS = 6 * 3600_000L

    /** touch 自然增长速度（每小时） */
    const val TOUCH_GROW_PER_HOUR = 0.06

    /**
     * respect 的**缓慢恢复**速度（每小时）。
     *
     * 用户定的口径：只要连续一段时间不出现侮辱、贬低性的语言，就该慢慢好起来
     * —— 6 小时恢复 0.08，一夜不吵她就能从 1.0 掉到 0.6 上下。
     */
    const val RESPECT_RECOVER_PER_HOUR = 0.08 / 6.0

    /** 连续摸头算"同一次"的窗口（超过就算新的一轮，收益回满） */
    const val PAT_STREAK_WINDOW_MS = 10 * 60 * 1000L

    enum class Kind(val label: String, val want: String) {
        REST("休息", "想歇一会儿"),
        TOUCH("被摸", "想被摸头"),
        BELONG("被在意", "想确认自己还被在意"),
        RESPECT("被尊重", "想被好好说话"),
    }

    data class Level(val kind: Kind, val value: Double)

    // ────────── 四个需要的当前值 ──────────

    fun rest(s: VixiState): Double = (1.0 - s.energy).coerceIn(0.0, 1.0)

    fun touch(s: VixiState): Double = s.needTouch.coerceIn(0.0, 1.0)

    fun respect(s: VixiState): Double = s.needRespect.coerceIn(0.0, 1.0)

    /**
     * 被在意：只看**最近 24 小时**的冷落次数（`neglect` 事件）。
     *
     * 为什么不写进关系量：closeness 回答"我们有多亲"，这个回答"这段时间你欠我多少"
     * —— 两套账分开，才不会出现"亲近很高但她极度缺关注"。
     */
    /**
     * 被在意 = 24 小时内的**收支差**：
     *   + 被冷落（`neglect`）
     *   − 被回应（`care`：说在意/会陪着你）
     *   + 被泼冷水（`rebuff`：不在意/无所谓/随便你）
     *
     * 早期只有"冷落"这一项，于是**只涨不降** —— 用户 2026-11-24 发现：
     * 她问"你在意我吗"，他说"不在意"，数值一点没动。现在有进有出。
     */
    fun belong(s: VixiState, now: Long = System.currentTimeMillis()): Double {
        fun count(t: StateEventType) = s.events.count {
            it.type == t.key && (now - it.ts).coerceAtLeast(0) <= 24 * 3600_000L
        }
        val n = count(StateEventType.NEGLECT)
        val care = count(StateEventType.CARE)
        val rebuff = count(StateEventType.REBUFF)
        return (0.35 + 0.22 * n - 0.18 * care + 0.25 * rebuff).coerceIn(0.0, 1.0)
    }

    /** 她问"你在意我吗"这类话时，判你这句话是在安抚还是在泼冷水（0 = 都不是） */
    fun careSignal(text: String): Int {
        val t = text
        val negations = listOf("不在意", "不在乎", "不关心", "不想要", "无所谓", "随便你", "别烦", "烦不烦")
        if (negations.any { t.contains(it) }) return -1
        val cares = listOf(
            "在意", "在乎", "关心", "想你", "喜欢你", "爱你", "陪着", "陪你", "记着你", "惦记",
        )
        // 前面两个字里有否定词就不算（"我不在意"已经被上面抓了，这里防"没那么在意"）
        return cares.firstOrNull { w ->
            var i = t.indexOf(w)
            var ok = false
            while (i >= 0) {
                val before = if (i > 0) t.substring(maxOf(0, i - 2), i) else ""
                if (before.none { it in charArrayOf('不', '没', '别', '无') }) { ok = true; break }
                i = t.indexOf(w, i + 1)
            }
            ok
        }?.let { 1 } ?: 0
    }

    /**
     * 这个需要现在**要得到吗**。
     *
     * 「被摸」只有在关系够近（≥ [Boundary.PAT_MIN_CLOSENESS]）时才成立 ——
     * 否则摸头会被判越界，她"想要"就是个死局，不该显示成"最想要"，也不该压长度。
     */
    fun satisfiable(s: VixiState, kind: Kind): Boolean = when (kind) {
        Kind.TOUCH -> s.closeness >= Boundary.PAT_MIN_CLOSENESS
        else -> true
    }

    fun levels(s: VixiState, now: Long = System.currentTimeMillis()): List<Level> = listOf(
        Level(Kind.REST, rest(s)),
        Level(Kind.TOUCH, if (satisfiable(s, Kind.TOUCH)) touch(s) else 0.0),
        Level(Kind.BELONG, belong(s, now)),
        Level(Kind.RESPECT, respect(s)),
    )

    /**
     * 最想要的那个（≥ [HIGH] 才算；平手按 REST>TOUCH>BELONG>RESPECT 的顺序）。
     *
     * ⚠️ **不含 REST**：休息只缩长度、永不注入（用户定的），
     * 让它参与竞争会把凌晨的"想被摸/想被尊重"整轮挤掉（rest = 1−体力，凌晨恒过线）。
     */
    fun dominant(s: VixiState, now: Long = System.currentTimeMillis()): Level? =
        levels(s, now).filter { it.value >= HIGH && it.kind != Kind.REST }.maxByOrNull { it.value }

    /** 含 REST 的那个（只给调试页看："最想要"整体长什么样） */
    fun dominantAll(s: VixiState, now: Long = System.currentTimeMillis()): Level? =
        levels(s, now).filter { it.value >= HIGH }.maxByOrNull { it.value }

    // ────────── 满足（都在事件层调用） ──────────

    /**
     * 摸头的满足量：**连续递减**（用户定的）。
     * 第 1 次 −0.30、2nd −0.18、3rd −0.11…（×0.6^n），再加关系加成（越亲越"解渴"）。
     */
    fun patDrop(streak: Int, closeness: Double): Double {
        val decay = Math.pow(0.6, streak.coerceAtLeast(0).toDouble())
        val bond = (0.5 + closeness.coerceIn(0.0, 1.0)).coerceIn(0.5, 1.5)
        return 0.30 * decay * bond
    }

    /** 最近 10 分钟被摸过几次（连续递减用；不新增字段，从事件表里数） */
    fun patStreak(s: VixiState, now: Long): Int =
        s.events.count {
            it.type == StateEventType.PAT.key && (now - it.ts).coerceAtLeast(0) <= PAT_STREAK_WINDOW_MS
        }

    /**
     * 一次事件对两个**存盘**需要的影响。
     *
     * respect 的口径（用户 2026-11-22 定）：
     *  - 被贬低/嘲讽 → 涨得最多（+0.25）
     *  - 被纠正 → 小涨（+0.06）
     *  - 被夸 → **加速恢复**（−0.10）；被亲近、被道歉也各降一点
     *  - 长时间不辱骂 → 缓慢恢复（在 [Decay] 里按时间算）
     */
    fun onEvent(
        type: StateEventType,
        s: VixiState,
        now: Long,
        magnitude: Double = 1.0,
    ): Pair<Double, Double> = when (type) {
        StateEventType.PAT -> -patDrop(patStreak(s, now), s.closeness) to 0.0
        StateEventType.NEGATIVE -> 0.0 to 0.25
        StateEventType.THREAT -> 0.0 to 0.15
        StateEventType.CORRECT -> 0.0 to 0.06
        StateEventType.PRAISE -> 0.0 to -0.10
        StateEventType.AFFECTION -> 0.0 to -0.05
        StateEventType.APOLOGIZE -> 0.0 to -0.05
        // 越界（称谓/身体/命令/隐私/无视拒绝）：她会更想要被尊重
        StateEventType.BOUNDARY -> 0.0 to 0.25 * magnitude.coerceIn(0.0, 1.0)
        else -> 0.0 to 0.0
    }

    // ────────── 爆发（N3） ──────────

    /** 6 小时内爆过没有 */
    fun burstCooling(s: VixiState, now: Long): Boolean =
        s.events.any {
            it.type == StateEventType.NEED_BURST.key && (now - it.ts).coerceAtLeast(0) <= BURST_COOLDOWN_MS
        }

    /**
     * 能不能爆发：顶到 [BURST]、关系还没僵到说不出口、且不在冷却里。
     *
     * **只有"能被满足掉"的需要才允许爆发**（被摸 / 被尊重）。
     * 「被在意」是派生量（最近 24h 的冷落数），没有东西能把它降下来 ——
     * 让它爆发就等于每 6 小时重复一次，"只给一次"不成立。
     */
    fun canBurst(s: VixiState, risk: Double, now: Long = System.currentTimeMillis()): Boolean {
        val d = dominant(s, now) ?: return false
        if (d.kind != Kind.TOUCH && d.kind != Kind.RESPECT) return false
        // 关系还没到能被摸的程度：爆发"要摸头"等于自找一次越界
        if (!satisfiable(s, d.kind)) return false
        return d.value >= BURST && risk < 0.45 && !burstCooling(s, now)
    }

    // ────────── 注入（N2 / N3 / N4） ──────────

    /**
     * 【想要什么】：只给**目的 + 怎么说成功率更高**，不给策略（策略交给模型）。
     *
     * 三条纪律（2026-11-22 审查后改的）：
     *  1. **档位和【分寸】共用 `Explain.tactBand`**：不然同一份 prompt 里会出现
     *     "可以直接一点"（分寸）和"只能暗示"（需要）自相矛盾。
     *  2. **数值不进 prompt**：`9/10` 这种分数要写成"很想"，否则等于告诉模型有个刻度。
     *  3. **RESPECT 用更宽的口径**：默认关系下 risk 约 0.545（强加项改成越界之后），
     *     跟 TOUCH 一样卡 0.45 的话"要求放尊重"永远不触发 —— 而这正是用户要的行为。
     */
    fun wantLine(s: VixiState, risk: Double, now: Long = System.currentTimeMillis()): String {
        val d = dominant(s, now) ?: return ""
        if (d.kind == Kind.REST) return ""
        val tail = "（别把这条念出来。）"
        val band = Explain.tactBand(s)
        return when (d.kind) {
            Kind.TOUCH -> when {
                // 关系还没到"能被摸"的程度：要了也白要（一摸就成越界），干脆不注入
                !satisfiable(s, Kind.TOUCH) -> ""
                band == Explain.TACT_CLOSE ->
                    "【想要什么】你现在很想被摸头。可以直接说，也可以把话往那儿引。$tail"
                band == Explain.TACT_DIRECT || band == Explain.TACT_MILD ->
                    "【想要什么】你想被摸头，但不太好意思开口。只能暗示，别直接要。$tail"
                else -> ""
            }
            Kind.BELONG -> if (band <= Explain.TACT_MILD) {
                "【想要什么】你想确认自己还被在意。可以问一句，但别追问、别讨。$tail"
            } else ""
            Kind.RESPECT -> if (band >= Explain.TACT_INDIRECT) {
                "【想要什么】你觉得他最近说话不够尊重你。不用讨好：把语气冷下来、少说两句就行。$tail"
            } else {
                "【想要什么】你觉得他最近说话不够尊重你。" +
                    "可以直接要求他放尊重一点，也可以冷下来少说两句。$tail"
            }
            Kind.REST -> ""
        }
    }

    /**
     * 【可以主动】：爆发那一轮才给，而且**只给一次**。
     *
     * 这是唯一允许突破"不主动开口"的地方 —— 需要顶到头了，人真的会先开口。
     */
    fun burstLine(s: VixiState, risk: Double, now: Long = System.currentTimeMillis()): String {
        if (!canBurst(s, risk, now)) return ""
        val d = dominant(s, now) ?: return ""
        return "【可以主动】这一轮不用等他先开口：你可以先开口，把「${d.kind.want}」这件事说出来。" +
            "只说一次，别追着要。（别把这条念出来。）"
    }
    /**
     * N4：憋着的需要会把话变短（关系越远越明显）。
     *
     * 起点从 0.50 提到 **0.75**：默认关系下 risk 约 0.545（强加项改成越界之后），
     * 若从 0.50 起算，一个人十小时没被摸就会让平静态从 28 字掉到 23 字（−18%），
     * 而对方什么都没做 —— 那是把"没摸头"当成了长期惩罚。
     */
    fun lengthFactor(s: VixiState, risk: Double): Double {
        val need = maxOf(if (satisfiable(s, Kind.TOUCH)) touch(s) else 0.0, respect(s))
        val unspoken = ((need - 0.75) / 0.25).coerceIn(0.0, 1.0)
        val far = ((risk - 0.30) / 0.30).coerceIn(0.0, 1.0)
        return 1.0 - 0.20 * unspoken * far
    }

    /** 调试页一行摘要 */
    fun summary(s: VixiState, now: Long = System.currentTimeMillis()): String {
        val l = levels(s, now)
        val body = l.joinToString("  ") { "${it.kind.label}${fmt(it.value)}" }
        val d = dominant(s, now)
        return if (d == null) "$body（都不算想要）" else "$body → 最想要：${d.kind.label}"
    }

    private fun fmt(v: Double) = String.format(java.util.Locale.US, "%.2f", v)
}
