package com.leaf.vixi.proactive

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.leaf.vixi.MainActivity

/**
 * 「她主动找你」的通知渠道。
 *
 * 为什么和 `Notifier`（channel `vixi_reply`）分开：这两种提醒在用户心里不是一件事 ——
 * "她回我了"是对我说话的应答，"她自己想说话"是她发起。分成两个渠道，
 * 用户可以在系统设置里只关掉后一种（很多人恰恰只希望静音这个），而不用连回复提醒一起关掉。
 *
 * 用 `NotificationCompat`：`Notifier` 里用的是 framework 的 `Notification.Builder` +
 * 手写 O 版本分支；新代码不必再抄那套兼容分支（core 已经进了依赖，见 build.gradle.kts）。
 */
object ProactiveNotifications {

    const val CHANNEL_ID = "vixi_proactive"
    private const val NOTIFICATION_ID_BASE = 3000

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "她主动找你",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "她自己想说话、主动找你时会提醒（不是回复你的消息）"
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * 发一条"她主动开口"的提醒。
     *
     * @return 真的发出去了才 true。没权限 / 没 manager / 系统拒绝都返回 false，
     *         **调用方（Worker）不需要因此补偿**：消息已经写进聊天记录了，
     *         用户下次打开 App 照样能看见，只是没被弹一下。
     */
    fun notify(context: Context, title: String, text: String): Boolean {
        if (!canPost(context)) return false
        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false

        // 点开回到 MainActivity（会话已经由 Worker 切到她的单聊，所以落地就在那条消息上）
        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            0,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val preview = text.replace("\n", " ").take(60).ifBlank { "（她想说话）" }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(400)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        // 每次换一个 id：同 id 只是"更新已有通知"，系统不会再提醒/震动
        val id = NOTIFICATION_ID_BASE + (System.currentTimeMillis() % 4000).toInt()
        return runCatching { manager.notify(id, notification) }.isSuccess
    }

    /**
     * Android 13+ 没有 POST_NOTIFICATIONS 就发不出去（系统会把 notify 静默丢掉）。
     * 权限本身由 `MainActivity.askNotificationPermission()` 在启动时请求，
     * 这里只负责"没给就别白写一条通知再被系统扔掉"。
     */
    private fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }
}
