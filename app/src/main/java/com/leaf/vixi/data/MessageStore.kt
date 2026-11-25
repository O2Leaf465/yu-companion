package com.leaf.vixi.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 一条消息的种类。
 *   TEXT   普通文字
 *   IMAGE  图片（images 字段指向 files/images/ 下的文件，可以多张）
 *   ACTION 动作事件（例如"摸了摸她的头"），不是聊天内容，但会进 prompt
 */
enum class MsgKind { TEXT, IMAGE, ACTION }

/**
 * 一条落盘的消息。
 *
 * 注意 fromUser 的方向：true = 手机这头的人（Leaf）发的，false = 鱼发的。
 * 别和 llm.Turn.self 混了 —— 那个 self 表示"是鱼说的"，正好相反。
 */
data class StoredMessage(
    val fromUser: Boolean,
    val text: String,
    val ts: Long,
    val kind: MsgKind = MsgKind.TEXT,
    /** 图片文件名列表（files/images 下）。一条消息可以带多张，对应"一次调用发多图" */
    val images: List<String> = emptyList(),
    /** 被引用的那句话的原文（快照，不是引用消息 id —— 引用对象被删也不会悬空） */
    val quote: String? = null,
    /**
     * 群聊里这条是谁说的（角色 id）。fromUser=true 时为空。
     * 前端靠它选头像和名字，检索片段靠它加说话人前缀。
     */
    val speakerId: String? = null,
)

/**
 * 聊天记录落盘，JSONL 追加写。
 *
 * 为什么不用 Room：只需要"关掉 App 再打开记录还在"。
 * 一行一条、只追加；删除单条时整文件重写（数据量小，值得换来实现简单）。
 *
 * 兼容：早期版本用单数 `image` 字段，读的时候兼容；写出去统一成 `images`。
 */
class MessageStore(context: Context) {

    private val dir = context.filesDir

    /** 当前会话 id：一个会话就是一份记录文件，单聊之间因此天然隔离 */
    private var convId: String = "v1"
    private var sandbox = false

    private var file = fileFor(convId, sandbox = false)
    private val lock = Any()

    fun open(conversationId: String) {
        synchronized(lock) {
            convId = conversationId
            // 测试环境也要**按会话分开**：以前全局一份 chat_sandbox.jsonl，
            // 有了多会话之后切过去会看到同一段记录、说话人还会归错人
            file = fileFor(convId, sandbox)
        }
    }

    fun setSandbox(on: Boolean) {
        synchronized(lock) {
            sandbox = on
            file = fileFor(convId, on)
        }
    }

    private fun fileFor(id: String, sandbox: Boolean): File =
        File(dir, if (sandbox) "chat_sandbox_$id.jsonl" else "chat_$id.jsonl")

    fun load(): List<StoredMessage> = synchronized(lock) { parse(file) }

    /**
     * 读**另一个**会话的记录，不移动当前指针。
     *
     * 只给"群记忆下渗到单聊"用：单聊检索时要把她所在群的记录拼进语料。
     * 群聊**不反查**成员的单聊 —— 那是隐私边界，不是实现细节。
     */
    fun read(conversationId: String): List<StoredMessage> = synchronized(lock) {
        parse(File(dir, "chat_$conversationId.jsonl"))
    }

    private fun parse(f: File): List<StoredMessage> {
        if (!f.exists()) return emptyList()
        return f.readLines(Charsets.UTF_8).mapNotNull { line ->
            runCatching {
                val o = JSONObject(line)
                val text = o.optString("text")
                val kind = runCatching { MsgKind.valueOf(o.optString("kind", "TEXT")) }
                    .getOrDefault(MsgKind.TEXT)
                val images = readImages(o)
                if (text.isBlank() && images.isEmpty()) null
                else StoredMessage(
                    fromUser = o.optBoolean(KEY_FROM_USER),
                    text = text,
                    ts = o.optLong("ts", 0L),
                    kind = kind,
                    images = images,
                    quote = o.optString("quote").takeIf { it.isNotBlank() },
                    speakerId = o.optString("speakerId").takeIf { it.isNotBlank() },
                )
            }.getOrNull()
        }
    }

    private fun readImages(o: JSONObject): List<String> {
        val arr = o.optJSONArray("images")
        if (arr != null) {
            return (0 until arr.length()).mapNotNull { i ->
                arr.optString(i).takeIf { it.isNotBlank() }
            }
        }
        // 兼容旧的单数字段
        return o.optString("image").takeIf { it.isNotBlank() }?.let { listOf(it) } ?: emptyList()
    }

    /**
     * @return 是否真的落盘成功。**调用方必须看**：磁盘满 / IO 错时界面上"已发送"是假的，
     *  重启之后这条消息就不存在了（以前这里 `runCatching` 把异常吞掉，没人知道）。
     */
    fun append(msg: StoredMessage): Boolean = synchronized(lock) {
        runCatching {
            file.appendText(encode(msg).toString() + "\n", Charsets.UTF_8)
            true
        }.getOrDefault(false)
    }

    /** 删掉一条（按时间戳 + 文本匹配），整文件重写 */
    fun delete(ts: Long, text: String) {
        synchronized(lock) {
            runCatching {
                val kept = load().filterNot { it.ts == ts && it.text == text }
                rewrite(kept)
            }
        }
    }

    private fun rewrite(messages: List<StoredMessage>) {
        runCatching {
            // tmp 名按会话区分：固定叫 chat.jsonl.tmp 的话，两个会话同时整份重写会互相踩
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.bufferedWriter(Charsets.UTF_8).use { w ->
                messages.forEach { w.write(encode(it).toString()); w.newLine() }
            }
            // **同目录 rename 是原子的**：目标要么是旧内容、要么是完整新内容。
            // copyTo 是先 truncate 目标再逐块复制 —— 中途被杀或磁盘满，
            // 留下的是一段"合法的前缀"，后面几天的对话永久消失，而且没有任何提示。
            if (!tmp.renameTo(file)) {
                // 少数文件系统不支持覆盖式 rename：退回复制（仍比直接往目标上写安全）
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
    }

    private fun encode(msg: StoredMessage): JSONObject = JSONObject().apply {
        put(KEY_FROM_USER, msg.fromUser)
        put("text", msg.text)
        put("ts", msg.ts)
        put("kind", msg.kind.name)
        if (msg.images.isNotEmpty()) {
            put("images", JSONArray().apply { msg.images.forEach { put(it) } })
        }
        msg.quote?.let { put("quote", it) }
        msg.speakerId?.let { put("speakerId", it) }
    }

    fun clear() {
        synchronized(lock) { runCatching { file.delete() } }
    }

    private companion object {
        const val KEY_FROM_USER = "fromUser"
    }
}
