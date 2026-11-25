package com.leaf.vixi.state

import com.leaf.vixi.data.VixiState

/**
 * 交叉层·缄口（X2）：**知道的事，可以不说**。
 *
 * 他的原话："秘密不是知不知道，是说不说。判断看三样：多敏感、关系到什么程度、
 * 现在是什么场合。三样合起来给一个动作：说、瞒、或者软化成一部分说。
 * 瞒不是免费的：每压下一件，压力加一点。"
 *
 * 我们的实现：**压力就是已有的暗流（backlog）** —— 压下去 → 暗流涨 → 到顶一次倒出来。
 * 不需要新开一个"压抑值"。
 */
object Withhold {

    enum class Act(val zh: String) {
        TELL("说"),
        WITHHOLD("瞒"),
        REFRAME("软化"),
    }

    /** 敏感词分档：越贴近"伤"的越敏感 */
    private val SENSITIVE: List<Pair<List<String>, Double>> = listOf(
        listOf("去世", "死了", "自杀", "家暴", "出轨", "流产") to 0.80,
        listOf("前任", "分手", "癌症", "抑郁", "自残", "离婚") to 0.72,
        listOf("工资", "欠债", "借钱", "生病", "为什么活着") to 0.55,
    )

    /** 压下去的代价（暗流涨这么多） */
    const val WITHHOLD_COST = 0.08
    const val REFRAME_COST = 0.04

    /** 话题敏感度（0~1）：命中敏感词 = 0.55 起，多重命中还会叠加 */
    fun sensitivity(text: String, conflict: Double = 0.0): Double {
        if (text.isBlank()) return 0.0
        val hits = SENSITIVE.mapNotNull { (words, w) -> words.firstOrNull { text.contains(it) }?.let { w } }
        val base = hits.maxOrNull() ?: 0.0
        // 多重命中再加一点（同时提到前任和分手，比只提一个更敏感）
        val extra = if (hits.size > 1) 0.10 else 0.0
        return (base + extra + 0.25 * conflict.coerceIn(0.0, 1.0)).coerceIn(0.0, 1.0)
    }

    /**
     * 三样合起来给动作。
     *
     * - 很敏感 + 关系还没到 → **瞒**
     * - 有点敏感 + 关系一般 → **软化**（只说一部分）
     * - 其余 → **说**
     * - 群聊里门槛抬高（公开场合更收着）
     */
    fun decide(state: VixiState, text: String, isGroup: Boolean = false): Act {
        val s = sensitivity(text, state.conflict)
        if (s <= 0.0) return Act.TELL
        // 群聊额外 +0.15 的"场合压力"
        val eff = (s + if (isGroup) 0.15 else 0.0).coerceIn(0.0, 1.0)
        // 公开场合 = 关系"打折"（同样亲密的程度，在群里更不该说）
        val close = (state.closeness.coerceIn(0.0, 1.0)) * (if (isGroup) 0.8 else 1.0)
        return when {
            eff >= 0.70 && close < 0.55 -> Act.WITHHOLD
            eff >= 0.50 && close < 0.75 -> Act.REFRAME
            else -> Act.TELL
        }
    }

    /** 压下去的代价（写进暗流） */
    fun cost(act: Act): Double = when (act) {
        Act.WITHHOLD -> WITHHOLD_COST
        Act.REFRAME -> REFRAME_COST
        Act.TELL -> 0.0
    }

    /** 提示词里那一行：只给做法，不解释为什么 */
    fun line(act: Act): String = when (act) {
        Act.WITHHOLD -> "【怎么说】这件事先别提：绕开它，或者只说「没什么」。"
        Act.REFRAME -> "【怎么说】可以说，但只说一部分，别全交代。"
        Act.TELL -> ""
    }
}
