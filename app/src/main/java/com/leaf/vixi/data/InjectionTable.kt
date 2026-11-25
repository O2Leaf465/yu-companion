package com.leaf.vixi.data

import android.content.Context
import java.io.File

/**
 * 注入文案表（2026-11-25 用户要求：**「想念」「被在意」这类标签注入也要能在调试页改**）。
 *
 * 为什么单独一张表：这些都是"给模型的一句话"，不是数值。
 * 数值调错了是手感不对，这句话调错了是**语气不对** —— 而语气只有你自己知道想要什么味道。
 * 写死在代码里就只能等我改代码、重新构建、重新装包，太慢。
 *
 * 每个角色一份：`files/injection_<角色id>.txt`，一行一个键：
 * ```
 * # 只改你想改的行，删掉某行就回到默认
 * missing.mild=【想念】有点想他：可以顺口提一句「好久没见」，别撒娇过头。
 * ```
 * 找不到（没写这一行）就用内置默认 —— 所以**这个文件不存在时行为和以前逐字一致**。
 *
 * 注意：`（别把这条念出来。）` 这种尾巴由代码补，不写进表里。
 */
class InjectionTable(context: Context) {

    private val dir = context.filesDir

    private fun fileOf(charId: String) = File(dir, "injection_$charId.txt")

    /** 给编辑框看的内容：文件不存在时给它一份带注释的默认模板 */
    fun raw(charId: String): String = runCatching {
        fileOf(charId).takeIf { it.exists() }?.readText(Charsets.UTF_8)
    }.getOrNull() ?: TEMPLATE

    fun save(charId: String, text: String): Boolean = runCatching {
        fileOf(charId).parentFile?.mkdirs()
        fileOf(charId).writeText(text, Charsets.UTF_8)
    }.isSuccess

    fun reset(charId: String): Boolean = runCatching {
        fileOf(charId).takeIf { it.exists() }?.delete()
        true
    }.getOrDefault(false)

    /** 切会话/切角色时调一次：把这一份的覆盖项装进全局，纯函数那边才读得到 */
    fun bind(charId: String?) {
        LAST_CHAR_ID = charId
        current = if (charId == null) emptyMap() else parse(raw(charId))
    }

    companion object {

        /** 内置默认：**和改造前的字面量逐字一致**（不写文件的角色行为完全不变） */
        val DEFAULTS: Map<String, String> = linkedMapOf(
            "missing.mild" to "【想念】有点想他：可以顺口提一句「好久没见」，别撒娇过头。",
            "missing.strong" to "【想念】好几天没好好说话了：可以先问一句他这些天怎么样，别一上来就埋怨。",
            "need.touch.close" to "【想要什么】你现在很想被摸头。可以直接说，也可以把话往那儿引。",
            "need.touch.mild" to "【想要什么】你想被摸头，但不太好意思开口。只能暗示，别直接要。",
            "need.belong" to "【想要什么】你想确认自己还被在意。可以问一句，但别追问、别讨。",
            "need.respect.warm" to
                "【想要什么】你觉得他最近说话不够尊重你。可以直接要求他放尊重一点，也可以冷下来少说两句。",
            "need.respect.cold" to
                "【想要什么】你觉得他最近说话不够尊重你。不用讨好：把语气冷下来、少说两句就行。",
            "length.short" to "【说多少】很短，一两句就够。",
            "length.normal" to "【说多少】两三句，别铺开。",
            "length.long" to "【说多少】可以多说两句，但别写成一段。",
        )

        /** 编辑框里的初始模板 */
        val TEMPLATE: String = buildString {
            appendLine("# 注入文案（每个角色一份）。改哪行写哪行，删掉就回默认。")
            appendLine("# 键 = 文案。不要在这里写动态内容（数值/情绪由代码补）。")
            appendLine("# 「（别把这条念出来。）」这种尾巴代码会自己补齐，不用写。")
            appendLine()
            DEFAULTS.forEach { (k, v) -> appendLine("$k=$v") }
        }

        private var current: Map<String, String> = emptyMap()

        /** 调试页要改的是哪个角色（和 ReactionTable 同一套用法） */
        var LAST_CHAR_ID: String? = null

        /** 取文案：覆盖 → 默认 → 空串（空串调用方当"不注入"处理） */
        fun text(key: String): String = current[key] ?: DEFAULTS[key] ?: ""

        private fun parse(text: String): Map<String, String> {
            val out = HashMap<String, String>()
            text.lineSequence().forEach { rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEach
                val i = line.indexOf('=')
                if (i <= 0) return@forEach
                val key = line.substring(0, i).trim()
                val value = line.substring(i + 1).trim()
                if (key.isNotEmpty() && value.isNotEmpty()) out[key] = value
            }
            return out
        }
    }
}
