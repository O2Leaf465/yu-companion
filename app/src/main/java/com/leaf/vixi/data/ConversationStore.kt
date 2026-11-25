package com.leaf.vixi.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 会话表：单聊（一个人）或群聊（多个人）。
 *
 * 一个会话对应一份聊天记录 `chat_<id>.jsonl`；
 * 单聊与单聊之间**记忆完全隔离**，群聊的记忆可以在成员的单聊里被检索到（见设计文档）。
 */
data class Conversation(
    val id: String,
    val kind: String,        // "single" | "group"
    val memberIds: List<String>,
    val title: String,
    val createdAt: Long = 0L,
) {
    val isGroup: Boolean get() = kind == "group"
}

class ConversationStore(context: Context) {

    private val file = File(context.filesDir, "conversations.json")
    private val currentFile = File(context.filesDir, "mem/current_conv.txt")

    fun all(): List<Conversation> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText(Charsets.UTF_8))
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val members = mutableListOf<String>()
                o.optJSONArray("memberIds")?.let { a ->
                    (0 until a.length()).forEach { members += a.optString(it) }
                }
                Conversation(
                    id = o.optString("id"),
                    kind = o.optString("kind", "single"),
                    memberIds = members,
                    title = o.optString("title"),
                    createdAt = o.optLong("createdAt"),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun get(id: String?): Conversation? = all().firstOrNull { it.id == id }

    private fun write(list: List<Conversation>) {
        val arr = JSONArray()
        list.forEach { c ->
            arr.put(
                JSONObject()
                    .put("id", c.id)
                    .put("kind", c.kind)
                    .put("memberIds", JSONArray(c.memberIds))
                    .put("title", c.title)
                    .put("createdAt", c.createdAt)
            )
        }
        runCatching { file.writeText(arr.toString(), Charsets.UTF_8) }
    }

    var currentId: String
        get() = runCatching { currentFile.readText(Charsets.UTF_8).trim() }.getOrDefault("")
        set(value) = runCatching {
            currentFile.parentFile?.mkdirs()
            currentFile.writeText(value, Charsets.UTF_8)
        }.let { }

    /** 下一个空位 id：不能让两个会话共用一个 id —— 那等于两份记录写同一个 chat_v3.jsonl */
    private fun nextId(list: List<Conversation>): String {
        var n = list.size + 1
        while (list.any { it.id == "v$n" }) n++
        return "v$n"
    }

    /** 确保至少存在一个"角色对应的单聊"，找不到就建一个 */
    fun ensureSingleFor(character: Character): Conversation {
        val list = all()
        val found = list.firstOrNull { !it.isGroup && it.memberIds == listOf(character.id) }
        if (found != null) return found
        val conv = Conversation(
            id = nextId(list),
            kind = "single",
            memberIds = listOf(character.id),
            title = character.name,
            createdAt = System.currentTimeMillis(),
        )
        write(list + conv)
        return conv
    }

    fun createGroup(title: String, memberIds: List<String>): Conversation? {
        if (memberIds.size < 2) return null
        val list = all()
        val conv = Conversation(
            id = nextId(list),
            kind = "group",
            memberIds = memberIds,
            title = title.trim().ifBlank { "群聊" },
            createdAt = System.currentTimeMillis(),
        )
        write(list + conv)
        return conv
    }

    /** 群聊 id：给某个角色所在的所有群（用于"群记忆下渗到单聊"） */
    fun groupsOf(characterId: String): List<Conversation> =
        all().filter { it.isGroup && characterId in it.memberIds }
}
