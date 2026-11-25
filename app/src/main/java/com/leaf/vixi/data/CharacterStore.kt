package com.leaf.vixi.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 角色表。一个角色 = 名字 + 人设。
 *
 * 为什么人设存在 json 里而不是每个角色一个文件：角色数量少、体积小，
 * 集中一份方便整体读写和迁移；早期单角色时代的 persona_override.md 会在
 * 第一次启动时搬到 c1 这条记录里。
 *
 * 格式约定（怎么分条、不许编造记忆）**是全局的**，不在这里 —— 它不属于某个角色。
 */
data class Character(
    val id: String,
    val name: String,
    val persona: String,
    val createdAt: Long = 0L,
    /**
     * 害羞阈值（0~1，默认 0.5）。**数字越大越不容易害羞**。
     *
     * 为什么放在角色上而不是全局：害羞是性格的一部分 —— 内向/傲娇的一被夸就炸，
     * 大大咧咧的只会笑。旧记录没有这个字段，parse 按 0.5 兜底。
     */
    val shy: Double = 0.5,
)

class CharacterStore(context: Context) {

    private val file = File(context.filesDir, "characters.json")
    private val assets = context.assets

    fun defaultPersona(): String = runCatching {
        assets.open("persona.md").bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
    }.getOrDefault("你是一只小鲸鱼。")

    fun all(): List<Character> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText(Charsets.UTF_8))
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Character(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    persona = o.optString("persona"),
                    createdAt = o.optLong("createdAt"),
                    shy = o.optDouble("shy", 0.5).coerceIn(0.0, 1.0),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun get(id: String?): Character? = all().firstOrNull { it.id == id }

    /** 默认角色（表里的第一个，正常就是迁移过来的 c1）：设置页「人设」编辑的是他 */
    fun primary(): Character? = all().firstOrNull()

    private fun write(list: List<Character>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("persona", it.persona)
                    .put("createdAt", it.createdAt)
                    .put("shy", it.shy)
            )
        }
        runCatching {
            // 原子写：导出/归档线程可能同时在读 characters.json，
            // 直接 writeText 覆盖会让它们读到半截 JSON（runCatching 一吞，那一轮人设全丢且无声）
            val tmp = File(file.parentFile, "characters.json.tmp")
            tmp.writeText(arr.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
    }

    fun create(name: String, persona: String): Character? {
        val cleanName = name.trim().ifBlank { return null }
        val list = all().toMutableList()
        if (list.any { it.name == cleanName }) return null
        val next = (list.size + 1)
        val c = Character(
            id = "c$next",
            name = cleanName,
            persona = persona.trim().ifBlank { defaultPersona() },
            createdAt = System.currentTimeMillis(),
        )
        // id 可能撞（删过角色），往后找空位
        var candidate = c
        var n = next
        while (list.any { it.id == candidate.id }) {
            n++
            candidate = c.copy(id = "c$n")
        }
        list += candidate
        write(list)
        return candidate
    }

    /** 改名字或人设；重名返回 null（气泡和 prompt 都靠名字对齐，重名会直接串台） */
    fun update(
        id: String,
        name: String? = null,
        persona: String? = null,
        shy: Double? = null,
    ): Character? {
        val list = all().toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return null
        val old = list[idx]
        val newName = name?.trim()?.takeIf { it.isNotBlank() } ?: old.name
        if (newName != old.name && list.any { it.name == newName }) return null
        val next = old.copy(
            name = newName,
            persona = persona?.trim()?.ifBlank { old.persona } ?: old.persona,
            shy = shy?.coerceIn(0.0, 1.0) ?: old.shy,
        )
        list[idx] = next
        write(list)
        return next
    }

    fun savePersona(id: String, persona: String): Boolean =
        if (persona.isBlank()) false else update(id, persona = persona) != null

    /** 首次启动的迁移：把旧的单角色人设变成 c1 */
    fun ensureMigrated(legacyPersona: String): Character {
        val existing = all()
        if (existing.isNotEmpty()) return existing.first()

        val migratedName = "鱼"
        val migrated = Character(
            id = "c1",
            name = migratedName,
            persona = legacyPersona.ifBlank { defaultPersona() },
            createdAt = System.currentTimeMillis(),
        )
        write(listOf(migrated))
        return migrated
    }
}
