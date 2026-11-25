package com.leaf.vixi.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/** 用户的一次纠正。text 是原话，turn 是当时的轮次，便于回溯 */
data class Correction(val text: String, val at: Long, val turn: Int)

/**
 * 记忆的第一层（M2 阶段）：会话摘要 + 纠正记录 + 轮次计数。
 *
 * 为什么用文件而不是数据库：
 *   - 单人单机、一天几百条，够用；
 *   - 出问题时能直接打开看，不用查表；
 *   - 以后换记忆引擎时整个目录丢掉即可，不留迁移负担。
 *
 * 目录：files/mem/
 *   summary.txt        会话摘要（3 行以内，纯文本）
 *   corrections.jsonl  纠正记录，追加写
 *   state.json         轮次计数（跨重启不丢，否则"每 10 轮总结"永远触发不了）
 */
class MemoryStore(context: Context) {

    private val dir = File(context.filesDir, "mem").apply { mkdirs() }

    /**
     * 纠正记录按会话隔离：A 的"你记错了"跑到 B 身上，就是凭空给她安了一个错误记忆。
     * 摘要与轮次是全局统计，不跟着会话走。
     */
    private var correctionsFile = File(dir, "corrections_v1.jsonl")
    private val summaryFile = File(dir, "summary.txt")
    private val stateFile = File(dir, "state.json")
    private val lock = Any()

    fun open(conversationId: String) {
        synchronized(lock) { correctionsFile = File(dir, "corrections_$conversationId.jsonl") }
    }

    fun summary(): String = synchronized(lock) {
        runCatching { summaryFile.readText(Charsets.UTF_8).trim() }.getOrDefault("")
    }

    fun saveSummary(text: String) {
        synchronized(lock) {
            runCatching { summaryFile.writeText(text.trim(), Charsets.UTF_8) }
        }
    }

    fun corrections(): List<Correction> = synchronized(lock) {
        if (!correctionsFile.exists()) return emptyList()
        correctionsFile.readLines(Charsets.UTF_8).mapNotNull { line ->
            runCatching {
                val o = JSONObject(line)
                Correction(
                    text = o.optString("text"),
                    at = o.optLong("at"),
                    turn = o.optInt("turn"),
                )
            }.getOrNull()
        }.filter { it.text.isNotBlank() }
    }

    fun addCorrection(correction: Correction) {
        synchronized(lock) {
            runCatching {
                val line = JSONObject()
                    .put("text", correction.text)
                    .put("at", correction.at)
                    .put("turn", correction.turn)
                    .toString()
                correctionsFile.appendText(line + "\n", Charsets.UTF_8)
            }
        }
    }

    fun turns(): Int = synchronized(lock) {
        runCatching { JSONObject(stateFile.readText(Charsets.UTF_8)).optInt("turns", 0) }
            .getOrDefault(0)
    }

    /** 直接把轮次写成给定值（内存里同步加、这里异步落盘，避免竞态） */
    fun setTurns(n: Int) {
        synchronized(lock) {
            runCatching { stateFile.writeText(JSONObject().put("turns", n).toString(), Charsets.UTF_8) }
        }
    }

    fun bumpTurns(): Int = synchronized(lock) {
        val n = turns() + 1
        runCatching { stateFile.writeText(JSONObject().put("turns", n).toString(), Charsets.UTF_8) }
        n
    }
}
