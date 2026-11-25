package com.leaf.vixi.state

import com.leaf.vixi.data.VixiState
import kotlin.math.abs

/**
 * 生物层·入心（S5）：五个旋钮按**证据**慢慢挪，`lr ≤ 0.05`，单次变化有界。
 *
 * 他的原话："证据模块攒够同类证据，才让旋钮挪一小步；挪完又回到表达。默认关，没数据时不动。"
 *
 * 为什么必须"攒够证据"：单条消息的信号噪声极大 —— 他今天话少可能只是累。
 * 只有同类证据连着出现 [NEED_EVIDENCE] 次，才承认"关系真的在往这个方向走"。
 *
 * 现在接上的三个（引用规则、修复速度先不动，它们要长期数据才有意义）：
 * - **表达强度** `express`：他愿意多聊 → 她也可以多说；老是敷衍 → 少说
 * - **礼貌阈值** `tact`：越界频发 → 更收着（risk 上调）
 * - **关系增益** `bond`：被夸/示好多 → 亲近涨得更快
 */
object Growth {

    const val LEARNING_RATE = 0.05
    const val NEED_EVIDENCE = 3

    /** 学到的东西会淡：3 天半衰回 1.0（只升不降的旋钮最后会把关系锁死） */
    const val FORGET_HALF_LIFE_MS = 3 * 24 * 3_600_000L

    /** 时间衰减：三个旋钮都朝 1.0 慢慢回去 */
    fun relax(k: Knobs, dtMs: Long): Knobs {
        if (dtMs <= 0L) return k
        val f = 1.0 - Math.pow(0.5, dtMs.toDouble() / FORGET_HALF_LIFE_MS)
        fun back(v: Double) = (v + (1.0 - v) * f).coerceIn(MIN, MAX)
        return Knobs(back(k.express), back(k.tact), back(k.bond))
    }

    /** 旋钮范围：别让"学到"变成"变了一个人" */
    const val MIN = 0.80
    const val MAX = 1.20

    enum class Knob { EXPRESS, TACT, BOND }

    data class Knobs(val express: Double = 1.0, val tact: Double = 1.0, val bond: Double = 1.0)

    /** 一轮里收到的信号（0 或 1），null = 这轮不算数据（比如纯图片、摸头模板） */
    data class Signal(val express: Int?, val tact: Int?, val bond: Int?)

    /**
     * 证据 = **方向 + 连击数**。
     *
     * 第一版只存计数、不看方向：信号 1,0,1 会攒满三次、按最后一格的方向挪 ——
     * "连续同类三次"根本没实现（审查发现的）。
     */
    data class Evidence(val sign: Int = 0, val count: Int = 0) {
        fun feed(sig: Int): Evidence =
            if (sig == sign) copy(count = count + 1) else Evidence(sig, 1)
    }

    fun encode(k: Knobs): String = java.util.Locale.US.run { "%.3f,%.3f,%.3f".format(k.express, k.tact, k.bond) }

    fun decode(raw: String): Knobs {
        val parts = raw.split(',').mapNotNull { it.trim().toDoubleOrNull() }
        if (parts.size < 3) return Knobs()
        return Knobs(
            express = parts[0].coerceIn(MIN, MAX),
            tact = parts[1].coerceIn(MIN, MAX),
            bond = parts[2].coerceIn(MIN, MAX),
        )
    }

    /**
     * 一步学习。
     *
     * @param evidence 每种信号连续出现了几次（同一个方向）
     * @return 新旋钮 + 新的证据计数
     */
    fun encodeEvidence(ev: Map<Knob, Evidence>): String =
        ev.entries.joinToString(",") { "${it.key.name}:${it.value.sign}:${it.value.count}" }

    fun decodeEvidence(raw: String): Map<Knob, Evidence> =
        raw.split(',').mapNotNull { seg ->
            val parts = seg.split(':')
            if (parts.size < 3) return@mapNotNull null
            val k = Knob.entries.firstOrNull { it.name == parts[0] } ?: return@mapNotNull null
            k to Evidence(parts[1].toIntOrNull() ?: 0, parts[2].toIntOrNull() ?: 0)
        }.toMap()

    fun step(
        knobs: Knobs,
        signal: Signal,
        evidence: Map<Knob, Evidence>,
    ): Pair<Knobs, Map<Knob, Evidence>> {
        var k = knobs
        val ev = evidence.toMutableMap()

        /** 攒够证据才挪一步；返回挪后的值 */
        fun stepOne(knob: Knob, sig: Int?, cur: Double): Double {
            if (sig == null) return cur
            val fed = (ev[knob] ?: Evidence()).feed(sig)
            if (fed.count < NEED_EVIDENCE) {
                ev[knob] = fed
                return cur
            }
            ev[knob] = Evidence()
            val target = if (sig > 0) MAX else MIN
            return (cur + LEARNING_RATE * (target - cur)).coerceIn(MIN, MAX)
        }

        k = k.copy(express = stepOne(Knob.EXPRESS, signal.express, k.express))
        k = k.copy(tact = stepOne(Knob.TACT, signal.tact, k.tact))
        k = k.copy(bond = stepOne(Knob.BOND, signal.bond, k.bond))
        return k to ev
    }

    // ────────── 旋钮的作用（都只放大/缩小，不改变符号） ──────────

    /**
     * 礼貌阈值：越界多了会更收着。
     *
     * **只加在"强加项"上**（最多 +0.1），不乘整段 risk ——
     * 乘整段会把分寸档位、`canBurst(risk<0.45)` 一起推走，而那个布尔是标定过的。
     */
    fun risk(base: Double, imposition: Double, tact: Double): Double =
        (base + (tact.coerceIn(MIN, MAX) - 1.0) * imposition).coerceIn(0.0, 1.0)

    /** 关系增益：被善待多了，亲近涨得更快 */
    fun closenessGain(s: VixiState, base: Double): Double = base * decode(s.knobs).bond

    /** 表达强度：愿意聊的时候可以说更多 */
    fun budgetMul(s: VixiState): Double = decode(s.knobs).express

    /** 从这一轮拿到信号（只在学了才返回非空） */
    fun signalOf(
        userText: String,
        praiseOrAffection: Boolean,
        violated: Boolean,
        negative: Boolean = false,
        isText: Boolean = true,
    ): Signal {
        // 摸头模板有 19 个字、纯图片没有字——都不该被当成"你说得长/短"
        val express = if (!isText || userText.isBlank()) {
            null
        } else if (userText.length >= 20) {
            1
        } else if (userText.length <= 5) {
            0
        } else {
            null
        }
        return Signal(
            express = express,
            tact = if (violated) 1 else null,
            // 只有"夸/撒娇"是正证据、"被冒犯"是负证据；中间的普通聊天**不算数据**
            // （第一版把每一句非夸奖都当负证据，会把 bond 一路压到底）
            bond = when {
                praiseOrAffection -> 1
                negative -> 0
                else -> null
            },
        )
    }

    /** 调试/测试用：旋钮偏离 1.0 多少 */
    fun drift(k: Knobs): Double =
        abs(k.express - 1.0) + abs(k.tact - 1.0) + abs(k.bond - 1.0)
}
