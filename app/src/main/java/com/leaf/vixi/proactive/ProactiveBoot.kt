package com.leaf.vixi.proactive

import android.content.Context
import androidx.startup.Initializer
import androidx.work.WorkManagerInitializer

/**
 * 在 App 启动时把周期任务排上 —— **不改 `App.kt` 的办法**。
 *
 * 为什么不用 `App.onCreate()`：本次改动的文件边界只允许新建文件 + `AndroidManifest.xml`
 * + `app/build.gradle.kts`，`App.kt` 和 `MainActivity.kt` 都在别人的手里。
 * androidx.startup 正好是为此存在的：一个 `Initializer` + 清单里一条 `<meta-data>`，
 * 系统在建进程时就会回调它（由 `androidx.startup.InitializationProvider` 触发，
 * 早于任何 Activity）。
 *
 * `dependencies()` 返回 [WorkManagerInitializer] 是**必须**的：WorkManager 自己
 * 也是用 startup 初始化的，两个 initializer 谁先跑决定了 `WorkManager.getInstance()`
 * 会不会抛 `IllegalStateException: WorkManager is not initialized`。
 * 声明依赖之后顺序是确定的，不用依赖清单里 meta-data 的书写次序。
 *
 * 注意 [schedule] 内部是幂等的（`enqueueUniquePeriodicWork` + KEEP），
 * 所以每次进程启动都调用也没关系，不会叠加任务、也不会重置计时。
 */
class ProactiveBoot : Initializer<Unit> {

    override fun create(context: Context) {
        ProactiveScheduler.schedule(context)
    }

    override fun dependencies(): List<Class<out Initializer<*>>> =
        listOf(WorkManagerInitializer::class.java)
}
