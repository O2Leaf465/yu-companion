package com.leaf.vixi.data

import android.content.Context
import com.leaf.vixi.state.EmotionLabel
import com.leaf.vixi.state.EmotionMix
import java.io.File

/**
 * 「情绪 → 反应」表（2026-11-25 用户要求：**注入的标签要便于更改**）。
 *
 * 同一个「委屈」，内向的人设是收着、直率的人设是边哭边骂 —— 所以这张表
 * **不是写死在代码里**，而是每个角色一份可编辑的文本：
 *
 * ```
 * # 主情绪>次情绪=动作|长度乘子
 * 委屈=话说一半就收，别解释太多|0.7
 * 委屈>生气=想怼回去，但更多是委屈，话只说一半|0.75
 * ```
 *
 * 找不到精确组合时依次退：`主>次` → `主` → 内置默认 → 不发指令（绝不让它变哑巴）。
 */
class ReactionTable(context: Context) {

    private val dir = context.filesDir

    private fun fileOf(charId: String) = File(dir, "emotion_map_$charId.txt")

    fun raw(charId: String): String =
        runCatching { fileOf(charId).takeIf { it.exists() }?.readText(Charsets.UTF_8) }
            .getOrNull() ?: DEFAULT

    fun save(charId: String, text: String): Boolean = runCatching {
        fileOf(charId).writeText(text, Charsets.UTF_8)
    }.isSuccess

    fun reset(charId: String): Boolean = runCatching {
        fileOf(charId).takeIf { it.exists() }?.delete()
        true
    }.getOrDefault(false)

    /** 一行渲染好的结果 */
    data class Rendered(val main: EmotionLabel, val second: EmotionLabel?, val text: String, val lengthMul: Double)

    /**
     * 这一轮"注入行为提示"的模板（用户要求：**可修改**）。
     *
     * 在同一个可编辑文件里写一行：
     * ```
     * _模板=【此刻状态】{main}{second}（{act}）。
     * ```
     * 占位符：`{main}` 主情绪、`{second}` 次情绪（没有就空）、`{act}` 表里的动作。
     * 不写就用下面的默认值。
     */
    fun template(charId: String): String = parse(raw(charId))[TEMPLATE_KEY]?.text ?: DEFAULT_TEMPLATE

    /** 把模板填成一行（没写模板时和原来的输出完全一样） */

    /** 把情绪池渲染成「一句话 + 长度乘子」 */
    fun render(mix: List<EmotionMix.Pair>, fallback: EmotionLabel?): Rendered? {
        val main = mix.firstOrNull()?.label ?: fallback ?: return null
        val second = mix.getOrNull(1)
            ?.takeIf { it.w >= (mix[0].w * 0.45) }
            ?.label
        val table = parse(raw(LAST_CHAR_ID))
        val hit = (second?.let { table["${main.zh}>${it.zh}"] } ?: table[main.zh] ?: parse(DEFAULT)[main.zh])
            ?: return null
        return Rendered(main, second, hit.text, smooth(hit.mul, mix))
    }

    /** 缓存"当前角色"，避免每轮把 Context 传进来 */
    fun bind(charId: String) {
        LAST_CHAR_ID = charId
    }

    data class Entry(val text: String, val mul: Double)

    companion object {
        /** 模板那一行的键名 */
        const val TEMPLATE_KEY = "_模板"

        /** 默认模板：和以前写死的那句一模一样，避免"没写模板就变样" */
        const val DEFAULT_TEMPLATE = "【此刻状态】{main}{second}（{act}）。"

        /** 把模板填成一行（没写模板时和原来的输出完全一样） */
        fun fill(tpl: String, main: EmotionLabel, second: EmotionLabel?, act: String): String {
            val secondPart = second?.let { "里混着一点${it.zh}" } ?: ""
            return tpl.replace("{main}", main.zh)
                .replace("{second}", secondPart)
                .replace("{act}", act)
        }
        /** 当前正在聊的角色（由 ViewModel 在切会话时 bind） */
        @Volatile
        private var LAST_CHAR_ID: String = "c1"

        /**
         * 长度：**加权几何平均**（乘子意义上的平均）。
         * 55% 委屈(0.7) + 30% 生气(1.0) ≈ ×0.83 —— 不是取最小，也不是算术平均。
         */
        fun smooth(base: Double, mix: List<EmotionMix.Pair>): Double {
            if (mix.isEmpty()) return base
            val total = mix.sumOf { it.w }
            if (total <= 0.0) return base
            val ln = mix.sumOf {
                val m = when (it.label) {
                    EmotionLabel.HURT -> 0.70
                    EmotionLabel.SHY -> 0.75
                    EmotionLabel.AVERSION -> 0.85
                    EmotionLabel.SHAME -> 0.85
                    EmotionLabel.ANGER -> 1.05
                    EmotionLabel.EXCITED -> 1.15
                    else -> 1.0
                }
                it.w * Math.log(m)
            }
            return (base * Math.exp(ln / total)).coerceIn(0.5, 1.5)
        }

        /** 解析文本表；`#` 开头是注释；`A>B=动作|0.8` 或 `A=动作` */
        fun parse(text: String): Map<String, Entry> = buildMap {
            text.lineSequence().forEach { line ->
                val l = line.trim()
                if (l.isEmpty() || l.startsWith("#")) return@forEach
                val eq = l.indexOf('=')
                if (eq <= 0) return@forEach
                val key = l.substring(0, eq).trim()
                val rhs = l.substring(eq + 1).trim()
                if (key.isEmpty() || rhs.isEmpty()) return@forEach
                val bar = if (key.startsWith("_")) -1 else rhs.lastIndexOf('|')
                val (body, mul) = if (bar > 0) {
                    val m = rhs.substring(bar + 1).trim().toDoubleOrNull()
                    if (m != null) rhs.substring(0, bar).trim() to m.coerceIn(0.5, 1.5) else rhs to 1.0
                } else {
                    rhs to 1.0
                }
                put(key, Entry(body, mul))
            }
        }

        /**
         * 内置默认表：覆盖最常见的组合，其余走「只用主情绪」。
         * 这张表是**性格中性的**，人设细节请在三张（每个角色一份）里改。
         */
        val DEFAULT: String = """
# 主情绪>次情绪=动作|长度乘子
# 只写主情绪也行；没写的组合会自动退回「只用主情绪」
平静=正常说就行|1.0
开心=放松一点，可以主动接一句|1.0
开心>害羞=笑一下又低头，不太接话|0.8
心动=语气软一点，可以主动一句|1.0
害羞=脸有点热，话会卡；别直接接这句，能岔开就岔开|0.75
害羞>开心=想笑又不好意思，话说得断|0.8
兴奋=可以多说一句，带点语气词|1.15
生气=语气硬一点，不要主动服软|1.05
生气>委屈=硬完又软，说完会补一句|0.85
委屈=话说一半就收，别解释太多|0.7
委屈>生气=想怼回去，但更多是委屈，话只说一半|0.75
自责=往心里去，话说得少|0.8
担心=先问一句他怎么了，别下判断|0.95
失落=话少，回得慢一点也没关系|0.75
烦=短回，别接梗|0.8
反感=语气冷下来，别给台阶；不用解释|0.85
反感>生气=不想多说，但语气带刺|0.8
        """.trimIndent()
    }
}
