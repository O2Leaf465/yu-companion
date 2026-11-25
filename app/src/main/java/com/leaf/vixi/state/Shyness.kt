package com.leaf.vixi.state

import com.leaf.vixi.data.VixiState

/**
 * 害羞（SHY）：被**看见**的那一刻想躲。
 *
 * 为什么单独一层而不是直接塞进评价层：
 *  害羞不是"你对我做了什么"，而是"这件事发生得太突然/太直白"。同一个夸奖，
 *  天天被夸的人不害羞，偶尔认真夸一次才害羞 —— 起作用的是**意外度**，不是利害。
 *
 * 口径（2026-11-22 与用户商定）：
 *  - 触发源：评价层已经判成**心动 / 开心 / 娇嗔**的那些（不用新造词表）
 *  - 强度 = 利害 × 亲近折扣 × 意外度 × (1 − 人设阈值)
 *  - 意外度 = 1 − 最近同类事件密度 + 夸奖强度跳变
 *  - 阈值来自**角色**（`Character.shy`，越大越不容易害羞）
 */
object Shyness {

    /** 生效门槛：低于它就不当回事（不注入、不改行为） */
    const val THRESHOLD = 0.50

    /** 最近窗口里同类事件（夸/示好/摸头）算密度的条数 */
    const val DENSITY_WINDOW = 20

    /** 强度跳变：比上一次同类高出这么多才算"突然" */
    const val JUMP = 0.35

    /**
     * 算这一轮的害羞强度（0~1）。
     *
     * @param label      评价层判出来的标签
     * @param intensity  强度（0~1）
     * @param prevIntensity 上一轮同源事件的强度（0 = 没有上一轮）——用来抓"突然变重"
     * @param shyBias    角色的人设阈值（0=很容易害羞，1=几乎不害羞）
     */
    fun evaluate(
        label: EmotionLabel,
        intensity: Double,
        prevIntensity: Double,
        state: VixiState,
        shyBias: Double = 0.5,
    ): Double {
        // 只有"被夸/被示好/被逗"这类才可能害羞（娇嗔在标签层就是心动或开心）
        if (label != EmotionLabel.TENDER && label != EmotionLabel.JOY) {
            return 0.0
        }
        if (intensity < 0.15) return 0.0

        // 同类事件密度：越密集 → 越习以为常（这就是"阈值随关系/频率升高"的机制）
        // 注意：分类结果（praise/affection/tease）**不会落成独立事件**，
        // 它们只出现在 EMOTION 事件的 note 里（`心动·praise·原文`）。
        // 第一版按独立事件类型数，真机上密度恒为 0 —— 测试因为手工造了事件才绿。
        val recent = state.events.takeLast(DENSITY_WINDOW)
        val same = recent.count {
            it.type == StateEventType.PAT.key ||
                (it.type == StateEventType.EMOTION.key &&
                    (it.note.contains("praise") || it.note.contains("affection") || it.note.contains("tease")))
        }
        val density = same.toDouble() / DENSITY_WINDOW
        // 夸奖强度跳变：这次比上次重很多 → 突然。
        // 注意"跳变"只在**已经有点常见**的时候才有意义：密度 0 时意外度本来就是满的，
        // 再叠加会被 clamp 吃掉（第一版就是这么写成死代码的）。
        val jumped = if (prevIntensity > 0 && intensity - prevIntensity >= JUMP) 1.0 else 0.0
        val base = (1.0 - 2.0 * density).coerceIn(0.0, 1.0)
        val surprise = (base + 0.5 * jumped * (1.0 - base)).coerceIn(0.0, 1.0)

        // 关系越近，"看见"的冲击越大（被陌生人夸是客气，被在意的人夸才会慌）
        val bond = (0.5 + 0.5 * state.closeness).coerceIn(0.0, 1.0)
        // ×3 是增益，按真机尺度校准过：
        //   默认人设（bias .5）+ 刚认识（亲近 .2 → bond .6）+ 一句普通夸（强度 .6）→ 0.54 ✓ 过门槛
        //   「天天被夸」（密度 .4 → 意外度 .2）→ 0.10 ✗ 不触发
        //   bias 1.0（几乎不害羞）→ 0 ✗
        val raw = intensity * bond * surprise * (1.0 - shyBias.coerceIn(0.0, 1.0)) * 3.0
        return raw.coerceIn(0.0, 1.0)
    }
}
