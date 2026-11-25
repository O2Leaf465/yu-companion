package com.leaf.vixi.state

import com.leaf.vixi.data.VixiState

/**
 * 生物层·心动（S4）：情绪起来之后，**挑一种应对**，并把"哪种好用"记下来。
 *
 * 他的公式：`策略得分 = 成功率 − 0.05 × 平均成本`。
 *
 * 三个应对（不枚举具体台词，只给方向，台词交给模型）：
 * - **靠近**：往前一步（问清楚 / 说自己的感受）—— 最容易受伤，成本最高
 * - **退开**：往后一点（岔开 / 短回）—— 成本中等
 * - **不动**：按他说的接，不推进也不退 —— 成本最低，但成功率也通常最低
 *
 * 为什么用常数字典而不是学出来的嵌入：每轮只 1 次调用，没有梯度信号；
 * 用小表（每个应对的胜负计数）就够让"哪种好用下次先用"成立。
 */
object Strategy {

    enum class Move(val zh: String) {
        APPROACH("靠近"),
        WITHDRAW("退开"),
        HOLD("不动"),
        ;

        companion object {
            fun of(zh: String): Move? = entries.firstOrNull { it.zh == zh }
        }
    }

    /** 每个应对的成本（0~1）：靠近最贵 */
    fun cost(m: Move): Double = when (m) {
        Move.APPROACH -> 0.60
        Move.WITHDRAW -> 0.30
        Move.HOLD -> 0.10
    }

    /** 成功率用 Laplace 平滑：(胜 +1) / (总 +2)，没数据时都从 0.5 起步 */
    fun rate(win: Int, n: Int): Double = (win + 1.0) / (n + 2.0)

    fun score(m: Move, win: Int, n: Int): Double = rate(win, n) - 0.05 * cost(m)

    /**
     * 挑一个应对。平手时偏保守（不动 → 退开 → 靠近），
     * 因为"没把握就别往前冲"更符合一个刚被冒犯过的人。
     */
    fun choose(stats: Map<Move, Pair<Int, Int>>): Move {
        val order = listOf(Move.HOLD, Move.WITHDRAW, Move.APPROACH)
        return order.maxByOrNull { m ->
            val (w, n) = stats[m] ?: (0 to 0)
            score(m, w, n)
        } ?: Move.HOLD
    }

    /** 情境修正：关系很僵时，就算"靠近"分高也不该主动贴上去 */
    fun adjust(m: Move, s: VixiState): Move = when {
        m == Move.APPROACH && (s.conflict >= 0.40 || s.load >= 0.30) -> Move.HOLD
        else -> m
    }

    /** 注入：只给方向，不给台词 */
    fun line(m: Move, s: VixiState): String = when (m) {
        Move.APPROACH ->
            "【怎么做】这一轮可以往前一步：问清楚他什么意思，或者说说你自己的感受。"
        Move.WITHDRAW ->
            "【怎么做】这一轮别追：把话岔开，或者就短回一句。"
        Move.HOLD ->
            "【怎么做】这一轮按他说的接就行，不推进也不退。"
    } + if (s.trust <= Explain.TRUST_COLD && m == Move.APPROACH) {
        "（别太主动，他还不够可信）"
    } else {
        ""
    }

    // ────────── 落盘格式 ──────────
    // "A2/3,W1/2,H0/1|W"：三个应对的 胜/总，末尾是上一轮挑的那个字母

    fun encode(stats: Map<Move, Pair<Int, Int>>, last: Move?): String = buildString {
        append(
            Move.entries.joinToString(",") { m ->
                val (w, n) = stats[m] ?: (0 to 0)
                "${m.name.first()}$w/$n"
            },
        )
        if (last != null) append("|").append(last.name.first())
    }

    data class Decoded(val stats: Map<Move, Pair<Int, Int>>, val last: Move?)

    fun decode(raw: String): Decoded {
        if (raw.isBlank()) return Decoded(Move.entries.associateWith { 0 to 0 }, null)
        val head = raw.substringBefore('|')
        val tail = raw.substringAfter('|', "")
        val stats = Move.entries.associateWith { m ->
            val seg = head.split(',').firstOrNull { it.startsWith(m.name.first()) }
            val n = (seg?.substringAfter('/')?.toIntOrNull() ?: 0).coerceAtLeast(0)
            // 夹紧：坏 json 里 "A5/2" 会让成功率 >1，"靠近"就永久被高估
            val w = (seg?.substringAfter(m.name.first())?.substringBefore('/')?.toIntOrNull() ?: 0)
                .coerceIn(0, n)
            w to n
        }
        val last = Move.entries.firstOrNull { it.name.first() == tail.firstOrNull() }
        return Decoded(stats, last)
    }
}
