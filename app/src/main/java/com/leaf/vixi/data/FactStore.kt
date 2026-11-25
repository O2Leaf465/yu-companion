package com.leaf.vixi.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 长期人物事实（2026-11-25 用户设计，这里落成代码）。
 *
 * 和"记忆检索"是两件事，别混：
 *  · **记忆**是原文，靠检索、会过期、可以被遗忘；
 *  · **事实**是结构化的一条断言（"喜欢草莓"），**每轮都进提示词**，不衰减。
 *
 * 为什么不做成"模型每轮返回一个 JSON 字段"：
 * 那等于每轮多要一次结构化输出，模型会把"刚才有点难过"也写成事实。
 * 这里用一条**可选标签** `[记住:…]`（和 `[引用:]` / `[图:]` 同一套，本地剥），
 * 只在真的出现"关于她的稳定信息"时才写。
 *
 * 三个纪律：
 * 1. **一人一份文件**：`files/facts_<角色id>.jsonl` —— 群聊里谁说的事实就记在谁头上。
 * 2. **写入前过关**：长度 4~40、去重（子串互相包含算同一条）、每人上限 [MAX] 条。
 * 3. **注入在 user 段**、不在 system 段：system 是缓存前缀，事实会变，写进去等于每轮烧一次钱。
 */
class FactStore(context: Context) {

    private val dir = context.filesDir

    data class Fact(val text: String, val at: Long)

    private fun fileOf(charId: String) = File(dir, "facts_${sanitize(charId)}.jsonl")

    fun list(charId: String): List<Fact> = runCatching {
        val f = fileOf(charId)
        if (!f.exists()) return emptyList()
        f.readLines(Charsets.UTF_8).filter { it.isNotBlank() }.mapNotNull { line ->
            runCatching {
                val o = JSONObject(line)
                val t = o.optString("text").trim()
                if (t.isEmpty()) null else Fact(t, o.optLong("at", 0L))
            }.getOrNull()
        }
    }.getOrDefault(emptyList())

    /**
     * @return true = 真的新增了（false = 太短/重复/已满）
     *
     * 去重不只看完全相等："喜欢草莓" 和 "她喜欢草莓" 算同一条 —— 用互相包含判断，
     * 否则模型换个说法就能把同一件事记三遍。
     */
    fun add(charId: String, raw: String, convId: String? = null): Boolean {
        val text = normalize(raw) ?: return false
        val cur = list(charId)
        if (cur.size >= MAX) return false
        if (cur.any { same(it.text, text) }) return false
        return runCatching {
            val f = fileOf(charId)
            f.parentFile?.mkdirs()
            val o = JSONObject()
                .put("text", text)
                .put("at", System.currentTimeMillis())
                .put("conv", convId ?: "")
            f.appendText(o.toString() + "\n", Charsets.UTF_8)
            true
        }.getOrDefault(false)
    }

    fun remove(charId: String, text: String): Boolean = runCatching {
        val f = fileOf(charId)
        if (!f.exists()) return false
        val keep = f.readLines(Charsets.UTF_8).filter { line ->
            if (line.isBlank()) false
            else runCatching { JSONObject(line).optString("text") != text }.getOrDefault(true)
        }
        f.writeText(keep.joinToString("") { it + "\n" }, Charsets.UTF_8)
        true
    }.getOrDefault(false)

    fun clear(charId: String): Boolean = runCatching {
        fileOf(charId).takeIf { it.exists() }?.delete() ?: false
    }.getOrDefault(false)

    /**
     * 注入用的一段话。**群聊要带名字**，否则"喜欢草莓"会变成整个群共有的设定。
     * 空表返回空串 —— 调用方据此不注入，行为与加这个功能之前一致。
     */
    fun block(charIds: List<String>, nameOf: (String) -> String, group: Boolean): String {
        val rows = charIds.mapNotNull { id ->
            val facts = list(id)
            if (facts.isEmpty()) return@mapNotNull null
            val head = if (group) "${nameOf(id)}：" else ""
            head to facts
        }
        if (rows.isEmpty()) return ""
        return buildString {
            appendLine("【关于" + (if (group) "她们" else "她") + "的事实（长期有效，不用问、不用复述出处）】")
            rows.forEach { (head, facts) ->
                facts.takeLast(INJECT_MAX).forEach { appendLine("- " + head + it.text) }
            }
            append("（这些是已经确认过的设定；跟正文冲突时以这些为准。别把它们念出来。）")
        }
    }

    private fun normalize(raw: String): String? {
        val t = raw.trim().trimEnd('。', '.', '，', ',').trim()
        if (t.length < 4 || t.length > 40) return null
        return t
    }

    /**
     * 互相包含 + 去掉"她/你"之后的相似，都算同一条。
     *
     * ⚠️ **但"喜不喜欢"必须分得清**（2026-11-25 审查发现）：单纯用包含判断的话，
     * 「不喜欢草莓」包含「喜欢草莓」→ 被当成重复丢掉，反义永远写不进去，
     * 提示词里就一直留着旧说法。所以包含判定还要**否定词数量相同**。
     */
    private fun same(a: String, b: String): Boolean {
        fun norm(s: String) = s.replace("她", "").replace("你", "").replace(" ", "")
        fun negs(s: String) = s.count { it in "不没别勿无非" }
        val x = norm(a)
        val y = norm(b)
        if (x.isEmpty() || y.isEmpty()) return a == b
        if (x == y) return true
        return (x.contains(y) || y.contains(x)) && negs(x) == negs(y)
    }

    private fun sanitize(s: String) = s.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "c1" }

    companion object {
        /** 每人上限：超过就不再写（避免提示词里挂一长串） */
        const val MAX = 40

        /** 注入时最多带几条（取最近的） */
        const val INJECT_MAX = 20
    }
}
