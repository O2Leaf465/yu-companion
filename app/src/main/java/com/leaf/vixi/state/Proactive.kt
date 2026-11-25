package com.leaf.vixi.state

import com.leaf.vixi.data.VixiState
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * 主动开口（后台版）的**全部判定逻辑**。
 *
 * 故意做成一个纯函数对象：不 import 任何 android.*、不读文件、不写状态，
 * 只吃 `(VixiState, 现在, 上次主动的记录)` 吐一个 [Decision]。
 * 这样"她到底会不会开口"这件事可以拿固定输入单测，不用跑 WorkManager、
 * 不用等三小时、也不用连着调 API。
 *
 * ```
 * 想吃话指数 = **0.60×想念 + 0.40×暗流**（2026-11-25 用户定：只由这两项决定）
 *              需要顶格度 / 时段**不进加权和** —— 时段改成硬门（半夜直接不发），
 *              需要只影响"说什么"（wantLine / 爆发），不影响"要不要开口"
 *              × 冷却惩罚（3 小时内主动过 → ×0.5）
 *              × 被拒惩罚（上次主动没被回 → ×0.5）
 * ```
 * 各分项都归一到 0~1，加权和使用固定权重，所以**总分也在 0~1**，
 * 阈值 [THRESHOLD] 才有一个稳定的含义（而不是"随权重调整而漂移"）。
 */
object Proactive {

    // ────────── 权重（加起来正好 1.0，改动时请保持） ──────────
    // 只有这两项进加权和（用户 2026-11-25 定：开口由"想念 + 暗流"决定）
    //
    // ⚠️ 0.60 / 0.40 而不是 0.50 / 0.50：门槛是 0.55，而想念的上限是 1.0。
    // 0.50 权重时"想念顶格 + 暗流 0"只有 0.50，**数学上永远跨不过门槛** ——
    // 于是"三天没见应该会想他"这条路径根本不存在（真机日志 0.01 → 永不说）。
    const val W_MISSING = 0.60
    const val W_BACKLOG = 0.40

    /** 保留常量：需要/时段不再进分，但日志和调试页还看得到 */
    @Deprecated("开口不再看需要顶格度")
    const val W_NEED = 0.0

    @Deprecated("时段改成硬门")
    const val W_WINDOW = 0.0

    /**
     * 需要顶格度的起点：`needTouch` / `needRespect` 超过这个值才算"顶格"。
     *
     * 为什么不是从 0 算起：这两个需要平时就有一个基础值（默认 0.35 / 0.15），
     * 从 0 归一的话"什么都没发生"也能拿 0.3 分，想念和暗流就被稀释了。
     */
    const val NEED_CAP = 0.70

    /** 冷却：3 小时内已经主动过，这轮权重减半（别变成连发骚扰） */
    const val COOLDOWN_MS = 3L * 60 * 60 * 1000
    const val COOLDOWN_FACTOR = 0.5

    /** 被拒：上次主动她开了口，你没回 —— 再开口的意愿减半（自尊，不是惩罚用户） */
    const val REJECT_FACTOR = 0.5

    /** 安静时段（23:00–8:00）的时段分：不是 0，只是很不想说话（0.3） */
    const val QUIET_FACTOR = 0.3

    /** 越过这条线才真的发。0.55 ≈ "想念很足"或"暗流+需要同时偏高" */
    const val THRESHOLD = 0.55

    /** 时段合适的判定区间（小时，含左不含右）：8:00–23:00 */
    const val AWAKE_HOUR_FROM = 8
    const val AWAKE_HOUR_TO = 23

    /**
     * 上次主动的结果，落在 `files/proactive.json`（由 `proactive/ProactiveRecords` 读写）。
     *
     * 为什么不塞进 state json：那会改状态文件，而主动开口**只读**状态；
     * 而且这两项是"通知账本"，跟情绪数值没有关系，混在一起会让人以为它们也是状态。
     */
    data class History(
        /** 上次真的发出去的时间戳；0 = 从没主动过 */
        val lastSentAt: Long = 0L,
        /** 上次主动之后**她有没有被回**。true = 被回了（或还没主动过） */
        val lastReplied: Boolean = true,
    )

    /**
     * 判定结果。
     * @param send 是否真的发（`score >= `[THRESHOLD]`）
     * @param score 算完所有惩罚之后的最终分（0~1），**日志和调试看这个**
     * @param reason 一行给人看的推导过程（为什么发/为什么不发），直接进 proactive.log
     */
    data class Decision(
        val send: Boolean,
        val score: Double,
        val reason: String,
    )

    /** 分项明细：单测和 reason 都从这里取，避免"reason 和 score 各算一遍" */
    data class Parts(
        val missing: Double,
        val backlog: Double,
        val need: Double,
        val window: Double,
        /** 冷却惩罚是否生效 */
        val cooled: Boolean,
        /** 被拒惩罚是否生效 */
        val rejected: Boolean,
        /** 惩罚前的加权和 */
        val base: Double,
        val score: Double,
    )

    /**
     * 主入口。
     *
     * @param hour 0~23。默认用 `now` 在**设备时区**下的小时数；
     *             显式给出来是为了单测能固定"现在是几点"，不然测试会随机器时区漂。
     */
    fun decide(
        state: VixiState,
        now: Long,
        history: History = History(),
        hour: Int = hourOf(now),
    ): Decision {
        val p = parts(state, now, history, hour)
        val send = p.score >= THRESHOLD
        val verdict = if (send) ">= ${fmt(THRESHOLD)} 开口" else "< ${fmt(THRESHOLD)} 不说"
        val reason = buildString {
            append("missing=").append(fmt(p.missing))
            // 存盘值被"按缺席时长推算"顶上去时标出来：否则看日志的人会对不上状态文件的数
            if (p.missing > state.missing.coerceIn(0.0, 1.0) + 1e-9) append("(推)")
            append(" backlog=").append(fmt(p.backlog))
            append(" 顶格=").append(fmt(p.need))
            append(" 时段=").append(fmt(p.window))
            append(" → ").append(fmt(p.base))
            if (p.cooled) append(" · 3h 内主动过 ×").append(fmt(COOLDOWN_FACTOR))
            if (p.rejected) append(" · 上次没被回 ×").append(fmt(REJECT_FACTOR))
            // 时段外是**硬门**：直接归零，不能让它看起来像"惩罚后的总分"
            if (p.window <= 0.0) append(" · 时段外硬门（归零）")
            append(" = ").append(fmt(p.score))
            append(" ").append(verdict)
        }
        return Decision(send = send, score = p.score, reason = reason)
    }

    /** 分项计算（纯算术，单测可以直接断言每一项） */
    fun parts(
        state: VixiState,
        now: Long,
        history: History = History(),
        hour: Int = hourOf(now),
    ): Parts {
        val missing = effectiveMissing(state, now)
        val backlog = state.backlog.coerceIn(0.0, 1.0)
        val need = needCapScore(state)
        val window = windowScore(hour)

        // 2026-11-25 用户定：**只由"想念 + 暗流"决定开不开口**。
        // 需要顶格、时段都不进加权和 —— 时段改成**硬门**（半夜直接不发），
        // 需要继续影响"说什么"（它已经通过 wantLine/爆发在表达层生效）。
        val base = W_MISSING * missing + W_BACKLOG * backlog

        // 冷却：3 小时内已经主动过，减半。**只看时间差**，不看是否被回 ——
        // "刚说完又来说"和"上次说完没人理"是两种不同的抑制，各自有自己的系数。
        val cooled = history.lastSentAt > 0L && now - history.lastSentAt < COOLDOWN_MS
        val rejected = history.lastSentAt > 0L && !history.lastReplied

        var score = base
        if (cooled) score *= COOLDOWN_FACTOR
        if (rejected) score *= REJECT_FACTOR
        // 半夜不发：不是"分低"，是干脆不出门
        if (window <= 0.0) score = 0.0

        return Parts(
            missing = missing,
            backlog = backlog,
            need = need,
            window = window,
            cooled = cooled,
            rejected = rejected,
            base = round3(base),
            score = round3(score),
        )
    }

    /**
     * 需要顶格度：`max(needTouch, needRespect)` 超过 [NEED_CAP] 的部分归一化到 0~1。
     *
     * 取 max 而不是平均：被摸和被尊重是**两个方向**的缺口，
     * 任意一头憋到顶（0.95）就该开口，不该被另一头（0.2）平均掉。
     */
    fun needCapScore(state: VixiState): Double {
        val peak = max(state.needTouch, state.needRespect)
        val span = 1.0 - NEED_CAP
        if (span <= 0.0) return 0.0
        return ((peak - NEED_CAP) / span).coerceIn(0.0, 1.0)
    }

    /** 时段合适：8:00–23:00 是 1.0，其余 0.3（深夜也能开口，只是门槛高得多） */
    /** 醒着的时段返回 1，其余返回 0（**硬门**：半夜不发，不是"分低"） */
    fun windowScore(hour: Int): Double =
        if (hour in AWAKE_HOUR_FROM until AWAKE_HOUR_TO) 1.0 else 0.0

    /**
     * 此刻的想念：**取存盘值和"按缺席时长推出的目标值"的较大者**。
     *
     * 为什么要推一遍：这个判定跑在后台，而状态只在"她说话/你看调试页"时被
     * [Decay.settle] 补算（惰性结算，没有定时器）。用户三天没开 App，存盘的
     * `missing` 还停在上次的值（可能是 0.1），而按 18 小时常数此刻该是 0.98 ——
     * 直接读存盘值等于"你最该被想起来的那个场景，她偏偏不开口"。
     *
     * 用的是 [Decay.MISSING_TAU_MS] 同一个常数，所以和前台算出来的目标值一致；
     * 只读不改，不碰状态文件。
     */
    fun effectiveMissing(state: VixiState, now: Long): Double {
        val stored = state.missing.coerceIn(0.0, 1.0)
        if (state.lastHeardAt <= 0L || now <= state.lastHeardAt) return stored
        val absent = now - state.lastHeardAt
        val target = 1.0 - exp(-absent.toDouble() / Decay.MISSING_TAU_MS)
        return max(stored, target).coerceIn(0.0, 1.0)
    }

    /** 时间戳在设备时区下是几点（0~23） */
    fun hourOf(now: Long, zone: TimeZone = TimeZone.getDefault()): Int =
        Calendar.getInstance(zone).apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)

    private fun round3(v: Double): Double = (v * 1000.0).roundToLong() / 1000.0

    private fun fmt(v: Double): String = String.format(java.util.Locale.US, "%.2f", v)
}
