package com.leaf.vixi.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 评价层（P1）的回归测试。
 *
 * 这一层现在**只算不写**：它不碰状态数值，所以测试只看"这句话被理解成什么"。
 * 重点是两条区分能站住：
 *   · 「你真蠢」→ 怪他 → 生气
 *   · 「你这毛病得改改」→ 往自己身上想 → 委屈
 */
class AppraisalTest {

    private fun judge(text: String, closeness: Double = 0.3, trust: Double = 0.5): EmotionLabel =
        Labeler.judge(Appraiser.appraise(text), closeness, trust).label

    private fun types(text: String) = EventRules.classifyUser(text)

    // ────────── 相关性 ──────────

    @Test
    fun `冲她说的相关性最高`() {
        assertEquals(1.0, Appraiser.appraise("你好可爱").relevance, 0.001)
    }

    @Test
    fun `泛泛而谈相关性低`() {
        assertEquals(0.4, Appraiser.appraise("今天天气不错").relevance, 0.001)
    }

    @Test
    fun `相关性太低就一律平静`() {
        // 天气里出现负面词也不该让她难受
        assertEquals(EmotionLabel.CALM, judge("今天好累啊真的好烦"))
    }

    // ────────── 正向 ──────────

    @Test
    fun `夸奖给正向利害`() {
        val a = Appraiser.appraise("你好可爱")
        assertTrue(a.impact > 0.3)
        assertTrue(a.intensity > 0.2)
    }

    @Test
    fun `陌生距离时被夸是开心`() {
        assertEquals(EmotionLabel.JOY, judge("你好可爱", closeness = 0.2))
    }

    @Test
    fun `已经很亲近时被夸是心动`() {
        assertEquals(EmotionLabel.TENDER, judge("你好可爱", closeness = 0.6))
    }

    @Test
    fun `撒娇强度高于普通夸奖`() {
        val tender = Appraiser.appraise("抱抱").intensity
        val praise = Appraiser.appraise("你好可爱").intensity
        assertTrue(tender >= praise)
    }

    // ────────── 负向：靠归因区分 ──────────

    @Test
    fun `骂人是怪他且应付得来`() {
        val a = Appraiser.appraise("你真蠢")
        assertTrue("强贬低应该怪他", a.attribution > 0.3)
        assertTrue("强贬低应付感不该太低", a.coping >= 0.5)
    }

    @Test
    fun `骂人判成生气而不是委屈`() {
        assertEquals(EmotionLabel.ANGER, judge("你真蠢"))
    }

    @Test
    fun `挑剔是往自己身上想`() {
        val a = Appraiser.appraise("你这毛病得改改")
        assertTrue("挑剔应该往自己身上想", a.attribution < 0)
        assertTrue("挑剔应付感低", a.coping < 0.5)
    }

    @Test
    fun `挑剔判成委屈`() {
        assertEquals(EmotionLabel.HURT, judge("你这毛病得改改"))
    }

    @Test
    fun `被纠正更容易自责`() {
        val a = Appraiser.appraise("你记错了")
        assertTrue(a.attribution < 0)
        assertEquals(EmotionLabel.SHAME, judge("你记错了"))
    }

    @Test
    fun `威胁判成怪他`() {
        val a = Appraiser.appraise("我不理你了")
        assertTrue(a.attribution > 0.3)
        assertTrue(a.impact < -0.3)
    }

    @Test
    fun `冷淡是轻微负向`() {
        assertEquals(EmotionLabel.ANNOYED, judge("哦"))
    }

    // ────────── 强度 ──────────

    @Test
    fun `强度的上限是一`() {
        val a = Appraiser.appraise("你真蠢")
        assertTrue(a.intensity in 0.0..1.0)
    }

    @Test
    fun `无关的话强度接近零`() {
        // 泛泛而谈（没冲她、也没命中任何事件）不该有情绪强度
        assertTrue(Appraiser.appraise("今天天气不错").intensity < 0.05)
        assertEquals(EmotionLabel.CALM, judge("今天天气不错"))
    }

    @Test
    fun `评价会带上命中事件方便对账`() {
        assertTrue(StateEventType.PRAISE in Appraiser.appraise("你好可爱").events)
        assertTrue(StateEventType.NEGATIVE in Appraiser.appraise("你真蠢").events)
    }

    // ────────── 贬低轻重 ──────────

    @Test
    fun `骂人算二级贬低`() {
        assertEquals(2, EventRules.negativeSeverity("你真蠢"))
    }

    @Test
    fun `挑剔算一级贬低`() {
        assertEquals(1, EventRules.negativeSeverity("你这毛病得改改"))
    }

    @Test
    fun `不是贬低就是零级`() {
        assertEquals(0, EventRules.negativeSeverity("今天天气不错"))
    }

    // ────────── 娇嗔（TEASE） ──────────

    @Test
    fun `娇嗔不算骂人`() {
        val t = types("Nami你臭臭的")
        assertTrue(StateEventType.TEASE in t)
        assertTrue(StateEventType.NEGATIVE !in t)
    }

    @Test
    fun `傻傻的不是骂人但傻是`() {
        assertTrue(StateEventType.TEASE in types("你傻傻的"))
        assertTrue(StateEventType.NEGATIVE !in types("你傻傻的"))
        assertTrue(StateEventType.NEGATIVE in types("你真傻"))
    }

    @Test
    fun `娇嗔给正向利害`() {
        val a = Appraiser.appraise("Nami你臭臭的")
        assertTrue("娇嗔应该是轻微正的", a.impact > 0)
    }

    @Test
    fun `娇嗔在亲近时是心动`() {
        assertEquals(EmotionLabel.TENDER, judge("Nami你臭臭的", closeness = 0.5))
    }

    @Test
    fun `娇嗔在陌生距离是开心`() {
        assertEquals(EmotionLabel.JOY, judge("Nami你臭臭的", closeness = 0.1))
    }

    @Test
    fun `娇嗔强度不会掉成零`() {
        val v = Labeler.judge(Appraiser.appraise("Nami你臭臭的"), closeness = 0.5)
        assertTrue(v.intensity >= 0.12)
    }

    // ────────── 情绪增量（P3：标签选形状，强度选大小） ──────────

    @Test
    fun `生气抬高支配度委屈压低支配度`() {
        val anger = EmotionDelta.of(EmotionLabel.ANGER, 1.0)
        val hurt = EmotionDelta.of(EmotionLabel.HURT, 1.0)
        assertTrue("生气应该更有底气", anger.d > 0)
        assertTrue("委屈应该更被动", hurt.d < 0)
    }

    @Test
    fun `委屈攒暗流生气涨冲突`() {
        assertTrue(EmotionDelta.of(EmotionLabel.HURT, 1.0).backlog > 0)
        assertTrue(EmotionDelta.of(EmotionLabel.ANGER, 1.0).conflict > 0)
    }

    @Test
    fun `强度越大数值变化越大`() {
        val weak = EmotionDelta.of(EmotionLabel.ANGER, 0.3)
        val strong = EmotionDelta.of(EmotionLabel.ANGER, 0.9)
        assertTrue(kotlin.math.abs(strong.p) > kotlin.math.abs(weak.p))
    }

    @Test
    fun `平静不给任何增量`() {
        val d = EmotionDelta.of(EmotionLabel.CALM, 1.0)
        assertEquals(0.0, d.p + d.a + d.d + d.conflict + d.backlog, 0.0001)
    }

    @Test
    fun `正向情绪抬高效价`() {
        assertTrue(EmotionDelta.of(EmotionLabel.JOY, 0.8).p > 0)
        assertTrue(EmotionDelta.of(EmotionLabel.TENDER, 0.8).p > 0)
    }

    // ────────── 审查补上的几条（别再把关系量丢掉） ──────────

    @Test
    fun `挑剔和纠正要涨冲突`() {
        assertTrue("委屈也该有摩擦", EmotionDelta.of(EmotionLabel.HURT, 1.0).conflict > 0)
        assertTrue("自责也是矛盾", EmotionDelta.of(EmotionLabel.SHAME, 1.0).conflict > 0)
    }

    @Test
    fun `被敷衍时唤醒是往下走的`() {
        assertTrue(EmotionDelta.of(EmotionLabel.ANNOYED, 1.0).a < 0)
    }

    @Test
    fun `威胁判成担心而不是委屈`() {
        assertEquals(EmotionLabel.ANXIOUS, judge("我不理你了"))
    }

    @Test
    fun `归因模糊且信任低时是失落`() {
        // 「哦」：归因 +0.6 但应付感低 → 信任低时先往失落走
        val a = Appraiser.appraise("嗯……")
        val v = Labeler.judge(a, closeness = 0.3, trust = 0.2)
        assertTrue(v.label == EmotionLabel.GLOOM || v.label == EmotionLabel.ANNOYED || v.label == EmotionLabel.HURT)
    }

    @Test
    fun `娇嗔不能盖住强贬低`() {
        val t = types("你真蠢，欠揍")
        assertTrue("强贬低必须还在", StateEventType.NEGATIVE in t)
        assertTrue("不能被判成轻微正向", Appraiser.appraise("你真蠢，欠揍").impact < 0)
    }
}
