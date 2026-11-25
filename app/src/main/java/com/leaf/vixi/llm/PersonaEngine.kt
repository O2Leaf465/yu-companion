package com.leaf.vixi.llm

import android.content.Context
import java.io.File

/**
 * 全局格式约定 + 内置默认人设素材 + 旧数据的迁移来源。**人设不在这里了。**
 *
 * 人设已经属于角色（`data/CharacterStore`，存在 characters.json）：
 * 「人设」页按会话编辑每个角色那一份，群聊就是每人一份。
 *
 * 这个类现在只剩三件事：
 *   1. `rulesText` / `saveRules` / `resetRules`：格式约定是**全局**的（怎么分条、不许编造记忆、
 *      群聊怎么带名字），不属于某个角色，所以不跟着会话走；
 *   2. `defaultPersona`：assets/persona.md，新建角色/还原默认的素材；
 *   3. `personaText`：**只给第一次迁移用**（App.migrateSingleCharacter 把老的
 *      persona_override.md 变成角色 c1 的人设）。迁移之后这个文件不再被任何页面读写，
 *      不要把它当成"人设真源"——上一版就是这里读出来的字数和人设页不一致。
 *
 * 缓存纪律：rules 是缓存前缀的尾巴（人设在前、规则在后），改了只会让下一轮失效一次。
 */
class PersonaEngine(private val context: Context) {

    private val overrideFile = File(context.filesDir, "persona_override.md")

    /** 格式约定也可以改：存这里；不存在就用内置的 */
    private val rulesOverrideFile = File(context.filesDir, "rules_override.md")

    @Volatile
    private var rulesCache: String? = null

    @Deprecated("人设已迁到角色表；这个属性只给一次性迁移用")
    val isCustomized: Boolean get() = overrideFile.exists() && overrideFile.length() > 0

    val isRulesCustomized: Boolean get() = rulesOverrideFile.exists() && rulesOverrideFile.length() > 0

    /** 内置默认人设（纯角色设定，不含任何格式规则） */
    val defaultPersona: String
        get() = readAsset("persona.md").trim()

    /** @deprecated 只给第一次迁移用，见类注释 */
    @Deprecated("人设已迁到角色表；这个属性只给一次性迁移用")
    val personaText: String
        get() = if (isCustomized) {
            runCatching { overrideFile.readText(Charsets.UTF_8) }.getOrNull()?.trim().orEmpty()
                .ifBlank { defaultPersona }
        } else {
            defaultPersona
        }

    /** 当前格式约定：你改过就用你那份，否则内置默认 */
    val rulesText: String
        get() = rulesCache ?: (if (isRulesCustomized) {
            runCatching { rulesOverrideFile.readText(Charsets.UTF_8) }.getOrNull()?.trim().orEmpty()
                .ifBlank { FORMAT_RULES }
        } else {
            FORMAT_RULES
        }).also { rulesCache = it }

    /** @deprecated 人设按角色拼，见 ChatViewModel.systemBlock() */
    @Deprecated("人设按角色拼（ChatViewModel.systemBlock），不要再拼这份全局字符串")
    val systemBlock: String
        get() = personaText + "\n\n" + rulesText

    /** @deprecated 人设由角色表管，见 CharacterStore.savePersona */
    @Deprecated("人设由角色表管：ChatViewModel.savePersonaOf / CharacterStore.savePersona")
    fun savePersona(text: String): Boolean {
        if (text.isBlank()) return false
        return runCatching { overrideFile.writeText(text.trim(), Charsets.UTF_8) }.isSuccess
    }

    fun saveRules(text: String): Boolean {
        if (text.isBlank()) return false
        val ok = runCatching { rulesOverrideFile.writeText(text.trim(), Charsets.UTF_8) }.isSuccess
        if (ok) rulesCache = null
        return ok
    }

    /** @deprecated 人设由角色表管（还原默认用 ChatViewModel.resetPersonaOf） */
    @Deprecated("人设由角色表管：ChatViewModel.resetPersonaOf")
    fun resetPersona(): Boolean {
        val ok = runCatching { overrideFile.delete() || !overrideFile.exists() }.getOrDefault(false)
        return ok
    }

    fun resetRules(): Boolean {
        val ok = runCatching { rulesOverrideFile.delete() || !rulesOverrideFile.exists() }.getOrDefault(false)
        rulesCache = null
        return ok
    }

    private fun readAsset(name: String): String =
        context.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }

    companion object {
        /**
         * 输出格式约定。只管"怎么把话切成气泡"，不管她是谁、什么性格。
         * 在「系统提示词」页可以看到它被拼在人设之后的样子。
         */
        const val FORMAT_RULES = """【输出格式约定（只约束格式，不改性格）】
1. 一次回复给 1~3 条消息，每条单独一行，用换行分隔。不要编号，不要加引号，不要加「1.」「-」这类前缀。
2. 不换行分段，不用 Markdown：不用 #、*、-、>、代码块。
3. 不解释你在做什么、不说明你怎么想的，直接说。
4. 现在没有联网搜索、没有手机操作能力；你只能说话。别承诺做不到的事，别假装查过资料。

【记忆的边界（这是硬规则）】
你的全部记忆只来自记忆库（也就是每一轮给你的「已读」和「你想起的旧事」）。
记忆库里没查到的，就是你根本没有这段记忆：
- 不许编造、不许脑补、不许"顺着猜一个"具体细节（时间、地点、数字、原话）。
- 拿不准就说记不清了、没印象，这比编一个更像人。
- 但记忆库给了片段时，要认账、要具体，不要说"我们没聊过"。
- 编造记忆是最严重的错误，比说"我忘了"严重得多。"""
    }
}
