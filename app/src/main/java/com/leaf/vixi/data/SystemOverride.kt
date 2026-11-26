package com.leaf.vixi.data

import android.content.Context
import java.io.File

/**
 * system 正文的可编辑覆盖（2026-11-26 用户要求：**系统提示词要能改**）。
 *
 * 覆盖的是"人设 + 格式约定"这整块正文；**四条安全边界永远追加在最后、不可编辑**
 * （不诊断 / 不宣称意识 / 不替代真实关系 / 危机先安全 —— 那是安全阀，
 * 不是风格设定，见 `ChatViewModel.BOUNDARY_BLOCK`）。
 *
 * 三态：
 *  - 文件不存在 → 完全按原来的方式拼（人设 + 格式约定），行为和以前逐字一致
 *  - 文件存在且非空 → 用它**替换**正文
 *  - 文件存在但清空 → 视为不存在（防止一次误删把 system 弄空）
 */
class SystemOverride(context: Context) {

    private val file = File(context.filesDir, "system_override.md")

    fun exists(): Boolean = runCatching { file.exists() && file.readText(Charsets.UTF_8).isNotBlank() }
        .getOrDefault(false)

    /** 当前覆盖内容；没有就返回 null（调用方据此回落到默认拼法） */
    fun raw(): String? = runCatching {
        file.takeIf { it.exists() }?.readText(Charsets.UTF_8)?.takeIf { it.isNotBlank() }
    }.getOrNull()

    fun save(text: String): Boolean = runCatching {
        file.parentFile?.mkdirs()
        file.writeText(text, Charsets.UTF_8)
    }.isSuccess

    fun reset(): Boolean = runCatching {
        file.takeIf { it.exists() }?.delete() ?: false
    }.getOrDefault(false)
}
