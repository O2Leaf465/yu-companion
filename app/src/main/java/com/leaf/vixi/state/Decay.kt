package com.leaf.vixi.state

import com.leaf.vixi.data.VixiState
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * 时间衰减：没有新消息时，数值自己往基线回落。
 *
 * 为什么不启定时器：手机被系统杀掉、息屏省电、关机都不该影响结果。
 * 做法是**惰性补算** —— 只在"读状态"的那一刻，用 `现在 − lastTick` 一次性把这段时间的
 * 回落补上，然后写回 `lastTick`。结果和每秒算一次完全一样，但不耗电。
 *
 * 谁不受影响（故意）：
 *   · 信任/亲近 —— 只由事件改
 *
 * 暗流 backlog **原来写的是"不衰减"**，理由是"没说出口的话不会自己消失"。
 * 但 2026-11-24 用户发现它**只涨不降**：`her_short`/委屈/自责/越界都在加，
 * 没有任何一条在减 → 聊几天就顶到 1.0，状态行永远"憋着一肚子话"、主动开口永远被锁。
 * 现在三处出闸：她说得多（`her_long`）、主动发问（`her_ask`）、你道歉（`apologize`），
 * 外加 10 小时半衰 —— 闷一晚上会淡，但不会隔一分钟就没。
 *
 * 冲突 conflict 会衰减（24 小时半衰）：吵完不道歉，一天后也消一半。
 * 不衰减的话 `conflict ≥ 0.20` 会把"信任回升"永久锁死 —— 加召回再激活之后更糟，
 * 因为想起旧事会涨 conflict（生气 +0.06、委屈 +0.03），闭环会自己把关系焊死。
 */
object Decay {

    /**
     * 心情回落半衰期：**90 分钟**（正式值）。
     *
     * 注意：这是**缓冲池**的半衰。情绪池追它（[INERTIA_TAU_MS]），所以情绪实际比 90 分钟慢。
     * 调试时想立刻看到回落，就把它改小——但**别改到和 INERTIA_TAU_MS 相等**，
     * 那样两池联立的解会退化（代码里有兜底，但手感会变得奇怪）。
     */
    const val DECAY_HALF_LIFE_MS = 90 * 60 * 1000L

    /** 心境回落半衰期：6 小时（"今天有点闷"能挂一天，但挂不了三天） */
    const val MOOD_HALF_LIFE_MS = 6 * 60 * 60 * 1000L

    /**
     * 缓冲池把余温倒进情绪池的时间常数：**30 分钟**。
     *
     * 比情绪半衰（90 分钟）快，所以"一句话只能当场兑现四分之一，剩下半小时内陆续到账"。
     */
    const val INERTIA_TAU_MS = 30 * 60 * 1000L

    /** 想念的累积半衰（缺席多久算"很想"）：18 小时 */
    const val MISSING_TAU_MS = 18 * 60 * 60 * 1000L

    /** 想念回落半衰：4 小时（**有滞后**——刚见面不会立刻不想他） */
    const val MISSING_RELEASE_HALF_MS = 4 * 60 * 60 * 1000L

    /**
     * 负荷的"一轮"折算成多久（他的公式是按轮写的，我们按时间化）。
     *
     * 取 **10 分钟**：一天高强度（压力 0.8）约 144 轮 → 负荷涨到 0.3 左右，
     * 连压两三天才到"撑不住"。用 30 分钟会慢到永远到不了 0.7。
     */
    const val LOAD_ROUND_MS = 10 * 60 * 1000L

    /** 暗流的自然回落：10 小时半衰（"没说出口的话"不会立刻消失，但也不会挂一辈子） */
    const val BACKLOG_HALF_LIFE_MS = 10 * 60 * 60 * 1000L

    /** 修复量（repair）的半衰期：3 小时；同时它会被"信任回升"消耗掉 */
    const val REPAIR_HALF_LIFE_MS = 3 * 60 * 60 * 1000L

    /**
     * 冲突自然衰减半衰期：**24 小时**。
     *
     * 比情绪（90 分钟）慢得多、比心境（6 小时）还慢：一场架不该隔夜就忘，
     * 但也不该"不道歉就永远 0.4"。24 小时让 0.40 → 0.20 → 0.10，
     * 第二天她还记得这件事，只是没那么绷了。
     */
    const val CONFLICT_HALF_LIFE_MS = 24 * 60 * 60 * 1000L

    /**
     * 修复量衰减掉的部分里，有多少变成信任。
     * 0.5 = 一半变信任、一半真的消散；repair 0.5 最终约 +0.25 信任（收敛，不无限涨）。
     */
    private const val RECOVERY_SHARE = 0.5

    /**
     * 冲突高于这条线时，**修复量冻结不衰减**（等到裂痕降到 0.20 以下再开始消耗）。
     *
     * 为什么需要：repair 半衰 3h、conflict 半衰 24h，差 8 倍。
     * 真吵架后道歉给 0.06，会让它在 conflict 还没跨过门槛前就掉到 0.02 以下，
     * 于是"道歉"这条修复路径在真吵架时永远不生效（用户 2026-11-25 审查发现）。
     */
    private const val REPAIR_FREEZE_CONFLICT = 0.20

    /**
     * 体力回归半衰期：**2 小时**。
     *
     * 以前是 6 小时、目标是常数 0.8，而没有任何事件改 energy
     * —— 所以真机上它永远卡在 0.80 附近，看起来"根本没变化"（用户 2026-11-22 反馈）。
     * 现在目标跟着**本机时间**走（见 [energyBaseAt]），2 小时足够让它跟上昼夜变化。
     */
    const val ENERGY_HALF_LIFE_MS = 2 * 60 * 60 * 1000L

    /**
     * 这个小时"该有多少体力"（昼夜曲线，0~1）。
     *
     * 作息系统没做，先用本机时间近似一天：凌晨最低、上午回升、午后小低谷、傍晚最高。
     * 关键点之间线性插值 —— 平滑到"不会因为整点跳一格"，又不至于要一条公式。
     *
     * 这是**目标值**，不是当前值：当前值按 [ENERGY_HALF_LIFE_MS] 追它，
     * 所以半夜三点打开 App，体力是被"拉低"而不是瞬间归零。
     */
    fun energyBaseAt(hourOfDay: Double): Double {
        val h = ((hourOfDay % 24) + 24) % 24
        val pts = listOf(
            0.0 to 0.30,   // 半夜
            3.0 to 0.22,   // 最困
            6.0 to 0.45,   // 起床
            9.0 to 0.80,   // 上午
            13.0 to 0.72,  // 午后低谷
            15.0 to 0.85,  // 下午最好
            22.0 to 0.70,  // 晚上
            24.0 to 0.30,  // 又到半夜（和 0 点对齐，保证连续）
        )
        pts.zipWithNext { (h0, v0), (h1, v1) ->
            if (h >= h0 && h <= h1) {
                val t = if (h1 == h0) 0.0 else (h - h0) / (h1 - h0)
                return v0 + (v1 - v0) * t
            }
        }
        return 0.30
    }

    /** 从毫秒时间戳取"本地几点"（含小数，便于插值） */
    fun hourOf(now: Long): Double {
        val z = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault())
        return z.hour + z.minute / 60.0
    }

    /** 此刻的体力目标（调试页显示用："为什么体力不动"就是"当前值已经贴着目标了"） */
    fun energyTarget(now: Long = System.currentTimeMillis()): Double = energyBaseAt(hourOf(now))

    /**
     * 把 [now] 之前这段时间的回落补上。
     *
     * @param hour 覆盖"现在几点"（测试用；不传就按 [now] 的本地时间）
     * @return 结算后的状态（`lastTick = now`）；未启用或时钟没往前走时原样返回
     */
    fun settle(
        state: VixiState,
        now: Long = System.currentTimeMillis(),
        hour: Double? = null,
    ): VixiState {
        if (!state.enabled) return state
        // 第一次结算（lastTick 还是 0）没有"过去"可补，只把基准定到现在
        if (state.lastTick <= 0L) return state.copy(lastTick = now)
        val dt = now - state.lastTick
        if (dt <= 0L) return state

        // 指数衰减：经过 1 个半衰期，离基线的距离剩一半
        val f = 1.0 - 0.5.pow(dt.toDouble() / DECAY_HALF_LIFE_MS)
        val fe = 1.0 - 0.5.pow(dt.toDouble() / ENERGY_HALF_LIFE_MS)

        val fm = 1.0 - 0.5.pow(dt.toDouble() / MOOD_HALF_LIFE_MS)
        val fr = 1.0 - 0.5.pow(dt.toDouble() / REPAIR_HALF_LIFE_MS)
        val fc = 1.0 - 0.5.pow(dt.toDouble() / CONFLICT_HALF_LIFE_MS)
        val fb = 1.0 - 0.5.pow(dt.toDouble() / BACKLOG_HALF_LIFE_MS)
        val fi = 1.0 - 0.5.pow(dt.toDouble() / INERTIA_TAU_MS)
        // 两池联立要用的两个衰减率（每秒）
        val ls = Math.log(2.0) / (DECAY_HALF_LIFE_MS / 1000.0)
        val ld = Math.log(2.0) / (INERTIA_TAU_MS / 1000.0)
        val LS = ls
        val LD = ld
        val xS = Math.exp(-ls * (dt / 1000.0))
        val xD = Math.exp(-ld * (dt / 1000.0))
        val fmis = 1.0 - 0.5.pow(dt.toDouble() / MISSING_RELEASE_HALF_MS)

        // 修复成功（冲突已经下来）时，修复量**衰减掉的那部分**有一半变成信任。
        //
        // 为什么用"衰减量"而不是"每次结算的上限"：
        // 以前写成 min(0.02, repair × dt/3h)，那个 0.02 是**每次结算**的上限 ——
        // 调试页每 2 秒结算一次，同一段时间里会被放大 14 倍（3 小时一次 +0.02，2 秒一次 +0.29）。
        // 现在 recovery 与衰减量成比例，分多少段结算结果都一样，而且不会和衰减重复扣。
        // 冲突先按时间降，再判断能不能回升信任：这样"吵完等一天"也是一条修复路径，
        // 不必非等到一句道歉。顺序反了的话，decay 永远不会让 conflict 跨过 0.20 那道线。
        // ⚠️ 这里**不取三位**：conflict 半衰 24 小时，30 秒一轮的单步变化只有 2.4e-4，
        // 取三位会把每步都抹成 0，而 lastTick 照旧推到 now —— 那段衰减永远补不回来。
        val conflictNow = (state.conflict * (1.0 - fc)).coerceIn(0.0, 1.0)

        // 修复量在"裂痕还没消"的时候**冻结**，不衰减。
        //
        // 为什么：repair 半衰 3 小时、conflict 半衰 24 小时，差 8 倍。
        // 真吵一架（conflict 0.35）之后道歉拿到的 repair 0.06，
        // 会在 conflict 降到 0.20 门槛之前就先掉到 0.02 以下 —— 修复量白白过期，
        // "道歉"这条路径在真吵架时永远涨不了信任。冻结之后它会一直等到门开。
        val decays = if (conflictNow >= REPAIR_FREEZE_CONFLICT) 0.0 else state.repair * fr
        val canRecover = state.repair > 0.02 && conflictNow < 0.20
        val recovery = if (canRecover) decays * RECOVERY_SHARE else 0.0

        // 体力的目标跟着钟点走：半夜打开 App 它是被"拉到 0.3"，不是瞬间归零
        val energyTarget = energyBaseAt(hour ?: hourOf(now))

        // ── 需要（N1）：时间和事件一起推动 ──
        // touch：没人碰就会慢慢想被碰（每小时 +0.06）
        // respect：**长时间不出现侮辱/贬低就缓慢恢复**（用户定的口径，每小时 −0.08/6）
        val hours = dt / 3_600_000.0

        // ⚠️ 摸头这条要**先看关系够不够近**（用户 2026-11-23 指出）：
        // 亲近 < Boundary.PAT_MIN_CLOSENESS 时，摸头会被判成越界（扣亲近、扣心情、涨"被尊重"）。
        // 如果这种状态下还让她"想被摸"，就会出现**死锁**：她越想要 → 越要不到 → 一摸反而挨罚。
        // 所以：还不能被摸的时候，这个需要**不涨**。
        val touchReady = state.closeness >= Boundary.PAT_MIN_CLOSENESS
        val touchNow = if (touchReady) {
            (state.needTouch + Needs.TOUCH_GROW_PER_HOUR * hours).coerceAtMost(1.0)
        } else {
            state.needTouch
        }
        val respectNow = (state.needRespect - Needs.RESPECT_RECOVER_PER_HOUR * hours).coerceAtLeast(0.0)

        // ── 生物层·余温（S1）──
        // 缓冲池先按情绪半衰朝基线回落
        //（"余温"就是它：一句话进来的量先存在这儿，然后按 90 分钟半衰散去）
        val surgeP = state.baseP + (state.surgeP - state.baseP) * (1.0 - f)
        val surgeA = state.baseA + (state.surgeA - state.baseA) * (1.0 - f)
        val surgeD = state.baseD + (state.surgeD - state.baseD) * (1.0 - f)

        // ── 生物层·牵挂（S2）──
        // 想念 = 1 − e^(−缺席/18h)；重逢后按 4 小时半衰回落（有滞后，不会一见面就清零）
        val absent = if (state.lastHeardAt > 0L) (now - state.lastHeardAt).coerceAtLeast(0L) else 0L
        val missingTarget = if (state.lastHeardAt <= 0L) {
            0.0
        } else {
            1.0 - Math.exp(-absent.toDouble() / MISSING_TAU_MS)
        }
        val missingNow = if (missingTarget >= state.missing) {
            missingTarget
        } else {
            state.missing + (missingTarget - state.missing) * fmis
        }

        // ── 生物层·压痕（S3）──
        // 负荷 += 0.01 × (压力 − 0.55) × (1 − 负荷)，压力不足时同样这个式子往回走（自调节）
        val pressureBase = (0.6 * conflictNow + 0.4 * (1.0 - state.trust)).coerceIn(0.0, 1.0)
        // 单次结算最多算一天的量：隔三天回来一次结算 432 轮会把负荷推向 0（长窗口恒定低估压力）
        val rounds = (dt.toDouble() / LOAD_ROUND_MS).coerceIn(-144.0, 144.0)
        val loadNow = (state.load + 0.01 * (pressureBase - 0.55) * (1.0 - state.load) * rounds)
            .coerceIn(0.0, 1.0)

        // 缓冲池把余温**倒进**情绪池：30 分钟时间常数。
        // 两池联立有精确解 u(t) = C·e^(−λs·t) + (u0−C)·e^(−λd·t) —— 用它而不是分步近似，
        // 否则"每 2 秒结算一次"和"三小时结算一次"会得到不同结果（调试页每 2 秒就会结算一次）。
        fun drain(p0: Double, s0: Double, base: Double): Double {
            val u0 = p0 - base
            val v0 = s0 - base
            // 两个衰减率太接近时 C = λd·v0/(λd−λs) 会炸（改了半衰期常量就会踩到）。
            // 那时退化成"单池跟随"：p 直接取缓冲池的值。
            if (Math.abs(LD - LS) < 1e-9) return base + v0 * xS
            val c = LD * v0 / (LD - LS)
            return base + c * xS + (u0 - c) * xD
        }
        // 注意传的是 **state.surgeP**（积分起点），不是已经衰减过的 surgeP —— 否则会衰减两次
        val pNow = drain(state.p, state.surgeP, state.baseP)
        val aNow = drain(state.a, state.surgeA, state.baseA)
        val dNow = drain(state.d, state.surgeD, state.baseD)

        // 入心（S5）：学到的旋钮会淡忘（只升不降会把分寸锁死）
        val knobsNow = if (state.learnOn) {
            Growth.encode(Growth.relax(Growth.decode(state.knobs), dt))
        } else {
            state.knobs
        }

        return state.copy(
            knobs = knobsNow,
            // 混合情绪池跟着情绪一起淡
            emoMix = com.leaf.vixi.state.EmotionMix.decay(state.emoMix, dt),
            // ⚠️ 这里**一律不取三位**。settle 是惰性结算：每次只算"上次到现在"这一段，
            // 把变化量舍到 0.001 一档等于把这一段衰减直接丢掉（lastTick 仍然推到 now），
            // 结算越频繁丢得越多 —— 调试页每 2 秒结算一次时会把慢变量彻底冻住。
            // 需要"好看的数字"的地方（调试页、落盘）自己格式化，不靠状态本身凑整。
            p = pNow.coerceIn(-1.0, 1.0),
            a = aNow.coerceIn(0.0, 1.0),
            d = dNow.coerceIn(0.0, 1.0),
            surgeP = surgeP,
            surgeA = surgeA,
            surgeD = surgeD,
            load = loadNow,
            missing = missingNow,
            energy = state.energy + (energyTarget - state.energy) * fe,
            // 心境朝**基线**回落（和 p 同一个基准，否则 baseP≠0 时两者会分叉）
            mood = state.baseP + (state.mood - state.baseP) * (1.0 - fm),
            repair = (state.repair - decays).coerceAtLeast(0.0),
            trust = (state.trust + recovery).coerceAtMost(1.0),
            conflict = conflictNow,
            // 暗流朝 0 回落
            backlog = (state.backlog * (1.0 - fb)).coerceAtLeast(0.0),
            needTouch = touchNow,
            needRespect = respectNow,
            lastTick = now,
        )
    }

}
