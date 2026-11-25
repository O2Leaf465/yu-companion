package com.leaf.vixi.state

import com.leaf.vixi.data.VixiState

/**
 * 混合情绪（2026-11-25 用户提出）：人说话时心里常常有**好几个声音**，
 * 不是被单一情绪支配。
 *
 * 做法：
 * - 每个情绪事件把自己那份加进一个**情绪池**（同一标签累加）
 * - 池子按 90 分钟半衰淡出（和情绪同一个节奏）
 * - 只留**前两个**（用户定的：三个就开始像八点档）
 * - **主情绪决定动作，次情绪只调一个参数**（见 `ReactionTable`）
 *
 * 存盘格式（≤40 字）：`"委屈:0.55,生气:0.30"`
 */
object EmotionMix {

    /** 池子最多留几个（用户定：两个） */
    const val MAX_KEEP = 2

    /** 权重低于它就不算混合了 */
    const val MIN_WEIGHT = 0.12

    /** 池子的半衰，和情绪一致 */
    const val HALF_LIFE_MS = 90 * 60 * 1000L

    data class Pair(val label: EmotionLabel, val w: Double)

    /** 解析；坏串当空池 */
    fun decode(raw: String): List<Pair> =
        raw.split(',').mapNotNull { seg ->
            val name = seg.substringBefore(':')
            val w = seg.substringAfter(':', "").toDoubleOrNull() ?: return@mapNotNull null
            val label = EmotionLabel.entries.firstOrNull { it.zh == name } ?: return@mapNotNull null
            if (w <= 0.0) null else Pair(label, w)
        }.sortedByDescending { it.w }.take(MAX_KEEP)

    fun encode(list: List<Pair>): String =
        list.filter { it.w >= MIN_WEIGHT }.take(MAX_KEEP)
            .joinToString(",") { "${it.label.zh}:${fmt(it.w)}" }

    /** 加一份：同标签累加，其他不变；再裁到前二 */
    fun add(raw: String, label: EmotionLabel, intensity: Double): String {
        if (intensity <= 0.0) return raw
        val cur = decode(raw).toMutableList()
        val idx = cur.indexOfFirst { it.label == label }
        if (idx >= 0) {
            cur[idx] = cur[idx].copy(w = (cur[idx].w + intensity).coerceAtMost(1.5))
        } else {
            cur += Pair(label, intensity.coerceAtMost(1.5))
        }
        return encode(cur.sortedByDescending { it.w })
    }

    /** 时间衰减：池子按半衰期整体变淡（相对大小不变） */
    fun decay(raw: String, dtMs: Long): String {
        if (dtMs <= 0L || raw.isBlank()) return raw
        val f = Math.pow(0.5, dtMs.toDouble() / HALF_LIFE_MS)
        val out = decode(raw).map { it.copy(w = it.w * f) }.filter { it.w >= MIN_WEIGHT }
        return encode(out)
    }

    /** 主情绪（没有池子就回落 lastLabel） */
    fun dominant(s: VixiState): EmotionLabel? {
        val pool = decode(s.emoMix)
        if (pool.isNotEmpty()) return pool.first().label
        return EmotionLabel.entries.firstOrNull { it.zh == s.lastLabel }
    }

    /** 次情绪：权重至少是主情绪的一半才算"混"进来 */
    fun secondary(s: VixiState): EmotionLabel? {
        val pool = decode(s.emoMix)
        if (pool.size < 2) return null
        return if (pool[1].w >= pool[0].w * 0.45) pool[1].label else null
    }

    private fun fmt(v: Double) = String.format(java.util.Locale.US, "%.2f", v)
}
