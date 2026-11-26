package com.leaf.vixi.proactive

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.leaf.vixi.data.CharacterStore
import com.leaf.vixi.data.Conversation
import com.leaf.vixi.data.ConversationStore
import com.leaf.vixi.data.MessageStore
import com.leaf.vixi.data.SettingsStore
import com.leaf.vixi.data.StateStore
import com.leaf.vixi.data.StoredMessage
import com.leaf.vixi.data.VixiState
import com.leaf.vixi.llm.LlmClient
import com.leaf.vixi.llm.ReplySplitter
import com.leaf.vixi.state.Proactive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 「主动开口（后台版）」：每 3 小时被 WorkManager 叫醒一次，判断她要不要自己找你说句话。
 *
 * 一次触发的完整流程（任何一步不满意就**安静收场**，不留半截消息）：
 * ```
 * 有 key？ → 找到她的单聊 → 只读状态 → 算指数 → 不够就 return
 *   → 用同一套配置发一次请求 → 不空 → 切到这一条气泡 → 真的落盘了？
 *   → 记账本（冷却起点）→ 发通知
 * ```
 *
 * 几条刻意的约束：
 *  · **不碰状态**：只 `StateStore.load()`，绝不 `settle()`/`applyEvents()` —— 后台不该
 *    推进 `lastTick`、不该改任何数值（缺的那点衰减由 [Proactive.effectiveMissing] 只读推算）；
 *  · **不碰 ChatViewModel**：它管前台轮次，后台去拿它的锁只会互相阻塞；
 *    落盘直接走 `MessageStore`，和前台用的是同一份文件、同一种行格式；
 *  · **一次最多一条**：`ReplySplitter.split(maxBubbles = 1)`。她主动开口只需一句，
 *    连发三条会把"想说话"变成刷屏；
 *  · **失败不重试**：一律返回 `Result.success()`。返回 retry 的话，
 *    WorkManager 会在下一次网络恢复时重跑，可能变成"同一次想念说了两遍"。
 */
class ProactiveWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            runOnce(applicationContext, force = inputData.getBoolean("force", false))
        } catch (e: CancellationException) {
            // 被系统取消（省电模式、用户停止）要原样抛出去，不能当成"成功跑完"
            throw e
        } catch (e: Exception) {
            ProactiveLog.append(applicationContext, "fail: ${e.javaClass.simpleName} ${e.message?.take(160)}")
        }
        // 其余情况都算"这次跑完了"：安静地结束，不重试
        Result.success()
    }

    private suspend fun runOnce(app: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()

        // ⓪ **前台不再整轮跳过**（2026-11-26 用户问："难道在前台就不能主动开口了吗？"）。
        //
        // 原来的理由：前台界面是 ChatViewModel 的内存副本，往 jsonl 追加一条它不会重读，
        // 于是"落盘了但界面上没有"，要等切会话才冒出来。
        // 现在有 `ChatViewModel.reloadIfChanged()` 了：写盘后通知它重读一次就行。
        // 前台时**不发通知**（人就在看屏幕，弹通知反而吵），只在聊天里多一条气泡。
        val fg = runCatching { com.leaf.vixi.App.instance.isForeground }.getOrDefault(false)

        val settings = SettingsStore(app)

        // ① 没有 key / 地址就是没配置：什么都不做（连日志都只是一行，方便排查"为什么她从不开口"）
        if (!settings.isConfigured()) {
            ProactiveLog.append(app, "skip: 未配置 API（baseUrl/model/key）")
            return
        }

        // ② 找到"点开通知就能看见这条消息"的那个会话
        val conversations = ConversationStore(app)
        val target = resolveConversation(conversations) ?: run {
            ProactiveLog.append(app, "skip: 没有可用的单聊会话")
            return
        }
        val characterId = target.memberIds.first()
        val character = CharacterStore(app).get(characterId) ?: run {
            ProactiveLog.append(app, "skip: 会话 ${target.id} 的角色 $characterId 不在角色表里")
            return
        }

        // ③ 只读状态（不要 settle：那会写状态文件）
        val state: VixiState = StateStore(app).load(characterId)

        // ④ 账本：把"上次主动之后其实已经被回了"这件事先补上再判定
        val records = ProactiveRecords(app)
        var history = records.load()

        val store = MessageStore(app)
        // 和 ChatViewModel 一样按"当前环境"指文件：测试环境的记录在 chat_sandbox_*.jsonl 里，
        // 写错文件就是"消息落盘了、界面却看不见"
        store.setSandbox(settings.sandbox)
        store.open(target.id)
        val messages = store.load()

        val answered = records.markRepliedIfAnswered(history, messages)
        if (answered != history) {
            records.save(answered)
            history = answered
        }

        // ⑤ 判定
        val hour = Proactive.hourOf(now)
        val decision = Proactive.decide(state, now, history, hour)
        ProactiveLog.append(app, "decide ${character.name}@${target.id}: ${decision.reason}")
        // force：调试页测试时连"分数不够"也跳过，只验投递链路
        if (!decision.send && !force) return

        // ⑥ 发一次请求（同一套配置：baseUrl / model / apiKey 全在 SettingsStore 里）
        val prompt = ProactivePrompt.build(
            characterName = character.name,
            persona = character.persona,
            recent = messages,
            state = state,
            now = now,
        )
        val raw = try {
            LlmClient(settings).chat(prompt).text
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 无网 / 超时 / key 失效 / 余额不足：安静收场，不通知、不留半截消息
            ProactiveLog.append(app, "skip: 请求失败 ${e.message?.take(120)}")
            return
        }

        // ⑦ 只取一条。`maxBubbles = 1` 不会丢字：多句会被并成一条（见 ReplySplitter 第 51 行），
        //    所以这里既满足"一次最多一条消息"，也不用自己截断她的话。
        if (raw.isBlank()) {
            ProactiveLog.append(app, "skip: 空回复")
            return
        }
        val line = ReplySplitter.split(raw, maxBubbles = 1).firstOrNull()?.trim().orEmpty()
        // ReplySplitter 在"输出只剩标点/噪音"时会回一条占位「……」——
        // 那不是她开口，别把它写进聊天记录，更别为它发通知
        if (line.isBlank() || line == SPLITTER_EMPTY_FALLBACK) {
            ProactiveLog.append(app, "skip: 模型没给出可用的句子（${raw.take(40)}）")
            return
        }

        // ⑧ 落盘 + **回读确认**。通知的依据必须是"文件里真的有这一条"，
        //    否则点进去就是空聊天页（MessageStore.append 内部吞异常，不能只看它没抛）
        val ts = System.currentTimeMillis()
        store.append(
            // 单聊也写 speakerId：换角色后导出/归档靠它认人（和 ChatViewModel 一致）
            StoredMessage(fromUser = false, text = line, ts = ts, speakerId = characterId),
        )
        val written = store.load().any { !it.fromUser && it.ts == ts && it.text == line }
        if (!written) {
            ProactiveLog.append(app, "fail: 没写进聊天记录，不发通知")
            return
        }

        // ⑨ 记冷却起点 + 把当前会话切到她（通知点开就落在刚写进去的那条上）
        //
        // ⚠️ **force（调试页「主动测试」）不记账**（2026-11-26 定位到的大坑）：
        // 以前测试也写 `lastSentAt + lastReplied=false`，于是每次测试都会
        //   · 让接下来 3 小时的分数被 ×0.5（冷却）
        //   · 并且因为"测试消息没人回"，再叠一个 ×0.5（被拒）
        // 连按两三次之后，自然触发要的真实分数被 ×0.25 压死 ——
        // 真机日志里 17:48 那次 base 0.94 被压到 0.24 就是这个原因。
        if (!force) {
            records.save(Proactive.History(lastSentAt = ts, lastReplied = false))
        }
        conversations.currentId = target.id

        // ⑩ 通知（前台不发：人就在屏幕前；没权限就只是没弹一下，消息已经落盘）
        val posted = if (fg) {
            // 前台：让界面自己重读一次，气泡立刻出现（不用等切会话）
            runCatching { com.leaf.vixi.App.instance.onProactiveLanded?.invoke() }
            false
        } else {
            ProactiveNotifications.notify(app, character.name, line)
        }
        ProactiveLog.append(
            app,
            "sent ${character.name}@${target.id} notify=$posted fg=$fg" +
                (if (force) " [force·不记冷却/被拒]" else "") +
                " 「${line.take(40)}」",
        )
    }

    /**
     * 选一个"她的单聊"。
     *
     * 优先当前会话（用户刚在这儿聊过，写进去马上能看见）；
     * 当前是群聊就退回第一个单聊 —— 群聊不参与主动开口：那需要挑说话人和群内语气，
     * 不是这一版要解决的问题，硬做只会写出"某个角色在群里莫名发言"。
     */
    private fun resolveConversation(conversations: ConversationStore): Conversation? {
        val current = conversations.get(conversations.currentId)
        if (current != null && !current.isGroup && current.memberIds.isNotEmpty()) return current
        return conversations.all().firstOrNull { !it.isGroup && it.memberIds.isNotEmpty() }
    }

    private companion object {
        /**
         * `ReplySplitter` 在"输出只剩标点、没有实义字符"时回的占位串。
         * 它是那个类的内部约定（没有公开常量，而那个文件不许改），所以在这里复制一份字面量，
         * 并在这个注释里标明来源 —— 那边变了不会编译报错，但最坏结果只是多发一条「……」。
         */
        const val SPLITTER_EMPTY_FALLBACK = "……"
    }
}

/**
 * 诊断落盘：`files/proactive.log`。
 *
 * 为什么写文件而不是 logcat：这台机器上拿不到 logcat（见 `Notifier` 里同样的理由），
 * 而"她为什么这几小时一句话都没说"只能靠这行 reason 回答。
 * 只追加、超过 [MAX_BYTES] 截掉前一半，避免常驻后台把存储写满。
 */
private object ProactiveLog {

    private const val FILE_NAME = "proactive.log"
    private const val MAX_BYTES = 64L * 1024

    fun append(context: Context, message: String) {
        runCatching {
            val file = File(context.filesDir, FILE_NAME)
            if (file.exists() && file.length() > MAX_BYTES) {
                val kept = file.readLines(Charsets.UTF_8).let { it.drop(it.size / 2) }
                file.writeText(kept.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
            }
            file.appendText("${System.currentTimeMillis()} $message\n", Charsets.UTF_8)
        }
    }
}
