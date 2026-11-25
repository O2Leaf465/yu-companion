package com.leaf.vixi.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 三项配置：baseUrl / model / apiKey。
 *
 * apiKey 不落明文：用 AndroidKeyStore 里的 AES-GCM 密钥加密后存 SharedPreferences。
 * 密钥本身onChip/在 TEE 里，App 卸载即失效，备份带不走 —— 比 enc 库少一个依赖。
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("vixi_settings", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString(K_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        set(value) = prefs.edit().putString(K_BASE_URL, value.trim().trimEnd('/')).apply()

    var model: String
        get() = prefs.getString(K_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = prefs.edit().putString(K_MODEL, value.trim()).apply()

    /**
     * 测试环境：不记忆、不归档、不检索、不导出，用独立的聊天记录文件。
     * 存在的意义：调情绪系统时要反复跑同一个场景，真实记忆会污染结果。
     */
    var sandbox: Boolean
        get() = prefs.getBoolean("sandbox", false)
        set(value) = prefs.edit().putBoolean("sandbox", value).apply()

    enum class KeyState { None, Ok, Broken }

    /**
     * key 的三种状态。Broken = 密文还在但解不开（AndroidKeyStore 的密钥被系统清了，
     * 比如换锁屏、恢复备份、或写入过程中进程被杀）。
     *
     * 第一版把解不开直接返回 ""，界面上就和"没设置过"一模一样 —— 看起来像 key 被偷偷清掉了。
     * 现在把它单独标出来，并且绝不覆盖原有密文，等你重新填一次。
     */
    val keyState: KeyState
        get() {
            val enc = prefs.getString(K_API_KEY, null) ?: return KeyState.None
            val dec = decrypt(enc) ?: return KeyState.Broken
            return if (dec.isBlank()) KeyState.None else KeyState.Ok
        }

    var apiKey: String
        get() = prefs.getString(K_API_KEY, null)?.let { decrypt(it) } ?: ""
        set(value) {
            val enc = if (value.isBlank()) null else encrypt(value.trim())
            prefs.edit().putString(K_API_KEY, enc).apply()
        }

    fun isConfigured(): Boolean = keyState == KeyState.Ok && baseUrl.isNotBlank() && model.isNotBlank()

    /** 显示用：只露头尾，防止截图/日志泄漏 */
    fun maskedKey(): String {
        val k = apiKey
        return when {
            keyState == KeyState.Broken -> "读取失败，需要重填"
            k.isBlank() -> "未设置"
            k.length <= 10 -> "已设置（${k.length} 位）"
            else -> "${k.take(6)}••••${k.takeLast(4)}"
        }
    }

    // ---------- AES-GCM / AndroidKeyStore ----------

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun secretKey(): SecretKey {
        val ks = keyStore()
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(iv + body, Base64.NO_WRAP)
    }.getOrNull()

    private fun decrypt(encoded: String): String? = runCatching {
        val raw = Base64.decode(encoded, Base64.NO_WRAP)
        val iv = raw.copyOfRange(0, GCM_IV_LEN)
        val body = raw.copyOfRange(GCM_IV_LEN, raw.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        String(cipher.doFinal(body), Charsets.UTF_8)
    }.getOrNull()

    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
        const val DEFAULT_MODEL = "deepseek-chat"

        private const val K_BASE_URL = "base_url"
        private const val K_MODEL = "model"
        private const val K_API_KEY = "api_key_enc"

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "vixi_api_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LEN = 12
        private const val GCM_TAG_BITS = 128
    }
}
