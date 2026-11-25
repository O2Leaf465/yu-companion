package com.leaf.vixi.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * 图片与头像的落盘。
 *
 * 三条纪律：
 *   1. 一律降采样再存 —— 相册原图动辄 5MB，聊天里只需要 1024px；
 *   2. 发给模型的那份再小一档（768px / q80），图片 token 按视觉编码算，不必喂原图；
 *   3. 解码都放在调用方的 IO 线程里，Composable 里不要直接 BitmapFactory。
 */
class ImageStore(private val context: Context) {

    private val imagesDir = File(context.filesDir, "images").apply { mkdirs() }
    private val avatarDir = File(context.filesDir, "avatar").apply { mkdirs() }

    // ---------- 聊天图片 ----------

    /** 从相册选中的图存进 App，返回文件名（失败返回 null） */
    fun savePicked(uri: Uri, maxPx: Int = 1024): String? = runCatching {
        // 文件名带随机后缀：只靠毫秒时间戳的话，同一毫秒内连存两张会互相覆盖，
        // 一旦覆盖，chat.jsonl 里两条消息会指向同一张图，且不可恢复。
        val name = "img_${System.currentTimeMillis()}_${(100..999).random()}.jpg"
        val target = File(imagesDir, name)
        val bitmap = decode(uri, maxPx) ?: return null
        FileOutputStream(target).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        bitmap.recycle()
        name
    }.getOrNull()

    fun imageFile(name: String): File = File(imagesDir, name)

    fun deleteImage(name: String) {
        runCatching { File(imagesDir, name).delete() }
    }

    /**
     * 存到系统相册（Pictures/鱼）。
     * 走 MediaStore 插入，不用申请存储权限（自己插入的条目在 scoped storage 下可直接写）。
     */
    fun saveToGallery(name: String): Boolean = runCatching {
        val src = imageFile(name)
        if (!src.exists()) return false

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "vixi_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/鱼")
            }
        }
        val uri = context.contentResolver
            .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        context.contentResolver.openOutputStream(uri)?.use { out ->
            src.inputStream().use { it.copyTo(out) }
        } ?: return false
        true
    }.getOrDefault(false)

    /**
     * 要发给模型的那份：768px / q80，返回 JPEG 字节。
     * 返回 null 表示文件没了或解码失败 —— 调用方应该退化成纯文字，而不是发一张坏图。
     */
    fun bytesForApi(name: String, maxPx: Int = 768): ByteArray? = runCatching {
        val file = imageFile(name)
        if (!file.exists()) return null
        val bitmap = decode(Uri.fromFile(file), maxPx) ?: return null
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
        bitmap.recycle()
        out.toByteArray()
    }.getOrNull()

    // ---------- 头像 ----------

    fun avatarFile(which: String): File = File(avatarDir, "$which.jpg")

    fun hasAvatar(which: String): Boolean = avatarFile(which).let { it.exists() && it.length() > 0 }

    fun saveAvatar(uri: Uri, which: String): Boolean = runCatching {
        val bitmap = decode(uri, 256) ?: return false
        FileOutputStream(avatarFile(which)).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        true
    }.getOrDefault(false)

    /** 已经裁好的位图直接存成头像（裁剪页用） */
    fun saveAvatarBitmap(bitmap: Bitmap, which: String): Boolean = runCatching {
        val longest = maxOf(bitmap.width, bitmap.height)
        val final = if (longest > 256) {
            val s = 256f / longest
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * s).toInt().coerceAtLeast(1),
                (bitmap.height * s).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            bitmap
        }
        FileOutputStream(avatarFile(which)).use { final.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        if (final !== bitmap) final.recycle()
        true
    }.getOrDefault(false)

    fun clearAvatar(which: String) {
        runCatching { avatarFile(which).delete() }
    }

    // ---------- 解码 ----------

    /** 按 maxPx 降采样解码；调用方负责在工作线程执行 */
    fun decode(uri: Uri, maxPx: Int): Bitmap? = runCatching {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val src = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return null

        val longest = maxOf(src.width, src.height)
        if (longest <= maxPx) {
            src
        } else {
            val scale = maxPx.toFloat() / longest
            val scaled = Bitmap.createScaledBitmap(
                src,
                (src.width * scale).toInt().coerceAtLeast(1),
                (src.height * scale).toInt().coerceAtLeast(1),
                true,
            )
            if (scaled !== src) src.recycle()
            scaled
        }
    }.getOrNull()

    fun decode(file: File, maxPx: Int): Bitmap? =
        if (!file.exists()) null else decode(Uri.fromFile(file), maxPx)
}
