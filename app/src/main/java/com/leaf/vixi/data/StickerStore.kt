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

    private val ctx: Context = context
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

    /**
     * 从相册加一张（2026-11-25 用户要求：贴纸库要能在界面上编辑，不能只靠 adb）。
     *
     * 统一压成 jpg、最长边 320px：贴纸在聊天里最大也就 180dp，
     * 存原图只是白占空间（而且复制进 images 时还要再来一份）。
     */
    fun addFromUri(uri: android.net.Uri, displayName: String? = null): String? = runCatching {
        val resolver = ctx.contentResolver
        // 名字优先用相册里的真实文件名（"猫猫点头.jpg" 比 "贴纸_12345" 有用得多）
        val fromGallery = displayName ?: runCatching {
            resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
        val name = baseName(fromGallery) ?: ("贴纸_" + System.currentTimeMillis())
        val bmp = resolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) }
            ?: return null
        val longest = maxOf(bmp.width, bmp.height)
        val scaled = if (longest > 320) {
            val k = 320.0 / longest
            android.graphics.Bitmap.createScaledBitmap(
                bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true,
            )
        } else {
            bmp
        }
        stickersDir.mkdirs()
        // 重名就加序号，**绝不覆盖已有贴纸**（outputStream() 会直接截断目标）
        var out = File(stickersDir, "$name.jpg")
        var i = 2
        while (out.exists() && i < 100) {
            out = File(stickersDir, "${name}_$i.jpg")
            i++
        }
        out.outputStream().use { scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it) }
        out.name
    }.getOrNull()

    fun delete(name: String): Boolean = runCatching {
        File(stickersDir, name).takeIf { it.exists() }?.delete() ?: false
    }.getOrDefault(false)

    /** 改名 = 改她看到的图名（提示词里列的就是这个名字） */
    fun rename(old: String, newName: String): Boolean = runCatching {
        val base = baseName(newName) ?: return false
        val src = File(stickersDir, old)
        if (!src.exists()) return false
        val ext = src.extension.ifBlank { "jpg" }
        val dst = File(stickersDir, "$base.$ext")
        // **同名的"改名"其实是保存一下没改**：dst == src 不算冲突，否则点保存永远报错
        if (dst.absolutePath != src.absolutePath && dst.exists()) return false
        src.renameTo(dst)
    }.getOrDefault(false)

    /** 文件名不许带路径分隔符/非法字符，长度限 12 个字（提示词里要列出全部名字） */
    private fun baseName(raw: String?): String? {
        val s = raw?.substringBeforeLast('.')?.trim() ?: return null
        val cleaned = s.replace(Regex("[\\\\/:*?\"<>|\\s]"), "").take(12)
        return cleaned.ifBlank { null }
    }
}
