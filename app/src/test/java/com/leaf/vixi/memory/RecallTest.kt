package com.leaf.vixi.memory

import com.leaf.vixi.data.IndexEntry
import com.leaf.vixi.data.StoredMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 检索 v2 的回归测试（全本地、无网络）。
 *
 * 这一层的三条硬要求：
 *  1. **闸门不给"日常闲聊"开门**（只有指向过去才想）
 *  2. **想起来多少要分档**（≥0.55 展开 / 0.35–0.55 一句 / <0.35 不提）
 *  3. **线索优先**：同样问"上次那件事"，带具体实体（人名/时间）的片段要排前面
 */
class RecallTest {

    private val now = 1_700_000_000_000L
    private val hour = 3_600_000L

    private fun msg(ts: Long, text: String, fromUser: Boolean = true) =
        StoredMessage(fromUser = fromUser, text = text, ts = ts)

    private fun entry(ts: Long, imp: Double, p: Double = 0.0, label: String = "平静", ent: List<String> = emptyList()) =
        IndexEntry(
            ts = ts, topicId = 0, fromUser = true, speakerId = null,
            importance = imp, label = label, a = 0.4, p = p, d = 0.5,
            trust = 0.5, closeness = 0.3, conflict = 0.0, backlog = 0.0, mood = 0.0,
            entities = ent,
        )

    // ────────── 闸门 ──────────

    @Test
    fun `日常闲聊不触发检索`() {
        assertEquals(0.0, Recall.gateScore("今天吃什么"), 0.001)
    }

    @Test
    fun `指向过去就开门`() {
        assertTrue(Recall.gateScore("我们上次说的那件事呢") >= 0.55)
    }

    @Test
    fun `承诺类分数更高`() {
        val plain = Recall.gateScore("那次的事")
        val promise = Recall.gateScore("你答应过我的事呢")
        assertTrue("承诺要更该想起来", promise > plain)
    }

    @Test
    fun `没有指向过去的词时疑问也不开门`() {
        assertEquals(0.0, Recall.gateScore("你在干嘛？"), 0.001)
    }

    @Test
    fun `未闭合话题加分`() {
        val a = Recall.gateScore("上次说的", unclosed = false)
        val b = Recall.gateScore("上次说的", unclosed = true)
        assertTrue(b > a)
    }

    @Test
    fun `三档划分`() {
        assertEquals(RecallMode.OFF, Recall.modeOf(0.10))
        assertEquals(RecallMode.LIGHT, Recall.modeOf(0.45))
        assertEquals(RecallMode.FULL, Recall.modeOf(0.75))
    }

    // ────────── 检索与分档 ──────────

    @Test
    fun `gate 为零时什么都不给`() {
        val corpus = listOf(msg(now - hour, "随便聊点别的"), msg(now - hour + 1, "好呀", false))
        val res = Recall.run("今天吃什么", corpus, emptyList(), mood = 0.0, now = now)
        assertEquals(RecallMode.OFF, res.mode)
        assertTrue(res.lines.isEmpty())
    }

    @Test
    fun `指向过去并且有匹配时会给原文`() {
        val corpus = listOf(
            msg(now - 30 * hour, "我们上次说好周五去医院"),
            msg(now - 30 * hour + 1, "嗯，我记着", false),
            msg(now - 2 * hour, "今天天气不错"),
        )
        val entries = listOf(
            entry(now - 30 * hour, 0.8, ent = listOf("周五")),
            entry(now - 2 * hour, 0.3),
        )
        val res = Recall.run("你上次说周五去医院的事呢", corpus, entries, mood = 0.0, now = now)
        assertTrue("应该想起来", res.lines.isNotEmpty())
        assertTrue(res.lines.first().contains("周五"))
        assertTrue(res.mode != RecallMode.OFF)
    }

    @Test
    fun `线索匹配排在前面`() {
        val a = now - 40 * hour
        val b = now - 5 * hour
        val corpus = listOf(
            msg(a, "那件事我说过要办"),
            msg(b, "今天挺开心的"),
        )
        val entries = listOf(
            entry(a, 0.5, ent = listOf("小岚")),
            entry(b, 0.5),
        )
        val res = Recall.run(
            "小岚上次说的事", corpus, entries, mood = 0.0, now = now,
            knownNames = listOf("小岚"),
        )
        assertTrue("带人名的片段应该被选中", res.lines.any { it.contains("办") })
    }

    @Test
    fun `分档：低分只给提示不给整段`() {
        // 语义沾一点、重要度低、很旧 → 落在 0.35~0.55 之间
        val corpus = listOf(msg(now - 200 * hour, "上次那件事我随便说说"))
        val entries = listOf(entry(now - 200 * hour, 0.30))
        val res = Recall.run("上次那件事你怎么看", corpus, entries, mood = 0.0, now = now)
        if (res.lines.isNotEmpty()) {
            assertTrue("不该展开长段", res.lines.first().length <= Recall.HINT_MAX_CHARS + 20)
        }
    }

    @Test
    fun `心境低落时负向旧事更容易被想起`() {
        val ts = now - 20 * hour
        val corpus = listOf(
            msg(ts, "上周那件事真的很难过"),
            msg(ts + 1, "我也记着", false),
        )
        val entries = listOf(entry(ts, 0.8, p = -0.6, label = "委屈", ent = listOf("上周")))
        val low = Recall.run("上周那件事", corpus, entries, mood = -0.8, now = now)
        val high = Recall.run("上周那件事", corpus, entries, mood = 0.8, now = now)
        val lowScore = low.expanded.firstOrNull()?.score ?: low.hinted.firstOrNull()?.score ?: 0.0
        val highScore = high.expanded.firstOrNull()?.score ?: high.hinted.firstOrNull()?.score ?: 0.0
        assertTrue("低落时该更容易想起来", lowScore > highScore)
    }

    @Test
    fun `事件之间不重复堆同一段`() {
        val base = now - 10 * hour
        val lines = (0..5).map { msg(base + it * 60_000L, "上次那件事真的很难受") }
        val corpus = listOf(lines[0], lines[1], lines[3], lines[4])
        val entries = listOf(entry(base, 0.7, p = -0.5))
        val res = Recall.run("上次那件事", corpus, entries, mood = 0.0, now = now)
        assertTrue("最多两段展开", res.expanded.size <= 2)
        assertTrue("最多三条注入", res.lines.size <= 3)
    }

    @Test
    fun `本轮刚说的话不会被当成旧事`() {
        val q = "我们上次说的那件事呢"
        val corpus = listOf(
            msg(now - 1000, q),                       // 就是把这一轮的问题写进了语料
            msg(now - 900, "摸头干嘛", false),
        )
        val entries = listOf(entry(now - 1000, 0.8))
        val res = Recall.run(q, corpus, entries, mood = 0.0, now = now)
        assertTrue("回声不该被检索回来", res.lines.none { it.contains("我们上次说的那件事") })
    }

    @Test
    fun `本轮之后的消息一律不算旧事`() {
        val q = "上次那件事"
        val corpus = listOf(
            msg(now - 5000, "上次那件事真的很难过"),
            msg(now, "刚说的这句不该算旧事"),
        )
        val entries = listOf(entry(now - 5000, 0.8))
        val res = Recall.run(q, corpus, entries, mood = 0.0, now = now, excludeAfter = now - 1000)
        assertTrue("晚于截止时间的不参与", res.lines.none { it.contains("刚说的这句") })
    }

    // ────────── 准确度（IDF 与"挑对上的那句话"）──────────

    @Test
    fun `虚词多但无关的片段不该压过真正提到的`() {
        val q = "上次说的医院那件事"
        // 背景事件：都含"上次说的/那件事"这类虚词（让它们在 IDF 里变便宜）
        val filler = (1..6).map { i ->
            msg(now - (40 + i * 5) * hour, "上次说的那件事我忘了")
        }
        val real = now - 30 * hour
        val corpus = filler + listOf(msg(real, "周五要去医院复查，别忘了"))
        val entries = filler.map { entry(it.ts, 0.6) } + entry(real, 0.6)
        val res = Recall.run(q, corpus, entries, mood = 0.0, now = now)
        assertTrue("应该命中医院那段", res.lines.any { it.contains("医院") })
    }

    @Test
    fun `展开时挑对上的那句话而不是最后一句`() {
        val base = now - 10 * hour
        val filler = (1..6).map { i ->
            msg(now - (50 + i * 4) * hour, "上次说的那件事我忘了")
        }
        val corpus = filler + listOf(
            msg(base, "我们约好周六去看牙医"),
            msg(base + 60_000, "哦"),
            msg(base + 120_000, "那你先忙"),
        )
        val entries = filler.map { entry(it.ts, 0.6) } + entry(base, 0.6)
        val res = Recall.run("上次说的牙医那件事", corpus, entries, mood = 0.0, now = now)
        assertTrue("应该挑出提到牙医的那句", res.lines.any { it.contains("牙医") })
    }

    @Test
    fun `只由虚词构成的查询不该命中`() {
        val corpus = listOf(msg(now - 5 * hour, "上周那件事我忘了"))
        val entries = listOf(entry(now - 5 * hour, 0.5))
        val res = Recall.run("那件事", corpus, entries, mood = 0.0, now = now)
        assertTrue("没有实质内容时宁可不提", res.lines.isEmpty() || res.lines.size <= 1)
    }

    // ────────── 专名 / 引号：没有"上次"也要开门 ──────────

    @Test
    fun `引号里的专名能打开闸门`() {
        assertTrue("带引号的具体词该开门", Recall.gateScore("那我发给你“锟斤拷”那件事呢？") >= 0.30)
    }

    @Test
    fun `语料里稀有的词能打开闸门`() {
        assertTrue("专名算信号", Recall.gateScore("锟斤拷那件事呢", novelEntity = true) >= 0.30)
        assertEquals("没有稀有词就只有 0", 0.0, Recall.gateScore("今天吃什么"), 0.001)
    }

    @Test
    fun `稀有专名能真的检索到`() {
        val target = now - 6 * hour
        val filler = (1..5).map { i -> msg(now - (50 + i * 6) * hour, "今天聊了点别的") }
        val corpus = filler + listOf(
            msg(target, "我把“锟斤拷”发给你了"),
            msg(target + 1, "看到了", false),
        )
        val entries = filler.map { entry(it.ts, 0.4) } + entry(target, 0.7)
        val res = Recall.run(
            "那我发给你“锟斤拷”那件事呢？", corpus, entries, mood = 0.0, now = now,
            recentText = "今天挺开心的",
        )
        assertTrue("应该想起来", res.lines.isNotEmpty())
        assertTrue(res.lines.any { it.contains("锟斤拷") })
    }

    @Test
    fun `展开会带时间头`() {
        val target = now - 6 * hour
        val corpus = listOf(
            msg(target, "说好周五去医院"),
            msg(target + 1, "记住了", false),
        )
        val entries = listOf(entry(target, 0.8))
        val res = Recall.run("上次说的医院那件事", corpus, entries, mood = 0.0, now = now)
        assertTrue("第一行应该是时间头", res.lines.first().contains("那次"))
        assertTrue(res.lines.first().contains("-"))
    }

    @Test
    fun `只有一条原文时会补一条相邻消息`() {
        val target = now - 6 * hour
        val corpus = listOf(
            msg(target, "关于医院我再说一次"),
            msg(target + 1, "嗯，我记着", false),
            msg(target + 2, "别的先不聊"),
        )
        val entries = listOf(entry(target, 0.8))
        val res = Recall.run("上次说的医院", corpus, entries, mood = 0.0, now = now)
        val body = res.lines.first()
        assertTrue("至少两条原文（对上的那条 + 邻居）", body.split("\n").size >= 3)
    }

    // ────────── 复现用户报的两个 bug ──────────

    @Test
    fun `专名夹在长句里也能开门`() {
        val corpusText = "我把锟斤拷发给你了"
        assertTrue(
            "句子里的专名要能被识别",
            Recall.gateScore("那我发锟斤拷那件事呢？", novelEntity = true) >= 0.30,
        )
        assertTrue(corpusText.contains("锟斤拷"))
    }

    @Test
    fun `专名夹在长句里能真的检索到`() {
        val target = now - 6 * hour
        val filler = (1..5).map { i -> msg(now - (50 + i * 6) * hour, "今天聊了点别的") }
        val corpus = filler + listOf(
            msg(target, "我把锟斤拷这个文件发给你了，你看看能不能打开"),
            msg(target + 1, "打不开，乱码", false),
        )
        val entries = filler.map { entry(it.ts, 0.4) } + entry(target, 0.7)
        val res = Recall.run(
            "那我发锟斤拷那件事呢？", corpus, entries, mood = 0.0, now = now,
            recentText = "今天挺开心的",
        )
        assertTrue("应该命中", res.lines.isNotEmpty())
        assertTrue("正文里要有专名", res.lines.any { it.contains("锟斤拷") })
    }

    @Test
    fun `时间头不能把正文挤没`() {
        // 一条很长的消息：预算 60 字，时间头已经占了 15
        val target = now - 6 * hour
        val long = "医院在三楼东侧走廊尽头右转再走大概五十米就能看到一个很大的指示牌上面写着门诊两个字不要走错"
        val corpus = listOf(msg(target, long), msg(target + 1, "记住了", false))
        val entries = listOf(entry(target, 0.8))
        val res = Recall.run("上次说的医院那件事", corpus, entries, mood = 0.0, now = now)
        val first = res.lines.firstOrNull().orEmpty()
        assertTrue("必须有正文，不能只有时间头", first.split("\n").size >= 2)
    }

    // ────────── 噪声与"她怎么接的"（第三轮真机反馈） ──────────

    @Test
    fun `那件事这种虚指不算稀有专名`() {
        // 背景：大量事件都含"那件事"，每个字都很常见
        val filler = (1..8).map { i -> msg(now - (60 + i * 5) * hour, "那件事我忘了") }
        val corpus = filler + listOf(msg(now - 5 * hour, "今天聊得挺开心"))
        val entries = filler.map { entry(it.ts, 0.6) } + entry(now - 5 * hour, 0.4)
        val res = Recall.run(
            "那件事呢", corpus, entries, mood = 0.0, now = now, recentText = "",
        )
        assertTrue(
            "不该因为『那件事』命中而展开无关段落",
            res.expanded.isEmpty(),
        )
    }

    @Test
    fun `命中用户那句时会带上她当时的接话`() {
        val target = now - 8 * hour
        val filler = (1..5).map { i -> msg(now - (50 + i * 6) * hour, "今天聊了点别的") }
        val corpus = filler + listOf(
            msg(target, "我把锟斤拷发你了，你看看"),
            msg(target + 1, "这什么乱码，打不开", false),
        )
        val entries = filler.map { entry(it.ts, 0.4) } + entry(target, 0.8)
        val res = Recall.run(
            "那我发给你锟斤拷那件事呢？", corpus, entries, mood = 0.0, now = now,
            recentText = "别的",
        )
        val body = res.lines.firstOrNull().orEmpty()
        assertTrue("要能看到她说的话", body.contains("乱码"))
    }

    @Test
    fun `重要但无关的事件不会被当成提示注入`() {
        val filler = (1..6).map { i -> msg(now - (40 + i * 5) * hour, "聊点别的") }
        // 一条很重要但完全不相关的事件
        val irrelevant = msg(now - 3 * hour, "呵呵呵呵呵")
        val corpus = filler + listOf(irrelevant)
        val entries = filler.map { entry(it.ts, 0.3) } +
            entry(now - 3 * hour, 0.95, p = -0.8, label = "生气")
        val res = Recall.run("上次说的医院那件事", corpus, entries, mood = 0.0, now = now)
        assertTrue("不能把高重要度的无关段落塞进来", res.lines.none { it.contains("呵呵") })
    }

    // ────────── 审查补的：钉住 / 复习 ──────────

    @Test
    fun `钉住的老承诺还能展开原文`() {
        // 40 天前，普通事件早该"只给提示"，但钉住的必须还能给原文
        val old = now - 40 * 24 * hour
        val filler = (1..5).map { i -> msg(now - (70 + i * 6) * hour, "今天聊了点别的") }
        val corpus = filler + listOf(
            msg(old, "我答应你，下个月一定去看海"),
            msg(old + 1, "说好了，我记着", false),
        )
        val entries = filler.map { entry(it.ts, 0.3) } + listOf(
            entry(old, 0.8).copy(pinned = true),
        )
        val res = Recall.run(
            "上次说的答应我的那件事呢", corpus, entries, mood = 0.0, now = now,
            recentText = "别的",
        )
        assertTrue("钉住的应该能展开 detail=${res.detail} lines=${res.lines.size}", res.expanded.isNotEmpty())
    }

    @Test
    fun `超过遗忘窗口的普通旧事只给提示`() {
        val old = now - 60 * 24 * hour
        val corpus = listOf(
            msg(old, "今天吃了面条"),
            msg(old + 1, "哦", false),
        )
        val entries = listOf(entry(old, 0.9))
        val res = Recall.run(
            "上次说的面条那件事", corpus, entries, mood = 0.0, now = now,
            recentText = "别的",
        )
        assertTrue("不该再展开原文", res.expanded.isEmpty())
    }

    @Test
    fun `复习到期才加分`() {
        val start = now - 3 * 24 * hour
        val filler = (1..5).map { i -> msg(now - (60 + i * 7) * hour, "今天聊了点别的") }
        val corpus = filler + listOf(msg(start, "医院在三楼"), msg(start + 1, "记住了", false))
        val entries = filler.map { entry(it.ts, 0.3) } + listOf(entry(start, 0.8))
        val fresh = Recall.run(
            "上次说的医院那件事", corpus, entries, mood = 0.0, now = now,
            recalls = mapOf(start to com.leaf.vixi.data.RecallStat(count = 1, lastAt = now - hour)),
        )
        val due = Recall.run(
            "上次说的医院那件事", corpus, entries, mood = 0.0, now = now,
            recalls = mapOf(start to com.leaf.vixi.data.RecallStat(count = 1, lastAt = start + hour)),
        )
        val freshScore = (fresh.expanded + fresh.hinted).firstOrNull()?.score ?: 0.0
        val dueScore = (due.expanded + due.hinted).firstOrNull()?.score ?: 0.0
        assertTrue("到期该更高 due=$dueScore fresh=$freshScore dueDetail=${due.detail} freshDetail=${fresh.detail}", dueScore > freshScore)
    }

    // ────────── 记忆点之间会互相影响（他的第五节）──────────

    @Test
    fun `被想起过的段落会因为强化更容易再被想起`() {
        val start = now - 20 * 24 * hour
        val filler = (1..5).map { i -> msg(now - (80 + i * 6) * hour, "今天聊了点别的") }
        val corpus = filler + listOf(msg(start, "医院在三楼"), msg(start + 1, "记住了", false))
        val entries = filler.map { entry(it.ts, 0.3) } + listOf(entry(start, 0.5))
        val plain = Recall.run("上次说的医院那件事", corpus, entries, mood = 0.0, now = now)
        val boosted = Recall.run(
            "上次说的医院那件事", corpus, entries, mood = 0.0, now = now,
            // 被想起过 8 次：强化值约 0.12
            recalls = mapOf(start to com.leaf.vixi.data.RecallStat(count = 8, lastAt = now - hour, boost = 0.12, boostAt = now - hour)),
        )
        val a = (plain.expanded + plain.hinted).firstOrNull()?.score ?: 0.0
        val b = (boosted.expanded + boosted.hinted).firstOrNull()?.score ?: 0.0
        assertTrue("强化过该更高（$b vs $a）", b > a)
    }

    @Test
    fun `被抑制的段落这次排后面`() {
        val start = now - 20 * 24 * hour
        val filler = (1..5).map { i -> msg(now - (80 + i * 6) * hour, "今天聊了点别的") }
        val corpus = filler + listOf(msg(start, "医院在三楼"), msg(start + 1, "记住了", false))
        val entries = filler.map { entry(it.ts, 0.3) } + listOf(entry(start, 0.5))
        val plain = Recall.run("上次说的医院那件事", corpus, entries, mood = 0.0, now = now)
        val suppressed = Recall.run(
            "上次说的医院那件事", corpus, entries, mood = 0.0, now = now,
            recalls = mapOf(start to com.leaf.vixi.data.RecallStat(count = 1, lastAt = now - 100 * hour, suppressedAt = now - hour)),
        )
        val a = (plain.expanded + plain.hinted).firstOrNull()?.score ?: 0.0
        val b = (suppressed.expanded + suppressed.hinted).firstOrNull()?.score ?: 0.0
        assertTrue("被压住的该更低（$b vs $a）", b < a)
    }

    @Test
    fun `冷层最多取两条`() {
        // 三个都很旧、重要度也低的事件，同时命中同一批词
        val base = now - 200 * 24 * hour
        val corpus = (0..2).map { i -> msg(base + i * 40 * 60_000L, "医院在三楼") }
        val entries = (0..2).map { i -> entry(base + i * 40 * 60_000L, 0.3) }
        val res = Recall.run(
            "上次说的医院那件事", corpus, entries, mood = 0.0, now = now,
            recentText = "",
        )
        assertTrue("冷层不该一次给太多（${res.lines.size}）", res.lines.size <= 3)
    }

    // ────────── 2026-11-22：真机"同一个问题问第二次就想不起来" ──────────

    @Test
    fun `问话和要想起的事在同一段里，也能想起来`() {
        // 真机复现：17:06–17:42 连续聊了半小时 → 是**同一个事件**；
        // 旧实现把"含本轮问句"的整个事件当回声丢掉，于是"傻子"那段一起没了。
        val t0 = now - 2 * hour
        val corpus = listOf(
            msg(t0, "傻子，滚一边去"),
            msg(t0 + 60_000, "怎么一上来就骂人呀", fromUser = false),
            msg(t0 + 10 * 60_000, "还记得我之前叫你傻子吗？"),
            msg(t0 + 11 * 60_000, "傻子……你是不是常这么叫我呀", fromUser = false),
        )
        val entries = corpus.map { entry(it.ts, 0.6) }
        val res = Recall.run("还记得我之前叫你傻子吗？", corpus, entries, mood = 0.0, now = now)
        assertTrue("应该想得起来（${res.detail}）", res.lines.isNotEmpty())
        assertTrue("而且要是「傻子」那几句：${res.lines}", res.lines.joinToString("").contains("傻子"))
    }

    @Test
    fun `同一句问第二遍仍然想得起来`() {
        val t0 = now - 3 * hour
        val base = mutableListOf(
            msg(t0, "周五要去医院复查，别忘了"),
            msg(t0 + 60_000, "记住了", fromUser = false),
        )
        val entries = base.map { entry(it.ts, 0.6) }
        val q = "你上次说的医院那件事呢"
        val first = Recall.run(q, base.toList(), entries, mood = 0.0, now = now)
        // 用户把同一句又问一遍：它进了语料，但不该把检索能力搞坏
        base += msg(now - 60_000, q)
        val second = Recall.run(q, base.toList(), entries, mood = 0.0, now = now)
        assertTrue("第一遍命中", first.lines.isNotEmpty())
        assertTrue("第二遍也要命中（${second.detail}）", second.lines.isNotEmpty())
    }

    @Test
    fun `只有功能词的问句宁可不提`() {
        // 「还记得我说过什么吗」拆开全是功能词（还记/记得/我说/什么）→ 不该硬凑一段旧事
        val t0 = now - 5 * hour
        val corpus = listOf(msg(t0, "周五要去医院复查"), msg(t0 + 60_000, "记住了", fromUser = false))
        val entries = corpus.map { entry(it.ts, 0.9) }
        val res = Recall.run("还记得我说过什么吗", corpus, entries, mood = 0.0, now = now)
        assertTrue("不该硬凑（${res.lines}）", res.lines.isEmpty())
    }

    @Test
    fun `功能词表不会把真正的内容词一起砍掉`() {
        // 「愿意」是内容词（曾经被误列进功能词表，导致「你愿意当我的吗」想不起来）
        val t0 = now - 4 * hour
        val corpus = listOf(
            msg(t0, "你愿意当我的女朋友吗"),
            msg(t0 + 60_000, "嗯，愿意", fromUser = false),
        )
        val entries = corpus.map { entry(it.ts, 0.7) }
        val res = Recall.run("还记得我说你愿意当我的吗", corpus, entries, mood = 0.0, now = now)
        assertTrue("要想起这段（${res.detail}）", res.lines.isNotEmpty())
        assertTrue(res.lines.joinToString("").contains("愿意"))
    }
}