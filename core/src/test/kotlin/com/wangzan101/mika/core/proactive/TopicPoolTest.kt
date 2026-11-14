package com.wangzan101.mika.core.proactive

import java.time.ZoneOffset
import kotlin.math.exp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TopicPoolTest {
    private val zone = ZoneOffset.UTC
    private val day = LearnTuning.DAY_MS
    private val t0 = 20_000 * day + 3_600_000L

    private fun ev(i: Int, at: Long = t0, pkg: String = "com.shop", outcome: LearnOutcome = LearnOutcome.None) =
        LearnEvent(i.toLong(), "k$i", pkg, at, "", "", outcome)

    private fun new(vararg idx: Int, kind: TopicKind = TopicKind.Shopping, label: String = "running shoes") =
        LearnSignal(idx.toList(), newKind = kind, newLabel = label)

    @Test fun evidenceIsPerAppAndDayNotPerEvent() {
        val p = TopicPool()
        // 同一天同一个 App 的十条只算一份
        val events = (0 until 10).map { ev(it, at = t0 + it * 60_000L) }
        val r = p.apply("com.shop", events, listOf(new(*(0 until 10).toList().toIntArray())), t0 + day / 2, zone)
        val t = p.all().single()
        assertEquals(1, t.evidenceCount)
        assertEquals(mapOf("com.shop" to listOf(20_000L)), t.evidence)
        assertEquals(1, r.applied)
        assertEquals((0 until 10).toSet(), r.byEvent.keys)
        assertFalse(t.established)
        // 第二天再来一次:两份证据,已确立
        p.apply("com.shop", listOf(ev(0, at = t0 + day)), listOf(LearnSignal(listOf(0), topicId = t.id)), t0 + day, zone)
        val t2 = p[t.id]!!
        assertEquals(2, t2.evidenceCount)
        assertEquals(2, t2.days)
        assertTrue(t2.established)
        assertEquals(t0 + day, t2.lastSeenMs)
        assertEquals(t0 + day / 2, t2.firstSeenMs)
    }

    @Test fun secondAppSameDayIsMoreEvidenceButNotEstablished() {
        val p = TopicPool()
        p.apply("com.shop", listOf(ev(0)), listOf(new(0)), t0, zone)
        p.apply("com.mall", listOf(ev(0, pkg = "com.mall")), listOf(new(0)), t0, zone)
        val t = p.all().single()
        assertEquals(2, t.apps)
        assertEquals(2, t.evidenceCount)
        assertEquals(1, t.days)
        assertFalse(t.established)
    }

    // 证据记到哪个 App 名下只看调用方给的包名:回复里写什么都影响不到(LearnSignal 里根本没有 App 这一项)
    @Test fun replyCannotAttributeEvidenceToAnotherApp() {
        val p = TopicPool()
        val parsed = NoticeLearn.parse(
            """{"signals":[{"ids":["n1"],"app":"com.bank","package":"com.bank","new":{"kind":"interest","label":"Hiking","app":"com.bank"}}]}""",
            1, emptyList(),
        )!!
        // 事件上写的包名也不算:只认这批的包名
        p.apply("com.shop", listOf(ev(0, pkg = "com.bank")), parsed.signals, t0, zone)
        assertEquals(setOf("com.shop"), p.all().single().evidence.keys)
    }

    @Test fun sameKindAndNormalizedLabelIsAReference() {
        val p = TopicPool()
        p.apply("com.shop", listOf(ev(0)), listOf(new(0, label = "Running Shoes")), t0, zone)
        p.apply("com.shop", listOf(ev(0, at = t0 + day)), listOf(new(0, label = "ｒｕｎｎｉｎｇ　shoes")), t0 + day, zone)
        p.apply("com.shop", listOf(ev(0, at = t0 + day)), listOf(new(0, label = "runningshoes")), t0 + day, zone)
        assertEquals(1, p.all().size)
        assertEquals("Running Shoes", p.all().single().label)
        // 种类不同就是另一个话题
        p.apply("com.shop", listOf(ev(0)), listOf(new(0, kind = TopicKind.Interest, label = "running shoes")), t0, zone)
        assertEquals(2, p.all().size)
        // 同一个回复里两条同名的新话题也只起一个
        val q = TopicPool()
        q.apply("com.shop", listOf(ev(0), ev(1)), listOf(new(0, label = "Tea"), new(1, label = "tea")), t0, zone)
        assertEquals(1, q.all().size)
    }

    @Test fun outcomesAreCountedOncePerEventAndTopic() {
        val p = TopicPool()
        val events = listOf(ev(0, outcome = LearnOutcome.Opened), ev(1, outcome = LearnOutcome.Dismissed), ev(2))
        // 同一个回复里两条都落到同一个话题上:同样的通知不重复算
        p.apply("com.shop", events, listOf(new(0, 1, 2), new(0, 1)), t0, zone)
        val id = p.all().single().id
        assertEquals(1, p[id]!!.opened)
        assertEquals(1, p[id]!!.dismissed)
        assertTrue(p.addOutcome(setOf(id, "gone"), LearnOutcome.Opened))
        assertEquals(2, p[id]!!.opened)
        assertFalse(p.addOutcome(setOf("gone"), LearnOutcome.Dismissed))
    }

    @Test fun deletedTopicIsNotResurrected() {
        val p = TopicPool()
        p.apply("com.shop", listOf(ev(0)), listOf(new(0)), t0, zone)
        val id = p.all().single().id
        assertTrue(p.delete(id))
        val r = p.apply("com.shop", listOf(ev(0)), listOf(LearnSignal(listOf(0), topicId = id)), t0, zone)
        assertTrue(p.all().isEmpty())
        assertEquals(1, r.missing)
        assertTrue(r.byEvent.isEmpty())
    }

    @Test fun strengthAndRetention() {
        val t = ObservedTopic("a", TopicKind.Interest, "x", t0, t0, mapOf("p" to listOf(1L, 2L), "q" to listOf(2L)), opened = 2, dismissed = 4)
        assertEquals(3 + 2 * LearnTuning.OPENED_BONUS - 4 * LearnTuning.DISMISSED_PENALTY, t.strength, 1e-9)
        assertEquals(1.0, t.retention(t0), 1e-9)
        val s = LearnTuning.STABILITY_DAYS * (1 + t.strength)
        assertEquals(exp(-10 / s), t.retention(t0 + 10 * day), 1e-9)
        // 划掉的多也不低于 0
        assertEquals(0.0, t.copy(dismissed = 100).strength, 0.0)
        // 强的忘得慢
        val weak = t.copy(evidence = mapOf("p" to listOf(1L)), opened = 0, dismissed = 0)
        assertTrue(weak.retention(t0 + 20 * day) < t.retention(t0 + 20 * day))
    }

    @Test fun maintenanceForgetsFadedTopics() {
        val p = TopicPool()
        p.apply("com.shop", listOf(ev(0)), listOf(new(0, label = "old")), t0, zone)
        repeat(5) { d -> p.apply("com.shop", listOf(ev(0, at = t0 + d * day)), listOf(new(0, label = "strong")), t0 + d * day, zone) }
        assertEquals(5, p.all().first { it.label == "strong" }.days)
        // 一份证据:S = 14 天,留存低于 0.1 要 ln(10)×14 ≈ 32 天
        assertTrue(p.maintain(t0 + 30 * day).isEmpty())
        val dropped = p.maintain(t0 + 33 * day)
        assertEquals(1, dropped.size)
        assertEquals(listOf("strong"), p.all().map { it.label })
    }

    @Test fun capEvictsLowestScoreFirst() {
        val p = TopicPool()
        repeat(LearnTuning.POOL_CAP) { i -> p.apply("com.shop", listOf(ev(0, at = t0 + i)), listOf(new(0, label = "t$i")), t0 + i, zone) }
        // 让 t5 变强
        val strong = p.all().first { it.label == "t5" }.id
        p.apply("com.shop", listOf(ev(0, at = t0 + day)), listOf(LearnSignal(listOf(0), topicId = strong)), t0 + day, zone)
        p.apply("com.shop", listOf(ev(0, at = t0 + day)), listOf(new(0, label = "newcomer")), t0 + day, zone)
        assertEquals(LearnTuning.POOL_CAP, p.all().size)
        assertNull(p.all().firstOrNull { it.label == "t0" }) // 最旧、最弱的被挤掉
        assertTrue(p.all().any { it.label == "t5" })
        assertTrue(p.all().any { it.label == "newcomer" })
    }

    @Test fun daysPerAppAreCapped() {
        var ev: Map<String, List<Long>> = emptyMap()
        repeat(80) { ev = TopicPool.addDays(ev, "p", listOf(it.toLong())) }
        assertEquals((20L until 80L).toList(), ev.getValue("p"))
        repeat(LearnTuning.APPS_PER_TOPIC + 3) { ev = TopicPool.addDays(ev, "a$it", listOf(100L + it)) }
        assertEquals(LearnTuning.APPS_PER_TOPIC, ev.size)
        assertFalse("p" in ev) // 最久没出现的 App 先丢
    }

    @Test fun clearAppRemovesItsEvidenceAndOrphans() {
        val p = TopicPool()
        p.apply("com.shop", listOf(ev(0)), listOf(new(0, label = "only shop")), t0, zone)
        p.apply("com.shop", listOf(ev(0)), listOf(new(0, label = "both")), t0, zone)
        p.apply("com.mall", listOf(ev(0, pkg = "com.mall")), listOf(new(0, label = "both")), t0, zone)
        val dropped = p.clearApp("com.shop")
        assertEquals(1, dropped.size)
        assertEquals(listOf("both"), p.all().map { it.label })
        assertEquals(setOf("com.mall"), p.all().single().evidence.keys)
    }

    @Test fun sensitiveTopicsFollowTheSwitch() {
        val p = TopicPool()
        val flagged = LearnSignal(listOf(0), newKind = TopicKind.Interest, newLabel = "diabetes", sensitive = true)
        // 关着:标了敏感的新话题不收
        assertEquals(1, p.apply("com.health", listOf(ev(0)), listOf(flagged), t0, zone).missing)
        assertTrue(p.all().isEmpty())
        // 开着:收下并带着标记
        p.apply("com.health", listOf(ev(0)), listOf(flagged), t0, zone, allowSensitive = true)
        val t = p.all().single()
        assertTrue(t.sensitive)
        // 关着时引用它、或起同名的新话题,都不加证据
        val r = p.apply("com.health", listOf(ev(0, at = t0 + day)),
            listOf(LearnSignal(listOf(0), topicId = t.id), new(0, kind = TopicKind.Interest, label = "Diabetes")), t0 + day, zone)
        assertEquals(2, r.missing)
        assertEquals(1, p[t.id]!!.evidenceCount)
        // 关着时,后来才到的点开/划掉也不往敏感话题上补
        assertFalse(p.addOutcome(setOf(t.id), LearnOutcome.Opened))
        assertEquals(0, p[t.id]!!.opened)
        assertTrue(p.addOutcome(setOf(t.id), LearnOutcome.Opened, allowSensitive = true))
        assertEquals(1, p[t.id]!!.opened)
        // 关着时也不给模型看
        assertTrue(p.strongest(t0).isEmpty())
        assertEquals(1, p.strongest(t0, includeSensitive = true).size)
        p.apply("com.shop", listOf(ev(0)), listOf(new(0)), t0, zone)
        assertEquals(setOf(t.id), p.deleteSensitive())
        assertEquals(listOf("running shoes"), p.all().map { it.label })
    }

    @Test fun dataRoundTrip() {
        val p = TopicPool()
        p.apply("com.shop", listOf(ev(0, outcome = LearnOutcome.Opened)), listOf(new(0)), t0, zone)
        val back = TopicPool(p.data())
        assertEquals(p.all(), back.all())
        back.apply("com.shop", listOf(ev(0)), listOf(new(0, label = "other")), t0, zone)
        assertEquals(2, back.all().map { it.id }.toSet().size)
    }

    // 话题 id 不重用:新起了话题却没存上(进程没了、存盘失败),重新读出旧的那份后再起的话题不能拿到同一个 id——
    // 别处(联系人上连着的话题)可能还记着那个 id,会连到不相干的话题上
    @Test fun idsAreNotReusedAfterALostSave() {
        val p = TopicPool()
        p.apply("com.shop", listOf(ev(0)), listOf(new(0, label = "kept")), t0, zone)
        val saved = p.data()
        p.apply("com.shop", listOf(ev(0)), listOf(new(0, label = "lost")), t0, zone)
        val lost = p.all().first { it.label == "lost" }.id
        val back = TopicPool(saved)
        back.apply("com.shop", listOf(ev(0)), listOf(new(0, label = "later")), t0, zone)
        assertTrue(lost !in back.all().map { it.id })
        // 整个池子没了、从空的重来也一样
        val fresh = TopicPool()
        fresh.apply("com.shop", listOf(ev(0)), listOf(new(0, label = "again")), t0, zone)
        assertTrue(fresh.all().single().id !in p.all().map { it.id })
    }

    // 给的 id 和已有的撞了就再要一个
    @Test fun aCollidingNewIdIsNotUsed() {
        val ids = ArrayDeque(listOf("a", "a", "a", "b"))
        val p = TopicPool(newId = { ids.removeFirst() })
        p.apply("com.shop", listOf(ev(0), ev(1)), listOf(new(0, label = "one"), new(1, label = "two")), t0, zone)
        assertEquals(mapOf("one" to "a", "two" to "b"), p.all().associate { it.label to it.id })
    }

    // 一次回复对一个话题最多记一天:跨了午夜的一批、几条归纳指着同一个话题,都不能凭一次判断就凑出「已确立」
    @Test fun oneReplyCountsAsOneDayPerTopic() {
        val events = listOf(ev(0, at = t0), ev(1, at = t0 + day))
        val p = TopicPool()
        p.apply("com.shop", events, listOf(new(0, 1)), t0 + day, zone)
        // 记在引用的通知里最新那条的日子上
        assertEquals(mapOf("com.shop" to listOf(20_001L)), p.all().single().evidence)
        assertFalse(p.all().single().established)
        // 拆成两条同名的新话题、各引用一天的,也一样
        val q = TopicPool()
        q.apply("com.shop", events, listOf(new(0), new(1)), t0 + day, zone)
        assertEquals(mapOf("com.shop" to listOf(20_001L)), q.all().single().evidence)
        // 一条引用已有的、一条新起同名的,也并在一起算
        val r = TopicPool()
        r.apply("com.shop", listOf(ev(0, at = t0 - 5 * day)), listOf(new(0)), t0 - 5 * day, zone)
        val id = r.all().single().id
        val applied = r.apply("com.shop", events, listOf(LearnSignal(listOf(0), topicId = id), new(1)), t0 + day, zone)
        assertEquals(listOf(19_995L, 20_001L), r[id]!!.evidence.getValue("com.shop"))
        assertEquals(2, applied.applied)
        assertEquals(mapOf(0 to setOf(id), 1 to setOf(id)), applied.byEvent)
        assertEquals(t0 + day, r[id]!!.lastSeenMs)
    }

    // 证据的日子是通知发出的那天,不是判断的那天:同一天的通知分两天判,还是一天
    @Test fun judgingOnAnotherDayAddsNoDay() {
        val p = TopicPool()
        p.apply("com.shop", listOf(ev(0, at = t0)), listOf(new(0)), t0, zone)
        p.apply("com.shop", listOf(ev(0, at = t0 + 60_000L)), listOf(new(0)), t0 + day, zone)
        assertEquals(1, p.all().single().days)
        assertFalse(p.all().single().established)
    }

    // 用户单独删掉的话题不再被学回来:同名的新话题不收,不管归到哪一种、大小写和空白怎么写;别的名字照常收
    @Test fun aDeletedTopicNameIsNotLearnedAgain() {
        val p = TopicPool()
        p.apply("com.shop", listOf(ev(0)), listOf(new(0)), t0, zone)
        assertTrue(p.delete(p.all().single().id))
        assertEquals(1, p.blockedCount)
        val r = p.apply("com.shop", listOf(ev(0), ev(1), ev(2)),
            listOf(new(0), new(1, kind = TopicKind.Interest, label = " Running  SHOES "), new(2, label = "trail shoes")), t0 + 1, zone)
        assertEquals(listOf("trail shoes"), p.all().map { it.label })
        assertEquals(2, r.blocked)
        assertEquals(1, r.applied)
        assertEquals(0, r.missing)
        assertEquals(setOf(2), r.byEvent.keys)
        // 存下来的只有指纹,没有删掉的那个名字;读回来照样拦
        val data = p.data()
        assertEquals(listOf(TopicPool.fingerprint("running shoes")), data.blocked)
        assertFalse(data.toString().contains("running shoes", ignoreCase = true))
        val back = TopicPool(data)
        back.apply("com.shop", listOf(ev(0)), listOf(new(0)), t0 + 2, zone)
        assertEquals(listOf("trail shoes"), back.all().map { it.label })
        // 重新允许之后可以再学
        assertTrue(back.allowAgain())
        assertFalse(back.allowAgain())
        back.apply("com.shop", listOf(ev(0)), listOf(new(0)), t0 + 3, zone)
        assertEquals(listOf("trail shoes", "running shoes"), back.all().map { it.label })
    }

    // 留着的同名话题照常用:先认已有的,要新起时才看是不是删掉过的名字
    @Test fun aKeptTopicWithABlockedNameKeepsWorking() {
        val p = TopicPool()
        p.apply("com.shop", listOf(ev(0), ev(1)), listOf(new(0), new(1, kind = TopicKind.Interest)), t0, zone)
        val (shopping, interest) = p.all()
        assertTrue(p.delete(shopping.id))
        // 同名、另一种的那个还在:模型再提它(按名字新起)算引用它,照常加证据
        val r = p.apply("com.mail", listOf(ev(0, at = t0 + day, pkg = "com.mail")), listOf(new(0, kind = TopicKind.Interest)), t0 + day, zone)
        assertEquals(1, r.applied)
        assertEquals(0, r.blocked)
        assertEquals(setOf("com.shop", "com.mail"), p[interest.id]!!.evidence.keys)
        // 删掉的那一种不会借此回来
        assertEquals(1, p.apply("com.shop", listOf(ev(0)), listOf(new(0)), t0 + day, zone).blocked)
        assertEquals(1, p.all().size)
    }

    // 只有单独删才记「不再记录」:清空全部、清掉某个 App 的、删掉敏感的、久了忘掉的、数量满了挤掉的都不记
    @Test fun onlyASingleDeleteBlocksAName() {
        val cleared = TopicPool()
        cleared.apply("com.shop", listOf(ev(0)), listOf(new(0)), t0, zone)
        cleared.clear()
        val byApp = TopicPool()
        byApp.apply("com.shop", listOf(ev(0)), listOf(new(0)), t0, zone)
        assertEquals(1, byApp.clearApp("com.shop").size)
        val sensitive = TopicPool()
        sensitive.apply("com.shop", listOf(ev(0)), listOf(new(0).copy(sensitive = true)), t0, zone, allowSensitive = true)
        assertEquals(1, sensitive.deleteSensitive().size)
        val faded = TopicPool()
        faded.apply("com.shop", listOf(ev(0)), listOf(new(0)), t0, zone)
        assertEquals(1, faded.maintain(t0 + 400 * day).size)
        for (p in listOf(cleared, byApp, sensitive, faded)) {
            assertEquals(0, p.blockedCount)
            assertTrue(p.all().isEmpty())
            p.apply("com.shop", listOf(ev(0, at = t0 + 400 * day)), listOf(new(0)), t0 + 400 * day, zone, allowSensitive = true)
            assertEquals(1, p.all().size)
        }
        // 清空全部是重来,但之前单独删过的名字还记着
        val both = TopicPool()
        both.apply("com.shop", listOf(ev(0), ev(1)), listOf(new(0), new(1, label = "tent")), t0, zone)
        both.delete(both.all().first { it.label == "tent" }.id)
        both.clear()
        assertEquals(1, both.blockedCount)
        // 名单有上限,多了丢最早的
        val many = TopicPool()
        repeat(LearnTuning.TOPIC_BLOCKED_CAP + 3) { i ->
            many.apply("com.shop", listOf(ev(0)), listOf(new(0, label = "topic $i")), t0, zone)
            many.delete(many.all().single().id)
        }
        assertEquals(LearnTuning.TOPIC_BLOCKED_CAP, many.blockedCount)
        assertEquals(0, many.apply("com.shop", listOf(ev(0)), listOf(new(0, label = "topic 0")), t0, zone).blocked)
        assertEquals(1, many.apply("com.shop", listOf(ev(0)), listOf(new(0, label = "topic 5")), t0, zone).blocked)
    }

    // 旧版本存下来的(没有「不再记录」的名单):照样读得出来
    @Test fun olderTopicFilesStillDecode() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        // 名单是空的时候不写进文件:这就是旧版本文件的样子
        val old = json.encodeToString(TopicPoolData.serializer(), TopicPoolData(topics = listOf(ObservedTopic("k1", TopicKind.Shopping, "running shoes", 1, 2))))
        assertFalse(old, "blocked" in old)
        val p = TopicPool(json.decodeFromString(TopicPoolData.serializer(), old))
        assertEquals(listOf("running shoes"), p.all().map { it.label })
        assertEquals(0, p.blockedCount)
    }
}
