package com.leaf.vixi.data

import android.content.Context
import java.io.File

/**
 * 她可以发的图（贴纸库）。
 *
 * 图片来源这件事有几种做法（本地图库 / 生成图 / 贴纸），**选贴纸**的理由：
 * 不花调用、不花额度、不需要联网，而且"表情包"本来就是聊天里最常见的"发图"。
 *
 * 用法：把图片丢进 `files/stickers/`（文件名就是可读的名字），
 * 提示词里会把文件名列给她，她按 `[图:文件名]` 发出来。
 *
 * **目录为空 = 这个能力不存在**：不注入【可以发的图】，她也不会产出 `[图:…]`，
 * 行为与加这个功能之前逐字一致。
 */
class StickerStore(context: Context) {

    private val stickersDir = File(context.filesDir, "stickers")
    private val imagesDir = File(context.filesDir, "images")

    private val allowed = setOf("jpg", "jpeg", "png", "webp", "gif")

    /** 可用贴纸文件名（稳定排序，避免同一份提示词每次顺序不同 → 破坏缓存前缀） */
    fun names(): List<String> = runCatching {
        stickersDir.listFiles()
            ?.filter { it.isFile && it.length() > 0 && it.extension.lowercase() in allowed }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()
    }.getOrDefault(emptyList())

    /**
     * 把贴纸复制进 `files/images/`，返回**气泡能用的文件名**。
     *
     * 为什么不在气泡里直接指向 stickers 目录：图片渲染、导出归档、删记录时清图
     * 全都按 `files/images/` 这一套走。复制一份（几十 KB）比在这里开第二条路径便宜。
     */
    fun materialize(name: String, ts: Long): String? = runCatching {
        if (name !in names()) return null
        val src = File(stickersDir, name)
        if (!src.exists() || src.length() <= 0L) return null
        imagesDir.mkdirs()
        val ext = src.extension.ifBlank { "jpg" }
        val out = File(imagesDir, "her_${ts}_${kotlin.math.abs(name.hashCode())}.$ext")
        src.copyTo(out, overwrite = true)
        out.name
    }.getOrNull()

    fun dir(): File = stickersDir
}
