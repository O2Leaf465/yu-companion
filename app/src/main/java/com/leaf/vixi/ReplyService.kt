package com.leaf.vixi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * 只在"等她回话"这段时间存在的轻量前台服务。
 *
 * 为什么必须有它：Android（尤其 vivo 这类 ROM）会在 App 退到后台后冻结甚至杀掉进程，
 * 协程被冻住 = 她的回复根本不发生，自然也就没有通知。前台服务能把进程钉住，
 * 让这一轮请求跑完、通知发出去。
 *
 * 用 shortService 类型：不需要额外运行时权限，系统会在几分钟后自动收掉 —— 正好符合
 * "等一句话"的生命周期，不会变成常驻耗电。用完我们自己也会 stop。
 */
class ReplyService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        ensureChannel()

        val open = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = android.app.PendingIntent.getActivity(
            this, 0, open,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("鱼")
            .setContentText("正在等她把话说完…")
            .setOngoing(true)
            .setContentIntent(pending)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        // 低优先级、无声：保活通知不该打扰你
        val channel = NotificationChannel(
            CHANNEL_ID,
            "等她回话",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "你切出去时暂时保持连接，让她能把这句回完"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "vixi_keepalive"
        private const val NOTIFICATION_ID = 2001

        fun start(context: Context) {
            val intent = Intent(context, ReplyService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, ReplyService::class.java)) }
        }
    }
}
