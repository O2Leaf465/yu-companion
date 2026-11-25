package com.leaf.vixi.state

/**
 * 评价层：一句话 → 四个维度。
 *
 * 为什么不再直接用"夸→+0.08"这种一对一表：
 * 同一句「你真蠢」和「你这毛病得改改」，前者是"他攻击我"（怪他 → 生气），
 * 后者夹着"我是不是该改"（往自己身上想 → 委屈）。只看词表分不出来，
 * 拆成四维之后才有得选。
 *
 * 四维沿用认知评价（appraisal）的经典划分：
 *
 * | 维度 | 回答的问题 | 范围 |
 * |---|---|---|
 * | relevance 相关性 | 这事跟我有关系吗 | 0..1 |
 * | impact 利害 | 对我好还是坏 | −1..1 |
 * | coping 应对 | 我应付得来吗 | 0..1 |
 * | attribution 归因 | 这该怪谁（负=怪自己，正=怪他） | −1..1 |
 *
 * 这一层**只算不写**：数值由 P2/P3 决定怎么用。
 */
data class Appraisal(
    val relevance: Double = 0.0,
    val impact: Double = 0.0,
    val coping: Double = 0.5,
    val attribution: Double = 0.0,
    /** 这句话命中的事件类型（调试页显示，方便对账） */
    val events: List<StateEventType> = emptyList(),
) {
    /**
     * 强度：利害有多大 × 这事跟她有多大关系。
     *
     * 为什么乘相关性：他随口一句"今天好热"里的负面词，不该让她难受；
     * 只有"冲着她来的"才该有强度。
     */
    val intensity: Double
        get() = (kotlin.math.abs(impact) * (0.4 + 0.6 * relevance)).coerceIn(0.0, 1.0)
}

/** 情绪标签：离散、一个人一个词，强度另算 */
enum class EmotionLabel(val zh: String) {
    CALM("平静"),
    JOY("开心"),
    TENDER("心动"),
    SHY("害羞"),
    AVERSION("反感"),
    EXCITED("兴奋"),
    ANGER("生气"),
    HURT("委屈"),
    SHAME("自责"),
    ANXIOUS("担心"),
    GLOOM("失落"),
    ANNOYED("烦"),
}

/**
 * 评价 + 一点关系上下文 → 情绪标签与强度。
 *
 * 归因是这里最关键的开关：
 *  - 怪他 + 应付得来 → 生气（可以怼回去）
 *  - 怪他 + 应付不来 → 委屈（只能受着）
 *  - 怪自己 → 自责
 */
object Labeler {
    data class Verdict(val label: EmotionLabel, val intensity: Double)

    fun judge(a: Appraisal, closeness: Double = 0.3, trust: Double = 0.5): Verdict {
        val i = a.intensity
        // 娇嗔是"轻正向"，但强度天然小，别让它掉进"平静"里
        if (StateEventType.TEASE in a.events && a.impact > 0) {
            val label = if (closeness >= 0.35) EmotionLabel.TENDER else EmotionLabel.JOY
            return Verdict(label, i.coerceAtLeast(0.12))
        }

        if (a.relevance < 0.30 || i < 0.10) return Verdict(EmotionLabel.CALM, i)

        // 威胁：焦虑拉起来（原来这条分支不可达）
        if (StateEventType.THREAT in a.events) return Verdict(EmotionLabel.ANXIOUS, i)

        // 正向：亲近的人夸 = 心动；很强的不亲近 = 兴奋；其余开心
        if (a.impact >= 0.35) {
            val label = when {
                closeness >= 0.35 -> EmotionLabel.TENDER
                a.impact >= 0.65 -> EmotionLabel.EXCITED
                else -> EmotionLabel.JOY
            }
            return Verdict(label, i)
        }

        // 负向：归因决定是生气、委屈还是自责
        if (a.impact <= -0.30) {
            val label = when {
                // 明确怪自己（被指错）
                a.attribution <= -0.35 -> EmotionLabel.SHAME
                // 怪他：怼得回去是生气，怼不回去是委屈
                a.attribution >= 0.30 ->
                    if (a.coping >= 0.50) EmotionLabel.ANGER else EmotionLabel.HURT
                // 归因模糊：信任低先发闷，其次按应付感分委屈/烦
                else -> when {
                    trust < 0.40 -> EmotionLabel.GLOOM
                    a.coping < 0.50 -> EmotionLabel.HURT
                    else -> EmotionLabel.ANNOYED
                }
            }
            return Verdict(label, i)
        }

        // 轻微负向
        if (a.impact <= -0.10) return Verdict(EmotionLabel.ANNOYED, i)
        return Verdict(EmotionLabel.CALM, i)
    }
}

/**
 * 规则版评价：还是本地词表 + 句法，**不调模型**。
 *
 * 这一版的目标不是准确，是"可解释"：每个维度都能在调试页看到它为什么是这个值。
 */
object Appraiser {

    fun appraise(text: String): Appraisal {
        val t = text.trim()
        if (t.isEmpty()) return Appraisal()

        val events = EventRules.classifyUser(t)
        val severity = EventRules.negativeSeverity(t)

        // ── 相关性：冲着她说的最高，泛泛而谈最低 ──
        val relevance = when {
            t.contains("你") || t.contains("妳") -> 1.0
            StateEventType.AFFECTION in events -> 1.0
            StateEventType.TEASE in events -> 1.0
            StateEventType.THREAT in events -> 1.0
            StateEventType.PAT in events -> 1.0
            t.contains('？') || t.contains('?') -> 0.7
            events.isNotEmpty() -> 0.7
            else -> 0.4
        }

        // ── 利害：先看正向，再看负向（强度靠 severity 区分）──
        // 负面优先判：一句"你真蠢，欠揍"里同时有强贬低和玩闹词，应该按骂人算
        val impact = when {
            StateEventType.NEGATIVE in events && severity >= 2 -> -0.9
            StateEventType.THREAT in events -> -0.6
            StateEventType.NEGATIVE in events -> -0.5
            StateEventType.CORRECT in events -> -0.35
            StateEventType.COLD in events -> -0.25
            StateEventType.AFFECTION in events -> 0.7
            StateEventType.PRAISE in events -> 0.6
            StateEventType.APOLOGIZE in events -> 0.3
            StateEventType.TEASE in events -> 0.25
            StateEventType.SHARE in events -> 0.2
            else -> 0.0
        }

        // ── 应对：她能不能改变这件事 ──
        val coping = when {
            StateEventType.TEASE in events -> 0.8
            StateEventType.AFFECTION in events -> 0.9
            StateEventType.PRAISE in events -> 0.8
            StateEventType.SHARE in events -> 0.7
            StateEventType.APOLOGIZE in events -> 0.5
            StateEventType.CORRECT in events -> 0.5
            // 强贬低：只能听着，但还不至于被压垮 → 生气
            StateEventType.NEGATIVE in events && severity >= 2 -> 0.55
            // 挑剔：这是"我该改"的事，应对感低 → 委屈
            StateEventType.NEGATIVE in events -> 0.35
            StateEventType.COLD in events -> 0.4
            StateEventType.THREAT in events -> 0.3
            else -> 0.5
        }

        // ── 归因：怪他还是怪自己 ──
        val attribution = when {
            StateEventType.NEGATIVE in events && severity >= 2 -> 0.8
            StateEventType.NEGATIVE in events -> -0.2
            StateEventType.COLD in events -> 0.6
            StateEventType.THREAT in events -> 0.7
            StateEventType.CORRECT in events -> -0.4
            StateEventType.TEASE in events -> 0.4
            StateEventType.PRAISE in events || StateEventType.AFFECTION in events -> 0.5
            StateEventType.APOLOGIZE in events -> 0.4
            else -> 0.0
        }

        return Appraisal(
            relevance = relevance,
            impact = impact,
            coping = coping,
            attribution = attribution,
            events = events,
        )
    }
}

/**
 * 情绪增量：**标签选形状，强度选大小**（P3 起由它接管 p/a/d）。
 *
 * 为什么不用"每种事件一组固定增量"：那是词表的写法，
 * 分不出「怼得回去」和「只能受着」——前者 d 该升，后者 d 该降。
 * 这里按离散情绪给形状，强度（0~1）整体缩放，所以同一件事骂得越狠数值越大。
 */
object EmotionDelta {

    /**
     * 情绪增益：2026-11-22 用户反馈「情绪状态变化太慢」。
     *
     * 只放大 **p / a / d**（"这一句让我多难受"），**不动关系四量**：
     * 信任和冲突本来就该慢 —— 一起放大就变成"吵一次就回不来"。
     */
    const val GAIN = 1.5

    fun of(label: EmotionLabel, intensity: Double): Delta {
        val f = intensity.coerceIn(0.0, 1.0)
        val base = when (label) {
            EmotionLabel.CALM -> Delta()
            EmotionLabel.JOY -> Delta(p = 0.08, a = 0.02, d = 0.02, closeness = 0.01)
            EmotionLabel.TENDER -> Delta(p = 0.07, a = 0.02, closeness = 0.03)
            // 害羞：**是心动 + 想躲**，不是"换一种弱一点的心动"。
            // 第一版只给 p+0.03，把被夸应有的正向增量吃掉了（审查指出）。
            EmotionLabel.SHY -> Delta(p = 0.07, a = 0.06, d = -0.05, closeness = 0.03)
            // 反感：被冒犯到的那一下（越界更猛时由 Boundary.impact 承担主要冲击，这里给个底）
            EmotionLabel.AVERSION -> Delta(p = -0.10, a = 0.08, d = 0.03, closeness = -0.02)
            EmotionLabel.EXCITED -> Delta(p = 0.10, a = 0.07, d = 0.03, closeness = 0.01)
            // 生气：d 升（怼得回去）、冲突涨
            EmotionLabel.ANGER -> Delta(
                p = -0.10, a = 0.07, d = 0.03,
                closeness = -0.02, conflict = 0.06, trust = -0.003,
            )
            // 委屈：d 降（只能受着）、暗流涨
            EmotionLabel.HURT -> Delta(
                p = -0.09, a = 0.03, d = -0.04,
                closeness = -0.02, backlog = 0.05, conflict = 0.03, trust = -0.002,
            )
            // 自责：d 降得多，往心里去
            EmotionLabel.SHAME -> Delta(
                p = -0.06, a = 0.04, d = -0.05,
                backlog = 0.03, conflict = 0.02, trust = -0.002,
            )
            EmotionLabel.ANXIOUS -> Delta(p = -0.05, a = 0.06, d = -0.03, backlog = 0.03)
            EmotionLabel.GLOOM -> Delta(p = -0.06, a = -0.04, closeness = -0.01)
            // 被敷衍：唤醒是往下走的（原来写成 +0.01，等于她越被冷落越清醒）
            EmotionLabel.ANNOYED -> Delta(p = -0.04, a = -0.01, conflict = 0.02, backlog = 0.02)
        }
        val scaled = base.scale(f)
        // 只放大情绪三维；关系量按原样走（见 GAIN 的说明）
        return scaled.copy(
            p = scaled.p * GAIN,
            a = scaled.a * GAIN,
            d = scaled.d * GAIN,
        )
    }
}