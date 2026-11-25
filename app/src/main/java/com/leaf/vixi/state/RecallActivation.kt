package com.leaf.vixi.state

import com.leaf.vixi.data.VixiState
import kotlin.math.abs
import kotlin.math.pow

/**
 * 交叉层第一条边：**记忆 → 状态（召回再激活）**。
 *
 * 之前检索只读不写：想起一件旧事，只是提示词里多几行原文，她的心情一点都不受影响。
 * 这里把"想起来"变成一条真的状态事件 —— 检索命中的旧事反过来推当下的 p。
 *
 * **闭环必须有断点**，所以这个文件同时也是刹车。五条：
 *  1. 系数 0.15：一次回想约等于半次真冲突（被骂是 p −0.10），不能比真事更重
 *  2. 单轮合计 |Δp| ≤ 0.05：同时想起三件旧事也不叠加成灾难
 *  3. 只认"展开档"（≥0.55）：只是觉得有点熟的那一档不动心情
 *  4. conflict ≥ 0.40 不激活：有裂痕时不翻旧账
 *     —— 否则 conflict 只升不降，而信任回升要求 conflict < 0.20，闭环会自己把关系焊死
 *  5. 同一条 24 小时内只算一次，第二次起 ×0.5：反刍 ≠ 记忆
 *
 * 为什么系数不是 handoff 里原来写的 0.30：
 * 拿 p=−0.5、重要度 0.8 的旧事算，0.30 给出 −0.12，比"当场被骂一句"的 −0.10 还重
 * —— 想起一件旧事比挨一句骂更伤人，不成立。0.15 给出 −0.06，大约半次。
 */
object RecallActivation {

    /** 记忆 p × 重要度 × 这个系数 */
    const val COEF = 0.15

    /** 单轮合计上限：正的想象和负的回想一起算 */
    const val MAX_ABS_P = 0.05

    /** 有裂痕（conflict ≥ 这个值）就不翻旧账 */
    const val RIFT_BLOCK = 0.40

    /** 同一条旧事重复激活的衰减窗口 */
    const val REPEAT_WINDOW_MS = 24L * 60 * 60 * 1000

    /** 窗口内第二次起，每次再乘这个数 */
    const val REPEAT_FACTOR = 0.5

    /** 小于这个量的增量不值得记一条事件（千分之一以下，落盘就没了） */
    const val MIN_DELTA = 0.001

    /** 一件被想起来的旧事（由 `Recall.Scored.event` 摊平过来） */
    data class Item(
        val start: Long,
        val p: Double,
        val importance: Double,
        val label: String = "",
    )

    /** 一次真的激活：给 p 多少、为什么（调试页和测试都看这个） */
    data class Activation(
        val start: Long,
        val label: String,
        val p: Double,
        val importance: Double,
        val deltaP: Double,
    )

    /**
     * 算出这一轮要激活哪几条、各推多少。
     *
     * 纯函数：不读盘、不改状态。调用方拿结果去落 [StateEventType.RECALL] 事件。
     *
     * @return 空列表 = 这轮不激活（没命中 / 有裂痕 / 增量太小）
     */
    fun plan(items: List<Item>, state: VixiState, now: Long): List<Activation> {
        if (items.isEmpty()) return emptyList()
        // 断点 4：有裂痕不翻旧账
        if (state.conflict >= RIFT_BLOCK) return emptyList()

        val seen = recentActivations(state, now)
        val raw = items.mapNotNull { item ->
            val r = item.p * item.importance * COEF
            if (abs(r) < MIN_DELTA) return@mapNotNull null
            // 断点 5：同一条被反复想起时逐步减半
            val times = seen[item.start] ?: 0
            val factor = REPEAT_FACTOR.pow(times.toDouble())
            Activation(
                start = item.start,
                label = item.label,
                p = item.p,
                importance = item.importance,
                deltaP = r * factor,
            )
        }
        if (raw.isEmpty()) return emptyList()

        // 断点 2：整轮合计夹在上限内（按比例缩，不丢条目）
        val total = raw.sumOf { it.deltaP }
        if (abs(total) <= MAX_ABS_P) return raw
        val scale = MAX_ABS_P / abs(total)
        return raw.map { it.copy(deltaP = it.deltaP * scale) }
    }

    /**
     * 24 小时窗口内每条旧事被激活过几次。
     *
     * 账本就在**事件表**里（`type = "recall"` + note 里的起点时间戳），
     * 不新开一份文件：状态本来就必须能追溯到事件，加一个字段就要改 json，没必要。
     */
    fun recentActivations(state: VixiState, now: Long): Map<Long, Int> {
        val out = HashMap<Long, Int>()
        state.events.forEach { e ->
            if (e.type != StateEventType.RECALL.key) return@forEach
            if (now - e.ts > REPEAT_WINDOW_MS) return@forEach
            val start = startOf(e.note) ?: return@forEach
            out[start] = (out[start] ?: 0) + 1
        }
        return out
    }

    /** 事件 note 的统一格式：`想起旧事·<事件起点毫秒>·<标签>` */
    fun noteOf(a: Activation): String = "想起旧事·${a.start}·${a.label}"

    /** 从 note 里反解出"想起的是哪一段" */
    fun startOf(note: String): Long? =
        note.split('·').getOrNull(1)?.trim()?.toLongOrNull()

    /** 调试页一行：这一轮想起来推了多少（没有就是空串） */
    fun summary(acts: List<Activation>): String {
        if (acts.isEmpty()) return ""
        val total = acts.sumOf { it.deltaP }
        val sign = if (total >= 0) "+" else "−"
        return String.format(
            java.util.Locale.US, "再激活 %d 条 %s%.2f", acts.size, sign, abs(total),
        )
    }
}
