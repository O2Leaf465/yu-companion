package com.leaf.vixi.ui

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 从系统文件选择器拿到的 Uri 里读文本。
 *
 * 只做一件事：读成字符串。不做编码猜测（统一按 UTF-8），不写盘 ——
 * 让调用方决定是"放进编辑器给你看"还是"直接保存"。
 */
object PromptFile {

    suspend fun read(context: Context, uri: Uri, maxChars: Int = 60_000): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { ins ->
                    ins.bufferedReader(Charsets.UTF_8).readText().take(maxChars)
                }
            }.getOrNull()
        }
}
