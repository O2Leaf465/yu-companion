package com.leaf.vixi.llm

import org.json.JSONObject

/**
 * 长期事实的**结构化返回**（2026-11-25 用户定稿）。
 *
 * 上一版让模型在正文里写 `[记住:喜欢草莓]` —— 实测不准确：标签会混在语气里、
 * 有时被正文改写、有时干脆不写。改成"回复 + 一个固定的机器尾巴"：
 *
 * ```
 * 那家店我记着呢。
 * ###FACT {"fact":"喜欢草莓"}
 * ```
 *
 * 三条约定：
 * 1. **尾巴必须是最后一行**，本地按最后一个 `###FACT` 切；切出来的部分不进气泡。
 * 2. **每轮都要给**：没有事实就写 `###FACT null` —— 强制它每轮做一次判断，
 *    而不是"想起来了才写"（这是用户方案里最关键的半步）。
 * 3. 群聊带 `who`：`{"who":"Nami","fact":"喜欢草莓"}`，否则不知道该记在谁头上。
 *
 * 解析只做"尽力而为"：尾巴坏了（不是 JSON、缺 fact）就当没有，绝不让整轮回复失败 ——
 * 她的话比一个字段重要。
 */
object FactBlock {

    private const val MARK = "###FACT"

    data class Item(val who: String?, val fact: String)

    data class Parsed(val body: String, val items: List<Item>) {
        val hasMark: Boolean get() = items.isNotEmpty() || marked
        /** 这一轮有没有出现过尾巴（哪怕内容是 null）——用来判断模型有没有按格式回 */
        internal var marked: Boolean = false
    }

    fun split(raw: String): Parsed {
        val i = raw.lastIndexOf(MARK)
        if (i < 0) return Parsed(raw.trim(), emptyList())

        val body = raw.substring(0, i).trim()
        val tail = raw.substring(i + MARK.length).trim()
        val items = parseTail(tail)
        return Parsed(body.ifBlank { "……" }, items).also { it.marked = true }
    }

    private fun parseTail(tail: String): List<Item> {
        if (tail.isBlank()) return emptyList()
        if (tail.equals("null", ignoreCase = true) || tail == "无") return emptyList()

        // 允许它把对象写在 ```json 里
        val cleaned = tail
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()

        val obj = runCatching { JSONObject(cleaned.substring(cleaned.indexOf('{')).substringBeforeLast('}') + "}") }
            .getOrNull() ?: return emptyList()

        // 兼容一次给多条：{"facts":[{...},{...}]}
        val arr = obj.optJSONArray("facts")
        val out = ArrayList<Item>()
        if (arr != null) {
            for (k in 0 until arr.length()) {
                arr.optJSONObject(k)?.let { one(it)?.let(out::add) }
            }
            return out
        }
        one(obj)?.let(out::add)
        return out
    }

    private fun one(o: JSONObject): Item? {
        val fact = o.optString("fact").trim()
        if (fact.isBlank() || fact == "null") return null
        val who = o.optString("who").trim().ifBlank { null }
        return Item(who, fact)
    }
}
