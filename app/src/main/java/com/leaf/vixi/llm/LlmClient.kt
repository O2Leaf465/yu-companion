package com.leaf.vixi.llm

import com.leaf.vixi.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class LlmException(message: String) : Exception(message)

data class LlmResult(
    val text: String,
    val promptTokens: Int,
    val completionTokens: Int,
    /** 接口返回的模型名（用来确认别名回落，例如 deepseek-chat → deepseek-flash） */
    val model: String,
    /** 联网搜索用到的来源（没有搜索时为空） */
    val sources: List<SearchSource> = emptyList(),
    /** 这一轮模型**真的**联网搜了没有（服务端工具由模型自己决定要不要用） */
    val searched: Boolean = false,
)

/** 联网搜索返回的一条来源（`server_tool_calls[].calls[].sources[]`） */
data class SearchSource(
    val title: String,
    val url: String,
    val snippet: String? = null,
    val siteName: String? = null,
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

    /**
     * @param webSearch 这一轮要不要让她联网（**服务端工具**，不是第二次调用）。
     *
     * 官方口径（2026-11 文档）：
     *  · 工具名 `deepseek_web_search`，必须 `model = deepseek-flash`
     *  · 必须显式 `thinking:{type:disabled}`，否则会变成 deep search（最多 ~20 次工具调用，很慢）
     *  · **不能强制**联网（`tool_choice` 指定它会 400），搜不搜由模型自己判断
     *  · 声明该工具后，服务端会忽略 temperature/top_p 等采样参数
     *  · 不单独计费，但搜索结果会进上下文按 token 算
     */
    suspend fun chat(prompt: LlmPrompt, webSearch: Boolean = false): LlmResult = withContext(Dispatchers.IO) {
        val first = call(
            prompt.user, prompt.system, prompt.maxTokens, prompt.images, webSearch = webSearch,
        )
        if (first.text.isNotBlank()) return@withContext first

        // 搜索路径不重试：命中 content_filter 时空 content 是审核拒绝，重试只会多花钱
        if (webSearch) return@withContext first

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

    private suspend fun call(
        user: String,
        system: String,
        maxTokens: Int,
        images: List<String> = emptyList(),
        temperature: Double = 1.1,
        webSearch: Boolean = false,
    ): LlmResult {
        require(settings.isConfigured()) { "还没配置 API" }

        val payload = JSONObject().apply {
            // 联网必须走 deepseek-flash（服务端工具的硬要求）；平常仍用设置里那个模型
            put("model", if (webSearch) WEB_SEARCH_MODEL else settings.model)
            put("stream", false)
            put("max_tokens", maxTokens)
            if (webSearch) {
                // thinking 默认是开的：不显式关掉会变成 deep search（最多约 20 次工具调用，非流式会等很久）
                put("thinking", JSONObject().put("type", "disabled"))
                put("tools", JSONArray().put(JSONObject().put("type", "deepseek_web_search")))
                // 声明工具后服务端会忽略 temperature —— 所以联网是"手动单轮开关"，不常开
            } else {
                put("temperature", temperature)
            }
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

        val (code, raw) = httpExecute(request, if (webSearch) 150L else 90L)

        if (code !in 200..299) {
            throw LlmException(
                when (code) {
                    401, 403 -> "Key 不对或没权限（$code）"
                    402 -> "余额不足（$code）"
                    429 -> "请求太频繁（429）"
                    in 500..599 -> "服务端出错（$code）"
                    else -> "HTTP $code：${raw.take(120)}"
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

        val msg = choice.optJSONObject("message")
        val text = msg?.optString("content").orEmpty()
        val usage = json.optJSONObject("usage")

        val promptTokens = usage?.optInt("prompt_tokens") ?: 0
        val completionTokens = usage?.optInt("completion_tokens") ?: 0
        val billedModel = json.optString("model").ifBlank { settings.model }

        // 联网搜索的结果**不在 content 里**，也不在 tool_calls 里：
        //   · 来源：message.server_tool_calls[].calls[].sources[]
        //   · 引用角标：message.annotations[]（type=url_citation）
        // 这里只取来源（够用来在气泡下面显示"她查了哪几条"），角标不去重排文本。
        var searched = false
        val sources = ArrayList<SearchSource>()
        msg?.optJSONArray("server_tool_calls")?.let { steps ->
            for (i in 0 until steps.length()) {
                val calls = steps.optJSONObject(i)?.optJSONArray("calls") ?: continue
                for (j in 0 until calls.length()) {
                    val c = calls.optJSONObject(j) ?: continue
                    if (!c.optString("kind").contains("search")) continue
                    searched = true
                    val arr = c.optJSONArray("sources") ?: continue
                    for (k in 0 until arr.length()) {
                        val r = arr.optJSONObject(k) ?: continue
                        val url = r.optString("url")
                        if (url.isBlank()) continue
                        sources += SearchSource(
                            title = r.optString("title").ifBlank { url },
                            url = url,
                            snippet = r.optString("snippet").ifBlank { null },
                            siteName = r.optString("site_name").ifBlank { null },
                        )
                    }
                }
            }
        }

        return LlmResult(
            text = text,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            model = billedModel,
            sources = sources.distinctBy { it.url }.take(5),
            searched = searched,
        )
    }

    /**
     * 发请求：**enqueue + 挂起**，不用 `execute()`。
     *
     * 为什么必须换：`execute()` 是阻塞调用，协程被取消时它照样跑完 ——
     * 用户切会话/退出页面，请求还在跑还在计费，而且 `withTimeout` 抛不进去，
     * 100 秒那层兜底是装饰性的（真正生效的只有 OkHttp 的 callTimeout 90 秒）。
     * 接到 `call.cancel()` 上之后，取消才真的把连接掐掉。
     */
    private suspend fun httpExecute(request: Request, timeoutSec: Long = 90): Pair<Int, String> =
        suspendCancellableCoroutine { cont ->
            val call = http.newCall(request)
            // 联网那一轮会多一轮"搜索 + 作答"，90 秒偏紧；按调用覆盖单次超时
            if (timeoutSec != 90L) runCatching { call.timeout().timeout(timeoutSec, TimeUnit.SECONDS) }
            cont.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(object : Callback {
                override fun onFailure(c: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWith(Result.failure(e))
                }

                override fun onResponse(c: Call, response: Response) {
                    val pair = response.use {
                        val raw = runCatching { it.body?.string().orEmpty() }.getOrDefault("")
                        it.code to raw
                    }
                    if (cont.isActive) cont.resume(pair)
                }
            })
        }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()

        /**
         * 联网搜索必须用这个模型（官方服务端工具 `deepseek_web_search` 的硬要求，
         * 2026-11 文档）。所以"联网"是**单轮开关**，不常开：
         * 声明工具后服务端会忽略 temperature，人设的温度会丢。
         */
        const val WEB_SEARCH_MODEL = "deepseek-flash"
    }
}
