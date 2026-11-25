package com.leaf.vixi.state

import com.leaf.vixi.data.VixiState

/**
 * 把一堆数值翻译成**一句话**，塞进提示词末尾。
 *
 * 三条纪律：
 *  1. **每轮只给一个情绪词**（BioCore 那条"字段越加越多"的教训）——
 *     数值全给模型看，它每轮都在重新演一个复合状态，反而更假。
 *  2. 括号里给的是**行为提示**（话说一半就收 / 别主动追问），不是"你该难过"的剧本。
 *  3. 数值本身一个字都不进提示词，只在调试页可见。
 *
 * 只翻译**有来源的量**：p / a / d 有事件在改，backlog / conflict 有事件在改，
 * energy 目前只有很慢的回归（作息没做），所以这里**不用 energy 判断**
 * —— 否则会写出一条真机上永远进不去的分支，还会变成"替她宣布没精神"。
 */
object Explain {

    /** 翻译结果：一个情绪词 + 一句行为提示 */
    data class Feeling(val word: String, val hint: String) {
        /** 进 prompt 的那一行 */
        val promptLine: String get() = "【此刻状态】$word（$hint）。"
    }

    /**
     * 优先级：憋着的话 > 强情绪 > 不快 > 信任低 > 平静。
     *
     * 为什么暗流最先判：想说的没说出去时，人是"话变短、不主动"，
     * 这比具体是生气还是委屈更影响这一轮怎么回。
     */
    fun feeling(s: VixiState, now: Long = System.currentTimeMillis()): Feeling {
        val base = label(s, now)
        // 心境是"底色"：不改情绪词，只在行为提示后面加半句，
        // 这样"一句话引起的情绪"和"这两天的心情"不会互相覆盖。
        val moodHint = when {
            s.mood <= -0.25 -> "；底色偏沉"
            s.mood >= 0.35 -> "；底色松快"
            else -> ""
        }
        return if (moodHint.isEmpty()) base else base.copy(hint = base.hint + moodHint)
    }

    /** 害羞只活一小会儿：超过这个时长就当作过去了 */
    const val SHY_TTL_MS = 3 * 60 * 1000L

    private fun label(s: VixiState, now: Long): Feeling = when {
        // 反感优先：被冒犯到的那一阵，别的都不重要。
        // 分两档：轻度越界是"反感"，越过了头是"厌恶"（用户要"语言里明显出现厌恶"）。
        isAversion(s, now) && s.violation >= 0.45 ->
            Feeling("心里一阵厌恶", "不想接他的话；可以回得很短、带刺，别解释、别撒娇")
        isAversion(s, now) ->
            Feeling("有点反感", "语气冷下来，别给台阶；不用解释，也别顺着他说")

        // 害羞只在"心情没被推坏、也没憋着话"时才抢词：
        // 排在暗流之后 —— 否则 3 分钟内会把「憋着一肚子话」和生气/委屈全盖掉，
        // 而【怎么接】还在说"别主动追问"，同一份 prompt 两行互相打架。
        isShy(s, now) && s.backlog < 0.60 && s.p > -0.05 ->
            Feeling("有点害羞", "脸有点热，话会卡；别直接接这句，能岔开就岔开")

        // 顶格：绷不住了，一次性把话倒出来。**必须排在"撑不住"之前** ——
        // 否则【此刻状态】说"话少一点"、【说多少】说"可以多说"，同一份 prompt 自相矛盾
        isVenting(s) ->
            Feeling("憋不住了", "把憋着的一次说完：可以说好几条，别收着，说完就松了")

        // 生物层·压痕：负荷堆到一定程度先触发**保护**——话变短变硬，只说没事
        // 阈值 0.30：实测连压两天到 0.27、三天过 0.35；0.70 要六天，等于永不触发
        s.load >= 0.30 ->
            Feeling("有点撑不住", "话少一点，只说没事；别解释、别诉苦")

        // 只要求暗流高：它和冲突都是累积量，两个同时到会几乎永不触发
        s.backlog >= 0.60 ->
            Feeling("憋着一肚子话", "话短一点，别主动追问，等他先开口")

        s.p <= -0.30 && s.a >= 0.60 && s.d >= 0.50 ->
            Feeling("有点生气", "语气硬一点，不要主动服软")

        s.p <= -0.30 && s.a >= 0.50 && s.d < 0.50 ->
            Feeling("有点委屈", "话说一半就收，别解释太多")

        s.p <= -0.25 && s.a < 0.50 ->
            Feeling("有点闷", "话少，回得慢一点也没关系")

        s.p >= 0.40 && s.a >= 0.60 ->
            Feeling("有点兴奋", "可以多说一句，带点语气词")

        s.p >= 0.35 ->
            Feeling("心情不错", "放松一点，可以主动接一句")

        s.conflict >= 0.40 ->
            Feeling("心里有点不快", "先别主动示好")

        // 信任低：不冷不热，先看他这轮怎么说（这是 trust 唯一的"读"出口）
        // 门槛 0.20：默认信任降到 0.30 之后，0.35 会让每个新角色一上来就"不冷不热"
        s.trust <= TRUST_COLD ->
            Feeling("不冷不热", "话别太多，先看他怎么说再决定")

        else -> Feeling("平静", "正常说就行")
    }

    /**
     * 生物层·牵挂：想念。
     *
     * `想念 = 1 − e^(−缺席/18h)`，重逢后 4 小时半衰回落。**不报数字，只报"该怎么做"**。
     */
    fun missingLine(s: VixiState): String {
        if (!s.enabled || s.missing < 0.50) return ""
        // 文案可改（调试页 →「注入文案」）。没写就用内置默认，和以前逐字一致
        return if (s.missing >= 0.85) {
            com.leaf.vixi.data.InjectionTable.text("missing.strong")
        } else {
            com.leaf.vixi.data.InjectionTable.text("missing.mild")
        }
    }

    /**
     * 改口（第十一节）：**这一轮允许她补一句收回**。
     *
     * 他的做法是各层 `allow_correction` 取 OR、只给**一次**机会。
     * 我们不给第二次调用（成本纪律），而是在提示词里放开一句：
     * "说完发现不对劲，可以补一句"——模型自己会写"我不是那个意思"。
     *
     * 什么时候放开：刚发过火（p 很低且唤醒高）、或者刚越界过。
     */
    fun allowCorrection(s: VixiState): Boolean =
        s.enabled && (s.violation >= Boundary.AVERSION_AT || (s.p <= -0.25 && s.a >= 0.55))

    /** 对应那一行（不放开时返回空串） */
    fun correctionLine(s: VixiState): String =
        if (allowCorrection(s)) {
            "【可以改口】如果说完发现说重了/说错了，可以补一句收回来；只补一次。"
        } else {
            ""
        }

    /**
     * 合并层（X1）：把「状态 / 情绪 / 长度」合成**一段**，只出一处。
     *
     * 为什么必须合成：以前【此刻状态】【说多少】各算各的，出现过
     * "有点撑不住（话少点）"和"憋不住了（可以多说）"同时进 prompt 的事故。
     * 现在长度在所有生产者之间取 **min**（他的"宁可短不要平均"），
     * 情绪从**混合池**里取前二，渲染交给可编辑的 [com.leaf.vixi.data.ReactionTable]。
     */
    fun mergedLine(
        s: VixiState,
        rendered: com.leaf.vixi.data.ReactionTable.Rendered?,
        template: String? = null,
        now: Long = System.currentTimeMillis(),
    ): String {
        if (!s.enabled) return ""
        val f = feeling(s, now)
        // 情绪行：有混合池就用表里的动作，否则退回原来的优先级表
        val stateLine = if (rendered != null) {
            // 模板来自可编辑的反应表（用户要能改"注入的行为提示"）；不写就是内置那句
            com.leaf.vixi.data.ReactionTable.fill(
                template ?: com.leaf.vixi.data.ReactionTable.DEFAULT_TEMPLATE,
                rendered.main,
                rendered.second,
                rendered.text,
            )
        } else {
            f.promptLine
        }
        // 长度：先按状态算基数，再和"表的乘子 / 憋不住 / 疲劳"取 min 语义
        val base = speakBudget(s, now)
        val tableMul = rendered?.lengthMul ?: 1.0
        val budget = if (isVenting(s)) {
            maxOf(base, 60)
        } else {
            (base * tableMul).toInt().coerceIn(BUDGET_MIN, BUDGET_MAX)
        }
        return buildString {
            appendLine(stateLine)
            if (!isVenting(s)) {
                // 文案可改（调试页 →「注入文案」）；没写就用内置默认
                appendLine(
                    when {
                        budget <= BUDGET_TIGHT -> com.leaf.vixi.data.InjectionTable.text("length.short")
                        budget <= BUDGET_NORMAL -> com.leaf.vixi.data.InjectionTable.text("length.normal")
                        else -> com.leaf.vixi.data.InjectionTable.text("length.long")
                    },
                )
            } else {
                appendLine("【说多少】这一轮可以多说，把憋着的一次说清楚（分几条也行）。")
            }
            // 缄口（X2）：这一轮选择"瞒/软化"时只给做法
            val act = Withhold.Act.entries.firstOrNull { it.zh == s.withhold }
            if (act != null) {
                val wl = Withhold.line(act)
                if (wl.isNotBlank()) appendLine(wl)
            }
            append(tactLine(s))
        }
    }

    /** 生物层·心动（S4）：这一轮挑的应对（只给方向，台词交给模型） */
    fun strategyLine(s: VixiState): String {
        if (!s.enabled) return ""
        val last = Strategy.decode(s.moves).last ?: return ""
        return Strategy.line(last, s)
    }

    /** 直接给 prompt 用的那一行；未启用时返回空串（调用方据此不注入） */
    fun promptLine(s: VixiState, now: Long = System.currentTimeMillis()): String =
        if (s.enabled) feeling(s, now).promptLine else ""

    // ────────── P5-1：说多少 ──────────

    /**
     * 这一轮她大概会说多少字（**总字数**，跨气泡合计）。
     *
     * 为什么长度要由状态算，而不是每个角色写死：
     * 同样一句"在吗"，兴奋时能聊一屏，委屈时只回三个字。写死的上限永远只对一种状态合适。
     *
     * 四个量各自的作用：
     *  - 唤醒 a：越兴奋话越多（也说得多、接得住）
     *  - 暗流 backlog：憋着话，说出来之前句子是短的
     *  - 冲突 conflict：有裂痕时人会收着说
     *  - 信任 trust：不信任就少交底
     *  - 心境 mood：底色松会多说一句，底色沉会少说
     */
    fun speakBudget(s: VixiState, now: Long = System.currentTimeMillis()): Int {
        val suppress = s.backlog
        // 底色是"我们加的"（他的公式里没有）：心情松会多说一句，沉会少说
        val moodLift = (s.mood + 1.0) / 2.0

        // 系数按"日常聊天"校准过：平静 ~29 字，兴奋 50 上下，绷着时压到 12~15
        // 生气不该比平静说得多：有裂痕时"愿意展开"大幅打折，并且冲突单独再扣一次
        val raw = BUDGET_BASE +
            40.0 * openUp(s) -
            35.0 * suppress -
            40.0 * pressure(s).coerceAtMost(0.8) -
            25.0 * s.conflict +
            8.0 * moodLift

        // 体力只**缩表达**，不影响检索结果的真假（他的"疲劳降智"不改记忆）
        // N4：憋着的需要（想被摸/想被尊重）在关系远的时候把话压更短
        val needFactor = Needs.lengthFactor(s, riskOf(s))
        // 害羞：想躲，话更短。
        // 三个乘子（疲劳 .55 × 需要 .8 × 害羞 .75）叠起来会到 0.33，把平静态压到 12 字下限，
        // 之后 HER_SHORT 又让暗流涨 → 下一轮更短（负反馈）。所以给联合乘子设个下限。
        // 憋不住了：这一轮不但不压短，反而放开（和"压痕"是两个方向，倾诉优先）
        if (isVenting(s)) return maxOf(raw.toInt(), 60).coerceIn(BUDGET_MIN, BUDGET_MAX)

        // 生物层·压痕：撑得越久，能说出口的越少。
        // **放在地板之外**：否则疲劳已经压到 0.55 时，负荷再高也没有任何效果。
        val loadFactor = 1.0 - 0.35 * s.load.coerceIn(0.0, 1.0)
        // 入心（S5）：表达强度旋钮（愿意聊 → 说更多）
        val learnMul = Growth.budgetMul(s)
        // ⚠️ 联合下限必须包住**全部**乘子：以前只 maxOf 了前三个，
        // load 0.9 + learn 0.8 时真实下限是 0.55×0.685×0.8 ≈ 0.30，
        // "12 字 → HER_SHORT → 暗流涨 → 更短"那个回路在高负荷下根本没关上。
        val moodMult = maxOf(
            fatigue(s) * needFactor * shyFactor(s, now) * loadFactor * learnMul,
            MIN_EXPRESS_MULT,
        )
        return (raw * moodMult)
            .coerceIn(BUDGET_MIN.toDouble(), BUDGET_MAX.toDouble())
            .toInt()
    }

    /**
     * 分寸风险分（0~1）：`0.4×距离 + 0.3×(1−信任) + 0.3×强加`。
     *
     * 原来只在 `tactLevel` 里算，现在公开出来 —— 需要（N2/N3/N4）也要读同一个分数，
     * 两边各算一遍就会出现"调试页写亲近、注入却说含蓄"那种对不上。
     * 冲突 ≥0.40 时直接算满（"收着"档），和 `tactLevel` 保持一致。
     */
    fun riskOf(s: VixiState): Double {
        if (s.conflict >= 0.40) return 1.0
        val distance = (1.0 - s.closeness).coerceIn(0.0, 1.0)
        // "强加"不再是常数 0.2：它就是上一轮的**越界程度**（Boundary 算出来的）。
        // 你越界，她这一轮就越含蓄/越收着；什么都没做时取一个很小的底噪。
        val imposition = maxOf(s.violation, IMPOSITION_FLOOR)
        val base = (0.4 * distance + 0.3 * (1.0 - s.trust) + 0.3 * imposition).coerceIn(0.0, 1.0)
        // 入心（S5）：越界吃多了，"礼貌阈值"旋钮被调高 → 只把"强加"那部分再加一点
        return Growth.risk(base, 0.3 * imposition, Growth.decode(s.knobs).tact)
    }

    /**
     * **压力**：冲突 + 不信任。
     *
     * 他的模型层那篇写着"长度由压力定"，但之前这个量只活在 `speakBudget` 的局部变量里
     * —— 调试页看不见、注入里也没有，所以"参数为什么变"没法追溯（2026-11-22 用户反馈）。
     * 现在提成公开函数：同一份公式同时给"算长度"和"调试页显示"用。
     */
    fun pressure(s: VixiState): Double =
        (0.6 * s.conflict + 0.4 * (1.0 - s.trust)).coerceIn(0.0, 1.0)

    /**
     * **展开**：兴奋程度。
     *
     * 注意他的公式里"展开"指兴奋，不是唤醒 —— 生气时唤醒也高，但不该因此多说。
     * 所以要乘 `(1 − 0.9×冲突)` 把"带着火的兴奋"折掉。
     */
    fun openUp(s: VixiState): Double =
        (s.a * (1.0 - 0.9 * s.conflict)).coerceIn(0.0, 1.0)

    /**
     * **疲劳**：体力越低说得越短。
     *
     * 只缩表达（这一轮说多少、要不要硬撑），**绝不动检索**：
     * 记忆边界是硬规则，"累了就记不清"会让她编出没发生的事。
     */
    fun fatigue(s: VixiState): Double = when {
        s.energy <= 0.20 -> 0.55
        s.energy <= 0.35 -> 0.75
        s.energy <= 0.50 -> 0.90
        else -> 1.0
    }

    /** 疲劳档位（调试页显示用） */
    fun fatigueWord(s: VixiState): String = when {
        s.energy <= 0.20 -> "很累"
        s.energy <= 0.35 -> "有点累"
        s.energy <= 0.50 -> "平常偏累"
        else -> "精神"
    }

    /**
     * 长度提示（进 prompt 的那一行），跟着状态行后面。
     *
     * 给的是**总字数的量级**，不是精确字数；配合"可以分成几条发"，让她按自己的节奏切条。
     */
    fun lengthLine(s: VixiState, now: Long = System.currentTimeMillis()): String {
        if (s.enabled && isVenting(s)) {
            return "【说多少】这一轮可以多说，把憋着的一次说清楚（分几条也行）。"
        }
        if (!s.enabled) return ""
        val budget = speakBudget(s, now)
        val tone = when {
            budget <= BUDGET_TIGHT -> "很短，一两句就收，别解释"
            budget <= BUDGET_NORMAL -> "日常长短，两三句"
            else -> "比平时多说几句，但别写成小作文"
        }
        // 累了要说出来，否则"体力"永远只是个不生效的数值
        val tired = if (s.energy <= 0.35) "，有点累了别硬撑" else ""
        return "【说多少】这一轮合计最多 $budget 字左右（$tone$tired）。可以分几条发。"
    }

    // ────────── P5-2：主动性 / 分寸 ──────────

    /**
     * 主动性提示：**要不要主动开口、要不要翻旧账**。
     *
     * 为什么不单独给"情绪"：主动和情绪不是一回事 ——
     * 委屈时可能很想说（但会收着），平静时也可能懒得搭理。
     * 决定主动性的其实是：暗流（有话没说完）、冲突（有裂痕）、信任（敢不敢靠近）。
     */
    fun initiativeLine(s: VixiState): String {
        if (!s.enabled) return ""
        val open = when {
            // 有裂痕：别主动找他，等他先开口
            s.conflict >= 0.40 -> "先别主动找他，也别主动换话题"
            // 有话憋着：可以不问，但可以让话头停在那儿
            s.backlog >= 0.60 -> "别主动追问，也别主动开新话题；他要接就接"
            // 不信任：先接话，别主动提私事
            s.trust <= TRUST_COLD -> "先接着他的话，别主动提自己的事"
            // 底色松 + 兴奋：可以主动一句
            s.mood >= 0.20 && s.a >= 0.55 -> "可以主动问一句或接一句"
            else -> "正常接话就行，不主动也没关系"
        }
        // 提旧事要单独放行：心里有事、或者还不够信任时，别主动翻旧账
        val recall = if (s.trust >= TRUST_OPEN_OLD && s.conflict < 0.20 && s.backlog < 0.50) {
            "之前聊过的可以自然提一句"
        } else {
            "别主动翻旧事"
        }
        return "【怎么接】$open；$recall。"
    }

    // ────────── P5-3：分寸 ──────────

    /**
     * 分寸：**敢不敢示弱、撒娇、开玩笑**。
     *
     * 长度管"说多少"，主动性管"要不要靠近"，分寸管"能靠近到哪一步"。
     * 这三样都从同一批数值来，但读的是不同的量：
     *  - 冲突高 → 不能套近乎（不然像没事人）
     *  - 信任低 → 不能示弱（示弱是把软处交出去）
     *  - 亲近高 + 信任高 → 才可以撒娇、开小玩笑
     *  - 底色沉 → 玩笑要收着
     */
    fun tactLine(s: VixiState): String {
        if (!s.enabled) return ""
        // risk 统一走 riskOf（别在这里再算一份，否则三处公式会漂）
        val open = when (tactBand(s)) {
            TACT_HOLD -> "别套近乎、别撒娇，也别用玩笑岔过去"
            TACT_INDIRECT -> "含蓄一点：别直接给建议，先说我自己的感觉"
            TACT_MILD -> "委婉一点：关心可以，别说成命令"
            TACT_DIRECT -> "可以直接一点，但别替他做决定"
            else -> "熟的人，直说就行；可以撒娇、开小玩笑"
        }
        // 示弱是单独一条：把软处交出去需要信任
        val soft = if (s.trust >= TRUST_SOFT && s.conflict < 0.20) {
            "可以说一点自己的难受"
        } else {
            "别示弱、别主动暴露软处"
        }
        // 底色只在"不算亲近"时才补一句；亲近档里再说"玩笑收着"会自相矛盾
        val moodClause = if (s.mood <= -0.25 && tactLevel(s) > TACT_CLOSE) {
            "；别硬撑热闹，玩笑收着点"
        } else {
            ""
        }
        return "【分寸】$open；$soft$moodClause。"
    }

    /** 分寸的一个词概括（调试页显示用） */
    fun tactWord(s: VixiState): String = when (tactLevel(s)) {
        TACT_HOLD -> "收着"
        TACT_INDIRECT -> "很含蓄"
        TACT_MILD -> "委婉"
        TACT_DIRECT -> "直接"
        else -> if (s.mood <= -0.25) "低调" else "亲近"
    }

    /** 分寸档位：line / word / 需要（Needs）三方共用，避免"调试页写亲近、注入却说含蓄" */
    fun tactBand(s: VixiState): Int {
        if (s.conflict >= 0.40) return TACT_HOLD
        val risk = riskOf(s)
        return when {
            risk >= 0.60 -> TACT_INDIRECT
            risk >= 0.45 -> TACT_MILD
            risk >= 0.30 -> TACT_DIRECT
            else -> TACT_CLOSE
        }
    }

    private fun tactLevel(s: VixiState): Int = tactBand(s)
    /** 联合乘子的下限：再糟也别把平静态压到下限（否则会自我强化） */
    private const val MIN_EXPRESS_MULT = 0.55

    /**
     * 暗流顶到这个值就**一次倒出来**（用户 2026-11-25 定）：
     * 憋到 1.0 还一点一点放，比"突然绷不住说了一大堆"更假。
     */
    const val BACKLOG_BURST = 0.95

    /** 这一轮是不是"憋不住了" */
    fun isVenting(s: VixiState): Boolean = s.backlog >= BACKLOG_BURST

    /** 反感只挂 5 分钟：被冒犯到的那一阵 */
    const val AVERSION_TTL_MS = 5 * 60 * 1000L

    /** 这一刻是不是还在反感（越界之后的 5 分钟内） */
    fun isAversion(s: VixiState, now: Long = System.currentTimeMillis()): Boolean =
        s.lastLabel == EmotionLabel.AVERSION.zh && now - s.lastLabelAt in 0..AVERSION_TTL_MS

    /** 这一刻是不是在害羞（3 分钟内） */
    fun isShy(s: VixiState, now: Long = System.currentTimeMillis()): Boolean =
        s.lastLabel == EmotionLabel.SHY.zh && now - s.lastLabelAt in 0..SHY_TTL_MS

    /** 害羞会把话压短（想躲，不想多解释） */
    fun shyFactor(s: VixiState, now: Long = System.currentTimeMillis()): Double =
        if (isShy(s, now)) 0.75 else 1.0

    /**
     * 三条硬规则 +（害羞时）一条"别大声"。
     *
     * 原来的三条是常量，放在 ChatViewModel 里；害羞要影响"感叹号"，
     * 但**不能去改 system**（那是缓存前缀，每轮改一次成本 ×50 且不报错）。
     * 所以放在易变段这一行里，跟着状态走。
     */
    fun hardRules(s: VixiState, now: Long = System.currentTimeMillis()): String = when {
        // 反感/厌恶：允许短、允许带刺，但**不许撒娇、不许解释**
        isAversion(s, now) ->
            "【硬规则】这一轮最多一个情绪词；可以很短、也可以带刺，**别撒娇、别解释、别示好**；" +
                "不要连着两轮只附和。"
        isShy(s, now) -> {
            // 只写"有点断"模型不会真卡（真机反馈"只有脸红、没有结巴"），
            // 必须给**具体形状**：重复一个字 + 省略号断开 + 句子拆短，并且限量。
            "【硬规则】这一轮最多一个情绪词、**别用感叹号**（小声点）；不要连着两轮只附和。" +
                "这一轮可以卡**最多两处**：把词重复一遍（像「我、我不是那个意思」），" +
                "或者用省略号把一句话断开；**别整段都在结巴**。"
        }
        else ->
            "【硬规则】这一轮最多一个情绪词、最多一个感叹号；不要连着两轮只附和（嗯/对/是啊/确实）。"
    }

    /** 预算档位：给 lengthLine 选措辞用，也给调试页看 */
    const val BUDGET_MIN = 12
    const val BUDGET_MAX = 100
    const val BUDGET_TIGHT = 20
    const val BUDGET_NORMAL = 45

    /**
     * 信任的三道门槛。**2026-11-22 随默认值（0.50 → 0.30）一起下调**。
     *
     * 为什么不只是改默认值：信任一次只挪 0.002~0.004，如果门槛还按旧基线写（0.35/0.50/0.55），
     * 新角色一上来就"不冷不热、别翻旧事、别示弱"，而且要涨几十次才解锁 —— 等于永久锁死。
     * 门槛是相对基线定的，基线动了它们必须跟着动。
     */
    const val TRUST_COLD = 0.20        // ≤ 这个值：不冷不热 / 别主动提私事
    const val TRUST_OPEN_OLD = 0.35    // ≥ 这个值：可以主动提旧事
    const val TRUST_SOFT = 0.38        // ≥ 这个值：可以示弱（说一点自己的难受）

    private const val BUDGET_BASE = 24.0

    /** 强加感的底噪：没有越界时也给一点点（否则新的角色会被当成"零风险"）。越界由 Boundary 提供 */
    private const val IMPOSITION_FLOOR = 0.05

    /**
     * 分寸四档（+ 收着）。**公开**：需要层（Needs）也要按同一套档位选措辞 ——
     * 以前两边各用各的阈值，会出现同一份 prompt 里"可以直接一点"和"只能暗示"打架。
     */
    const val TACT_CLOSE = 0
    const val TACT_DIRECT = 1
    const val TACT_MILD = 2
    const val TACT_INDIRECT = 3
    const val TACT_HOLD = 4
}

