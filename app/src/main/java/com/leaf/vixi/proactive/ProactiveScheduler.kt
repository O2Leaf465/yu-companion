package com.leaf.vixi.proactive

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * 3 小时一次的周期任务。
 *
 * 几个数值为什么是这几个：
 *  · **间隔 3 小时**：WorkManager 的最小周期是 15 分钟，但主动开口不是心跳 ——
 *    它要做的是"隔得够久，她想说话了"这件事；拉得太密既费电，又会把
 *    `backlog`/`missing` 这种按小时长起来的量摊薄到永远过不了阈值。
 *    3 小时 ≈ 一天最多 8 次机会，睡一觉、上一节课回来正好一次。
 *  · **网络约束 CONNECTED**：没有网就别叫醒进程 —— 读状态、算指数全在本地，
 *    但算完仍然要请求 LLM，没网只是白跑一趟、白耗一次唤醒和电量。
 *  · **首次延迟 15 分钟**：不给的话，装完/首次授权后 WorkManager 会"尽快"跑一次，
 *    表现成"刚打开 App 她就没头没脑说一句"。等 15 分钟，让账本和状态先落定。
 *  · **ExistingPeriodicWorkPolicy.KEEP**：已经排过就**不要重置计时**。
 *    用 UPDATE/REPLACE 的话，用户每次开 App 都会把"下一次触发"推后 3 小时 ——
 *    一天开几次就等于永远不触发（这是周期任务最经典的坑）。
 */
object ProactiveScheduler {

    /** 唯一任务名：同名 `enqueueUniquePeriodicWork` 才会去重，不会攒出 10 个任务 */
    const val UNIQUE_NAME = "vixi_proactive_3h"

    const val INTERVAL_HOURS = 3L
    const val INITIAL_DELAY_MINUTES = 15L

    fun schedule(context: Context): Boolean = runCatching {
        // KEEP 是有意的：改间隔/约束要换 UNIQUE_NAME 或临时 cancel()（见下面的注释）
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request(),
        )
    }.isSuccess

    fun request(): PeriodicWorkRequest = PeriodicWorkRequestBuilder<ProactiveWorker>(
        INTERVAL_HOURS,
        TimeUnit.HOURS,
    )
        .setInitialDelay(INITIAL_DELAY_MINUTES, TimeUnit.MINUTES)
        .setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build(),
        )
        .build()

    /**
     * 关掉（调试用）。
     *
     * 注意和 KEEP 的关系：想换间隔/约束时，先 `cancel()` 再 `schedule()`，
     * 只改 [INTERVAL_HOURS] 而不取消是没用的 —— KEEP 会保留旧请求，
     * 这种"改了代码但不生效"最容易被误判成代码没走到。
     */
    fun cancel(context: Context): Boolean = runCatching {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
    }.isSuccess
}
