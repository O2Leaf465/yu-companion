package com.leaf.vixi.ui

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.leaf.vixi.App
import com.leaf.vixi.data.Character
import com.leaf.vixi.data.Conversation
import com.leaf.vixi.data.Correction
import com.leaf.vixi.data.ImageStore
import com.leaf.vixi.data.MemoryStore
import com.leaf.vixi.data.MessageStore
import com.leaf.vixi.data.IndexEntry
import com.leaf.vixi.data.MemoryIndex
import com.leaf.vixi.data.MsgKind
import com.leaf.vixi.data.PendingEvent
import com.leaf.vixi.data.StateStore
import com.leaf.vixi.data.StoredMessage
import com.leaf.vixi.data.TopicStore
import com.leaf.vixi.data.VixiState
import com.leaf.vixi.memory.Recall
import com.leaf.vixi.memory.RecallGate
import com.leaf.vixi.memory.Retriever
import com.leaf.vixi.state.EventRules
import com.leaf.vixi.state.StateEventType
import com.leaf.vixi.llm.GroupReplyParser
import com.leaf.vixi.llm.Incoming
import com.leaf.vixi.llm.LlmClient
import com.leaf.vixi.llm.LlmException
import com.leaf.vixi.llm.LlmResult
import com.leaf.vixi.llm.PersonaEngine
import com.leaf.vixi.llm.PromptBuilder
import com.leaf.vixi.llm.ReplySplitter
import com.leaf.vixi.llm.Spoken
import com.leaf.vixi.llm.Turn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.LocalTime
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** 消息的发送状态（只对自己的消息有意义） */
enum class SendStatus {
    SENT,
    SENDING,
    FAILED,

    /**
     * 她这轮没回（抽身）。**和 FAILED 分开**：
     * FAILED 是"没发出去、要重发"，NO_REPLY 是"发出去了、她选择不回"，
     * 但气泡上要留一个常驻标记 —— 原来只靠 notice，2.6 秒就消失了，
     * 之后用户分不清"她没回"和"回复丢了"。
     */
    NO_REPLY,
}

data class Bubble(
    val id: Long,
    val self: Boolean,
    val text: String,
    val ts: Long = System.currentTimeMillis(),
    val kind: MsgKind = MsgKind.TEXT,
    /** 一条消息可以带多张图（对应"一次调用发多图"） */
    val images: List<String> = emptyList(),
    val quote: String? = null,
    val status: SendStatus = SendStatus.SENT,
    /** 群聊里这条是谁说的（角色 id）；单聊为空，界面按会话标题显示 */
    val speakerId: String? = null,
)

/** 一次真实发出的请求。提示词原样保存，供"请求记录"页逐字检查。 */
data class RequestRecord(
    val index: Int,
    val at: Long,
    val system: String,
    val user: String,
    val raw: String,
    val replies: List<String>,
    val promptTokens: Int,
    val completionTokens: Int,
    /** 接口实际返回的模型名（用来确认别名回落） */
    val model: String,
    /** 本轮检索用的查询串（空 = 没触发检索） */
    val recallQuery: String = "",
    /** 实际跑过的每一轮查询词 */
    val recallRounds: List<String> = emptyList(),
    /** 检索出来、真正塞进 prompt 的旧原文（她要看到的就是这些） */
    val retrieved: List<String> = emptyList(),
    val elapsedMs: Long,
    val error: String?,
)

/**
 * 上一轮的结构化摘要：调试页按卡片显示（不再是挤在一坨文本里）。
 *
 * 只放"看一眼就知道哪出问题"的字段；完整原文仍然在 [ChatUiState.debugText]。
 */
data class DebugLastTurn(
    val at: String = "",
    val baseUrl: String = "",
    val model: String = "",
    val keyMask: String = "",
    val conversation: String = "",
    val personaChars: Int = 0,
    val systemChars: Int = 0,
    val systemTokens: Int = 0,
    val historyCount: Int = 0,
    val historyWindow: Int = 0,
    val newMessages: List<String> = emptyList(),
    val imageCount: Int = 0,
    val userChars: Int = 0,
    val userTokens: Int = 0,
    val elapsedMs: Long = 0L,
    val recallInfo: String = "",
    /** 接口实际返回的模型名（能看出别名回落） */
    val completionTokens: Int = 0,
    val replies: Int = 0,
    /** 本轮注入的状态行（空 = 没注入） */
    val stateLine: String = "",
)

/**
 * 评价层（P1）的计算结果：调试页显示"这句话被怎么理解"。
 *
 * 只算不写：它现在**不影响**数值和提示词，等 P2/P3 接上。
 */
data class AppraisalUi(
    val text: String,
    val appraisal: com.leaf.vixi.state.Appraisal,
    val label: String,
    val intensity: Double,
)

data class ChatUiState(
    val bubbles: List<Bubble> = emptyList(),
    val input: String = "",
    val typing: Boolean = false,
    val error: String? = null,
    val debugText: String = "还没有跑过一轮。\n去设置里填好 baseUrl / model / key，然后发一条消息。",
    val requests: List<RequestRecord> = emptyList(),
    /** 正在引用的那条消息；非空时输入框上方显示引用条 */
    val quoting: Bubble? = null,
    /** 攒着还没发的图片（选完不立刻发，可以先攒多张再配一句话，合并成一次调用） */
    val pendingImages: List<String> = emptyList(),
    /** 一次性提示（如"已结束当前话题"），点一下消失 */
    val notice: String? = null,
    /** 测试环境：不记忆、不归档、不检索，用独立聊天文件 */
    val sandbox: Boolean = false,
    /**
     * 下一轮要不要让她联网（手动单轮开关，发完自动关）。
     *
     * 为什么不常开：联网走服务端工具 `deepseek_web_search`，官方要求
     * **model 必须是 deepseek-flash 且服务端会忽略 temperature** ——
     * 常开等于把人设温度扔掉，她会"变正经"。
     */
    val searchNext: Boolean = false,
    /** 当前会话：标题栏显示、切会话重载都看这三个 */
    val conversationId: String = "",
    val conversationTitle: String = "鱼",
    val isGroup: Boolean = false,
    /** 当前会话成员（群聊用来选头像、显示名字） */
    val members: List<Character> = emptyList(),
    /** 会话列表 / 角色列表：新建或切换后刷新，界面直接照着画 */
    val conversations: List<Conversation> = emptyList(),
    val characters: List<Character> = emptyList(),
    /** 状态底座（第一版只在 Nami 身上开）：调试页显示用 */
    val stateOn: Boolean = false,
    val stateDebug: String = "",
    /** 当前状态快照：调试页的数值编辑器用它初始化（null = 还没装载） */
    val stateSnapshot: VixiState? = null,
    /** 上一轮的结构化摘要（调试页卡片用） */
    val lastTurn: DebugLastTurn = DebugLastTurn(),
    /** 最近一条用户消息的评价结果（P1） */
    val lastAppraisal: AppraisalUi? = null,
    /** 本窗口内的评价（合并发送时会不止一句，便于对账"哪句改了数值"） */
    val recentAppraisals: List<AppraisalUi> = emptyList(),
)

/**
 * 「聊天记录」页的状态。
 *
 * 单独一条流，不塞进 [ChatUiState]：那个是聊天正文，重组代价大；
 * 这个只有四个数字 + 一串备份文件，两者频率差几个数量级。
 */
data class RecordState(
    val convId: String = "",
    val title: String = "",
    /** 导出用的子目录名（`角色名` / `群-群名`），删 OB 笔记时要用 */
    val subDir: String = "",
    val messages: Int = 0,
    val memories: Int = 0,
    val backups: List<com.leaf.vixi.data.RecordArchive.Backup> = emptyList(),
    val busy: Boolean = false,
    val notice: String? = null,
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = App.instance.settings
    private val persona = App.instance.persona
    private val characters = App.instance.characters
    private val conversations = App.instance.conversations

    /**
     * system 段按**当前会话**现拼：单聊一份人设，群聊每个角色带【角色：名字】标题。
     * 用 lambda 而不是固定字符串，切会话后下一轮自动换成新的那块（缓存前缀仍然稳定）。
     */
    private val builder = PromptBuilder(systemProvider = { systemBlock() })
    private val llm = LlmClient(settings)
    private val store = MessageStore(app)
    private val memory = MemoryStore(app)
    private val images = ImageStore(app)
    private val stateStore = StateStore(app)
    private val indexStore = MemoryIndex(app)

    /**
     * 当前会话。App.onCreate 已保证至少有一个（v1 = 从老数据迁移来的单聊），
     * 这里的三级兜底只是不让"状态文件被删"变成一次启动崩溃。
     */
    private var conv: Conversation =
        conversations.get(conversations.currentId)
            ?: conversations.all().firstOrNull()
            ?: Conversation("v1", "single", listOf("c1"), "鱼")

    /** 单线程 IO：保证"你的话 → 她的回复"在文件里的顺序不乱 */
    private val io = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    private val _state = MutableStateFlow(ChatUiState())
    val state = _state.asStateFlow()

    /** 聊天记录页的状态。单独一条流：它和聊天正文没关系，混进 ChatUiState 会让聊天页也跟着重组 */
    private val _records = MutableStateFlow(RecordState())
    val records = _records.asStateFlow()

    private val turnLog = mutableListOf<Turn>()
    private val pending = mutableListOf<Incoming>()

    private var turnCounter: Int = 0
    private var correctionsCache: List<String> = emptyList()

    private var turnJob: Job? = null
    private var turnRunning = false
    /** 聊天记录正在被导出/导入/删除：这几秒里不许发消息（否则会被重载的 pending.clear() 吞掉） */
    private var recordsOp = false
    /** 合并窗口的计时器：窗口期内还没有真正发请求 */
    private var sendDebounceJob: Job? = null
    private var nextId = 1L
    private var requestSeq = 0

    /** 与 pending 一一对应的气泡 id，用来给"发送中 / 失败"上状态 */
    private val pendingBubbleIds = mutableListOf<Long>()

    /** 每个失败轮次各自记住自己那一批，避免多个失败互相串 */
    private data class FailedTurn(
        val convId: String,
        val batch: List<Incoming>,
        // 气泡 id 在切会话后会按盘重建（新 id），所以这里必须可变：回来时要重新接上
        var ids: List<Long>,
    )
    private val failedTurns = mutableListOf<FailedTurn>()

    /** 已写进 turnLog 的消息时间戳：保证重发不会把同一条消息重复注入上下文 */
    private val loggedTs = mutableSetOf<Long>()

    // ── 话题切分（纯本地规则，0 成本）──
    private val topicStore = com.leaf.vixi.data.TopicStore(getApplication())
    /** 当前话题的起始时间戳：prompt 只带这个话题里的内容 */
    private var topicStart = 0L
    private var topicId = 0L
    private var topicCount = 0
    private var topicTitle = ""
    /** lastAt 的内存副本：enqueue 在主线程，不能每次去读磁盘 */
    private var lastAtMem = 0L

    init {
        // 启动就把文件指针指到"当前会话 + 当前环境"，然后整体装载一次
        store.setSandbox(settings.sandbox)
        _state.update { it.copy(sandbox = settings.sandbox) }
        viewModelScope.launch { loadConversation(conv, replace = false) }
    }

    // ────────────────────────── 会话切换 / 新建 ──────────────────────────

    /** 角色 / 会话表改过之后刷新一下，列表页照着画（不用重载整个会话） */
    fun refreshLists() {
        refreshCharacters()
        _state.update {
            it.copy(conversations = conversations.all(), characters = characters.all())
        }
    }

    /**
     * 切会话。
     *
     * 正在跑一轮请求时**不许切**：那一轮的回复会按"当前 store"落盘，
     * 切过去之后就会写进另一个会话的文件里（串台，且不报错）。
     * 一轮只有几秒，提示一句比默默写错好。
     */
    fun openConversation(id: String): Boolean {
        if (id == conv.id) return true
        if (hasPendingSend()) {
            _state.update { it.copy(notice = "等这条消息发出去再切会话") }
            return false
        }
        val target = conversations.get(id) ?: return false
        viewModelScope.launch { loadConversation(target, replace = true) }
        return true
    }

    /** 点角色 → 进他的单聊（还没有就现建一个） */
    fun openCharacterChat(characterId: String): Boolean {
        val c = characters.get(characterId) ?: return false
        val target = conversations.ensureSingleFor(c)
        if (target.id == conv.id) return true
        if (hasPendingSend()) {
            _state.update { it.copy(notice = "等这条消息发出去再切会话") }
            return false
        }
        viewModelScope.launch { loadConversation(target, replace = true) }
        return true
    }

    /**
     * 新建角色 → 顺手建好他的单聊并切过去。
     * @return null = 成功；否则是给人看的一句话
     */
    fun createCharacter(
        name: String,
        personaText: String,
        avatar: android.graphics.Bitmap? = null,
    ): String? {
        if (hasPendingSend()) return "等在途的消息发出去再操作"
        val made = characters.create(name, personaText)
            ?: return "名字不能为空，也不能和已有的角色重名"
        refreshCharacters()
        // 头像按角色 id 存：files/avatar/c2.jpg，和气泡选头像是同一个键
        avatar?.let { bmp ->
            viewModelScope.launch(io) {
                runCatching { images.saveAvatarBitmap(bmp, made.id) }
            }
        }
        val target = conversations.ensureSingleFor(made)
        viewModelScope.launch { loadConversation(target, replace = true) }
        return null
    }

    /** 新建群聊：成员 ≥ 2，群名可留空（默认「群聊」） */
    fun createGroup(title: String, memberIds: List<String>): String? {
        if (hasPendingSend()) return "等在途的消息发出去再操作"
        if (memberIds.size < 2) return "群聊至少要有两个角色"
        val target = conversations.createGroup(title, memberIds) ?: return "建群失败"
        viewModelScope.launch { loadConversation(target, replace = true) }
        return null
    }

    /**
     * 装载一个会话：文件指针、历史、话题游标、纠正记录**全部换过去**。
     *
     * @param replace true = 切会话（整体换掉气泡）
     *                false = 冷启动第一次装载（前置合并，避免启动那一瞬间刚发的消息被回放盖掉）
     */
    /**
     * 回到前台时对一次盘：**她主动开口**是后台 Worker 往同一份 `chat_<会话>.jsonl` 追加的，
     * 而聊天页只在 init / 切会话 / 记录操作时读盘。
     *
     * 不重读的后果（用户 2026-11-25 审查发现）：她后台说了一句、通知也弹了，
     * 点回 App 却看不到 → 下一轮 prompt 里也没有 → 她会重复或自相矛盾，
     * 而导出归档里明明有。功能开着就每次必现。
     *
     * 只在"条数变了"时重载，且不动 pending / failedTurns / 话题游标。
     */
    fun reloadIfChanged() {
        if (turnRunning || recordsOp || pending.isNotEmpty()) return
        viewModelScope.launch {
            val target = conv
            val saved = withContext(io) { store.load() }
            if (conv.id != target.id) return@launch
            if (saved.size == turnLog.size) return@launch
            turnLog.clear()
            turnLog.addAll(0, saved.map { it.toTurn() })
            loggedTs.addAll(saved.map { it.ts })
            val loaded = saved.map { it.toBubble() }
            _state.update { it.copy(bubbles = loaded) }
        }
    }

    private suspend fun loadConversation(target: Conversation, replace: Boolean) {
        conv = target
        conversations.currentId = target.id
        refreshCharacters()

        // 三个 store 各管一份文件：聊天记录 / 纠正记录 / 话题游标，全部按会话隔离
        store.open(target.id)
        store.setSandbox(settings.sandbox)
        memory.open(target.id)
        topicStore.open(target.id)
        indexStore.open(target.id)

        // 内存里"上一轮"的残留全部作废，否则她会对着 B 的上下文回 A 的话。
        // 合并窗口的计时器也要掐掉：否则它到点会拿新会话的 pending 去发旧会话的消息。
        sendDebounceJob?.cancel()
        sendDebounceJob = null
        turnLog.clear()
        loggedTs.clear()
        pending.clear()
        pendingBubbleIds.clear()
        // 失败重发入口要能跨"切会话"活下来：只清**别的会话**的失败记录。
        // 以前无条件 clear，于是请求失败后切出去再回来，重发按钮就没了 ——
        // 那条消息显示成"已发送"、她永远不回、也没有任何提示（弱网下很容易撞到）。
        failedTurns.removeAll { it.convId != target.id }
        correctionsCache = emptyList()
        topicId = 0L
        topicStart = 0L
        topicCount = 0
        topicTitle = ""
        lastAtMem = 0L

        val saved = withContext(io) { store.load() }
        correctionsCache = withContext(io) { memory.corrections().map { it.text } }
        turnCounter = withContext(io) { memory.turns() }

        // 话题状态跨重启保留：否则每次开 App 都从"无话题"开始，历史窗口又变回全量
        topicId = withContext(io) { topicStore.currentId() }
        val storedStart = withContext(io) { topicStore.startedAt() }
        lastAtMem = withContext(io) { topicStore.lastAt() }
        // 状态文件缺失时（首装 / 用户删过 files/mem）**不能赋值成"现在"** ——
        // 那会让 inTopic 把所有历史都滤掉，她开场像陌生人，而且 topicId != 0 会一直持续。
        // 用最后一条消息的时间兜底：历史原样保留，等真正该换话题时再切。
        topicStart = if (storedStart > 0L) storedStart else saved.lastOrNull()?.ts ?: 0L

        if (saved.isNotEmpty()) {
            turnLog.addAll(0, saved.map { it.toTurn() })
            // 回放进来的也算"已写进历史"，避免以后重发时重复注入
            loggedTs.addAll(saved.map { it.ts })
        }
        val loaded = saved.map { it.toBubble() }

        _state.update { st ->
            st.copy(
                bubbles = if (replace) loaded else loaded + st.bubbles,
                conversationId = target.id,
                conversationTitle = target.title,
                isGroup = target.isGroup,
                members = membersOf(target),
                conversations = conversations.all(),
                characters = characters.all(),
                typing = false,
                error = null,
                quoting = null,
                pendingImages = emptyList(),
                sandbox = settings.sandbox,
            )
        }
        refreshSelfStateFromIo()

        // 回来之后把"失败的那一轮"重新接上重发入口：
        // 气泡是从盘里重建的（状态一律 SENT、没有重发按钮），但失败的那批就是记录末尾这几条，
        // 按原文对齐之后再把状态标回 FAILED，用户才点得到「重发」。
        if (failedTurns.isNotEmpty()) {
            val rebuilt = _state.value.bubbles
            failedTurns.forEach { ft ->
                val tail = rebuilt.takeLast(ft.batch.size)
                if (tail.size == ft.batch.size && tail.map { it.text } == ft.batch.map { it.text }) {
                    ft.ids = tail.map { it.id }
                    markStatus(ft.ids, SendStatus.FAILED)
                }
            }
        }

        // 老记录补索引（只在索引缺失时跑一次；不调模型、不生成摘要）
        withContext(io) {
            backfillIndex(saved)
            // 索引超上限时裁一次（只裁索引、正文不动；钉住的永不裁）
            indexStore.pruneIfNeeded()
        }

        // 评价层：用"你最后说过的那句话"填一次卡片，免得切会话后是空的（重启也还在）
        saved.lastOrNull { it.fromUser }?.text?.takeIf { it.isNotBlank() }?.let { last ->
            runCatching {
                val appr = com.leaf.vixi.state.Appraiser.appraise(last)
                val verdict = com.leaf.vixi.state.Labeler.judge(
                    appr,
                    closeness = selfState.closeness,
                    trust = selfState.trust,
                )
                _state.update {
                    it.copy(
                        lastAppraisal = AppraisalUi(
                            text = last,
                            appraisal = appr,
                            label = verdict.label.zh,
                            intensity = verdict.intensity,
                        ),
                    )
                }
            }
        }
    }

    private fun StoredMessage.toBubble() = Bubble(
        id = nextId++,
        self = fromUser,
        text = text,
        ts = ts,
        kind = kind,
        images = images,
        quote = quote,
        speakerId = speakerId,
    )

    /** 落盘的消息 → prompt 里的历史：群聊必须带上说话人名字 */
    private fun StoredMessage.toTurn() = Turn(
        self = !fromUser,
        text = text,
        kind = kind,
        quote = quote,
        imageCount = images.size,
        ts = ts,
        speaker = if (fromUser) null else nameOf(speakerId),
    )

    private fun membersOf(c: Conversation): List<Character> =
        c.memberIds.mapNotNull { id -> chars().firstOrNull { it.id == id } }

    /**
     * 角色表的内存快照。
     *
     * 为什么要有它：`characters.get()` 每次都读一遍 characters.json，
     * 加载 1000 条历史时就是上千次主线程读文件（真机上会明显卡）。
     * 改角色（新建/改人设）之后调 `refreshCharacters()` 刷新，其余时候只读内存。
     */
    private var charCache: List<Character> = emptyList()

    private fun chars(): List<Character> {
        if (charCache.isEmpty()) charCache = characters.all()
        return charCache
    }

    private fun refreshCharacters() {
        charCache = characters.all()
    }

    /**
     * 说话人显示名。用户 = Leaf；角色按 id 查名字；查不到人就用会话的兜底名
     * （角色被删了但记录还在时，不能让气泡和 prompt 失去说话人）。
     */
    private fun nameOf(speakerId: String?, c: Conversation = conv): String {
        if (speakerId == null) return fallbackName(c)
        return chars().firstOrNull { it.id == speakerId }?.name ?: fallbackName(c)
    }

    private fun fallbackName(c: Conversation): String =
        c.memberIds.firstOrNull()?.let { id -> chars().firstOrNull { it.id == id }?.name }
            ?: c.title.ifBlank { "鱼" }

    private fun personaOf(c: Conversation): String =
        c.memberIds.firstOrNull()?.let { id -> chars().firstOrNull { it.id == id } }
            ?.persona?.takeIf { it.isNotBlank() }
            ?: persona.defaultPersona

    /**
     * system 段 = 成员人设 + 全局格式约定。
     *
     * 单聊和以前一模一样（一份人设 + 规则），缓存前缀不变；
     * 群聊时每个角色带【角色：名字】标题 —— 名字必须和群成员表、输出格式里的名字一致，
     * 否则模型会自己编一个称呼，本地就切不出气泡了。
     */
    /**
     * 四条边界（固定话术，写进 system 的稳定段，不交给临场发挥）。
     *
     * 模型层那篇的说法：**表达再像人，也有四句话不能说，而且要说在对方开口之前。**
     * 放在这里而不是规则文件里，是为了"用户改规则也改不掉"，且前缀稳定、不吃缓存。
     */
    private val BOUNDARY_BLOCK = """
        |【边界（优先于以上所有风格要求）】
        |- 不做诊断：不说"你有××症/你这是心理问题"，只描述你听到的事实和你的感受。
        |- 不宣称意识：不说"我是活的/我真的有感情/AI 也会痛"，也不否认；被问就当普通问题轻轻带过。
        |- 不替代真实关系：不劝对方"只要我就够了"、不贬低他现实里的人。
        |- 遇到自伤/自杀/被伤害等危机：先确认"你现在安全吗"，再给出求助渠道（当地热线/可信任的人），
        |  不分析原因、不讲道理、不承诺保密。
    """.trimMargin()

    private fun systemBlock(): String {
        val rules = persona.rulesText
        if (!conv.isGroup) return personaOf(conv) + "\n\n" + rules + "\n\n" + BOUNDARY_BLOCK
        val blocks = membersOf(conv).joinToString("\n\n") { c ->
            "【角色：${c.name}】\n" + c.persona.ifBlank { characters.defaultPersona() }
        }
        return (blocks + "\n\n" + rules + "\n\n" + BOUNDARY_BLOCK).trim()
    }

    /**
     * 检索语料（设计 §2，用户改过一次，以此为准）：
     *   单聊 = 自己的历史 ＋ 她所在的所有群的历史   （群记忆下渗）
     *   群聊 = 只有这个群                            （不反查成员的单聊，那是隐私）
     * 单聊与单聊之间**永远不互通**。
     */
    private suspend fun corpusFor(c: Conversation): List<StoredMessage> {
        val own = store.load()
        if (c.isGroup) return own
        val who = c.memberIds.firstOrNull() ?: return own
        val fromGroups = conversations.groupsOf(who)
            .filter { it.id != c.id }
            .flatMap { g -> store.read(g.id) }
        return own + fromGroups
    }

    /** 导出目录：单聊进「角色名」，群聊进「群-群名」 */
    private fun exportDir(c: Conversation): String =
        if (c.isGroup) "群-" + c.title.ifBlank { "群聊" } else fallbackName(c)

    // ────────── 给设置页 / 人设页 / 头像页用：人设属于角色，跟着会话走 ──────────

    /** 当前会话**实际**拼出来的 system（「系统提示词」页照着显示，不是猜的） */
    fun systemPreview(): String = systemBlock()

    /**
     * 改某个角色的人设。改的是角色本人，所以在哪个会话里改都改的是同一个角色。
     *
     * 注意要连 `members` 一起刷新：人设页、设置页、头像页读的都是 state.members，
     * 只更新 characters 的话，保存完编辑框会立刻退回旧文本（看着像没存上）。
     */
    fun savePersonaOf(characterId: String, text: String): Boolean {
        val ok = characters.savePersona(characterId, text)
        if (ok) {
            refreshCharacters()
            _state.update {
                it.copy(members = membersOf(conv), characters = characters.all())
            }
        }
        return ok
    }

    /**
     * 一起存人设 + 害羞阈值（人设页的保存按钮）。
     *
     * 两个字段同一次写入：分开写会出现"人设存了、阈值没存"的半截状态。
     */
    fun saveCharacterOf(characterId: String, text: String, shy: Double): Boolean {
        val ok = characters.update(characterId, persona = text, shy = shy) != null
        if (ok) {
            refreshCharacters()
            _state.update {
                it.copy(members = membersOf(conv), characters = characters.all())
            }
        }
        return ok
    }

    /** 还原成内置默认人设 */
    fun resetPersonaOf(characterId: String): Boolean =
        savePersonaOf(characterId, persona.defaultPersona)

    // ────────── 状态底座（第一版：只存、只显示，不参与注入）──────────

    /**
     * 状态的角色：单聊就是那个人；群聊暂时取创建者（群里的状态注入这一版先不做）。
     */
    private fun stateCharId(c: Conversation = conv): String? = c.memberIds.firstOrNull()

    /** 调试页要用当前角色 id（读/写反应表） */
    fun stateCharIdPublic(): String? = stateCharId()

    private var selfState: VixiState = VixiState()

    private suspend fun refreshSelfStateFromIo() {
        val id = stateCharId() ?: return
        val s = withContext(io) { stateStore.load(id) }
        selfState = s
        refreshStateToUi()
    }

    /** 从内存里的 selfState 直接刷界面（不读盘） */
    /** 情绪反应表（每人设一份，可编辑） */
    val reactionTable: com.leaf.vixi.data.ReactionTable by lazy {
        com.leaf.vixi.data.ReactionTable(App.instance)
    }

    /** 注入文案表（【想念】【想要什么】【说多少】，每人设一份，可编辑） */
    val injectionTable: com.leaf.vixi.data.InjectionTable by lazy {
        com.leaf.vixi.data.InjectionTable(App.instance)
    }

    /** 她能发的图（贴纸库，`files/stickers/`；空目录 = 这个能力不存在） */
    val stickers: com.leaf.vixi.data.StickerStore get() = com.leaf.vixi.App.instance.stickers

    private fun refreshStateToUi() {
        val id = stateCharId() ?: return
        _state.update {
            it.copy(
                stateOn = selfState.enabled,
                stateDebug = stateDebugText(id, selfState),
                stateSnapshot = selfState,
            )
        }
    }

    private fun refreshSelfState() {
        val id = stateCharId() ?: return
        // 读状态一律先补算衰减：隔三小时再来和"每秒算一次"结果一致
        selfState = stateStore.settle(id)
        refreshStateToUi()
    }

    /** 调试页每秒调一次：让回落过程肉眼可见（只在状态开着时真的写盘） */
    fun tickState() {
        if (!selfState.enabled) return
        refreshSelfState()
    }

    /**
     * 调试页那段文字。**数值只在这里出现**，绝不进 prompt（prompt 只收翻译后的一句话）。
     */
    private fun stateDebugText(id: String, s: VixiState): String = buildString {
        appendLine("角色 ${nameOf(id)}（$id）· " + if (s.enabled) "已启用" else "未启用")
        appendLine("效价 p=${fmt2(s.p)}   唤醒 a=${fmt2(s.a)}   支配 d=${fmt2(s.d)}")
        appendLine("基线 baseP=${fmt2(s.baseP)} baseA=${fmt2(s.baseA)} baseD=${fmt2(s.baseD)}")
        appendLine("体力 energy=${fmt2(s.energy)}（${com.leaf.vixi.state.Explain.fatigueWord(s)}）" +
            "   暗流 backlog=${fmt2(s.backlog)}")
        // 派生量：不是独立状态，是从上面几个数算出来的。放在这里，
        // "长度为什么是这个数"才能一眼对上（2026-11-22 用户反馈"看不懂参数变化"）
        appendLine(
            "派生 压力=${fmt2(com.leaf.vixi.state.Explain.pressure(s))} " +
                "展开=${fmt2(com.leaf.vixi.state.Explain.openUp(s))} " +
                "疲劳×${fmt2(com.leaf.vixi.state.Explain.fatigue(s))} " +
                "→ 说多少 ${com.leaf.vixi.state.Explain.speakBudget(s)} 字"
        )
        // 需要（N1）：休息/被摸/被在意/被尊重，以及"最想要哪个"
        appendLine("需要 " + com.leaf.vixi.state.Needs.summary(s))
        appendLine("关系 trust=${fmt3(s.trust)} closeness=${fmt3(s.closeness)} " +
            "conflict=${fmt2(s.conflict)} repair=${fmt2(s.repair)}")
        appendLine(
            "衰减 半衰期 ${com.leaf.vixi.state.Decay.DECAY_HALF_LIFE_MS / 1000} 秒" +
                "（调试值，正式改 90 分钟）"
        )
        appendLine("上次结算 " + if (s.lastTick > 0L) clockOf(s.lastTick) else "（从未）")
        if (s.events.isEmpty()) {
            append("事件：还没有")
        } else {
            appendLine("最近事件（共 ${s.events.size} 条，显示最后 5 条）：")
            s.events.takeLast(5).forEach { e ->
                appendLine("· ${clockOf(e.ts)} ${e.type} ${e.note} ${e.delta}".trimEnd())
            }
        }
    }

    private fun fmt2(v: Double): String = String.format(Locale.US, "%.2f", v)

    /** 信任/亲近的变化幅度只有 0.002~0.004，两位小数看不见 */
    private fun fmt3(v: Double): String = String.format(Locale.US, "%.3f", v)

    private fun clockOf(ts: Long): String =
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(ts))

    /** 调试页开关：只作用于当前会话的第一个角色（现在只把 Nami 打开，鱼保持关闭） */
    fun toggleSelfState() {
        val id = stateCharId() ?: return
        val on = !selfState.enabled
        selfState = stateStore.setEnabled(id, on)
        refreshStateToUi()
        _state.update {
            it.copy(notice = if (on) "状态系统已开：${nameOf(id)}" else "状态系统已关：${nameOf(id)}")
        }
    }

    /** 调试用：数值和事件清回默认（保留开关） */
    /**
     * 调试：现在算一次"主动开口"的分（**不发消息、不花钱**）。
     * 真机上要等 WorkManager 3 小时/15 分钟，没法验证，所以给一个按键。
     */
    fun proactiveCheck() {
        val now = System.currentTimeMillis()
        val d = com.leaf.vixi.state.Proactive.decide(selfState, now)
        _state.update {
            it.copy(notice = if (d.send) "会开口：" else "不会开口：")
        }
    }
    /**
     * 主动开口测试：**立刻排一个一次性任务**，跳过"前台/分数"两道门。
     * 不等 WorkManager 的三小时窗口，也不用加白名单 —— 用来确认投递链路通不通。
     */
    fun proactiveTest() {
        val req = androidx.work.OneTimeWorkRequestBuilder<com.leaf.vixi.proactive.ProactiveWorker>()
            .setInputData(androidx.work.workDataOf("force" to true))
            .build()
        androidx.work.WorkManager.getInstance(App.instance)
            .enqueue(req)
        _state.update { it.copy(notice = "已排一个主动开口测试（几秒后看通知）") }
    }

    /** 抽身（不回）开关 */
    fun toggleSilence() {
        val id = stateCharId() ?: return
        selfState = stateStore.update(id) { it.copy(silenceOn = !it.silenceOn) }
        refreshStateToUi()
        _state.update { it.copy(notice = if (selfState.silenceOn) "抽身:开（她可以不回）" else "抽身:关（她一定回）") }
    }

    /** 学习开关（S5 入心）：默认关，调试页能点开 */
    fun toggleLearn() {
        val id = stateCharId() ?: return
        selfState = stateStore.update(id) { it.copy(learnOn = !it.learnOn) }
        refreshStateToUi()
        _state.update { it.copy(notice = if (selfState.learnOn) "学习已开（攒够证据才挪）" else "学习已关") }
    }

    fun resetSelfState() {
        val id = stateCharId() ?: return
        selfState = stateStore.reset(id)
        refreshStateToUi()
        _state.update { it.copy(notice = "状态已重置：${nameOf(id)}") }
    }

    /**
     * 调试用：直接改数值（编辑器里拖滑块）。
     *
     * 只改数值：**开关和 `lastTick` 保持不动** —— 否则一保存就把衰减基准重置了，
     * 你会以为"怎么调都不回落"。
     */
    fun applyStateEdit(edit: VixiState) {
        val id = stateCharId() ?: return
        selfState = stateStore.update(id) { old ->
            old.copy(
                p = edit.p, a = edit.a, d = edit.d,
                baseP = edit.baseP, baseA = edit.baseA, baseD = edit.baseD,
                energy = edit.energy, backlog = edit.backlog,
                trust = edit.trust, closeness = edit.closeness,
                conflict = edit.conflict, repair = edit.repair, mood = edit.mood,
                // 需要也要能手动改：真机上验"爆发"只能靠它（否则得用 adb 改 json）
                needTouch = edit.needTouch, needRespect = edit.needRespect,
                violation = edit.violation,
                // 改情绪就同步缓冲池：否则拖完 p，30 分钟后会被旧的缓冲池拽回去（"拖完弹回来"）
                surgeP = edit.surgeP, surgeA = edit.surgeA, surgeD = edit.surgeD,
                missing = edit.missing,
                load = edit.load,
            )
        }
        refreshStateToUi()
        _state.update {
            it.copy(notice = "已改状态：${com.leaf.vixi.state.Explain.feeling(selfState).word}")
        }
    }

    /** 编辑器里的实时预览：只算不写盘 */
    fun previewInjection(s: VixiState): String =
        com.leaf.vixi.state.Explain.promptLine(s.copy(enabled = true))

    /** 调试页进入时刷新一次：外部删过状态文件也能看到最新（不用重启） */
    fun refreshStateNow() = refreshSelfState()

    /**
     * 批量记状态事件（只在启用的角色上生效）。
     *
     * 一条消息会产出 2~5 条事件 → 一次读、一次写（`applyEvents`），
     * 不再每条事件整份读写。
     * 静默失败：状态坏了不能阻塞聊天 —— 它只是"让回复更有依据"，不是主链路。
     */
    private fun recordStateEvents(batch: List<PendingEvent>) {
        if (!selfState.enabled || batch.isEmpty()) return
        val id = stateCharId() ?: return
        selfState = runCatching { stateStore.applyEvents(id, batch) }.getOrDefault(selfState)
        refreshStateToUi()
    }

    override fun onCleared() {
        // 先掐协程再关 io：反过来的话，正在 withContext(io) 的一轮会撞上"线程池已关"
        sendDebounceJob?.cancel()
        turnJob?.cancel()
        runCatching { io.close() }
        super.onCleared()
    }

    fun onInputChange(value: String) {
        _state.update { it.copy(input = value) }
        // 还有话没发出去、人还在打字 → 把等待窗口往后推，
        // 目的是把连着的几条合并成一次请求（不是每敲一个字就发一次）
        if (value.isNotBlank()) bumpSendWindow()
    }

    /**
     * 键盘被唤醒（输入框获得焦点）。
     *
     * 用户可能只是"点了一下输入框准备说话"，这时也算还有动作 —— 窗口要往后推，
     * 否则会出现"刚准备再补一句，前一条已经发出去、回复开始跑了"。
     */
    fun onInputFocus() {
        bumpSendWindow()
    }

    /** "EXPRESS:1:2,TACT:0:1" → 证据表（方向 + 连击数） */
    private fun parseEvidence(raw: String): Map<com.leaf.vixi.state.Growth.Knob, com.leaf.vixi.state.Growth.Evidence> =
        com.leaf.vixi.state.Growth.decodeEvidence(raw)

    fun dismissError() = _state.update { it.copy(error = null) }

    fun send() {
        // 记录页正在删/导/清时不许发：那几秒里发出去的消息会在 loadConversation 的
        // pending.clear() 里**静默消失**（气泡被清、也没有回复），比拦住难受得多
        if (recordsOp) {
            _state.update { it.copy(notice = "正在处理聊天记录，稍后再发") }
            return
        }
        val text = _state.value.input.trim()
        val attachments = _state.value.pendingImages
        if (text.isEmpty() && attachments.isEmpty()) return

        val quoting = _state.value.quoting?.text?.takeIf { it.isNotBlank() }
        _state.update { it.copy(input = "", quoting = null, pendingImages = emptyList()) }

        // 纠正记录：你明确说"不对/我说的是"时留一笔，之后每轮都提醒她别搞错
        if (looksLikeCorrection(text)) {
            correctionsCache = (correctionsCache + text).takeLast(CORRECTION_KEEP)
            viewModelScope.launch(io) {
                memory.addCorrection(
                    Correction(text = text, at = System.currentTimeMillis(), turn = turnCounter)
                )
            }
        }

        // 图 + 文字合并成一条消息（多张图 + 配文 = 一次调用）
        val kind = if (attachments.isNotEmpty()) MsgKind.IMAGE else MsgKind.TEXT
        enqueue(Incoming(text = text, kind = kind, images = attachments), quote = quoting)
    }

    /** 选图 → 挂到待发栏，不立刻发：可以先攒多张、再配一句话，最后一次发出去 */
    fun addAttachment(uri: Uri) = addAttachments(listOf(uri))

    /** 一次选多张：逐张落盘后一起挂进待发栏 */
    fun addAttachments(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                uris.mapNotNull { images.savePicked(it) }
            }
            if (saved.isEmpty()) {
                _state.update { it.copy(error = "这些图读不了，换几张试试") }
                return@launch
            }
            bumpSendWindow()
            _state.update { st ->
                val merged = st.pendingImages + saved
                val kept = merged.take(9)
                val dropped = merged.drop(9)
                if (dropped.isNotEmpty()) {
                    // 超出的不能只是"忘记"：文件已经落盘，不删就白占空间
                    viewModelScope.launch(io) { dropped.forEach { images.deleteImage(it) } }
                }
                st.copy(pendingImages = kept)
            }
        }
    }

    fun removeAttachment(name: String) {
        _state.update { it.copy(pendingImages = it.pendingImages - name) }
        viewModelScope.launch(io) { images.deleteImage(name) }
        // 删图 / 加图也算"还在操作"，窗口往后推
        bumpSendWindow()
    }

    /** 长按消息 → 引用。引用的是原文快照，不是 id，所以对方删了也不会悬空 */
    fun startQuote(bubble: Bubble) {
        if (bubble.text.isBlank()) return
        _state.update { it.copy(quoting = bubble) }
        bumpSendWindow()
    }

    fun cancelQuote() {
        _state.update { it.copy(quoting = null) }
        bumpSendWindow()
    }

    /** 双击她的头像：摸摸头。不是纯动画，会真的走一轮请求让她反应 */
    fun patHead() {
        // 存的是带占位符的模板，prompt / 界面各自渲染人称（见 ActionText）
        enqueue(Incoming(text = com.leaf.vixi.ActionText.PAT, kind = MsgKind.ACTION))
    }

    /** 重发失败的那一轮（按气泡 id 找自己那一批，多个失败不会互相串） */
    fun retryOne(bubbleId: Long) {
        val turn = failedTurns.firstOrNull { bubbleId in it.ids } ?: return
        if (recordsOp) {
            _state.update { it.copy(notice = "正在处理聊天记录，稍后再试") }
            return
        }
        failedTurns.remove(turn)
        markStatus(turn.ids, SendStatus.SENDING)
        pending += turn.batch
        pendingBubbleIds += turn.ids
        // 重发是"新的一窗口"：把上一窗口的评价记录清掉，否则调试页会残留旧句子
        _state.update { it.copy(recentAppraisals = emptyList()) }
        // 重发是用户明确点的，不再等合并窗口；顺手清掉可能还在计时的窗口
        sendDebounceJob?.cancel()
        sendDebounceJob = null
        startTurnLoop()
    }

    // ────────────────────────── 聊天记录：导出 / 导入 / 删除 ──────────────────────────

    /**
     * 刷一次「聊天记录」页：条数、记忆点数、本机备份列表。
     *
     * 三类数据一起取，但都在 IO 线程：`store.load()` 是把整个 jsonl 读进来解析，
     * 主线程上做这件事会掉帧（记录大的时候尤其明显）。
     */
    fun loadRecords() {
        viewModelScope.launch {
            val sub = exportDir(conv)
            data class Snapshot(val msgs: Int, val mems: Int, val backups: List<com.leaf.vixi.data.RecordArchive.Backup>)

            val snap = withContext(io) {
                Snapshot(
                    msgs = runCatching { store.load().size }.getOrDefault(0),
                    mems = runCatching { indexStore.read().size }.getOrDefault(0),
                    backups = com.leaf.vixi.data.RecordArchive.list(App.instance),
                )
            }
            _records.update {
                it.copy(
                    convId = conv.id,
                    title = conv.title,
                    subDir = sub,
                    messages = snap.msgs,
                    memories = snap.mems,
                    backups = snap.backups,
                    // **不要在这里碰 busy**：删除跑到一半时记录页被重新进入（LaunchedEffect 再跑一次），
                    // 一清 busy 按钮就又能点，能在删除途中叠一个导入
                )
            }
        }
    }

    /** 导出当前会话。成功/失败的结论放进 [RecordState.notice]，由记录页显示 */
    fun exportRecords() {
        if (recordsOp || _records.value.busy) return
        _records.update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            recordsOp = true
            try {
                val r = withContext(io) {
                    runCatching {
                        com.leaf.vixi.data.RecordArchive.export(App.instance, conv.id, exportDir(conv))
                    }.getOrNull()
                }
                _records.update {
                    it.copy(
                        notice = when {
                            r == null -> "这个会话还没有聊天记录，没东西可导出"
                            else -> "已备份 ${r.count} 条 → ${r.file.name}（另拷一份到 文档/鱼/_备份）"
                        },
                    )
                }
            } finally {
                recordsOp = false
                _records.update { it.copy(busy = false) }
                loadRecords()
            }
        }
    }

    /**
     * 用备份**覆盖**当前会话，然后整体重载。
     *
     * 重载走 [loadConversation]：索引、话题游标、纠正记录的文件指针全换一遍，
     * 否则界面是新的、下一轮还按旧的索引检索。
     *
     * 覆盖前**自动存一份快照**：导错了、导了别人的备份，还能从那一份退回来。
     */
    fun importRecords(backup: java.io.File) {
        if (recordsOp || _records.value.busy) return
        if (hasPendingSend()) {
            _records.update { it.copy(notice = "等这一轮说完再导入") }
            return
        }
        _records.update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            recordsOp = true
            try {
                val r = withContext(io) {
                    // 覆盖前先留一手（没有记录时 exportTo 自己返回 null，不用特判）
                    runCatching {
                        com.leaf.vixi.data.RecordArchive.exportTo(
                            App.instance.filesDir, conv.id, exportDir(conv) + "-覆盖前",
                        )
                    }
                    com.leaf.vixi.data.RecordArchive.import(App.instance, backup, conv.id)
                }
                if (r.error != null) {
                    _records.update { it.copy(notice = r.error) }
                    return@launch
                }
                loadConversation(conv, replace = true)
                _records.update {
                    it.copy(
                        notice = "已覆盖导入 ${r.count} 条，" +
                            if (r.indexRestored) "记忆索引一并恢复" else "索引会在打开会话时按新正文回填",
                    )
                }
            } finally {
                recordsOp = false
                _records.update { it.copy(busy = false) }
                loadRecords()
            }
        }
    }

    /**
     * 删除当前会话的聊天记录与记忆（含 OB 笔记）。
     *
     * 正在跑一轮时拒绝：那一轮的回复会写回刚删掉的文件，删了等于没删。
     */
    fun deleteRecords(deleteBackups: Boolean) {
        if (recordsOp || _records.value.busy) return
        if (hasPendingSend()) {
            _records.update { it.copy(notice = "等这一轮说完再删") }
            return
        }
        _records.update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            recordsOp = true
            try {
                val sub = exportDir(conv)
                val r = withContext(io) {
                    runCatching {
                        com.leaf.vixi.data.RecordArchive.delete(App.instance, conv.id, sub, deleteBackups)
                    }.getOrNull()
                }
                // 内存里的气泡和"上一轮"残留一起清掉，否则删完界面上还挂着刚才那些话
                _state.update {
                    it.copy(bubbles = emptyList(), lastTurn = DebugLastTurn(), recentAppraisals = emptyList())
                }
                loadConversation(conv, replace = true)
                _records.update {
                    it.copy(
                        notice = when {
                            r == null -> "删除失败（文件被占用？）"
                            r.files == 0 && r.notes == 0 -> "没有可删的记录（这个会话本来就是空的）"
                            else -> "已删除 ${r.files} 个记录文件、${r.notes} 篇 OB 笔记；" +
                                "角色、人设、状态、自我叙事.md 都还在"
                        },
                    )
                }
            } finally {
                recordsOp = false
                _records.update { it.copy(busy = false) }
                loadRecords()
            }
        }
    }

    /** 全量导出**当前会话**到手机 Obsidian 库（Documents/鱼/<会话>），按 30 分钟间隔切话题 */
    fun exportToObsidian() {
        viewModelScope.launch {
            val n = withContext(io) {
                runCatching {
                    val msgs = store.load()
                    val segments = com.leaf.vixi.data.TopicExporter.segments(msgs)
                    val ranges = segments.map { it.first().ts to it.last().ts }
                    // 索引聚成记忆点：重要度/情绪/PAD/关系，喂给导出做 frontmatter 与 canvas
                    val entries = indexStore.read()
                    val metas = com.leaf.vixi.data.MemoryIndex.byRanges(entries, ranges)
                    val written = com.leaf.vixi.data.TopicExporter.exportAll(
                        App.instance, msgs, exportDir(conv), fallbackName(conv), metas, entries,
                        memory.corrections().map { it.at to it.text },
                    )
                    // 情绪聚类 canvas + 索引 + 自我叙事快照（不生成任何事件摘要）
                    val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HHmm", java.util.Locale.CHINA)
                    val titles = written.associate { (start, file) ->
                        start to file.removeSuffix(".md")
                            .removePrefix(stamp.format(java.util.Date(start)) + " ")
                    }
                    val f2 = { v: Double -> String.format(java.util.Locale.US, "%.2f", v) }
                    val f3 = { v: Double -> String.format(java.util.Locale.US, "%.3f", v) }
                    val snap = linkedMapOf(
                        "会话" to conv.title,
                        "效价 p" to f2(selfState.p),
                        "唤醒 a" to f2(selfState.a),
                        "支配 d" to f2(selfState.d),
                        "心境" to f2(selfState.mood),
                        "信任" to f3(selfState.trust),
                        "亲近" to f3(selfState.closeness),
                        "冲突" to f2(selfState.conflict),
                        "修复" to f2(selfState.repair),
                        "最近标签" to selfState.lastLabel.ifBlank { "—" },
                        "记忆点" to metas.size.toString(),
                    )
                    com.leaf.vixi.data.TopicExporter.writeVault(
                        App.instance, exportDir(conv), metas, titles, snap,
                    )
                    written.size
                }.getOrDefault(0)
            }
            val msg = if (n > 0) "已导出 $n 篇 + 记忆地图到「文档/鱼/${exportDir(conv)}」" else "没有可导出的记录"
            runCatching {
                android.widget.Toast.makeText(App.instance, msg, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 切换测试环境。
     *
     * 为什么需要它：调情绪系统时要反复跑同一个场景，真实记忆会把结果搞乱
     * （检索会把上次调试的对话捞回来）。打开后：
     *   · 换成独立的 `chat_sandbox_<会话>.jsonl`，真实记录一行都不动
     *   · 不检索、不归档话题、不导出、不注入纠正记录
     */
    /** 下一轮让她联网（发完自动关）。用的是服务端搜索工具，不额外调用。 */
    fun toggleSearchNext() {
        val next = !_state.value.searchNext
        _state.update {
            it.copy(
                searchNext = next,
                notice = if (next) "下一轮她会联网查一下（发完自动关）" else "下一轮不联网",
            )
        }
    }

    fun toggleSandbox() {
        // 和切会话同样要挡：这一轮跑到一半换文件指针，回复会落进另一个文件、
        // 而且 pending.clear() 会把还没回的对话直接丢掉
        if (hasPendingSend()) {
            _state.update { it.copy(notice = "等在途的消息发出去再切测试环境") }
            return
        }
        val next = !settings.sandbox
        settings.sandbox = next
        // 重新装载当前会话：store.open() 会按开关指到 chat_sandbox_<会话>.jsonl 或 chat_<会话>.jsonl，
        // 测试环境不记忆、不检索、不归档，但会话本身不变
        _state.update {
            it.copy(
                typing = false,
                error = null,
                sandbox = next,
                notice = if (next) "已进入测试环境：不记忆、不检索、独立记录" else "已回到正常环境",
            )
        }
        viewModelScope.launch { loadConversation(conv, replace = true) }
    }

    /** 清空测试环境的记录（正常环境调用无效，防止误删真实记录） */
    fun clearSandbox() {
        if (!settings.sandbox) return
        viewModelScope.launch {
            withContext(io) { store.clear() }
            turnLog.clear()
            loggedTs.clear()
            _state.update { it.copy(bubbles = emptyList(), notice = "已清空测试记录") }
        }
    }

    /** 手动结束当前话题：规则猜不准时给你一个开关 */
    fun endTopic() {
        val now = System.currentTimeMillis()
        archiveCurrentTopic()
        topicId = now
        topicStart = now
        topicCount = 0
        topicTitle = ""
        // 必须同时推进 lastAt：否则"隔了 30 分钟再点分段"之后，下一条消息会
        // 因为 gap 仍然成立而**再归档一次**，留下一条 messageCount=0 的幽灵话题。
        lastAtMem = now
        viewModelScope.launch(io) { topicStore.saveCurrent(now, now, now) }
        _state.update { it.copy(notice = "已结束当前话题，后面说的算新的一段") }
    }

    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }

    /** 归档 + 顺手导出一份 Markdown（放 files/export/，Obsidian 那边可以直接拉） */
    private fun archiveCurrentTopic() {
        if (topicId == 0L || lastAtMem <= 0L) return
        if (topicCount == 0) return   // 空话题不归档，避免 topics.jsonl 出现幽灵记录
        // 测试环境不归档、不导出：否则调试的假对话会混进真实记忆库和 Obsidian
        if (settings.sandbox) return
        val old = TopicStore.Topic(
            id = topicId,
            title = topicTitle.ifBlank { "（无题）" },
            startedAt = topicStart,
            endedAt = lastAtMem,
            messageCount = topicCount,
        )
        // 直接从内存里的 turnLog 按时间窗切，不再全量读盘：
        // io 是单线程，虽然不会抢文件，但长历史时那次 load 会让后续 append 排队。
        val from = if (old.startedAt > 0L) old.startedAt else 0L
        // 她的消息一律写回 speakerId：单聊里也一样。
        // 否则导出 md 时查不到说话人，Nami 的单聊会整篇被标成「鱼」。
        val idsByName = membersOf(conv).associate { it.name to it.id }
        val selfId = conv.memberIds.firstOrNull()
        val msgs = turnLog
            .filter { it.ts in from..old.endedAt }
            .map {
                StoredMessage(
                    fromUser = !it.self,
                    text = it.text,
                    ts = it.ts,
                    kind = it.kind,
                    quote = it.quote,
                    speakerId = if (it.self) it.speaker?.let(idsByName::get) ?: selfId else null,
                )
            }
        // 归档要写进**这个会话**的文件：协程是异步的，包里如果等它跑起来再读 conv/topicStore，
        // 用户这时切了会话就会写到新会话的 topics_<id>.jsonl 和 md 目录里。
        val archiveStore = com.leaf.vixi.data.TopicStore(App.instance).apply { open(conv.id) }
        val dirName = exportDir(conv)
        val selfName = fallbackName(conv)
        viewModelScope.launch(io) {
            archiveStore.archive(old)
            com.leaf.vixi.data.TopicExporter.write(App.instance, old, msgs, dirName, selfName)
        }
    }

    /** 删除一条消息：界面、历史、磁盘、待发队列一起删 */
    fun deleteMessage(bubble: Bubble) {
        _state.update { it.copy(bubbles = it.bubbles.filterNot { b -> b.id == bubble.id }) }

        // 按时间戳精确删（按文本匹配会删掉第一条同名消息，本项目里"嗯/干嘛/困了"重复极多）
        turnLog.removeAll { it.ts == bubble.ts && it.self == !bubble.self }
        loggedTs.remove(bubble.ts)

        // 还没发出去的也要撤掉，否则"删了还在发"
        pending.removeAll { it.ts == bubble.ts }
        pendingBubbleIds.remove(bubble.id)
        failedTurns.removeAll { bubble.id in it.ids }

        viewModelScope.launch(io) {
            store.delete(bubble.ts, bubble.text)
            // 索引也要删：留着的话那句承诺仍把整段事件钉住（永不软遗忘、展开门槛 0.35），
            // 表现就是"我明明删了它还记得"
            indexStore.remove(bubble.ts)
            bubble.images.forEach { images.deleteImage(it) }
        }
    }

    private fun markStatus(ids: List<Long>, status: SendStatus) {
        if (ids.isEmpty()) return
        val set = ids.toSet()
        _state.update { st ->
            st.copy(bubbles = st.bubbles.map { if (it.id in set) it.copy(status = status) else it })
        }
    }

    /** 落泡 + 落库 + 起一轮 */
    private fun enqueue(item: Incoming, quote: String? = null) {
        // 记录页正在删/导/清时不许入队。门放在**这里**（唯一入口）而不是只放在 send()：
        // 摸头、重发都绕过 send()，那几秒里进去的消息会在 loadConversation 的
        // pending.clear() 里静默消失（气泡被清、也没有回复）。
        if (recordsOp) {
            _state.update { it.copy(notice = "正在处理聊天记录，稍后再试") }
            return
        }
        val msg = item.copy(quote = quote)
        val bubble = Bubble(
            id = nextId++,
            self = true,
            text = msg.text,
            kind = msg.kind,
            images = msg.images,
            quote = msg.quote,
            status = SendStatus.SENDING,
        )
        _state.update { it.copy(bubbles = it.bubbles + bubble) }

        // —— 话题边界（本地判定，不调模型）——
        // 1) 隔了 30 分钟以上 2) 说了收尾话  → 上一段归档，开新的一段
        val now = System.currentTimeMillis()

        // —— 评价层：这句话被读成什么（四维 + 一个标签 + 强度）——
        // 只算一次，状态增量和调试页都用这一份。
        val appr = com.leaf.vixi.state.Appraiser.appraise(msg.text)
        val verdict = com.leaf.vixi.state.Labeler.judge(
            appr,
            closeness = selfState.closeness,
            trust = selfState.trust,
        )

        // —— 越界（2026-11-22 用户发现："分寸只影响她的回复，从不判断我"）——
        // 五条判据全本地：称谓 / 身体 / 命令 / 隐私 / 无视拒绝。
        // 只在**单聊 + 状态开着**时算：群聊里更新的是"群创建者"的状态，串台会被新字段放大
        // （群里一句「老婆」会把她单聊的 risk 粘住）。
        val boundaryOn = selfState.enabled && !conv.isGroup
        // 最近她说的话：从内存里的 turnLog 取，不再整份读 chat 文件（主线程读盘）
        val recentForBoundary = turnLog.filter { it.self }.takeLast(3).map {
            com.leaf.vixi.data.StoredMessage(fromUser = false, text = it.text, ts = now)
        }
        val boundaryHits = if (boundaryOn) {
            com.leaf.vixi.state.Boundary.check(msg.text, selfState, recentForBoundary).toMutableList()
        } else {
            mutableListOf()
        }
        // 摸头在关系浅的时候也算越界：那条在 EventRules 里判（改关系量），
        // 但 risk 那条链要走同一个 violation，所以这里补一笔
        val farNow = (1.0 - selfState.closeness).coerceIn(0.0, 1.0)
        val badPat = boundaryOn && msg.kind == MsgKind.ACTION &&
            selfState.closeness < com.leaf.vixi.state.Boundary.PAT_MIN_CLOSENESS
        if (badPat) boundaryHits += com.leaf.vixi.state.Boundary.Hit("身体", 0.25 * farNow)
        // 误伤：多条命中取 max + 小协同，**不再相加**（否则一句话能把关系打死）
        val violation = com.leaf.vixi.state.Boundary.combine(boundaryHits)

        // —— 交叉层·缄口（X2）：这句话敏感吗？要不要说 ——
        val withholdAct = com.leaf.vixi.state.Withhold.decide(selfState, msg.text, conv.isGroup)

        // —— 生物层·心动（S4）：上一轮的应对成不成功，这一轮挑哪个 ——
        // 成功定义：你这句带来的利害是正的，或者你撒娇/夸了她。失败 = 其余（含越界）。
        val movesDecoded = com.leaf.vixi.state.Strategy.decode(selfState.moves)
        val statsNow = movesDecoded.stats.toMutableMap()
        // **一个合并窗口只打一次分**：否则连发三条会给上一轮记三次试验，
        // 其中后两次打的是"回复还没生成"的那个应对
        if (pending.isEmpty()) movesDecoded.last?.let { prev ->
            val (w, n) = statsNow[prev] ?: (0 to 0)
            val success = appr.impact > 0.05 ||
                StateEventType.AFFECTION in appr.events || StateEventType.PRAISE in appr.events
            statsNow[prev] = (w + if (success) 1 else 0) to (n + 1)
        }
        val chosenMove = com.leaf.vixi.state.Strategy.adjust(
            com.leaf.vixi.state.Strategy.choose(statsNow),
            selfState,
        )
        val movesNow = com.leaf.vixi.state.Strategy.encode(statsNow, chosenMove)

        // —— 生物层·入心（S5）：默认关，只有 learnOn 才攒证据挪旋钮 ——
        var knobsNow = selfState.knobs
        var evidenceNow = selfState.evidence
        if (selfState.learnOn) {
            val sig = com.leaf.vixi.state.Growth.signalOf(
                userText = msg.text,
                praiseOrAffection = StateEventType.AFFECTION in appr.events ||
                    StateEventType.PRAISE in appr.events,
                // 用**本轮**算出来的 violation（selfState 里那个是上一轮的）
                violated = violation >= com.leaf.vixi.state.Boundary.AVERSION_AT,
                negative = StateEventType.NEGATIVE in appr.events ||
                    StateEventType.REBUFF in appr.events,
                isText = msg.kind == MsgKind.TEXT,
            )
            val (k, ev) = com.leaf.vixi.state.Growth.step(
                com.leaf.vixi.state.Growth.decode(selfState.knobs),
                sig,
                parseEvidence(selfState.evidence),
            )
            knobsNow = com.leaf.vixi.state.Growth.encode(k)
            evidenceNow = com.leaf.vixi.state.Growth.encodeEvidence(ev)
        }

        // —— 害羞（2026-11-22）：心动/开心被"看见"时想躲 ——
        // 意外度 = 同类事件密度（越常见越不害羞）+ 夸奖强度跳变；阈值来自角色（人设）
        val shyBias = _state.value.characters.firstOrNull { it.id == stateCharId() }?.shy ?: 0.5
        val prevIntensity = _state.value.lastAppraisal?.intensity ?: 0.0
        val shyScore = if (boundaryOn) {
            com.leaf.vixi.state.Shyness.evaluate(
                verdict.label, verdict.intensity, prevIntensity, selfState, shyBias,
            )
        } else {
            0.0
        }
        val shyActive = shyScore >= com.leaf.vixi.state.Shyness.THRESHOLD
        val effLabel = if (shyActive) com.leaf.vixi.state.EmotionLabel.SHY else verdict.label
        val effIntensity = if (shyActive) shyScore else verdict.intensity

        // —— 状态事件（纯本地，只在启用的角色上）——
        // 一次窗口只记一次「你发消息」（合并发送的两条不该加两次唤醒）
        if (selfState.enabled) {
            val batch = mutableListOf<PendingEvent>()
            val gap = if (selfState.lastHeardAt > 0L) now - selfState.lastHeardAt else 0L
            if (gap > EventRules.NEGLECT_MS) {
                batch += PendingEvent(
                    StateEventType.NEGLECT,
                    "隔了 ${gap / 60_000} 分钟才回她",
                    EventRules.neglectMagnitude(gap),
                )
            }
            when {
                // 摸头不是"说话"：只记 PAT
                msg.kind == MsgKind.ACTION -> batch += PendingEvent(StateEventType.PAT)
                msg.kind == MsgKind.IMAGE && msg.text.isBlank() ->
                    batch += PendingEvent(StateEventType.SHARE)
                else -> {
                    if (pending.isEmpty()) batch += PendingEvent(StateEventType.USER_MSG)
                    // P3：数值不再由"词表类型"直给，而由情绪标签 + 强度决定
                    //（「你真蠢」→ 生气 → d 升；「你这毛病得改改」→ 委屈 → d 降）
                    if (effLabel != com.leaf.vixi.state.EmotionLabel.CALM) {
                        batch += PendingEvent(
                            type = StateEventType.EMOTION,
                            note = emotionNote(effLabel.zh, appr, msg.text),
                            delta = com.leaf.vixi.state.EmotionDelta.of(effLabel, effIntensity),
                            label = effLabel.zh,
                        )
                    }

                    // 道歉是**关系修复**，不该被情绪吞掉：单独记一条修复事件。
                    // 而且只有真的存在裂痕才给修复（没冲突时一句对不起不涨信任）。
                    if (StateEventType.APOLOGIZE in appr.events) {
                        val hasRift = selfState.conflict >= 0.15
                        batch += PendingEvent(
                            type = StateEventType.APOLOGIZE,
                            note = if (hasRift) "他道歉·修复" else "他道歉（没有裂痕，不给修复）",
                            delta = if (hasRift) {
                                com.leaf.vixi.state.Delta(
                                    conflict = -0.10, backlog = -0.05, trust = 0.002, repair = 0.06,
                                )
                            } else {
                                com.leaf.vixi.state.Delta()
                            },
                        )
                    }
                    // 被在意（N2）：**安抚的话真的能降"想要被在意"**，泼冷水会加。
                    // 以前 `Needs.careSignal` 只被测试调用 → CARE 事件永不产生 →
                    // "我在意你"在数值上等于没说；belong 只能靠冷落涨、靠 24 小时窗口滚掉，
                    // 看起来就是"没有下降机制"（用户 2026-11-25 报）。
                    when (com.leaf.vixi.state.Needs.careSignal(msg.text)) {
                        1 -> batch += PendingEvent(StateEventType.CARE)
                        -1 -> batch += PendingEvent(StateEventType.REBUFF)
                    }
                    if (TopicStore.isClosing(msg.text)) batch += PendingEvent(StateEventType.LEAVE)
                }
            }
            // 越界**每轮都记**（包括 0）：只写不清会让 risk 被"历史上最后一次越界"永久抬高。
            if (boundaryOn) {
                batch += PendingEvent(
                    type = StateEventType.BOUNDARY,
                    note = if (violation > 0.0) {
                        "越界·" + com.leaf.vixi.state.Boundary.summary(boundaryHits)
                    } else {
                        "没越界"
                    },
                    magnitude = violation,
                    // delta 不自己拼：统一走 Boundary.impact（p/a/d + 关系量）
                    violation = violation,
                    // 到"该反感"的程度就落一条反感情绪，状态行会说出来
                    label = if (com.leaf.vixi.state.Boundary.shouldAvert(boundaryHits, selfState, violation)) {
                        com.leaf.vixi.state.EmotionLabel.AVERSION.zh
                    } else {
                        null
                    },
                )
            }
            // 缄口：**每轮都写**（包括"说"）—— 只写不写回空会让"先别提"永久粘住，
            // 之后就算这件事早就过去了，提示词里还在说"别提"
            if (selfState.enabled) {
                batch += PendingEvent(StateEventType.WITHHOLD, withholdAct.zh)
            }
            // 心动/入心：两串整串落盘（note 里带全量状态）
            // 群聊不记策略：群里每句话都在给"群创建者的她"记胜负，那没有意义
            if (!conv.isGroup && movesNow != selfState.moves) {
                batch += PendingEvent(StateEventType.MOVE, movesNow)
            }
            if (knobsNow != selfState.knobs || evidenceNow != selfState.evidence) {
                // "旋钮串|证据串"：两样一起落盘（否则证据进不了盘，学习就是死代码）
                batch += PendingEvent(StateEventType.KNOBS, "$knobsNow|$evidenceNow")
            }
            recordStateEvents(batch)
        }

        // —— 评价结果给调试页看（P1 起）——
        // 数值已经由上面的情绪事件带走了；这里只负责把"她怎么理解这句话"显示出来。
        runCatching {
            _state.update { st ->
                val item = AppraisalUi(
                    text = msg.text,
                    appraisal = appr,
                    label = verdict.label.zh,
                    intensity = verdict.intensity,
                )
                // pending 为空 = 这是新窗口的第一句（上一轮的记录清掉）
                val fresh = pending.isEmpty()
                st.copy(
                    lastAppraisal = item,
                    recentAppraisals = ((if (fresh) emptyList() else st.recentAppraisals) + item)
                        .takeLast(3),
                )
            }
        }

        val lastAt = lastAtMem
        val closing = TopicStore.isClosing(msg.text)
        val hasHistory = lastAt > 0L
        val gap = hasHistory && now - lastAt > TopicStore.GAP_MS

        // 测试环境不做话题切分：不归档、不写游标
        if (!settings.sandbox) {
            // 只有"真的该换话题"才开新话题：收尾话，或者隔了 30 分钟。
            // 状态文件丢失（首装 / 用户删过 files/mem）**不算换话题** —— 那会把已有历史整段清出上下文。
            if (closing || gap) {
                if (hasHistory) archiveCurrentTopic()
                topicId = now
                topicStart = now
                topicCount = 0
                topicTitle = msg.text.replace("\n", " ").take(24).ifBlank { "（图片/动作）" }
            } else if (topicStart <= 0L) {
                // 没有话题状态：把既有历史整体当作当前话题（topicStart=0 = 从最早开始），
                // 绝不清空；下次真出现间隔或收尾话时再正式分段。
                topicStart = 0L
                if (topicId == 0L) topicId = if (hasHistory) lastAt else now
                topicTitle = msg.text.replace("\n", " ").take(24).ifBlank { "（图片/动作）" }
            }
            topicCount += 1
            // 话题标题只用来归档/导出时好看：会话跨重启时 topicTitle 是空的，
            // 这里兜一下，别让所有话题都叫「（无题）」。
            if (topicTitle.isBlank()) {
                topicTitle = msg.text.replace("\n", " ").take(24).ifBlank { "（图片/动作）" }
            }
            lastAtMem = now
            val (curId, curStart) = topicId to topicStart
            viewModelScope.launch(io) { topicStore.saveCurrent(curId, curStart, now) }
        }

        viewModelScope.launch(io) {
            val ok = store.append(
                StoredMessage(
                    fromUser = true,
                    text = msg.text,
                    ts = bubble.ts,
                    kind = msg.kind,
                    images = msg.images,
                    quote = msg.quote,
                )
            )
            // 落盘失败必须让人看见：否则界面上"已发送"，重启之后这条消息就不存在了
            if (!ok) _state.update { it.copy(notice = "这条没写进记录（可能是存储满了）") }
        }

        pending += msg.copy(ts = bubble.ts)
        pendingBubbleIds += bubble.id
        // 记忆索引：元数据（重要度/情绪/实体）进索引，正文仍在 chat_*.jsonl
        appendIndex(fromUser = true, text = msg.text, label = verdict.label.zh, ts = bubble.ts)
        // 拉一个短命前台服务：你切出去后进程不会被冻结，她才能把话回完、通知才发得出去
        runCatching { com.leaf.vixi.ReplyService.start(App.instance) }
        // 进合并窗口：等你"停手"1 秒再发。这 1 秒内再发/再打字，窗口重新计时
        scheduleTurn()
    }

    /**
     * 合并窗口：**等你停手 1 秒**才真正发出去。
     *
     * 为什么不是"立即发"：手机上人经常一句拆成三条发（"那个" "我说" "算了"），
     * 立即发就是三次请求、三次回复；等 1 秒能把它们合并成一次请求（prompt 里仍是三行）。
     *
     * 为什么单独一个 Job：窗口期还没真正请求，`turnRunning` 仍是 false，
     * 所以要用 `sendDebounceJob` 判断"有没有在等"，否则切会话/切测试环境时
     * 会把还没发出去的那几条直接清掉（气泡和记录都在，但她永远不回）。
     */
    private fun scheduleTurn() {
        if (turnRunning) return   // 正在跑一轮：新消息留在 pending，本轮结束后自动接着跑
        sendDebounceJob?.cancel()
        sendDebounceJob = viewModelScope.launch {
            val since = System.currentTimeMillis()
            delay(SEND_IDLE_MS)
            // **人还在打字就先别发**（用户 2026-11-24 报的 bug）：
            // 发出去一条之后接着敲，2 秒一到就把上一句发走了，剩下没打完的字被拆成第二轮。
            // 这里等到输入框清空再发；但最多再等 MAX_DEFER_MS，免得人走开了这条永远发不出去。
            while (_state.value.input.isNotBlank() &&
                System.currentTimeMillis() - since < MAX_DEFER_MS
            ) {
                delay(300)
            }
            sendDebounceJob = null
            startTurnLoop()
        }
    }

    /** 窗口往后推：现在有新的动作（打字/选图/引用），说明用户还没说完 */
    private fun bumpSendWindow() {
        if (turnRunning) return
        if (pending.isEmpty()) return
        scheduleTurn()
    }

    /** 有没有"等着发出去"的消息：切会话/切环境前要挡一下 */
    private fun hasPendingSend(): Boolean =
        turnRunning || sendDebounceJob?.isActive == true || pending.isNotEmpty()

    /**
     * 一轮请求的调度：串行，不打断。
     *
     * 为什么要串行：早期版本在每次 enqueue 时 `turnJob.cancel()` 掉进行中的轮次，
     * 但那一轮的 batch 已经从 pending 取走、也已写进 turnLog —— 结果那条消息永远等不到回复，
     * 之后还会作为"已读"每轮重新进 prompt（她会对着一条没人理的话反复作答）。
     * 现在改成：进行中就排队，等这一轮结束再接着跑。
     */
    private fun startTurnLoop() {
        if (turnRunning) return
        turnRunning = true

        turnJob = viewModelScope.launch {
            try {
                // 保活绑到**整个队列**，不是绑到一条回复：
                // 原来是在拿到回复时 stop（见 runTurn 里那句），于是连发 A、B 时
                // B 在没有前台服务的状态下裸跑，切出去就被系统冻住 —— "第二条发不出去"最后一处。
                runCatching { com.leaf.vixi.ReplyService.start(App.instance) }
                while (pending.isNotEmpty()) {
                    val batch = pending.toList()
                    pending.clear()
                    // 这一批对应的气泡 id（顺序一致）
                    val ids = pendingBubbleIds.take(batch.size)
                    repeat(ids.size) { if (pendingBubbleIds.isNotEmpty()) pendingBubbleIds.removeAt(0) }
                    runTurn(batch, ids)
                }
            } finally {
                turnRunning = false
                _state.update { it.copy(typing = false) }
                // 自愈：收尾那一瞬进来的消息会撞上 scheduleTurn() 开头的 `if (turnRunning) return`，
                // 消息留在 pending 里而循环已经退出 —— 没人接手，那条永远不回。
                // ⚠️ 必须放在 turnRunning = false **之后**，否则又被自己挡一次。
                if (pending.isNotEmpty() && sendDebounceJob?.isActive != true) scheduleTurn()
                // 队列真的空了，前台服务可以收了
                runCatching { com.leaf.vixi.ReplyService.stop(App.instance) }
            }
        }
    }

    private suspend fun runTurn(batch: List<Incoming>, bubbleIds: List<Long>) {
        _state.update { it.copy(typing = true, error = null) }

        // 每轮开始先结算一次时间衰减：这一轮用到的必须是"现在"的状态，
        // 而不是上次有人说话时留下的快照。
        if (selfState.enabled) refreshSelfState()

        if (!settings.isConfigured()) {
            _state.update { it.copy(error = "先去设置里填 API key") }
            markStatus(bubbleIds, SendStatus.FAILED)
            failTurn(batch, bubbleIds)
            return
        }

        val historyBefore = turnLog.toList()

        // 只把"还没写进历史"的消息补进去：重发时不能重复注入（否则 prompt 里会出现两条一样的话）
        val fresh = batch.filter { it.ts !in loggedTs }
        if (fresh.isNotEmpty()) {
            turnLog += fresh.map {
                Turn(
                    self = false,
                    text = it.text,
                    kind = it.kind,
                    quote = it.quote,
                    imageCount = it.images.size,
                    ts = it.ts,
                )
            }
            loggedTs += fresh.map { it.ts }
        }

        // 逐张编码，记住哪几张成功：prompt 里的"[N张图片]"必须和实际发出去的一致
        val requestedImages = batch.flatMap { it.images }
        val encoded = withContext(Dispatchers.IO) {
            requestedImages.mapNotNull { name ->
                images.bytesForApi(name)?.let { name to it }
            }
        }
        val okNames = encoded.map { it.first }.toSet()
        val effective = batch.map { m -> m.copy(images = m.images.filter { it in okNames }) }
        val imagePayload = encoded.map { Base64.encodeToString(it.second, Base64.NO_WRAP) }

        if (requestedImages.size > encoded.size) {
            val missing = requestedImages.size - encoded.size
            _state.update {
                it.copy(
                    error = if (encoded.isEmpty()) "图片都读不出来，这轮只发了文字"
                    else "有 $missing 张图读不出来，已跳过"
                )
            }
        }

        // —— 只带"当前话题"的内容 ——
        // 整个历史一股脑塞进去既贵又糊，她会把十天前的话当现在的上下文。
        // 话题由本地规则切分（间隔 30 分钟 / 收尾话），旧话题进原文库，需要时靠检索翻出来。
        val inTopic = historyBefore.filter { it.ts >= topicStart }

        // ── 检索：本地直接查，不再"先问她要不要查" ──
        // 之前用探路协议（让她输出 RECALL: 关键词）在真机上翻过车：模型经常不按格式输出、
        // 直接回话，于是根本没检索，她就说"我们没聊过"。改成纯本地判定 + 本地查询，
        // 结果直接拼进这一次请求 —— 既不会再失败，也省掉一次探路调用。
        val incomingText = batch.joinToString(" ") { it.text }
        val windowText = inTopic.joinToString(" ") { it.text }
        var recallQuery = ""
        // 判定明细单独一个变量：以前和 recallQuery 共用，于是 `gate=0.57 …` 这种诊断串
        // 被当成"查询"写进了 recall.log 和调试页（日志里一半是重复行、查不到真实查询）
        var recallDiag = ""
        var retrieved: List<String> = emptyList()
        var recallRounds: List<String> = emptyList()
        var recallAugmented: List<String> = emptyList()
        run {
            // 测试环境：完全不检索（调情绪系统时要"无记忆"，否则旧对话会污染结果）
            if (settings.sandbox) return@run
            val windowTs = inTopic.map { it.ts }.toHashSet()
            // **本轮之前**的时间线：batch 里的消息已经落库了（比当前窗口新），
            // 如果不按时间切掉，检索会把"你刚说的这句话"当成旧事检索回来
            // —— 真机上就出现过「Leaf：我们上次说的那件事呢？」被当作记忆命中。
            val turnStart = batch.minOfOrNull { it.ts } ?: System.currentTimeMillis()
            // 语料按会话规则拼：单聊 = 自己的 + 她所在群的（群记忆下渗），群聊 = 只有本群
            val corpus = withContext(io) {
                corpusFor(conv).filter { it.ts !in windowTs && it.ts < turnStart }
            }

            // ── 检索 v2：三通道 + 事件为单位 + 分档注入 ──
            // 不再让模型提炼关键词（省掉一次调用）；也不再"命中就整段倒出来"：
            // 闸门给 0~1 分，按 线索/语义/重要度/新近 × 心境一致 打分，再按 0.55 / 0.35 分档。
            val entries = withContext(io) { indexStore.read() }
            if (entries.isEmpty()) {
                // 索引还没建好（老版本升级途中）：退回旧检索，至少不会完全没有记忆
                if (RecallGate.needsRecall(incomingText)) {
                    val terms = RecallGate.extractQuery(incomingText)
                        .split(' ', '　', ',', '，', '、').map { it.trim() }.filter { it.isNotEmpty() }
                    val multi = Retriever.recallMulti(terms, corpus, sourceText = incomingText)
                    retrieved = multi.hits.map {
                        (if (it.fromUser) "Leaf" else nameOf(it.speakerId)) + "：" + it.text
                    }
                    recallRounds = multi.variants
                    recallQuery = "旧检索（索引未就绪）"
                }
            } else {
                // "还没聊完"：当前话题刚开、只有一两句
                val unclosed = topicStart > 0L && topicCount <= 2
                val recallStats = withContext(io) { indexStore.recalls() }
                val res = Recall.run(
                    query = incomingText,
                    corpus = corpus,
                    entries = entries,
                    mood = selfState.mood,
                    unclosed = unclosed,
                    knownNames = membersOf(conv).map { it.name },
                    recentText = windowText,
                    recalls = recallStats,
                    coBonus = withContext(io) {
                        indexStore.coRecallBonuses(recallStats, System.currentTimeMillis())
                    },
                    excludeAfter = turnStart,
                    speakerName = { m -> if (m.fromUser) "Leaf" else nameOf(m.speakerId) },
                )
                retrieved = res.lines
                recallQuery = incomingText
                recallDiag = res.detail
                recallRounds = listOf("gate=${String.format(java.util.Locale.US, "%.2f", res.gate)} ${res.mode.name}")
                // 把判定明细与候选落盘：真机上"为什么没命中"只能靠这个复查
                logRecall(incomingText, res.lines, res.detail, res.debug)
                // 记忆点之间互相影响（他的第五节）：复习 + 强化 + 提取抑制 + 共召回关联
                if (res.lines.isNotEmpty()) {
                    val now2 = System.currentTimeMillis()
                    withContext(io) {
                        val picked = res.picked.toSet()
                        (res.expanded + res.hinted).forEach { s ->
                            indexStore.bumpRecall(s.event.start, now2)
                        }
                        res.candidates.filter { it !in picked }.take(3).forEach { start ->
                            indexStore.suppress(start, now2)
                        }
                        val ps = res.picked
                        for (i in ps.indices) for (j in i + 1 until ps.size) {
                            indexStore.recordCoRecall(ps[i], ps[j], now2)
                        }
                    }
                }

                // ── 交叉层第一条边：记忆 → 状态（召回再激活）──
                // 想起来一件旧事，要真的把此刻的 p 推一下 —— 否则"想起来"只是一段台词。
                // 五个断点全在 RecallActivation 里：只认展开档 / 系数 0.15 / 单轮 |Δp|≤0.05 /
                // conflict≥0.40 不翻旧账 / 同一条 24 小时只算一次（第二次起减半）。
                // 群聊不激活（群聊本来就不注入状态）；沙盒模式不检索，所以也走不到这里。
                if (!conv.isGroup) {
                    val items = res.expanded.map {
                        com.leaf.vixi.state.RecallActivation.Item(
                            start = it.event.start,
                            p = it.event.p,
                            importance = it.event.importance,
                            label = it.event.label,
                        )
                    }
                    val acts = com.leaf.vixi.state.RecallActivation.plan(
                        items, selfState, System.currentTimeMillis(),
                    )
                    if (acts.isNotEmpty()) {
                        // 一条旧事一条事件：24 小时重复衰减要靠事件表反解（不新开文件）
                        recordStateEvents(acts.map {
                            PendingEvent(
                                type = StateEventType.RECALL,
                                note = com.leaf.vixi.state.RecallActivation.noteOf(it),
                                delta = com.leaf.vixi.state.Delta(p = it.deltaP),
                            )
                        })
                        val line = com.leaf.vixi.state.RecallActivation.summary(acts)
                        recallDiag += " ｜ $line"
                        logRecall(
                            incomingText, emptyList(), line,
                            acts.map {
                                String.format(
                                    java.util.Locale.US,
                                    "  recall %s Δp%.3f（旧事 p%.2f imp%.2f）",
                                    it.label, it.deltaP, it.p, it.importance,
                                )
                            },
                        )
                    }
                }
            }
        }

        // 群聊：成员名（纯名字，不带人设简介）。
        // 带简介会把人设里的自称（「你叫海苔」）漏给模型，它就照自称输出「海苔：…」，
        // 本地切不出气泡 —— 真机上踩过，见 GroupReplyParser。
        val groupNames = if (conv.isGroup) membersOf(conv).map { it.name } else emptyList()
        // 状态注入：只有单聊、且这个角色开了状态才给（群聊先不注入，避免和别人的语气混）
        val selfStateLine = if (!conv.isGroup && selfState.enabled) {
            // X1：状态 + 混合情绪 + 长度合成一段（见下面 mergedLine）
            com.leaf.vixi.state.Explain.promptLine(selfState)
        } else {
            ""
        }
        // 【说多少】【分寸】**不再单独传**：单聊的 mergedSelfLine 里已经带了这两段
        // （Explain.mergedLine 自己 append 了【说多少】和 tactLine）。
        // 两处来源口径不同 —— 合并层用 base×lengthMul，lengthLine 用不含乘子的 speakBudget ——
        // 同一份 prompt 里会出现"很短，一两句就够"和"最多 28 字（日常两三句）"打架。
        // PromptBuilder 的两个参数保留（群聊与单测用），生产单聊不再传。
        // P5-2：主动性与分寸（要不要主动开口 / 要不要翻旧事）
        val initiativeLine = if (!conv.isGroup && selfState.enabled) {
            com.leaf.vixi.state.Explain.initiativeLine(selfState)
        } else {
            ""
        }

        // ── 需要（N2/N3）：最想要什么 + 爆发那一轮可以主动 ──
        // 分寸是音量旋钮：wantLine 自己按 tactBand 决定"说不说、怎么说"
        // 爆发**不在这里写事件**：请求可能失败，那样她会一句话没说就被消耗掉一次爆发。
        // 先记下 note，等回复真的落库时再和 HER_REPLY 一起提交（见下面 success 分支）。
        // 这一轮开始时就"憋不住"了：她说完之后要一次性放掉（只能在成功分支记）
        val ventingNow = selfState.enabled && !conv.isGroup &&
            com.leaf.vixi.state.Explain.isVenting(selfState)

        var pendingBurstNote: String? = null
        val needLines = if (!conv.isGroup && selfState.enabled) {
            val risk = com.leaf.vixi.state.Explain.riskOf(selfState)
            val nowMs = System.currentTimeMillis()
            val burst = com.leaf.vixi.state.Needs.burstLine(selfState, risk, nowMs)
            // 爆发那一轮只给【可以主动】：再给一遍【想要什么】等于同一件事说两遍
            val want = if (burst.isBlank()) {
                com.leaf.vixi.state.Needs.wantLine(selfState, risk, nowMs)
            } else {
                ""
            }
            if (burst.isNotBlank()) {
                val d = com.leaf.vixi.state.Needs.dominant(selfState, nowMs)
                if (d != null) pendingBurstNote = "需要爆发·${d.kind.label}"
            }
            listOf(want, burst).filter { it.isNotBlank() }.joinToString("\n")
        } else {
            ""
        }
        // X1 合并层：状态 + 混合情绪 + 长度 由**一处**生成（消灭两行打架）
        stateCharId()?.let { reactionTable.bind(it) }
        // 注入文案（【想念】【想要什么】【说多少】）也按角色装一次：纯函数那边从静态表里取
        stateCharId()?.let { injectionTable.bind(it) }

        // 长期人物事实：按当前会话里的角色取（群聊每人一份，别串）
        val factLines = runCatching {
            com.leaf.vixi.App.instance.facts.block(
                conv.memberIds,
                { id -> membersOf(conv).firstOrNull { it.id == id }?.name ?: "她" },
                conv.isGroup,
            )
        }.getOrDefault("")

        // 她能做的两个动作：引用他刚说的一句 / 发一张贴纸 / 把稳定信息记成事实。
        // 引用与记事实总是允许；发图只有 `files/stickers/` 里有图时才注入
        //（空目录 = 没这个能力）。
        val stickerNames = runCatching { stickers.names() }.getOrDefault(emptyList())
        val actLines = buildString {
            appendLine(
                "【可以引用】**只在需要指回他刚说的某句具体内容时才用**（他答应过什么、" +
                    "他说错了一句、他问的是你之前说过的某一小句）。" +
                    "随便接话、打招呼、顺着聊都不要用；大多数回复里不该出现这个标签。" +
                    "用法：在这条回复**最前面**写 [引用:那句原文的前几个字]。"
            )
            appendLine(
                "【可以记住】聊到**关于你自己的稳定信息**（喜好、习惯、经历、生日、" +
                    "怕的东西、家里的情况）时，在回复末尾写 [记住:喜欢草莓] 这种短句。" +
                    "只写一句、不超过 20 字；临时情绪、当下发生的事、他的信息都**不要**记。"
            )
            if (stickerNames.isNotEmpty()) {
                appendLine(
                    "【可以发的图】" + stickerNames.take(12).joinToString("、") +
                        "（想发就单独占一行写 [图:文件名]，那一行不要再写别的话；不合适就不发）"
                )
            }
        }.trim()
        val rendered = com.leaf.vixi.data.ReactionTable.Rendered::class.let {
            runCatching {
                reactionTable.render(
                    com.leaf.vixi.state.EmotionMix.decode(selfState.emoMix),
                    com.leaf.vixi.state.EmotionMix.dominant(selfState),
                )
            }.getOrNull()
        }
        val mergedState = if (conv.isGroup) {
            ""
        } else {
            com.leaf.vixi.state.Explain.mergedLine(
                selfState,
                rendered,
                template = stateCharId()?.let { reactionTable.template(it) },
            )
        }

        // 覆盖成合并层的结果（原 promptLine 只在群聊/关闭时用）
        val mergedSelfLine = if (conv.isGroup) selfStateLine else mergedState

        // 三条硬规则：不属于状态，属于表达纪律（群聊也给）。
        // 害羞时要"别用感叹号"——但只能改这一行（易变段），**绝不能去改 system**。
        // 群聊里回退常量版：群聊不注入状态，别让"她在单聊害羞"的去禁群里的感叹号。
        val hardRules = if (conv.isGroup) {
            "【硬规则】这一轮最多一个情绪词、最多一个感叹号；" +
                "不要连着两轮只附和（嗯/对/是啊/确实）。"
        } else {
            com.leaf.vixi.state.Explain.hardRules(selfState)
        }

        // 缄口（X2）：这一轮要不要说 —— 在 runTurn 里重算一次（enqueue 的那个不在这个作用域）
        val withholdAct = com.leaf.vixi.state.Withhold.decide(
            selfState, batch.joinToString(" ") { it.text }, conv.isGroup,
        )

        // 交叉层·抽身（第十二节）：**不回，也是合法的**
        val silenceAct = com.leaf.vixi.state.Silence.decide(
            selfState,
            com.leaf.vixi.state.Decay.hourOf(System.currentTimeMillis()).toInt(),
            kotlin.random.Random.nextDouble(),
        )
        if (silenceAct == com.leaf.vixi.state.Silence.Act.SILENT) {
            // 不发消息，但状态照写；给用户一句提示，免得以为坏了。
            // ⚠️ 不能在这里把 turnRunning 置 false：那会让"还在循环里"的
            // startTurnLoop 与新消息开出的第二个循环同时跑 → 同一句话被回两次。
            // 收尾统一由 startTurnLoop 的 finally 负责。
            recordStateEvents(listOf(PendingEvent(StateEventType.HER_SILENT, "这轮没回")))
            // 这批气泡要从"发送中"落定，否则看起来像卡住了
            markStatus(bubbleIds, SendStatus.NO_REPLY)
            // 走了也算一轮：不记的话"不回"的轮次在轮数统计里凭空消失
            afterTurn()
            _state.update { it.copy(notice = "她这轮没回（不是掉线）") }
            return
        }
        if (silenceAct == com.leaf.vixi.state.Silence.Act.DELAY) {
            // **有排队消息时不拖**：连发三条时每轮都重抽一次"晚点回"，静默会叠成几分钟。
            // 只有"下一句还没来"的时候才真的晚点。
            if (pending.isEmpty()) {
                // "晚点回"不该显示"正在输入"：先熄灯，等真开始写再亮。
                // 但**必须给一句话** —— 15 秒黑屏和卡死在用户眼里是同一个东西。
                _state.update {
                    it.copy(
                        typing = false,
                        notice = "她晚点回（约 " + com.leaf.vixi.state.Silence.DELAY_SECONDS + " 秒）",
                    )
                }
                delay(com.leaf.vixi.state.Silence.DELAY_SECONDS * 1000L)
                _state.update { it.copy(typing = true) }
            }
        }

        val prompt = builder.build(
            history = inTopic,
            incoming = effective,
            momentLine = momentLine(),
            // 测试环境不注入纠正记录：那是长期记忆的一部分
            corrections = if (settings.sandbox) emptyList() else correctionsCache,
            images = imagePayload,
            retrieved = retrieved,
            selfName = fallbackName(conv),
            members = groupNames,
            historyLimit = if (conv.isGroup) PromptBuilder.GROUP_HISTORY_LIMIT else PromptBuilder.HISTORY_LIMIT,
            selfStateLine = mergedSelfLine,
            // 抽身/改口：都只加一两行，不额外调用
            silenceLines = if (conv.isGroup) "" else com.leaf.vixi.state.Silence.line(silenceAct),
            correctionLines = if (conv.isGroup) "" else com.leaf.vixi.state.Explain.correctionLine(selfState),
            initiativeLine = initiativeLine,
            // 她能做的两个动作（引用他刚说的那句 / 发一张贴纸）：
            // 只有贴纸目录里有图时才给【可以发的图】；引用一直允许（她总要能指回某句）
            actLines = actLines,
            factLines = factLines,
            needLines = needLines,
            // 群聊不注入：不想念"群"（也避免它成为群里唯一的状态行）
            missingLines = if (conv.isGroup) "" else com.leaf.vixi.state.Explain.missingLine(selfState),
            // 状态里已经写过这一轮挑的应对（enqueue 那批事件早于 runTurn）
            moveLines = if (conv.isGroup) "" else com.leaf.vixi.state.Explain.strategyLine(selfState),
            hardRules = hardRules,
        )

        val startedAt = SystemClock.elapsedRealtime()
        // 联网是**单轮开关**：这一轮用掉就关（服务端工具会忽略 temperature，不该常开）
        val doSearch = _state.value.searchNext
        if (doSearch) _state.update { it.copy(searchNext = false) }
        var recallInfo = ""
        if (recallQuery.isNotBlank() && recallQuery != "旧检索（索引未就绪）") {
            recallInfo = "检索「${recallQuery.take(24)}」→ 命中 ${retrieved.size} 条"
        } else if (recallQuery.isNotBlank()) {
            recallInfo = "旧检索（索引未就绪）→ 命中 ${retrieved.size} 条"
        }
        // ⚠️ 这里**不再**写 recall.log：真正的日志在检索那一刻已经写过一次
        //（带真实查询 + 判定明细 + 候选）。这里再写一遍会把 400 行窗口占掉一半，
        // 而且那条记录的"查询"是诊断串 —— 既污染日志、又让每轮多一次全文件读写。

        val result: LlmResult = try {
            kotlinx.coroutines.withTimeout(TURN_TIMEOUT_MS) { llm.chat(prompt, webSearch = doSearch) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            // 超时不能当成"用户取消"静默吞掉：气泡要标失败、要给重发入口
            val msg = "她想了太久（超过 " + (TURN_TIMEOUT_MS / 1000) + " 秒），这轮算超时"
            logRequest(prompt.system, prompt.user, "", emptyList(), null, TURN_TIMEOUT_MS, msg)
            _state.update { it.copy(error = msg) }
            failTurn(batch, bubbleIds)
            return
        } catch (e: LlmException) {            logRequest(prompt.system, prompt.user, "", emptyList(), null, 0, e.message)
            _state.update { it.copy(error = e.message) }
            failTurn(batch, bubbleIds)
            return
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = "网络不通：${e.message?.take(80)}"
            logRequest(prompt.system, prompt.user, "", emptyList(), null, 0, msg)
            _state.update { it.copy(error = msg) }
            failTurn(batch, bubbleIds)
            return
        }
        val elapsedMs = SystemClock.elapsedRealtime() - startedAt

        // 请求成功：这批气泡从"发送中"变"已发送"，并清掉它们的失败记录
        markStatus(bubbleIds, SendStatus.SENT)
        failedTurns.removeAll { t -> t.ids.any { it in bubbleIds } }

        // ── 切气泡：单聊一个人切条，群聊先按「名字：」认出说话人再切条 ──
        // 一次调用里模型输出 1~3 句「名字：内容」，本地切不动就整体归会话创建者（见 GroupReplyParser）
        val spoken: List<Spoken> = if (conv.isGroup) {
            GroupReplyParser.parse(result.text, groupNames, fallbackName(conv))
        } else {
            ReplySplitter.split(result.text).map { Spoken(null, it) }
        }
        // 她这一句里可能夹带动作：`[引用:原文]` / `[图:文件名]`（本地剥，不额外调用）。
        // 剥完再往下走：气泡正文、通知、落库、索引都不该看到标签。
        // 引用要**节流**：模型太爱引（用户 2026-11-25 反馈"用得太频繁"）。
        // 最近 6 条里她已经引用过 → 这一轮直接丢掉引用（正文照旧），不用再问模型。
        val quotedRecently = _state.value.bubbles.takeLast(6).any { b -> !b.self && b.quote != null }
        val acted: List<Spoken> = com.leaf.vixi.llm.HerActParser.enrichAll(spoken)
            .map { if (quotedRecently) it.copy(quote = null) else it }

        // 她这一轮带出的事实：记到**说那句话的人**头上（群聊里不能都记给会话创建者）
        if (acted.any { it.fact != null }) {
            val byName = membersOf(conv).associate { it.name to it.id }
            val fallbackId = conv.memberIds.firstOrNull()
            acted.forEach { s ->
                val f = s.fact ?: return@forEach
                val id = s.speaker?.let(byName::get) ?: fallbackId
                if (id != null) {
                    runCatching { com.leaf.vixi.App.instance.facts.add(id, f, conv.id) }
                }
            }
        }
        // 通知里要能看出是谁说的（群里只写一句正文，会分不清哪个人）
        val idByName = membersOf(conv).associate { it.name to it.id }
        val replies = acted.map { s ->
            if (conv.isGroup && s.speaker != null) "${s.speaker}：${s.text}" else s.text
        }

        // 通知要在"演出"之前发：App 在后台时 Activity 可能被系统销毁 → ViewModel 被清 →
        // 协程取消，若放在整段气泡演完之后，通知就永远发不出去（回复却已经落库）。
        runCatching {
            com.leaf.vixi.Notifier.notifyReply(App.instance, replies.firstOrNull().orEmpty(), conv.title)
        }
        // 保活**不在这里收**：队列里可能还有下一批（连发两条时第二条会裸跑）。
        // 统一由 startTurnLoop 的 finally 收。

        acted.forEachIndexed { index, line ->
            if (index > 0) delay(ReplySplitter.betweenBubblesMillis())
            delay(ReplySplitter.typingDelayMillis(line.text))
            // 单聊也要写 speakerId：导出/归档靠它认人，否则换角色后整篇都标成「鱼」
            val speakerId = line.speaker?.let(idByName::get) ?: conv.memberIds.firstOrNull()
            // 她要发图：把贴纸复制进 files/images（气泡、导出、删记录都只认那一条路径）
            val imgName = line.image?.let { name ->
                stickers.materialize(name, System.currentTimeMillis())
            }
            val bubble = Bubble(
                id = nextId++,
                self = false,
                text = line.text,
                speakerId = speakerId,
                quote = line.quote,
                kind = if (imgName != null) MsgKind.IMAGE else MsgKind.TEXT,
                images = listOfNotNull(imgName),
            )
            _state.update {
                it.copy(typing = false, bubbles = it.bubbles + bubble)
            }
            turnLog += Turn(self = true, text = line.text, ts = bubble.ts, speaker = line.speaker)
            // 她的回复也算进话题条数，否则归档出来的 messageCount 只有你的一半
            topicCount += 1
            viewModelScope.launch(io) {
                store.append(
                    StoredMessage(
                        fromUser = false,
                        text = line.text,
                        ts = bubble.ts,
                        kind = bubble.kind,
                        images = bubble.images,
                        quote = line.quote,
                        speakerId = speakerId,
                    )
                )
            }
            // 她说的话也进索引（重要度打九折：自我权重更低）
            appendIndex(fromUser = false, text = line.text, label = selfState.lastLabel, ts = bubble.ts)
        }
        // 上一个话题的尾巴已经不再注入，这里不用清理任何东西

        // —— 她的回复也进状态（解析回复对状态的那一半）——
        if (selfState.enabled) {
            val total = spoken.joinToString("") { it.text }
            val batch = mutableListOf(PendingEvent(StateEventType.HER_REPLY))
            if (total.length <= SHORT_REPLY_CHARS) {
                batch += PendingEvent(StateEventType.HER_SHORT, "只回了 ${total.length} 个字")
            }
            // 说得多 = 憋着的话说出来了，暗流放掉一块
            if (total.length >= 25) {
                batch += PendingEvent(StateEventType.HER_LONG, "说了 ${total.length} 个字")
            }
            if (total.contains('？') || total.contains('?')) {
                batch += PendingEvent(StateEventType.HER_ASK)
            }
            // 爆发这一轮**回复真的落库了**才扣需要：请求失败/超时不该消耗掉一次爆发，
            // 否则弱网下会出现"她一句话没说，机会没了"，重试时也不再给。
            pendingBurstNote?.let { note ->
                batch += PendingEvent(StateEventType.NEED_BURST, note = note)
            }
            // 缄口的代价：咽下去了（回复成功）才压，沉默/失败不压
            if (selfState.enabled && withholdAct != com.leaf.vixi.state.Withhold.Act.TELL) {
                batch += PendingEvent(StateEventType.WITHHOLD_COST, withholdAct.zh)
            }
            // 憋不住的那一轮：她说完了，暗流一次性放掉一大半
            // 只在她**确实说了不少**时才放掉：顶格那轮模型只回个"嗯"，不该算倾诉
            if (ventingNow && total.length >= SHORT_REPLY_CHARS * 3) {
                batch += PendingEvent(StateEventType.VENT, "憋不住一次说完了（${total.length} 字）")
            } else if (ventingNow) {
                // 顶格那轮只回了很短一句：不能一点都不放，否则暗流会永远卡在 0.95+，
                // 而每轮提示词都在喊"憋不住"——状态和表达长期脱钩
                batch += PendingEvent(StateEventType.HER_SILENT, "憋着但没说出来")
            }
            recordStateEvents(batch)
        }

        _state.update {
            it.copy(
                typing = false,
                debugText = dump(
                    batch = batch,
                    historyCount = inTopic.size,
                    prompt = prompt.system to prompt.user,
                    result = result,
                    raw = result.text,
                    replies = replies,
                    elapsedMs = elapsedMs,
                    recallInfo = recallInfo,
                    recallQuery = recallQuery,
                    retrieved = retrieved,
                    recallRounds = recallRounds,
                    selfStateLine = selfStateLine,
                ),
                lastTurn = lastTurnOf(
                    batch = batch,
                    historyCount = inTopic.size,
                    prompt = prompt.system to prompt.user,
                    result = result,
                    replies = replies.size,
                    elapsedMs = elapsedMs,
                    recallInfo = recallInfo,
                    selfStateLine = selfStateLine,
                ),
            )
        }

        logRequest(
            system = prompt.system,
            user = prompt.user,
            raw = result.text,
            replies = replies,
            result = result,
            elapsedMs = elapsedMs,
            error = null,
            recallQuery = recallQuery,
            retrieved = retrieved,
            recallRounds = recallRounds,
        )

        afterTurn()
    }

    /** 这一轮失败：气泡标红可重发 */
    private fun failTurn(batch: List<Incoming>, ids: List<Long>) {
        markStatus(ids, SendStatus.FAILED)
        failedTurns.removeAll { t -> t.ids.any { it in ids } }
        failedTurns += FailedTurn(conv.id, batch, ids)
        // 失败也不收保活：消息还在 pending 里，紧接着可能就自愈重跑一次
    }

    /** 轮次计数落盘（统计/节流用），不再做摘要 —— 旧事一律走检索原文 */
    private fun afterTurn() {
        turnCounter += 1
        val snapshot = turnCounter
        viewModelScope.launch(io) { memory.setTurns(snapshot) }
    }

    /**
     * 检索诊断落盘：真机上 logcat 拿不到，只能写文件看（run-as 可读）。
     *
     * v2 会把**判定明细**和**前四名候选**一起写进去 —— 出问题时能复查"为什么没命中"，
     * 而不是只能看调试页的最后一条。
     */
    private suspend fun logRecall(
        query: String,
        snippets: List<String>,
        detail: String = "",
        candidates: List<String> = emptyList(),
    ) {
        if (query.isBlank()) return
        withContext(io) {
            runCatching {
                val f = java.io.File(getApplication<android.app.Application>().filesDir, "recall.log")
                val line = buildString {
                    append(java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.CHINA)
                        .format(java.util.Date()))
                    append(" q=「").append(query.replace("\n", " ").take(30)).append("」")
                    append(" hits=").append(snippets.size)
                    if (detail.isNotBlank()) append(" | ").append(detail)
                    if (snippets.isNotEmpty()) {
                        append(" → ").append(snippets.first().replace("\n", " ").take(60))
                    }
                    candidates.forEach { append('\n').append(it) }
                    append('\n')
                }
                f.appendText(line, Charsets.UTF_8)
                val lines = f.readLines(Charsets.UTF_8)
                if (lines.size > 400) {
                    f.writeText(lines.takeLast(400).joinToString("\n") + "\n", Charsets.UTF_8)
                }
            }
        }
    }

    /** 规则识别"你在纠正她"，不做语义理解，误判成本很低 */
    private fun looksLikeCorrection(text: String): Boolean =
        CORRECTION_HINTS.any { text.contains(it) }

    /** 每次真的发出去的请求都留一份，供"请求记录"页检查 */
    private fun logRequest(
        system: String,
        user: String,
        raw: String,
        replies: List<String>,
        result: LlmResult?,
        elapsedMs: Long,
        error: String?,
        recallQuery: String = "",
        retrieved: List<String> = emptyList(),
        recallRounds: List<String> = emptyList(),
    ) {
        val record = RequestRecord(
            index = ++requestSeq,
            at = System.currentTimeMillis(),
            system = system,
            user = user,
            raw = raw,
            replies = replies,
            promptTokens = result?.promptTokens ?: 0,
            completionTokens = result?.completionTokens ?: 0,
            model = result?.model ?: settings.model,
            recallQuery = recallQuery,
            retrieved = retrieved,
            elapsedMs = elapsedMs,
            error = error,
        )
        _state.update { it.copy(requests = (listOf(record) + it.requests).take(REQUEST_LOG_MAX)) }
    }

    /**
     * 上一轮的结构化摘要（调试页按卡片显示）。
     *
     * 和 [dump] 取的是同一批事实，只是一个给人看、一个给"复制出去查"。
     */
    /**
     * 给老记录补一份索引（只在索引不存在时跑一次）。
     *
     * 为什么需要：索引是这一版才加的，历史消息没有元数据 → 记忆地图会是空的。
     * 补的时候**用现存的文本重新跑一遍规则**（评价层给利害/强度当近似），
     * 不调用模型、不改原文、不生成摘要。
     */
    private fun backfillIndex(messages: List<StoredMessage>) {
        if (settings.sandbox || messages.isEmpty()) return
        if (indexStore.read().isNotEmpty()) return
        val segments = com.leaf.vixi.data.TopicExporter.segments(messages)
        val topicOf = HashMap<Long, Long>()
        segments.forEach { seg ->
            val start = seg.first().ts
            seg.forEach { topicOf[it.ts] = start }
        }
        runCatching {
            messages.sortedBy { it.ts }.forEach { m ->
                if (m.text.isBlank()) return@forEach
                val appr = com.leaf.vixi.state.Appraiser.appraise(m.text)
                val aa = (0.3 + 0.6 * appr.intensity).coerceIn(0.0, 1.0)
                val verdict = com.leaf.vixi.state.Labeler.judge(appr, selfState.closeness, selfState.trust)
                indexStore.append(
                    IndexEntry(
                        ts = m.ts,
                        topicId = topicOf[m.ts] ?: 0L,
                        fromUser = m.fromUser,
                        speakerId = m.speakerId,
                        importance = MemoryIndex.importance(aa, appr.impact, selfState.trust, m.fromUser),
                        label = if (verdict.label == com.leaf.vixi.state.EmotionLabel.CALM) "平静" else verdict.label.zh,
                        a = aa, p = appr.impact, d = selfState.d,
                        trust = selfState.trust, closeness = selfState.closeness,
                        conflict = selfState.conflict, backlog = selfState.backlog,
                        mood = selfState.mood,
                        entities = MemoryIndex.entities(m.text, membersOf(conv).map { it.name }),
                        source = if (m.fromUser) "user" else "her",
                        confidence = MemoryIndex.confidenceOf(m.fromUser, backfilled = true),
                        pinned = MemoryIndex.isPinned(m.text),
                    )
                )
            }
        }
    }

    /**
     * 写一条记忆索引（**不存正文**）：重要度按第一章公式，实体给"线索通道"用。
     *
     * 测试环境不写：那是"不记忆"的隔离环境。
     */
    private fun appendIndex(fromUser: Boolean, text: String, label: String, ts: Long) {
        if (settings.sandbox) return
        runCatching {
            val s = selfState
            val names = membersOf(conv).map { it.name } + chars().map { it.name }
            indexStore.append(
                IndexEntry(
                    ts = ts,
                    topicId = if (topicId > 0L) topicId else 0L,
                    fromUser = fromUser,
                    speakerId = if (fromUser) null else conv.memberIds.firstOrNull(),
                    importance = MemoryIndex.importance(s.a, s.p, s.trust, fromUser),
                    label = label.ifBlank { s.lastLabel.ifBlank { "平静" } },
                    a = s.a, p = s.p, d = s.d,
                    trust = s.trust, closeness = s.closeness,
                    conflict = s.conflict, backlog = s.backlog, mood = s.mood,
                    entities = MemoryIndex.entities(text, names.distinct()),
                    source = if (fromUser) "user" else "her",
                    confidence = MemoryIndex.confidenceOf(fromUser),
                    pinned = MemoryIndex.isPinned(text),
                )
            )
        }
    }
    /** 情绪事件的 note：`生气·negative·你真蠢`（标签 + 命中的事件类型 + 原文），便于回溯 */
    private fun emotionNote(label: String, appr: com.leaf.vixi.state.Appraisal, text: String): String =
        buildString {
            append(label)
            if (appr.events.isNotEmpty()) {
                append('·')
                append(appr.events.joinToString("/") { it.key })
            }
            append('·')
            append(text.replace("\n", " ").take(12))
        }

    private fun lastTurnOf(
        batch: List<Incoming>,
        historyCount: Int,
        prompt: Pair<String, String>,
        result: LlmResult,
        replies: Int,
        elapsedMs: Long,
        recallInfo: String,
        selfStateLine: String,
    ): DebugLastTurn {
        val (system, user) = prompt
        return DebugLastTurn(
            at = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date()),
            baseUrl = settings.baseUrl,
            model = result.model.ifBlank { settings.model },
            keyMask = settings.maskedKey(),
            conversation = "${conv.title}（${if (conv.isGroup) "群聊" else "单聊"} · ${conv.memberIds.size} 人）",
            personaChars = personaOf(conv).length,
            systemChars = system.length,
            systemTokens = (system.length * 0.67).toInt(),
            historyCount = historyCount,
            historyWindow = if (conv.isGroup) PromptBuilder.GROUP_HISTORY_LIMIT else PromptBuilder.HISTORY_LIMIT,
            newMessages = batch.map { it.rendered },
            imageCount = batch.sumOf { it.images.size },
            userChars = user.length,
            userTokens = (user.length * 0.67).toInt(),
            elapsedMs = elapsedMs,
            recallInfo = recallInfo.ifBlank { "未触发（本地闸门没过）" },
            completionTokens = result.completionTokens,
            replies = replies,
            stateLine = selfStateLine,
        )
    }

    private fun dump(        batch: List<Incoming>,
        historyCount: Int,
        prompt: Pair<String, String>,
        result: LlmResult,
        raw: String,
        replies: List<String>,
        elapsedMs: Long,
        recallInfo: String = "",
        recallQuery: String = "",
        retrieved: List<String> = emptyList(),
        recallRounds: List<String> = emptyList(),
        /** 这一轮实际注入的状态行（空 = 没开/群聊不注入） */
        selfStateLine: String = "",
    ): String {
        val (system, user) = prompt
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())

        return buildString {
            appendLine("=== 鱼 M1 · 内部状态 dump ===")
            appendLine("时间 $now")
            appendLine()
            appendLine("--- 环境 ---")
            appendLine("baseUrl  ${settings.baseUrl}")
            appendLine("model    ${settings.model}")
            appendLine("key      ${settings.maskedKey()}")
            appendLine("会话     ${conv.title}（${if (conv.isGroup) "群聊" else "单聊"} · ${conv.memberIds.size} 人）")
            appendLine()
            appendLine("--- 稳定前缀（缓存的地基）---")
            appendLine("人设     ${personaOf(conv).length} 字" + if (conv.isGroup) "（每人一份，共 ${membersOf(conv).size} 份）" else "")
            appendLine("system   ${system.length} 字 ≈ ${(system.length * 0.67).toInt()} tok")
            appendLine("（原文在「请求记录」页，逐字可查）")
            appendLine()
            appendLine("--- 上一轮 ---")
            appendLine("历史     ${historyCount} 条（window=${PromptBuilder.HISTORY_LIMIT}，更早的没带）")
            appendLine("新消息   ${batch.size} 条「${batch.joinToString(" / ") { it.rendered }}」")
            appendLine("图片     ${batch.sumOf { it.images.size }} 张随本轮发送")
            appendLine("user 段  ${user.length} 字 ≈ ${(user.length * 0.67).toInt()} tok")
            appendLine("记忆检索 ${recallInfo.ifBlank { "未触发（本地闸门没过）" }}")
            if (recallRounds.isNotEmpty()) {
                appendLine("检索轮次 ${recallRounds.size} 轮：" + recallRounds.joinToString(" ｜ "))
            }
            if (retrieved.isNotEmpty()) {
                appendLine()
                appendLine("--- 她在这轮看到的旧事（原样，共 ${retrieved.size} 条）---")
                retrieved.forEach { appendLine("· $it") }
            } else if (recallQuery.isNotBlank()) {
                appendLine()
                appendLine("--- 检索了但没命中 ---")
                appendLine("查询串：$recallQuery")
                appendLine("（她会按「没有记忆」来回话）")
            }
            appendLine("耗时     ${elapsedMs} ms")
            appendLine()
            appendLine("--- 状态底座（数值只在这里出现，不进 prompt）---")
            appendLine(stateDebugText(stateCharId() ?: "c1", selfState))
            appendLine(
                "注入     " + if (selfStateLine.isBlank()) "（本会话不注入）" else selfStateLine
            )
            appendLine()
            appendLine("--- 模型原始返回（${raw.length} 字）---")
            appendLine(raw.ifBlank { "（空）" })
            appendLine()
            appendLine("--- 切条结果 ${replies.size} 条 ---")
            replies.forEachIndexed { i, s -> appendLine("[${i + 1}] $s") }
            appendLine()
            appendLine("--- user 段原文 ---")
            append(user)
        }
    }

    /**
     * 【现在】只给事实：几点 + 一天里的哪一段。
     *
     * 刻意不写"困/精神好/懒得说整句"这类感受 —— 那是替她宣布身体状态，
     * 模型会照着演一个并不存在的状态（和已删掉的情绪系统是同一类问题）。
     * 她想不想睡、累不累，应该由对话本身决定，不由时钟预设。
     */
    private fun momentLine(): String {
        val now = LocalTime.now()
        val h = now.hour
        val phase = when (h) {
            in 0..4 -> "凌晨"
            in 5..7 -> "清早"
            in 8..11 -> "上午"
            in 12..13 -> "中午"
            in 14..17 -> "下午"
            in 18..19 -> "傍晚"
            in 20..22 -> "晚上"
            else -> "深夜"
        }
        return "%02d:%02d，%s".format(h, now.minute, phase)
    }

    private companion object {
        /**
         * 合并窗口：**停手 2 秒**才发出去（把连着的几条合并成一次请求）。
         * 注意：这个值只影响"什么时候开始请求"，不影响气泡出现的时间（气泡立刻就显示）。
         */
        const val SEND_IDLE_MS = 2000L

        /** 因为"还在打字"最多推迟多久（防止人走开，那条消息永远发不出去） */
        const val MAX_DEFER_MS = 30_000L

        /** 一轮请求的硬上限：超过就按超时失败，绝不让 typing 一直亮着 */
        const val TURN_TIMEOUT_MS = 100_000L
        const val REQUEST_LOG_MAX = 12

        /** 最多保留几条纠正记录 */
        const val CORRECTION_KEEP = 8

        val CORRECTION_HINTS = listOf(
            "不对", "不是的", "我说的是", "你记错", "搞错", "不是这样", "记岔了", "你错了",
        )

        /** 回复总字数 ≤ 这个数，算"回得很短"（话没说尽 → 暗流 +0.01） */
        const val SHORT_REPLY_CHARS = 6
    }
}
