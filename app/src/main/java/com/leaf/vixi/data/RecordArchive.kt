package com.leaf.vixi.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 聊天记录的**档案层**：导出 / 导入 / 删除。
 *
 * 一个会话在磁盘上不止一份聊天记录，而是**八份**（正文 + 沙盒 + 话题表 + 话题游标 + 索引 + 复习 + 共召回 + 改口）。
 * 以前删一个会话要手动动四五个文件，很容易漏 —— 漏掉的那份会变成"删了还想起"。
 * 所以这里把"一个会话 = 哪几份文件"写成**一张表**，导出/导入/删除都照着它走。
 *
 * 分两层：
 *  - `*In(root)` 系列：**纯文件操作**，只认一个 root 目录（单测直接用临时目录跑）
 *  - 外面薄壳：接 `Context`、发一份到 `文档/鱼/_备份`、删公共目录里的 OB 笔记
 *
 * 三种操作的边界（也是删除确认框里那句话的依据）：
 *  - **导出**：八份文件原样打包成一个 json（无损），再发一份到 `文档/鱼/_备份/`
 *  - **导入**：覆盖回当前会话；正文换了，**从正文派生的记忆全部作废**（见 [DERIVED]）
 *  - **删除**：八份文件 + `文档/鱼/<会话>` 笔记 + `files/export/<会话>`；
 *    **不动**角色、人设、状态（情绪/关系）、`自我叙事.md`
 *
 * ⚠️ 安全底线：`convId` 和 `subDir` 都是**用户可改的文本**（角色名、群名、conversations.json），
 * 一律先过 [sanitize] 再用，且空值直接拒绝。少这一步，一个叫 `/../..` 的群名就能把 filesDir 删空。
 */
object RecordArchive {

    /** 备份文件的格式标记：导错文件时能立刻拒绝，而不是写坏当前记录 */
    const val FORMAT = "vixi-chat-backup"
    const val VERSION = 1

    /** 导出器自己生成的笔记之外，用户在同一个目录里手写的东西，删除时要留下 */
    const val KEEP_ON_PURGE = "自我叙事.md"

    private fun stamp(at: Long) =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.CHINA).format(Date(at))

    /** 会话 id 只允许这几个字符；真实值是 v1/v2/v3，这条规则是给手改过 conversations.json 的人兜底 */
    private fun validId(id: String) = id.matches(Regex("[A-Za-z0-9_-]+"))

    private fun sanitize(s: String) = s.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "v1" }

    /** 备份 json 里的字段名 = 这里的 key。**改 key 会作废旧备份** */
    private val KEYS = listOf(
        "chat", "chat_sandbox", "topics", "current_topic",
        "index", "recalls", "co", "corrections",
    )

    /**
     * 从正文派生出来的那几份。
     *
     * 备份里没有它们 → 必须删掉旧的：留着就是"记忆指向已经不存在的句子"。
     * `topics`/`current_topic` 也在这张表里 —— 话题是按正文 30 分钟切出来的，
     * 游标留在旧值会让 `inTopic` 把刚导回来的历史整段滤掉（"导回来了但她不认识"）。
     */
    private val DERIVED = listOf("index", "recalls", "co", "topics", "current_topic")

    /** 一个会话的所有落盘文件。`root` 正常是 `context.filesDir`，测试里是临时目录 */
    fun filesOfRoot(root: File, convId: String): Map<String, File> {
        val id = sanitize(convId)
        val mem = File(root, "mem")
        return linkedMapOf(
            "chat" to File(root, "chat_$id.jsonl"),
            "chat_sandbox" to File(root, "chat_sandbox_$id.jsonl"),
            "topics" to File(root, "topics_$id.jsonl"),
            "current_topic" to File(mem, "current_topic_$id.json"),
            "index" to File(mem, "index_$id.jsonl"),
            "recalls" to File(mem, "recalls_$id.json"),
            "co" to File(mem, "co_$id.json"),
            "corrections" to File(mem, "corrections_$id.jsonl"),
        )
    }

    fun backupDir(root: File): File = File(root, "backups").apply { mkdirs() }

    // ────────── 导出 ──────────

    data class Exported(val file: File, val count: Int, val bytes: Long)

    /**
     * 把会话打包成一个备份文件（写进 `root/backups`）。
     *
     * @return null = 没有聊天记录（空会话不值得备份，也避免用户拿到一个空 json）
     */
    fun exportTo(root: File, convId: String, title: String, at: Long = System.currentTimeMillis()): Exported? {
        if (!validId(convId)) return null
        val files = filesOfRoot(root, convId)
        val chat = files.getValue("chat")
        val sandbox = files.getValue("chat_sandbox")
        // 哪份是"这次真正在聊的"：沙盒模式开着时正文只有 sandbox 那份
        val main = when {
            chat.exists() && chat.length() > 0L -> chat
            sandbox.exists() && sandbox.length() > 0L -> sandbox
            else -> return null
        }
        val count = main.useLines { lines -> lines.count { it.isNotBlank() } }

        val obj = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("convId", convId)
            .put("title", title)
            .put("exportedAt", at)
            // 条数写进头部：列表页只读这一项，不用把每个备份整份解析一遍（大备份会 OOM）
            .put("count", count)
        val payload = JSONObject()
        files.forEach { (key, f) ->
            if (f.exists() && f.length() > 0L) payload.put(key, f.readText(Charsets.UTF_8))
        }
        obj.put("files", payload)

        val safe = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { convId }
        val target = freeName(backupDir(root), "${safe}_${stamp(at)}")
        target.writeText(obj.toString(), Charsets.UTF_8)
        return Exported(target, count, target.length())
    }

    /**
     * 拿一个没被占用的文件名。
     *
     * 为什么不是"时间戳够精确就行"：连点两次导出可能落在同一秒里，
     * 直接写会**静默覆盖**上一份备份 —— 用户以为存了两份，其实只有一份。
     */
    private fun freeName(dir: File, base: String): File {
        var f = File(dir, "$base.json")
        var i = 2
        while (f.exists()) {
            f = File(dir, "${base}_$i.json")
            i++
        }
        return f
    }

    data class Backup(val file: File, val convId: String, val title: String, val at: Long, val count: Int)

    /** 列出本机所有备份，新的在前。认不出的文件直接跳过，不让一个坏文件带崩整页 */
    fun listIn(root: File): List<Backup> {
        val files = File(root, "backups").listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?: return emptyList()
        return files.mapNotNull { f ->
            runCatching {
                val o = JSONObject(f.readText(Charsets.UTF_8))
                if (o.optString("format") != FORMAT) return@runCatching null
                // 头部有 count 就用它；老备份没有才去数正文（只为兼容，正常不会走到）
                val count = if (o.has("count")) {
                    o.optInt("count")
                } else {
                    o.optJSONObject("files")?.optString("chat").orEmpty()
                        .let { c -> if (c.isBlank()) 0 else c.trim().lines().count { it.isNotBlank() } }
                }
                Backup(
                    file = f,
                    convId = o.optString("convId"),
                    title = o.optString("title"),
                    at = o.optLong("exportedAt", f.lastModified()),
                    count = count,
                )
            }.getOrNull()
        }.sortedByDescending { it.at }
    }

    // ────────── 导入 ──────────

    /**
     * 导入结果。[error] 非空 = 没导成，`count` 无意义。
     *
     * 为什么错误也要返回而不是抛：调用方在协程里，抛出去会被 `runCatching` 吞成一句
     * "格式不对" —— 磁盘满和格式错要分开告诉用户，否则他会一直重试同一个坏文件。
     */
    data class Imported(
        val count: Int,
        val indexRestored: Boolean,
        val error: String? = null,
    )

    /**
     * 把备份里的文件覆盖回 `convId`。
     *
     * 三条规矩：
     *  1. 只覆盖备份里**有**的那几份
     *  2. 备份里没有的派生记忆（[DERIVED]）**删掉**，交给下次打开会话时按新正文重建
     *  3. 每个文件先写 `.tmp` 再 rename：中途失败不会留下"正文已换、索引还是旧"的半套
     *
     * @return [Imported]；`error != null` 表示一个字节都没动
     */
    fun importTo(root: File, backup: File, convId: String): Imported {
        if (!validId(convId)) return Imported(0, false, "会话 id 不合法（只允许字母数字下划线）")
        val text = runCatching { backup.readText(Charsets.UTF_8) }.getOrNull()
            ?: return Imported(0, false, "备份文件读不出来（可能被移动到别的目录）")
        val o = runCatching { JSONObject(text) }.getOrNull()
            ?: return Imported(0, false, "备份文件不是合法 json（被截断过？）")
        if (o.optString("format") != FORMAT) return Imported(0, false, "这不是本 App 的备份文件")
        val ver = o.optInt("version", 0)
        if (ver > VERSION) return Imported(0, false, "备份来自更新的版本（v$ver），当前 App 只认到 v$VERSION")
        val files = o.optJSONObject("files") ?: return Imported(0, false, "备份里没有 files 段")
        val targets = filesOfRoot(root, convId)

        var count = 0
        // 先全部写到 .tmp，都成功了再统一换上去：导入要么整体生效，要么一个不动
        val staged = mutableListOf<Pair<File, File?>>()
        try {
            targets.forEach { (key, target) ->
                if (!files.has(key)) return@forEach
                val body = files.optString(key)
                target.parentFile?.mkdirs()
                if (body.isEmpty()) {
                    staged += target to null     // 备份里明确是空的 → 落地时清掉
                } else {
                    val tmp = File(target.parentFile, target.name + ".tmp")
                    tmp.writeText(body, Charsets.UTF_8)
                    staged += target to tmp
                }
                if (key == "chat") count = body.trim().lines().count { it.isNotBlank() }
                if (key == "chat_sandbox") count = maxOf(count, body.trim().lines().count { it.isNotBlank() })
            }
        } catch (t: Throwable) {
            staged.forEach { (_, tmp) -> tmp?.let { runCatching { it.delete() } } }
            return Imported(0, false, "写入失败：${t.javaClass.simpleName}（磁盘满或被占用？）")
        }
        staged.forEach { (target, tmp) ->
            if (tmp == null) {
                runCatching { target.delete() }
            } else {
                runCatching { if (target.exists()) target.delete() }
                if (!runCatching { tmp.renameTo(target) }.getOrDefault(false)) {
                    // rename 失败（跨文件系统/被占用）就退回复制，别把内容丢在 .tmp 里
                    runCatching { tmp.copyTo(target, overwrite = true); tmp.delete() }
                }
            }
        }

        val indexRestored = files.optString("index").isNotEmpty()
        DERIVED.forEach { key ->
            val restored = when (key) {
                "index" -> files.optString("index").isNotEmpty()
                "recalls" -> files.optString("recalls").isNotEmpty()
                "co" -> files.optString("co").isNotEmpty()
                "topics" -> files.optString("topics").isNotEmpty()
                "current_topic" -> files.optString("current_topic").isNotEmpty()
                else -> true
            }
            if (!restored) runCatching { targets.getValue(key).delete() }
        }
        return Imported(count, indexRestored)
    }

    // ────────── 删除 ──────────

    /**
     * 删掉一个会话的记录与记忆（只动 `root` 里那八份 + 可选备份）。
     *
     * 故意不动：角色表、人设、`state/<角色id>.json`、别的会话。
     * 状态按角色存 —— 删一个会话不该让她忘记"被骂过"的后果，那是人的连续性，不是聊天记录。
     *
     * @param deleteBackups 连这个会话的历史备份一起删（备份里也全是聊天内容）
     * @return 删掉的文件数；`convId` 不合法时返回 0
     */
    fun deleteIn(root: File, convId: String, deleteBackups: Boolean = true): Int {
        if (!validId(convId)) return 0
        var n = 0
        filesOfRoot(root, convId).values.forEach { f ->
            if (f.exists() && runCatching { f.delete() }.getOrDefault(false)) n++
        }
        if (deleteBackups) {
            listIn(root).filter { it.convId == convId }.forEach {
                if (runCatching { it.file.delete() }.getOrDefault(false)) n++
            }
        }
        return n
    }

    /** 这个会话的历史备份文件名（删公共目录那份副本要用） */
    fun backupNamesOf(root: File, convId: String): List<String> =
        listIn(root).filter { it.convId == convId }.map { it.file.name }

    // ────────── Context 外壳：公共目录那两件 ──────────

    data class Deleted(val files: Int, val notes: Int, val keptUserNotes: Int = 0)

    /**
     * 完整删除：私有目录八份文件 + `files/export/<subDir>` + `文档/鱼/<subDir>` 的导出笔记。
     *
     * @param subDir 导出用的子目录名（`群-群名` / `角色名`）。**空白直接拒绝** ——
     *   否则 `文档/鱼/` 前缀会把所有会话的笔记和 `_备份` 一起清掉。
     */
    fun delete(
        context: Context,
        convId: String,
        subDir: String,
        deleteBackups: Boolean = true,
    ): Deleted {
        if (subDir.isBlank() || !validId(convId)) return Deleted(0, 0)
        // 目录名必须和导出时**同一个函数**算出来。
        // 以前这里用 `[^A-Za-z0-9_-] → _`，于是「鱼」算成 `__`、「群-群聊」算成 `______`，
        // 和真实目录 `鱼` / `群-群聊` 对不上 → 删会话时 notes=0，笔记全留在公共目录里。
        val dirName = TopicExporter.exportDirName(subDir)
        val backupNames = if (deleteBackups) backupNamesOf(context.filesDir, convId) else emptyList()

        val files = deleteIn(context.filesDir, convId, deleteBackups)

        val privateDir = File(context.filesDir, "export/$dirName")
        privateDir.listFiles()?.forEach { f -> if (f.name != KEEP_ON_PURGE) runCatching { f.delete() } }
        runCatching { privateDir.delete() }

        // 传**原始 subDir**：purgePublished 内部走同一套 sanitize，别在这里二次改写
        val notes = runCatching { TopicExporter.purgePublished(context, subDir) }.getOrDefault(0)
        // 公共目录里的备份副本也是整段对话，说了"连备份一起删"就得真删
        backupNames.forEach { runCatching { TopicExporter.purgePublishedBackup(context, it) } }
        return Deleted(files, notes)
    }

    /** 导出 + 顺手发一份到公共目录。发失败不影响本地备份 */
    fun export(context: Context, convId: String, title: String, at: Long = System.currentTimeMillis()): Exported? {
        val r = exportTo(context.filesDir, convId, title, at) ?: return null
        runCatching { publish(context, r.file.readText(Charsets.UTF_8), r.file.name) }
        return r
    }

    fun list(context: Context): List<Backup> = listIn(context.filesDir)

    fun import(context: Context, backup: File, convId: String): Imported =
        importTo(context.filesDir, backup, convId)

    /**
     * 写到公共目录 `文档/鱼/_备份/`。
     *
     * 用 IS_PENDING：写完才让它可见 —— 否则用户能在"写了一半"的窗口里打开一个 0 字节备份。
     */
    private fun publish(context: Context, body: String, fileName: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val resolver = context.contentResolver
        val relPath = "${Environment.DIRECTORY_DOCUMENTS}/鱼/_备份/"
        val bytes = body.toByteArray(Charsets.UTF_8)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Files.getContentUri("external"), values) ?: return
        val wrote = runCatching {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
            true
        }.getOrDefault(false)
        if (!wrote) {
            runCatching { resolver.delete(uri, null, null) }
            return
        }
        runCatching {
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }
    }
}
