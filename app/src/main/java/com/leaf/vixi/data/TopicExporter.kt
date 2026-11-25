package com.leaf.vixi.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 话题归档时导出一份 Markdown，**两处都写**：
 *   - 应用私有 `files/export/`（PC 端脚本可以直接拉）
 *   - 手机公共目录 `Documents/鱼/`（Obsidian 安卓版可以把这个文件夹当库打开）
 *
 * 公共目录用 MediaStore 写入，API 29+ 不需要任何存储权限。
 */
object TopicExporter {

    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm")
    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

    /** 手机上 Obsidian 能打开的目录：Documents/鱼 */
    private const val PUBLIC_DIR = "鱼"

    fun write(
        context: Context,
        topic: TopicStore.Topic,
        messages: List<StoredMessage>,
        /** 会话子目录：单聊 = 角色名，群聊 = 群-群名（多会话之后不能再全堆在一个文件夹里） */
        subDir: String = "",
        /** 查不到 speakerId 时用谁的名字：单聊里就是那个角色，不能一律写「鱼」 */
        selfName: String = "鱼",
        /** 记忆索引聚出来的元数据（重要度/情绪/PAD/关系），写进 frontmatter 供筛选用 */
        meta: TopicMemory? = null,
        /** 线索索引（人名/时间/数字）—— 正文可以走，索引必留 */
        entities: List<String> = emptyList(),
        prevFile: String? = null,
        nextFile: String? = null,
        sameEmotionFiles: List<String> = emptyList(),
        /** 这一段里被改口过的说法（他的"冲突标记/版本链"最小形态） */
        corrections: List<String> = emptyList(),
    ): File? {
        if (messages.isEmpty()) return null
        return runCatching {
            val dir = File(context.filesDir, "export").apply { mkdirs() }
                .let { base ->
                    if (subDir.isBlank()) base
                    else File(base, sanitize(subDir)).apply { mkdirs() }
                }
            val zone = ZoneId.systemDefault()
            val start = Instant.ofEpochMilli(topic.startedAt).atZone(zone)
            val title = sanitize(topic.title).ifBlank { "无题" }
            // 说话人名字按角色 id 查一次：一条话题里可能有好几个人说话
            val names = CharacterStore(context).all().associate { it.id to it.name }

            val body = buildString {
                appendLine("---")
                appendLine("date: ${STAMP.format(start)}")
                appendLine("messages: ${messages.size}")
                appendLine("minutes: ${((topic.endedAt - topic.startedAt) / 60_000L).coerceAtLeast(1)}")
                if (entities.isNotEmpty()) {
                    appendLine("entities: [${entities.joinToString(", ")}]")
                }
                if (meta != null) {
                    appendLine("emotion: ${meta.label.ifBlank { "平静" }}")
                    appendLine("importance: ${fmt2(meta.importance)}")
                    appendLine("pad: [${fmt2(meta.p)}, ${fmt2(meta.a)}, ${fmt2(meta.d)}]")
                    appendLine("trust: ${fmt2(meta.trust)}")
                    appendLine("closeness: ${fmt2(meta.closeness)}")
                    appendLine("conflict: ${fmt2(meta.conflict)}")
                    appendLine("mood: ${fmt2(meta.mood)}")
                }
                appendLine("tags:")
                appendLine("  - 鱼")
                appendLine("  - 话题")
                if (meta != null && meta.label.isNotBlank()) appendLine("  - 情绪/${meta.label}")
                if (meta != null) appendLine("  - 重要度/${importanceBand(meta.importance)}")
                if (meta?.pinned == true) appendLine("  - 钉住")
                if (subDir.isNotBlank()) appendLine("  - ${sanitize(subDir)}")
                if (corrections.isNotEmpty()) appendLine("  - 改口")
                appendLine("---")
                appendLine()
                appendLine("# $title")
                appendLine()
                val where = if (subDir.isBlank()) "鱼" else "鱼/${sanitize(subDir)}"
                appendLine("[[鱼]] · $where · ${start.monthValue}月${start.dayOfMonth}日 ${CLOCK.format(start)} 起 · ${messages.size} 条")
                if (meta != null) {
                    appendLine()
                    appendLine("> [!info] 记忆点")
                    appendLine("> 情绪 **${meta.label.ifBlank { "平静" }}** · 重要度 **${fmt2(meta.importance)}**（${importanceBand(meta.importance)}）")
                    appendLine("> PAD ${fmt2(meta.p)} / ${fmt2(meta.a)} / ${fmt2(meta.d)} · 心境 ${fmt2(meta.mood)}")
                    appendLine("> 关系 信任 ${fmt2(meta.trust)} · 亲近 ${fmt2(meta.closeness)} · 冲突 ${fmt2(meta.conflict)}")
                    if (meta.pinned) appendLine("> 📌 **钉住**（承诺/里程碑/自我定义，不参与遗忘）")
                }
                appendLine()
                // 索引区：正文可以走，索引必留
                if (entities.isNotEmpty()) appendLine("- 线索：" + entities.joinToString("、"))
                if (prevFile != null || nextFile != null) {
                    appendLine("- 前后：" +
                        (prevFile?.let { "← [[${it.removeSuffix(".md")}]]" } ?: "") +
                        " ｜ " +
                        (nextFile?.let { "[[${it.removeSuffix(".md")}]] →" } ?: ""))
                }
                if (sameEmotionFiles.isNotEmpty()) {
                    appendLine("- 同情绪：" + sameEmotionFiles.joinToString("、") { "[[${it.removeSuffix(".md")}]]" })
                }
                if (corrections.isNotEmpty()) {
                    appendLine()
                    appendLine("> [!warning] 改口记录（旧说法已作废，以新的为准）")
                    corrections.take(3).forEach { appendLine("> - $it") }
                }
                if (entities.isNotEmpty() || prevFile != null || sameEmotionFiles.isNotEmpty()) appendLine()
                messages.forEach { m ->
                    val who = if (m.fromUser) "Leaf" else (names[m.speakerId] ?: selfName)
                    val whenText = CLOCK.format(Instant.ofEpochMilli(m.ts).atZone(zone))
                    if (m.kind == MsgKind.ACTION) {
                        appendLine("> *（${m.text}）*")
                        appendLine()
                    } else {
                        val text = if (m.text.isBlank()) "（图片）" else m.text
                        appendLine("**$who** `$whenText`  ")
                        text.split("\n").forEach { appendLine("> $it") }
                        appendLine()
                    }
                }
            }

            val fileName = "${STAMP.format(start)} $title.md"
            val file = File(dir, fileName)
            file.writeText(body, Charsets.UTF_8)
            publish(context, fileName, body, subDir)
            file
        }.getOrNull()
    }

    /**
     * 同时写一份到手机公共目录 `Documents/鱼/`，Obsidian 安卓版可以直接把这个文件夹当库打开。
     * 用 MediaStore 插入：API 29+ 不需要任何存储权限，也不会污染应用私有目录。
     */
    private fun publish(
        context: Context,
        fileName: String,
        body: String,
        subDir: String = "",
        mime: String = "text/markdown",
    ) {
        runCatching {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
            val resolver = context.contentResolver
            // 相对路径必须参与匹配：只按文件名删，会把**另一个会话**的同名笔记删掉
            // （两个会话同一分钟归档、标题又都叫「（无题）」时就会撞名）
            val relPath = "${Environment.DIRECTORY_DOCUMENTS}/$PUBLIC_DIR" +
                if (subDir.isBlank()) "/" else "/" + sanitize(subDir) + "/"
            resolver.query(
                MediaStore.Files.getContentUri("external"),
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.RELATIVE_PATH),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(fileName, relPath),
                null,
            )?.use { c ->
                val idIdx = c.getColumnIndex(MediaStore.MediaColumns._ID)
                while (c.moveToNext()) {
                    val id = c.getLong(idIdx)
                    runCatching {
                        resolver.delete(
                            MediaStore.Files.getContentUri("external", id), null, null
                        )
                    }
                }
            }

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            }
            val uri = resolver.insert(MediaStore.Files.getContentUri("external"), values) ?: return
            resolver.openOutputStream(uri)?.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
    }

    /**
     * 删掉某个会话在公共目录里的导出笔记（`文档/鱼/<subDir>/`），返回删掉几个。
     *
     * 供「删除聊天记录」用：记忆删了、OB 里还留着整段对话，等于没删。
     *
     * 三条安全线（都是踩过/审出来的）：
     *  1. `subDir` 空白直接放弃 —— 否则前缀变成 `文档/鱼/`，**所有会话**的笔记和 `_备份` 一起没
     *  2. 用 `RELATIVE_PATH = ?` **精确匹配**目录，不用 LIKE：`_`/`%` 是通配符（角色名「50%鱼」「a_b」会误伤），
     *     前缀匹配也会连坐「群-鱼2」
     *  3. `自我叙事.md` 里「我想写下的」是用户手写的内容，**留着**
     */
    fun purgePublished(context: Context, subDir: String): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        if (subDir.isBlank()) return 0
        val dir = "${Environment.DIRECTORY_DOCUMENTS}/$PUBLIC_DIR/${sanitize(subDir)}/"
        return deleteWhere(context) {
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ?" to arrayOf(dir)
        }
    }

    /** 删掉 `文档/鱼/_备份/` 里的某一个备份文件（"连备份一起删"要删到公共目录那份） */
    fun purgePublishedBackup(context: Context, fileName: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        if (fileName.isBlank()) return false
        val dir = "${Environment.DIRECTORY_DOCUMENTS}/$PUBLIC_DIR/_备份/"
        return deleteWhere(context) {
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?" to
                arrayOf(dir, fileName)
        } > 0
    }

    private fun deleteWhere(
        context: Context,
        selection: () -> Pair<String, Array<String>>,
    ): Int {
        val resolver = context.contentResolver
        val (sel, args) = selection()
        var n = 0
        resolver.query(
            MediaStore.Files.getContentUri("external"),
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
            ),
            sel,
            args,
            null,
        )?.use { c ->
            val idIdx = c.getColumnIndex(MediaStore.MediaColumns._ID)
            val nameIdx = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            while (c.moveToNext()) {
                val name = if (nameIdx >= 0) c.getString(nameIdx) else null
                if (name == RecordArchive.KEEP_ON_PURGE) continue
                val id = c.getLong(idIdx)
                val ok = runCatching {
                    resolver.delete(MediaStore.Files.getContentUri("external", id), null, null) > 0
                }.getOrDefault(false)
                if (ok) n++
            }
        }
        return n
    }

    /**
     * 全量导出：把历史按"30 分钟间隔"切段，每段一篇笔记，全部写到 Documents/鱼/<会话>。
     * 供设置页的「导出到 Obsidian」按钮使用。
     */
    fun exportAll(
        context: Context,
        messages: List<StoredMessage>,
        subDir: String = "",
        selfName: String = "鱼",
        /** 索引里聚出来的记忆点（按时间最近匹配到每段，用来写 frontmatter） */
        metas: List<TopicMemory> = emptyList(),
        /** 索引原文条目：用来给每段抽"线索"（人名/时间/数字） */
        entries: List<IndexEntry> = emptyList(),
        /** 改口记录（时间 + 内容）：落在对应段的笔记里 */
        corrections: List<Pair<Long, String>> = emptyList(),
    ): List<Pair<Long, String>> {
        if (messages.isEmpty()) return emptyList()
        val sorted = messages.sortedBy { it.ts }
        val segments = mutableListOf<MutableList<StoredMessage>>()
        var cur = mutableListOf<StoredMessage>()
        var prev = 0L
        sorted.forEach { m ->
            if (prev > 0 && m.ts - prev > 30 * 60_000L && cur.isNotEmpty()) {
                segments += cur
                cur = mutableListOf()
            }
            cur += m
            prev = m.ts
        }
        if (cur.isNotEmpty()) segments += cur

        // 两遍：先算出每段的名字与元数据（这样才能写"前后 / 同情绪"链接），再落盘
        data class Item(
            val segment: List<StoredMessage>,
            val topic: TopicStore.Topic,
            val fileName: String,
            val meta: TopicMemory?,
            val entities: List<String>,
        )

        val collected = segments.filter { it.isNotEmpty() }.map { seg ->
            val firstUser = seg.firstOrNull { it.fromUser } ?: seg.first()
            val topic = TopicStore.Topic(
                id = seg.first().ts,
                title = sanitize(firstUser.text).ifBlank { "无题" },
                startedAt = seg.first().ts,
                endedAt = seg.last().ts,
                messageCount = seg.size,
            )
            // 只在同一段（±30 分钟）里找元数据：索引被裁剪后，邻近段不该借给这一段
            val meta = metas
                .filter { kotlin.math.abs(it.startTs - topic.startedAt) <= 30 * 60_000L }
                .minByOrNull { kotlin.math.abs(it.startTs - topic.startedAt) }
            val inside = entries.filter { it.ts in topic.startedAt..topic.endedAt }
                .flatMap { it.entities }
                .distinct()
                .take(6)
            Item(seg, topic, "${STAMP.format(Instant.ofEpochMilli(topic.startedAt).atZone(ZoneId.systemDefault()))} ${topic.title}.md", meta, inside)
        }

        val written = mutableListOf<Pair<Long, String>>()
        collected.forEachIndexed { i, item ->
            val prev = collected.getOrNull(i - 1)?.fileName
            val next = collected.getOrNull(i + 1)?.fileName
            val label = item.meta?.label.orEmpty()
            val same = if (label.isBlank()) emptyList() else collected
                .filterIndexed { j, other -> j != i && other.meta?.label == label }
                .sortedBy { kotlin.math.abs(it.topic.startedAt - item.topic.startedAt) }
                .take(3)
                .map { it.fileName }
            val fixed = corrections
                .filter { it.first in item.topic.startedAt..item.topic.endedAt }
                .map { it.second }
            val f = write(
                context, item.topic, item.segment, subDir, selfName,
                item.meta, item.entities, prev, next, same, fixed,
            )
            if (f != null) written += item.topic.startedAt to f.name
        }
        return written
    }

    /** 按 30 分钟间隔切段（导出与可视化必须用同一套切法，否则 canvas 引用的文件名对不上） */
    fun segments(messages: List<StoredMessage>): List<List<StoredMessage>> {
        if (messages.isEmpty()) return emptyList()
        val sorted = messages.sortedBy { it.ts }
        val out = mutableListOf<List<StoredMessage>>()
        var cur = mutableListOf<StoredMessage>()
        var prev = 0L
        sorted.forEach { m ->
            if (prev > 0 && m.ts - prev > 30 * 60_000L && cur.isNotEmpty()) {
                out += cur
                cur = mutableListOf()
            }
            cur += m
            prev = m.ts
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    private fun fmt2(v: Double) = String.format(java.util.Locale.US, "%.2f", v)

    private fun importanceBand(v: Double) = when {
        v >= 0.75 -> "高"
        v >= 0.55 -> "中"
        else -> "低"
    }

    /** 情绪 → Obsidian Canvas 的颜色编号（1红 2橙 3黄 4绿 5青 6紫） */
    private fun canvasColor(label: String): String = when {
        label.contains("生气") -> "1"
        label.contains("委屈") -> "6"
        label.contains("心动") || label.contains("兴奋") -> "3"
        label.contains("开心") -> "4"
        label.contains("失落") || label.contains("闷") || label.contains("不快") -> "5"
        else -> ""
    }

    /** 情绪聚类的中心点：同一种情绪摆在一起，像一团一团 */
    private fun clusterCenter(label: String): Pair<Int, Int> = when {
        label.contains("生气") -> -640 to -360
        label.contains("委屈") -> -200 to -520
        label.contains("心动") || label.contains("兴奋") -> 620 to -420
        label.contains("开心") -> 640 to -40
        label.contains("失落") || label.contains("闷") -> -680 to 220
        else -> 60 to 260
    }

    /**
     * 导出可视化：`_记忆地图.canvas` + `_索引.md` + `自我叙事.md`。
     *
     * Canvas 的映射：
     *  · 颜色 = 主导情绪（红生气 / 紫委屈 / 黄心动兴奋 / 绿开心 / 青失落闷 / 默认平静）
     *  · 大小 = 重要度（150×82 到 460×253）
     *  · 位置 = 情绪聚类（同情绪成团，团内按时间排）
     * 不生成任何事件摘要：`自我叙事.md` 只放当前状态数值快照。
     */
    fun writeVault(
        context: Context,
        subDir: String,
        memories: List<TopicMemory>,
        titles: Map<Long, String> = emptyMap(),
        /** 当前状态快照（自我叙事只用数值，不写摘要） */
        snapshot: Map<String, String> = emptyMap(),
    ): Int {
        if (memories.isEmpty() && snapshot.isEmpty()) return 0
        return runCatching {
            val dir = File(context.filesDir, "export").apply { mkdirs() }
                .let { base ->
                    if (subDir.isBlank()) base
                    else File(base, sanitize(subDir)).apply { mkdirs() }
                }
            val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm")
            val zone = ZoneId.systemDefault()

            // ── 1) Canvas：情绪聚类 + 大小按重要度 ──
            val nodes = org.json.JSONArray()
            val edges = org.json.JSONArray()
            val byCluster = memories.groupBy { canvasColor(it.label) }
            var seq = 0
            byCluster.forEach { (color, list) ->
                val (cx, cy) = clusterCenter(list.first().label)
                list.sortedBy { it.startTs }.forEachIndexed { i, m ->
                    val size = (150 + 310 * ((m.importance - 0.35) / 0.65).coerceIn(0.0, 1.0)).toInt()
                    val perRow = 3
                    val x = cx + (i % perRow) * (size / 2 + 40)
                    val y = cy + (i / perRow) * (size / 3 + 30)
                    val title = titles[m.topicId] ?: sanitizeDate(m.startTs, stamp, zone)
                    val fileName = "${sanitizeDate(m.startTs, stamp, zone)} $title.md"
                    val node = org.json.JSONObject().apply {
                        put("id", "m${seq++}")
                        put("type", "file")
                        put("file", fileName)
                        put("x", x)
                        put("y", y)
                        put("width", size)
                        put("height", (size * 0.55).toInt())
                        if (color.isNotBlank()) put("color", color)
                    }
                    nodes.put(node)
                }
            }
            // 同一情绪团内按时间连细线（超过 10 个就不连，免得糊成一团）
            byCluster.forEach { (_, list) ->
                val ids = list.sortedBy { it.startTs }.mapIndexed { i, _ -> "m${
                    memories.sortedBy { it.startTs }.indexOfFirst { it === list.sortedBy { a -> a.startTs }[i] }
                }" }
                if (ids.size in 2..10) {
                    ids.zipWithNext().forEachIndexed { i, (a, b) ->
                        edges.put(org.json.JSONObject().apply {
                            put("id", "e${seq++}")
                            put("fromNode", a)
                            put("toNode", b)
                        })
                    }
                }
            }
            // 两个入口节点：索引页与自我叙事快照，摆在左上当"首页"
            nodes.put(org.json.JSONObject().apply {
                put("id", "home1"); put("type", "file"); put("file", "_索引.md")
                put("x", -980); put("y", -760); put("width", 240); put("height", 130)
            })
            if (snapshot.isNotEmpty()) {
                nodes.put(org.json.JSONObject().apply {
                    put("id", "home2"); put("type", "file"); put("file", "自我叙事.md")
                    put("x", -700); put("y", -760); put("width", 240); put("height", 130)
                })
            }
            val canvas = org.json.JSONObject().apply {
                put("nodes", nodes)
                put("edges", edges)
            }
            val canvasName = "_记忆地图.canvas"
            File(dir, canvasName).writeText(canvas.toString(2), Charsets.UTF_8)
            publish(context, canvasName, canvas.toString(2), subDir, "application/octet-stream")

            // ── 2) 索引 md：三个维度 ──
            val idx = buildString {
                appendLine("# 记忆索引")
                appendLine()
                appendLine("共 ${memories.size} 个记忆点。正文在各自的笔记里，这里只做导航。")
                appendLine()
                appendLine("## 按情绪")
                memories.groupBy { it.label.ifBlank { "平静" } }
                    .toList().sortedByDescending { it.second.size }
                    .forEach { (label, list) ->
                        appendLine()
                        appendLine("### ${label}（${list.size}）")
                        list.sortedByDescending { it.importance }.forEach { m ->
                            val t = titles[m.topicId] ?: sanitizeDate(m.startTs, stamp, zone)
                            appendLine("- [[${sanitizeDate(m.startTs, stamp, zone)} $t]] · 重要度 ${fmt2(m.importance)}")
                        }
                    }
                appendLine()
                appendLine("## 按重要度")
                memories.sortedByDescending { it.importance }.take(20).forEach { m ->
                    val t = titles[m.topicId] ?: sanitizeDate(m.startTs, stamp, zone)
                    appendLine("- ${fmt2(m.importance)} · [[${sanitizeDate(m.startTs, stamp, zone)} $t]] · ${m.label.ifBlank { "平静" }}")
                }
                appendLine()
                appendLine("## 按时间")
                memories.sortedBy { it.startTs }.forEach { m ->
                    val t = titles[m.topicId] ?: sanitizeDate(m.startTs, stamp, zone)
                    appendLine("- ${Instant.ofEpochMilli(m.startTs).atZone(zone).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))} · [[${sanitizeDate(m.startTs, stamp, zone)} $t]]")
                }
            }
            val indexName = "_索引.md"
            File(dir, indexName).writeText(idx, Charsets.UTF_8)
            publish(context, indexName, idx, subDir)

            // ── 3) 自我叙事：只有数值快照与留白，不写任何事件摘要 ──
            if (snapshot.isNotEmpty()) {
                val self = buildString {
                    appendLine("---")
                    appendLine("tags:")
                    appendLine("  - 鱼")
                    appendLine("  - 自我叙事")
                    appendLine("---")
                    appendLine()
                    appendLine("# 自我叙事（当前快照）")
                    appendLine()
                    appendLine("> 只放数值，不自动写事件摘要。想写什么，写在下面的留白里。")
                    appendLine()
                    appendLine("| 项 | 值 |")
                    appendLine("|---|---|")
                    snapshot.forEach { (k, v) -> appendLine("| $k | $v |") }
                    appendLine()
                    appendLine("## 我想写下的")
                    appendLine()
                    appendLine("- ")
                }
                val selfName = "自我叙事.md"
                File(dir, selfName).writeText(self, Charsets.UTF_8)
                publish(context, selfName, self, subDir)
            }

            memories.size + 2
        }.getOrDefault(0)
    }

    private fun sanitizeDate(ts: Long, stamp: DateTimeFormatter, zone: ZoneId): String =
        stamp.format(Instant.ofEpochMilli(ts).atZone(zone))

    private fun sanitize(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|#^\\[\\]]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(28)
}
