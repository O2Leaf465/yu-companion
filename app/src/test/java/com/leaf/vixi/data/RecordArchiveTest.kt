package com.leaf.vixi.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 聊天记录档案层（导出 / 导入 / 删除）的回归测试。
 *
 * 这块是**唯一会真的删用户数据**的代码，所以测的不是"功能有没有"，而是安全性质：
 *  1. 导出必须**无损**：删干净再导回来，正文一字不差
 *  2. 导入必须**只覆盖该覆盖的**，且从正文派生的记忆不能留在原地
 *  3. 删除必须**删干净但不能多删**（角色/人设/状态/别的会话一个不能动）
 *  4. 用户可改的文本（角色名/群名/id）不能变成路径穿越或误删
 *
 * 跑在 `build/tmp` 下的临时目录，不碰手机、不碰 Context。
 * （不用 JUnit 的 TemporaryFolder：这台机器上测试进程对 %TEMP% 没有写权限。）
 */
class RecordArchiveTest {

    private lateinit var root: File

    private fun freshDir(name: String): File =
        File("build/tmp/record-archive-test/$name").apply {
            deleteRecursively()
            mkdirs()
        }

    private fun setup() {
        root = freshDir("root")
        File(root, "mem").mkdirs()
    }

    private fun write(name: String, text: String) {
        val f = File(root, name)
        f.parentFile?.mkdirs()
        f.writeText(text, Charsets.UTF_8)
    }

    /** 造一个"有聊天记录 + 有记忆"的会话 */
    private fun seedConversation(convId: String = "v1", marker: String = "hello") {
        setup()
        write("chat_$convId.jsonl", """{"ts":1000,"fromUser":true,"text":"$marker"}""" + "\n" +
            """{"ts":2000,"fromUser":false,"text":"hi","speakerId":"c1"}""" + "\n")
        write("topics_$convId.jsonl", """{"id":1000,"start":1000,"end":2000}""" + "\n")
        write("mem/current_topic_$convId.json", """{"id":1000,"startedAt":1000,"lastAt":2000}""")
        write("mem/index_$convId.jsonl", """{"ts":1000,"imp":0.6,"p":-0.3}""" + "\n")
        write("mem/recalls_$convId.json", """{"1000":{"n":2,"at":3000}}""")
        write("mem/co_$convId.json", """{"1000.2000":3}""")
        write("mem/corrections_$convId.jsonl", """{"at":1500,"text":"不是周四" }""" + "\n")
    }

    private fun backupJson(chat: String?, index: String? = null, version: Int = 1): File {
        val files = JSONObject()
        if (chat != null) files.put("chat", chat)
        if (index != null) files.put("index", index)
        val o = JSONObject()
            .put("format", RecordArchive.FORMAT).put("version", version)
            .put("convId", "v1").put("title", "鱼").put("exportedAt", 0L)
            .put("files", files)
        val f = File(freshDir("hand"), "b${System.nanoTime()}.json")
        f.writeText(o.toString(), Charsets.UTF_8)
        return f
    }

    // ────────── 导出 ──────────

    @Test
    fun `导出会带上八份文件里的七份`() {
        seedConversation()
        val r = RecordArchive.exportTo(root, "v1", "鱼")
        assertNotNull(r)
        assertEquals(2, r!!.count)
        val o = JSONObject(r.file.readText(Charsets.UTF_8))
        assertEquals(RecordArchive.FORMAT, o.getString("format"))
        assertEquals("条数写进头部，列表页才不用整份解析", 2, o.getInt("count"))
        val files = o.getJSONObject("files")
        assertTrue("正文要在", files.has("chat"))
        assertTrue("索引要在", files.has("index"))
        assertTrue("复习要在", files.has("recalls"))
        assertTrue("话题游标要在", files.has("current_topic"))
        assertFalse("不存在的文件不占位置", files.has("chat_sandbox"))
    }

    @Test
    fun `沙盒模式下只有沙盒记录也能导出`() {
        setup()
        write("chat_sandbox_v1.jsonl", """{"ts":1,"fromUser":true,"text":"调试"}""" + "\n")
        val r = RecordArchive.exportTo(root, "v1", "鱼")
        assertNotNull("沙盒记录也是记录", r)
        assertEquals(1, r!!.count)
    }

    @Test
    fun `空会话不给备份`() {
        setup()
        assertNull(RecordArchive.exportTo(root, "v1", "鱼"))
    }

    @Test
    fun `同一秒连点两次导出不会互相覆盖`() {
        seedConversation()
        val a = RecordArchive.exportTo(root, "v1", "鱼", at = 5_000L)!!
        val b = RecordArchive.exportTo(root, "v1", "鱼", at = 5_000L)!!
        assertFalse("第二份要换名字，不能静默覆盖第一份", a.file.name == b.file.name)
        assertTrue(b.file.name.contains("_2"))
        assertEquals(2, RecordArchive.listIn(root).size)
    }

    @Test
    fun `备份列表按时间倒序且条数来自头部`() {
        seedConversation()
        RecordArchive.exportTo(root, "v1", "鱼", at = 1_000L)
        RecordArchive.exportTo(root, "v1", "鱼", at = 2_000L)
        val list = RecordArchive.listIn(root)
        assertEquals(2, list.size)
        assertEquals(2_000L, list[0].at)
        assertEquals(2, list[0].count)
        assertEquals("鱼", list[0].title)
    }

    @Test
    fun `列表会跳过不是本 App 的 json`() {
        seedConversation()
        RecordArchive.exportTo(root, "v1", "鱼")
        write("backups/无关文件.json", """{"hello":"world"}""")
        assertEquals(1, RecordArchive.listIn(root).size)
    }

    // ────────── 导入：无损往返 ──────────

    @Test
    fun `删干净再导回来，正文一字不差`() {
        seedConversation(marker = "我们上次说的那件事")
        val before = File(root, "chat_v1.jsonl").readText(Charsets.UTF_8)
        val backup = RecordArchive.exportTo(root, "v1", "鱼")!!.file

        RecordArchive.deleteIn(root, "v1", deleteBackups = false)
        assertFalse(File(root, "chat_v1.jsonl").exists())

        val got = RecordArchive.importTo(root, backup, "v1")
        assertNull(got.error)
        assertEquals(2, got.count)
        assertTrue("索引是一起恢复的，不是重算的", got.indexRestored)
        assertEquals(before, File(root, "chat_v1.jsonl").readText(Charsets.UTF_8))
        assertTrue("索引也要回来", File(root, "mem/index_v1.jsonl").exists())
    }

    @Test
    fun `导入能落到另一个会话 id`() {
        seedConversation(marker = "搬过去的话")
        val backup = RecordArchive.exportTo(root, "v1", "鱼")!!.file
        val got = RecordArchive.importTo(root, backup, "v9")
        assertNull(got.error)
        assertEquals(2, got.count)
        assertTrue(File(root, "chat_v9.jsonl").readText(Charsets.UTF_8).contains("搬过去的话"))
        assertTrue("原来的 v1 不受影响", File(root, "chat_v1.jsonl").exists())
    }

    @Test
    fun `不是本 App 的备份一律拒绝且一个文件都不动`() {
        setup()
        val bad = File(freshDir("bad"), "x.json").apply { writeText("""{"a":1}""") }
        assertEquals("这不是本 App 的备份文件", RecordArchive.importTo(root, bad, "v1").error)
        val broken = File(bad.parentFile, "y.json").apply { writeText("这不是 json") }
        assertTrue(
            RecordArchive.importTo(root, broken, "v1").error!!.contains("合法 json"),
        )
        assertFalse(File(root, "chat_v1.jsonl").exists())
    }

    @Test
    fun `来自更新版本的备份要拒绝`() {
        setup()
        val f = backupJson("""{"ts":1,"fromUser":true,"text":"x"}""" + "\n", version = 99)
        val r = RecordArchive.importTo(root, f, "v1")
        assertTrue(r.error!!.contains("更新的版本"))
        assertFalse(File(root, "chat_v1.jsonl").exists())
    }

    @Test
    fun `会话 id 不合法就不许动`() {
        seedConversation()
        val backup = RecordArchive.exportTo(root, "v1", "鱼")!!.file
        assertTrue(RecordArchive.importTo(root, backup, "v 1").error!!.contains("id 不合法"))
        assertEquals("删除也不许动", 0, RecordArchive.deleteIn(root, "../v1"))
        assertEquals("导出也不许动", null, RecordArchive.exportTo(root, "v/1", "鱼"))
    }

    // ────────── 导入：派生记忆不许留原地 ──────────

    @Test
    fun `备份里没有索引时，索引和复习共召回都要清掉`() {
        seedConversation()
        val backup = backupJson("""{"ts":1000,"fromUser":true,"text":"新正文"}""" + "\n")
        val r = RecordArchive.importTo(root, backup, "v1")
        assertNull(r.error)
        assertEquals(1, r.count)
        assertFalse("备份里没索引 → 不能声称恢复了", r.indexRestored)
        assertTrue(File(root, "chat_v1.jsonl").readText(Charsets.UTF_8).contains("新正文"))
        assertFalse("索引指向已经不存在的句子 → 必须清掉", File(root, "mem/index_v1.jsonl").exists())
        assertFalse("复习记录同理", File(root, "mem/recalls_v1.json").exists())
        assertFalse("共召回同理", File(root, "mem/co_v1.json").exists())
    }

    @Test
    fun `备份里没有话题表和游标时要清掉，否则她"不认识"刚导回来的历史`() {
        seedConversation()
        val backup = backupJson("""{"ts":9000,"fromUser":true,"text":"新正文"}""" + "\n")
        RecordArchive.importTo(root, backup, "v1")
        assertFalse("话题表是正文派生的", File(root, "topics_v1.jsonl").exists())
        assertFalse("游标留着会把新历史整段滤掉", File(root, "mem/current_topic_v1.json").exists())
    }

    @Test
    fun `备份里索引是空串时，也要按"没有索引"处理`() {
        seedConversation()
        val backup = backupJson(
            """{"ts":1000,"fromUser":true,"text":"新正文"}""" + "\n",
            index = "",
        )
        val r = RecordArchive.importTo(root, backup, "v1")
        assertFalse(r.indexRestored)
        assertFalse("索引被清空就别留下复习计数", File(root, "mem/recalls_v1.json").exists())
    }

    @Test
    fun `备份里有的文件原样覆盖，没列出的不删`() {
        seedConversation()
        val backup = backupJson(
            """{"ts":9000,"fromUser":true,"text":"只有正文"}""" + "\n",
            index = """{"ts":9000,"imp":0.9}""" + "\n",
        )
        RecordArchive.importTo(root, backup, "v1")
        assertTrue("改口记录不在备份里 → 不动", File(root, "mem/corrections_v1.jsonl").exists())
    }

    @Test
    fun `导入失败时不会留下 tmp 残留`() {
        seedConversation()
        val backup = backupJson("""{"ts":1,"fromUser":true,"text":"x"}""" + "\n")
        RecordArchive.importTo(root, backup, "v1")
        val leftovers = root.walkTopDown().filter { it.name.endsWith(".tmp") }.toList()
        assertTrue("临时文件要全部消失：$leftovers", leftovers.isEmpty())
    }

    // ────────── 删除：删干净，但不多删 ──────────

    private fun seedFiles(convId: String) {
        write("chat_$convId.jsonl", """{"ts":1000,"fromUser":true,"text":"x"}""" + "\n")
        write("chat_sandbox_$convId.jsonl", """{"ts":1000,"fromUser":true,"text":"s"}""" + "\n")
        write("topics_$convId.jsonl", """{"id":1}""" + "\n")
        write("mem/current_topic_$convId.json", """{"id":1}""")
        write("mem/index_$convId.jsonl", """{"ts":1000}""" + "\n")
        write("mem/recalls_$convId.json", """{}""")
        write("mem/co_$convId.json", """{}""")
        write("mem/corrections_$convId.jsonl", """{"at":1,"text":"y"}""" + "\n")
    }

    @Test
    fun `删除只动这个会话的记录`() {
        setup()
        seedFiles("v1")
        seedFiles("v2")
        write("characters.json", """[{"id":"c1","name":"鱼","persona":"…"}]""")
        write("conversations.json", """[{"id":"v1","kind":"single","memberIds":["c1"],"title":"鱼"}]""")
        write("state/c1.json", """{"enabled":true,"p":-0.2,"trust":0.5}""")

        assertEquals("八份全删", 8, RecordArchive.deleteIn(root, "v1"))

        assertFalse(File(root, "chat_v1.jsonl").exists())
        assertFalse(File(root, "mem/index_v1.jsonl").exists())
        assertTrue("别的会话不许动", File(root, "chat_v2.jsonl").exists())
        assertTrue("角色不许动", File(root, "characters.json").exists())
        assertTrue("状态不许动（那是人的连续性，不是聊天记录）", File(root, "state/c1.json").exists())
        assertTrue("conversations.json 不许动", File(root, "conversations.json").exists())
    }

    @Test
    fun `删除时可以连备份一起删，但只删这个会话的`() {
        seedConversation()
        val mine = RecordArchive.exportTo(root, "v1", "鱼")!!
        // 另一个会话的备份
        write("chat_v2.jsonl", """{"ts":1,"fromUser":true,"text":"另一个"}""" + "\n")
        val other = RecordArchive.exportTo(root, "v2", "Nami")!!

        assertTrue(RecordArchive.deleteIn(root, "v1", deleteBackups = false) > 0)
        assertTrue("说了不删备份就得留着", mine.file.exists())

        write("chat_v1.jsonl", """{"ts":1,"fromUser":true,"text":"x"}""" + "\n")
        RecordArchive.deleteIn(root, "v1", deleteBackups = true)
        assertFalse("自己那份备份要删", mine.file.exists())
        assertTrue("别人的备份不许动", other.file.exists())
    }

    @Test
    fun `删除空会话不会崩也不报错数`() {
        setup()
        assertEquals(0, RecordArchive.deleteIn(root, "v1"))
    }
}
