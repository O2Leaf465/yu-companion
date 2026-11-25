package com.leaf.vixi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 她在后台回消息时的通知。
 *
 * 只在 App 不在前台时发 —— 你正看着她打字时弹通知是打扰。
 * 点通知直接回到聊天页。
 */
object Notifier {

    private const val CHANNEL_ID = "vixi_reply"
    private const val NOTIFICATION_ID = 1001

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "她的消息",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "你不在聊天页面时，她回话了会提醒你"
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    fun notifyReply(context: Context, text: String, title: String = "鱼") {
        // 诊断落盘：这台机器 logcat 拿不到，只能写文件看（run-as 可读）
        val diag = java.io.File(context.filesDir, "notify.log")
        fun log(line: String) {
            runCatching {
                diag.appendText("${System.currentTimeMillis()} $line\n", Charsets.UTF_8)
            }
        }

        // 前台时不打扰
        if (App.instance.isForeground) {
            log("skip: foreground")
            return
        }
        ensureChannel(context)

        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager == null) {
            log("fail: manager null")
            return
        }

        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            0,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }

        val preview = text.replace("\n", " ").take(60).ifBlank { "（新消息）" }

        val notification = builder
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(preview)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        // 每次用不同 id：同 id 只是"更新"，系统不会重新提醒/震动 —— 这就是
        // "第一条弹了、后面都不弹"的直接原因。
        val id = NOTIFICATION_ID + (System.currentTimeMillis() % 5000).toInt()
        val posted = runCatching { manager.notify(id, notification) }
        log("post id=$id ok=${posted.isSuccess} err=${posted.exceptionOrNull()?.message}")
    }

    fun clear(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.cancelAll() }
    }
}
