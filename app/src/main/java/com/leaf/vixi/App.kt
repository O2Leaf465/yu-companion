package com.leaf.vixi

import android.app.Application
import com.leaf.vixi.data.CharacterStore
import com.leaf.vixi.data.ConversationStore
import com.leaf.vixi.data.ImageStore
import com.leaf.vixi.data.SettingsStore
import com.leaf.vixi.llm.PersonaEngine
import java.io.File

class App : Application() {

    lateinit var settings: SettingsStore
        private set

    lateinit var persona: PersonaEngine
        private set

    lateinit var images: ImageStore
        private set

    /** 角色表 / 会话表 */
    lateinit var characters: CharacterStore
        private set

    lateinit var conversations: ConversationStore
        private set

    /** 她能发的图（`files/stickers/`）；界面在 设置 → 贴纸 */
    lateinit var stickers: com.leaf.vixi.data.StickerStore
        private set

    /** 长期人物事实（`files/facts_<角色id>.jsonl`）；界面在 设置 → 系统提示词 |
        ——「人物事实」一节 */
    lateinit var facts: com.leaf.vixi.data.FactStore
        private set

    /** 是否在前台：决定她的回复要不要发通知 */
    @Volatile
    var isForeground: Boolean = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = SettingsStore(this)
        persona = PersonaEngine(this)
        images = ImageStore(this)
        characters = CharacterStore(this)
        conversations = ConversationStore(this)
        stickers = com.leaf.vixi.data.StickerStore(this)
        facts = com.leaf.vixi.data.FactStore(this)
        runCatching { migrateSingleCharacter() }
        Notifier.ensureChannel(this)
    }

    /**
     * 单角色时代 → 多角色的数据迁移（幂等，只生效一次）。
     *
     * 原则：**只复制、不删除**。老文件全部留在原处，出问题还能回去。
     *   chat.jsonl          → chat_v1.jsonl
     *   topics.jsonl        → topics_v1.jsonl
     *   mem/current_topic.json  → mem/current_topic_v1.json
     *   mem/corrections.jsonl   → mem/corrections_v1.jsonl
     *   avatar/her.jpg      → avatar/c1.jpg（头像改成按角色 id 存）
     *   persona_override.md → 角色 c1 的人设
     */
    private fun migrateSingleCharacter() {
        val legacyPersona = runCatching { persona.personaText }.getOrDefault("")
        val first = characters.ensureMigrated(legacyPersona)
        val conv = conversations.ensureSingleFor(first)

        // 迁移只做一次。
        // 为什么不能只靠"目标文件不存在"：头像被**用户清掉**时目标文件就不存在了，
        // 每次启动都会把 her.jpg 再复制回来 —— 表现为"删掉的头像自己复活"。
        val flag = File(filesDir, "migrated_single_to_multi_v1")
        if (!flag.exists()) {
            copyIfMissing("chat.jsonl", "chat_${conv.id}.jsonl")
            // 测试环境以前是全局一份，现在按会话分开：老的测试记录归到 v1，不直接丢
            copyIfMissing("chat_sandbox.jsonl", "chat_sandbox_${conv.id}.jsonl")
            copyIfMissing("topics.jsonl", "topics_${conv.id}.jsonl")
            copyIfMissing("mem/current_topic.json", "mem/current_topic_${conv.id}.json")
            copyIfMissing("mem/corrections.jsonl", "mem/corrections_${conv.id}.jsonl")
            copyIfMissing("avatar/her.jpg", "avatar/${first.id}.jpg")
            runCatching { flag.writeText(System.currentTimeMillis().toString(), Charsets.UTF_8) }
        }

        if (conversations.currentId.isBlank()) {
            conversations.currentId = conv.id
        }
    }

    private fun copyIfMissing(from: String, to: String) {
        val src = File(filesDir, from)
        val dst = File(filesDir, to)
        if (!src.exists() || dst.exists()) return
        runCatching {
            dst.parentFile?.mkdirs()
            src.copyTo(dst, overwrite = false)
        }
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
