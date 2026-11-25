package com.leaf.vixi.llm

/**
 * 她这一句里夹带的"动作"：引用他刚说的一句 / 发一张图。
 *
 * 为什么用本地标签而不是再调一次模型：
 * 每轮 **1 次调用**是这个项目最硬的纪律（缓存前缀 + 成本）。所以"能不能引用、要不要发图"
 * 只能在**同一次返回**里表达 —— 让模型在正文里写两个短标签，本地把它们剥出来：
 *
 * ```
 * [引用:你不是说要早睡吗] 你自己看看几点了。
 * [图:熊猫头无语.jpg]
 * ```
 *
 * 两条纪律：
 * 1. **标签必须完全剥干净**，不能漏进气泡正文（否则用户会看到 `[图:xxx]` 这种东西）。
 * 2. **不认识的图 / 没有标签**一律当普通文字 —— 她不会因为写错一个文件名就哑掉。
 */
object HerActParser {

    private val QUOTE = Regex("\\[引用[:：]\\s*([^\\]]{1,80})]")
    private val IMAGE = Regex("\\[图[:：]\\s*([^\\]\\s]{1,80})]")
    private val FACT = Regex("\\[记住[:：]\\s*([^\\]]{1,60})]")

    /** 只剥标签，不判断图片是否存在（那件事交给调用方：它才知道贴纸目录里有什么） */
    fun enrich(s: Spoken): Spoken {
        val q = QUOTE.find(s.text)?.groupValues?.get(1)?.trim()?.ifBlank { null }
        val img = IMAGE.find(s.text)?.groupValues?.get(1)?.trim()?.ifBlank { null }
        val fact = FACT.find(s.text)?.groupValues?.get(1)?.trim()?.ifBlank { null }
        if (q == null && img == null && fact == null) return s

        var cleaned = s.text.replace(QUOTE, "").replace(IMAGE, "").replace(FACT, "").trim()
        // 标签后面常跟一个标点，剥完会剩个孤零零的"，" —— 去掉
        cleaned = cleaned.trimStart('，', '。', '、', '：', ':', ' ').trim()
        return s.copy(text = cleaned, quote = q, image = img, fact = fact)
    }

    /** 整批处理（切条之后逐条剥） */
    fun enrichAll(list: List<Spoken>): List<Spoken> = list.map { enrich(it) }
}
