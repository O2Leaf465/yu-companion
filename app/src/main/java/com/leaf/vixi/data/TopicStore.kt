package com.leaf.vixi.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 话题档案：把对话切成"话题"，一个话题结束后整段归档进原文库。
 *
 * 为什么要切：整个历史一股脑塞给模型既贵又糊（她会把十天前的话当现在的上下文）。
 * 只带当前话题，话题结束后归档 —— 需要旧事时再让检索工具去原文库里翻。
 *
 * 切分不调模型（0 成本），三条规则：
 *   1. 距上一条超过 30 分钟 → 新话题
 *   2. 你说"睡了/先这样/拜拜/晚安"这类收尾话 → 这个话题结束
 *   3. 手动新建（以后加）
 */
class TopicStore(context: Context) {

    private val root = context.filesDir

    /**
     * 话题按会话隔离：A 的话题不能变成 B 的上下文。
     * 文件名带会话 id；`open()` 由 ChatViewModel 在切换会话时调用。
     */
    private var convId = "v1"
    private var file = topicFile("v1")
    private var stateFile = stateFile("v1")

    fun open(conversationId: String) {
        convId = conversationId
        file = topicFile(conversationId)
        stateFile = stateFile(conversationId)
    }

    private fun topicFile(id: String) = File(root, "topics_$id.jsonl")

    private fun stateFile(id: String) = File(root, "mem/current_topic_$id.json")

    data class Topic(
        val id: Long,
        val title: String,
        val startedAt: Long,
        val endedAt: Long,
        val messageCount: Int,
    )

    /** 关闭一个话题并归档 */
    fun archive(topic: Topic) {
        runCatching {
            val obj = JSONObject()
                .put("id", topic.id)
                .put("conv", convId)
                .put("title", topic.title)
                .put("startedAt", topic.startedAt)
                .put("endedAt", topic.endedAt)
                .put("messageCount", topic.messageCount)
            file.appendText(obj.toString() + "\n", Charsets.UTF_8)
        }
    }

    fun all(): List<Topic> {
        if (!file.exists()) return emptyList()
        val out = mutableListOf<Topic>()
        runCatching {
            file.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val o = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
                out += Topic(
                    id = o.optLong("id"),
                    title = o.optString("title"),
                    startedAt = o.optLong("startedAt"),
                    endedAt = o.optLong("endedAt"),
                    messageCount = o.optInt("messageCount"),
                )
            }
        }
        return out
    }

    /** 当前话题状态（跨重启保留，否则每次开 App 都从"无话题"开始） */
    fun currentId(): Long = runCatching {
        if (!stateFile.exists()) 0L
        else JSONObject(stateFile.readText(Charsets.UTF_8)).optLong("id")
    }.getOrDefault(0L)

    fun lastAt(): Long = runCatching {
        if (!stateFile.exists()) 0L
        else JSONObject(stateFile.readText(Charsets.UTF_8)).optLong("lastAt")
    }.getOrDefault(0L)

    fun startedAt(): Long = runCatching {
        if (!stateFile.exists()) 0L
        else JSONObject(stateFile.readText(Charsets.UTF_8)).optLong("startedAt")
    }.getOrDefault(0L)

    fun saveCurrent(id: Long, startedAt: Long, lastAt: Long) {
        runCatching {
            stateFile.parentFile?.mkdirs()
            val obj = JSONObject()
                .put("id", id)
                .put("startedAt", startedAt)
                .put("lastAt", lastAt)
            stateFile.writeText(obj.toString(), Charsets.UTF_8)
        }
    }

    companion object {
        /** 超过这个间隔就当换了话题 */
        const val GAP_MS = 30 * 60 * 1000L

        /** 收尾话：说完这些，这个话题就算结束 */
        private val CLOSERS = listOf(
            "睡了", "我睡了", "晚安", "先这样", "先这样吧", "拜拜", "再见",
            "不聊了", "走了", "我走了", "出去一下", "回头说", "回头聊", "晚点聊",
            "困了", "睡了睡了", "下了", "我下了",
        )

        fun isClosing(text: String): Boolean {
            val t = text.trim()
            if (t.isEmpty() || t.length > 10) return false
            // 否定或他指的一律不算："我不困了"「他走了」「我下楼了」都是正常聊天，
            // 误判的代价是当场截断上下文（比不切话题更糟），所以宁可漏判。
            if (Regex("[不没别他她你]").containsMatchIn(t)) return false
            return CLOSERS.any { t == it || t.endsWith(it) }
        }
    }
}
