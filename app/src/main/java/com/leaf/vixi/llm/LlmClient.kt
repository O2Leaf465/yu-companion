package com.leaf.vixi.llm

import com.leaf.vixi.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class LlmException(message: String) : Exception(message)

data class LlmResult(
    val text: String,
    val promptTokens: Int,
    val completionTokens: Int,
    /** 接口返回的模型名（用来确认别名回落，例如 deepseek-chat → deepseek-flash） */
    val model: String,
)

/**
 * OpenAI 兼容协议，一次调用，无工具循环、无流式。
 *
 * 没有流式的理由：token 由输入输出长度决定，跟流式无关；
 * 而不流式少一个 SSE 解析器和一堆半包 bug，也更好"伪造打字节奏"。
 */
class LlmClient(private val settings: SettingsStore) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        // callTimeout 是**整次调用**（含读完 body）的硬上限。
        // 只设 readTimeout 时，服务端慢慢滴字会一直不超时 —— 真机上就是"卡在回复中"。
        .callTimeout(90, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun chat(prompt: LlmPrompt): LlmResult = withContext(Dispatchers.IO) {
        val first = call(prompt.user, prompt.system, prompt.maxTokens, prompt.images)
        if (first.text.isNotBlank()) return@withContext first

        // 带图不重试：图片按视觉 token 计费，重试等于双份，而失败往往不是预算问题。
        if (prompt.images.isNotEmpty()) return@withContext first

        // 空回复不能当成"她不想说"：多半是推理 token 把预算吃光了（坑 #2）
        call(prompt.user, prompt.system, prompt.maxTokens * 3)
    }

    /**
     * 设置页「测试连接」用：发一个最小请求，把 HTTP 层的问题一次说清楚。
     * 花不到 ¥0.0001，但能立刻区分是 key 错、地址错、模型名错、还是网络不通。
     */
    suspend fun ping(): String = withContext(Dispatchers.IO) {
        val r = call(user = "回一个字：ok", system = "只回一个字", maxTokens = 8)
        val text = r.text.trim().ifBlank { "（空回复）" }
        "通了 · 模型回了「${text.take(12)}」"
    }

    /**
     * 会话摘要：每 10 轮跑一次，摊薄到 0.1 次/轮。
     *
     * 它不改人设、不进气泡，只产出"我们在聊什么/定下了什么/还没说完的话头"，
     * 供下一轮塞进 user 段开头。这是治"思考不连贯"最直接的一刀。
     */
    suspend fun summarize(material: String, previous: String): String = withContext(Dispatchers.IO) {
        val system = "你是聊天记录压缩器。只写事实，不写评价，不寒暄，不解释你在做什么。"
        val user = buildString {
            if (previous.isNotBlank()) {
                appendLine("【已有的旧摘要】")
                appendLine(previous.take(600))
                appendLine()
            }
            appendLine("【新的对话】")
            appendLine(material.take(4000))
            appendLine()
            append("压成不超过 3 行：1) 我们在聊什么 2) 定下的事或态度 3) 还没说完的话头。")
            append("只写事实，不写评价。没有的项就省略，不要编。")
        }
        call(user = user, system = system, maxTokens = 260).text.trim()
    }

    /**
     * 只做一件事：把一句话削成"检索用的实词"。
     *
     * 为什么让模型干：本地词表能处理「房梁」「绳子」这种具体名词，但处理不了
     * 「我想要什么功能」这种 —— 去掉虚词只剩「功能」，而当时回答里根本没有"功能"这个词
     * （她说的是"能帮我规划日程的东西"）。模型能把这句话提炼成「功能 想要 需求」这种组合，
     * 命中率明显更高。代价：只在真的触发记忆时才多花一次极小的调用（输出 ≤ 30 token）。
     */
    suspend fun extractKeywords(text: String): String = withContext(Dispatchers.IO) {
        val system = "你是检索词提取器。只输出关键词，用空格分隔，最多 4 个。不要解释、不要标点、不要引号。"
        val user = "从这句话里提取用来检索旧聊天记录的实词（名词/动词），去掉你我他、还、记得、" +
            "什么、吗、的、了 这类虚词。只输出关键词：\n$text"
        val r = call(user = user, system = system, maxTokens = 30, temperature = 0.2)
        r.text.trim()
            .replace(Regex("[\"'“”‘’]"), "")
            .replace(Regex("[\\s,，、。;；]+"), " ")
            .trim()
            .take(40)
    }

    private fun call(
        user: String,
        system: String,
        maxTokens: Int,
        images: List<String> = emptyList(),
        temperature: Double = 1.1,
    ): LlmResult {
        require(settings.isConfigured()) { "还没配置 API" }

        val payload = JSONObject().apply {
            put("model", settings.model)
            put("stream", false)
            put("max_tokens", maxTokens)
            put("temperature", temperature)
            put(
                "messages", JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", system))
                    // 没有图就用纯字符串（缓存友好、兼容所有模型）；
                    // 有图才退化成 OpenAI 兼容的 content 数组。
                    val content: Any = if (images.isEmpty()) {
                        user
                    } else {
                        JSONArray().apply {
                            put(JSONObject().put("type", "text").put("text", user))
                            images.forEach { b64 ->
                                put(
                                    JSONObject().put("type", "image_url")
                                        .put(
                                            "image_url",
                                            JSONObject().put("url", "data:image/jpeg;base64,$b64")
                                        )
                                )
                            }
                        }
                    }
                    put(JSONObject().put("role", "user").put("content", content))
                }
            )
        }

        val request = Request.Builder()
            .url(settings.baseUrl + "/chat/completions")
            .addHeader("Authorization", "Bearer ${settings.apiKey}")
            .post(payload.toString().toRequestBody(JSON))
            .build()

        return http.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw LlmException(
                    when (resp.code) {
                        401, 403 -> "Key 不对或没权限（${resp.code}）"
                        402 -> "余额不足（${resp.code}）"
                        429 -> "请求太频繁（429）"
                        in 500..599 -> "服务端出错（${resp.code}）"
                        else -> "HTTP ${resp.code}：${raw.take(120)}"
                    }
                )
            }

            val json = runCatching { JSONObject(raw) }
                .getOrElse { throw LlmException("返回的不是 JSON：${raw.take(120)}") }

            json.optJSONObject("error")?.let {
                throw LlmException(it.optString("message", "接口报错"))
            }

            val choice = json.optJSONArray("choices")?.optJSONObject(0)
                ?: throw LlmException("响应里没有 choices")

            val text = choice.optJSONObject("message")?.optString("content").orEmpty()
            val usage = json.optJSONObject("usage")

            val promptTokens = usage?.optInt("prompt_tokens") ?: 0
            val completionTokens = usage?.optInt("completion_tokens") ?: 0
            val billedModel = json.optString("model").ifBlank { settings.model }

            LlmResult(
                text = text,
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                model = billedModel,
            )
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
