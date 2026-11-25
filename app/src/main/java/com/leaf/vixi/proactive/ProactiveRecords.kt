package com.leaf.vixi.proactive

import android.content.Context
import com.leaf.vixi.data.StoredMessage
import com.leaf.vixi.state.Proactive
import org.json.JSONObject
import java.io.File

/**
 * 主动开口的两项"通知账本"：`files/proactive.json`
 * ```json
 * {"lastSentAt":0,"lastReplied":true}
 * ```
 *
 * 为什么单独一个小文件：
 *  · 不许碰 `files/state/<角色id>.json` —— 主动开口**只读**状态，写进去会污染情绪数值，
 *    而且会让"她为什么是这个状态"的事件追溯多出一批后台改动；
 *  · 这两项的时间语义跟情绪无关（一个冷却计时、一个"上次有没有被回"），
 *    混进状态里以后改公式的人容易误当状态量处理。
 *
 * 容错：文件缺失 / 半截 JSON / 字段类型不对，一律回落到"从没主动过"，
 * 绝不因为账本坏了就让整个后台任务抛异常（那会变成 WorkManager 的一次失败重试）。
 */
class ProactiveRecords(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)
    private val lock = Any()

    fun load(): Proactive.History = synchronized(lock) {
        if (!file.exists()) return Proactive.History()
        runCatching {
            val o = JSONObject(file.readText(Charsets.UTF_8))
            Proactive.History(
                lastSentAt = o.optLong(KEY_LAST_SENT_AT, 0L).coerceAtLeast(0L),
                // 没写过这个键 = 还没主动过 → 当作"被回了"（不施加被拒惩罚）
                lastReplied = o.optBoolean(KEY_LAST_REPLIED, true),
            )
        }.getOrDefault(Proactive.History())
    }

    fun save(history: Proactive.History) {
        synchronized(lock) {
            runCatching {
                val json = JSONObject()
                    .put(KEY_LAST_SENT_AT, history.lastSentAt.coerceAtLeast(0L))
                    .put(KEY_LAST_REPLIED, history.lastReplied)
                    .toString()
                // 原子写：WorkManager 可能和下一次触发（或调试线程）撞上，
                // 半截 JSON 会被 load() 当成"从没主动过"→ 冷却失效 → 连着开口
                val tmp = File(file.parentFile, "$FILE_NAME.tmp")
                tmp.writeText(json, Charsets.UTF_8)
                if (!tmp.renameTo(file)) {
                    tmp.copyTo(file, overwrite = true)
                    tmp.delete()
                }
            }
        }
    }

    /**
     * 用聊天记录重算"上次主动之后她有没有被回"。
     *
     * 为什么不能只靠发送时写下的 `lastReplied=false`：那之后用户回了消息，
     * 后台任务可能好几天不跑；一旦跑了，账本必须立刻反映"其实被回了"，
     * 否则被拒惩罚会一直挂着（×0.5），表现成"她越来越不肯开口"。
     *
     * @param messages 当前会话的记录（`StoredMessage`），只用于看时间戳和方向
     */
    fun markRepliedIfAnswered(
        history: Proactive.History,
        messages: List<StoredMessage>,
    ): Proactive.History {
        if (history.lastSentAt <= 0L || history.lastReplied) return history
        val answered = messages.any { it.fromUser && it.ts > history.lastSentAt }
        return if (answered) history.copy(lastReplied = true) else history
    }

    private companion object {
        const val FILE_NAME = "proactive.json"
        const val KEY_LAST_SENT_AT = "lastSentAt"
        const val KEY_LAST_REPLIED = "lastReplied"
    }
}
