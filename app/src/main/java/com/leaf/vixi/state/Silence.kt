package com.leaf.vixi.state

import com.leaf.vixi.data.VixiState

/**
 * 交叉层·抽身（第十二节）：**不想回的时候，她真的不回**。
 *
 * 他的四选一：`{回, 晚点回, 这轮不回, 回一句然后结束话题}`。
 * 关键三条：
 * - 分数夹 0~1 归一化成分布，**抽一次**（不是确定性阈值）——同样状态也会有不同结果
 * - 沉默不等于消失：**不发消息，状态照写、记忆照记**
 * - 输入只读，决定不改状态；真正的记账在决定之后
 */
object Silence {

    enum class Act(val zh: String) {
        RESPOND("回"),
        DELAY("晚点回"),
        SILENT("不回"),
        END_TOPIC("收尾"),
    }

    /** 晚点回要等多久（秒） */
    /**
     * 晚点回要等多久。
     *
     * **从 90 秒改成 15 秒**（2026-11-25 真机反馈"第二条发不出去"）：
     * 90 秒太像卡死，而且用户连发几条时会叠成几分钟的静默。
     */
    const val DELAY_SECONDS = 15

    data class Weights(val respond: Double, val delay: Double, val silent: Double, val endTopic: Double)

    /**
     * 权重（0~1）。**能量低、负荷高、关系在冲突里** → 往"不回"偏；
     * **话题强度高**（暗流顶格、想你） → 往"收尾/回"偏。
     */
    fun weights(s: VixiState, hour: Int): Weights {
        val tired = (1.0 - s.energy).coerceIn(0.0, 1.0)
        val strained = s.load.coerceIn(0.0, 1.0)
        val hostile = s.conflict.coerceIn(0.0, 1.0)
        val night = if (hour < 7 || hour >= 24) 0.35 else 0.0

        // 不想动的程度
        val away = (0.45 * tired + 0.35 * strained + 0.55 * hostile + night).coerceIn(0.0, 1.0)
        // 想说话的强度：憋着 + 想你
        val pull = (0.6 * s.backlog + 0.4 * s.missing).coerceIn(0.0, 1.0)

        val respond = (0.55 + 0.35 * pull - 0.45 * away).coerceIn(0.0, 1.0)
        val delay = (0.10 + 0.30 * away).coerceIn(0.0, 1.0)
        val silent = (0.05 + 0.45 * away - 0.30 * pull).coerceIn(0.0, 1.0)
        val endTopic = (0.05 + 0.35 * s.backlog).coerceIn(0.0, 1.0)
        return Weights(round3(respond), round3(delay), round3(silent), round3(endTopic))
    }

    /**
     * 抽一次。
     *
     * @param roll 0~1 的随机数（测试传入定值；生产传 `Random.nextDouble()`）
     */
    fun decide(s: VixiState, hour: Int, roll: Double): Act {
        if (!s.enabled || !s.silenceOn) return Act.RESPOND
        val w = weights(s, hour)
        val total = w.respond + w.delay + w.silent + w.endTopic
        if (total <= 0.0) return Act.RESPOND
        var acc = roll.coerceIn(0.0, 0.999) * total
        acc -= w.respond
        if (acc < 0) return Act.RESPOND
        acc -= w.delay
        if (acc < 0) return Act.DELAY
        acc -= w.silent
        if (acc < 0) return Act.SILENT
        return Act.END_TOPIC
    }

    /** 这一轮她要说什么（给提示词）：沉默/延迟也给一句，只是不发给用户 */
    fun line(act: Act): String = when (act) {
        Act.END_TOPIC -> "【收尾】回一句就把这个话题收住，别展开、别追问。"
        Act.DELAY -> "【晚点】这一轮隔了一会儿才回：可以显得有点慢，不必解释原因。"
        else -> ""
    }

    private fun round3(v: Double) = (v * 1000).toLong() / 1000.0
}
