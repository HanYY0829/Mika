package com.wangzan101.mika.core.proactive

import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactPoolTest {
    private val day = LearnTuning.DAY_MS
    private val start = 20_000 * day
    private val utc = ZoneOffset.UTC
    private val s = ContactIdStrategy.Key

    private fun ev(i: Int, key: String, title: String, atMs: Long, pkg: String = "com.chat") =
        LearnEvent(i.toLong(), key, pkg, atMs, title, "text $i")

    private fun cid(key: String, pkg: String = "com.chat") = ContactIdentity.of(pkg, key, "x", s)!!.id

    /** 某一天里这个会话发了 [n] 条,模型把它们归成一条联系人。 */
    private fun ContactPool.day(
        d: Int,
        relation: ContactRelation,
        key: String = "alice",
        title: String = "Alice",
        n: Int = 1,
        type: ContactType = ContactType.Person,
        topics: Map<Int, Set<String>> = emptyMap(),
        allowSensitive: Boolean = false,
        pkg: String = "com.chat",
    ): ContactPool.Applied {
        val at = start + d * day + 3_600_000L
        val events = (0 until n).map { ev(it, key, title, at + it, pkg) }
        return apply(events, listOf(ContactSignal(events.indices.toList(), type, relation)), topics, at + n, utc, allowSensitive, s)
    }

    @Test fun oneVotePerDayAndManyMessagesAreOneVote() {
        val p = ContactPool()
        p.day(0, ContactRelation.Friend, n = 10)
        p.day(0, ContactRelation.Friend, n = 3)
        val c = p[cid("alice")]!!
        assertEquals(listOf(20_000L), c.days)
        assertEquals(mapOf(ContactRelation.Friend to listOf(20_000L)), c.relationVotes)
        assertEquals(mapOf(ContactType.Person to listOf(20_000L)), c.typeVotes)
        assertEquals("Alice", c.name)
        // 只有一天的票:还不显示
        assertEquals(ContactRelation.Unknown, c.relation)
    }

    @Test fun shownRelationNeedsTwoDaysAndAMajority() {
        val p = ContactPool()
        p.day(0, ContactRelation.Family)
        p.day(1, ContactRelation.Family)
        assertEquals(ContactRelation.Family, p[cid("alice")]!!.relation)
        // 「不确定」也算在总数里:2 比 2 不过半
        p.day(2, ContactRelation.Unknown)
        p.day(3, ContactRelation.Colleague)
        assertEquals(ContactRelation.Unknown, p[cid("alice")]!!.relation)
        p.day(4, ContactRelation.Family)
        assertEquals(ContactRelation.Family, p[cid("alice")]!!.relation)
    }

    @Test fun establishedAtThreeDays() {
        val p = ContactPool()
        p.day(0, ContactRelation.Friend)
        p.day(1, ContactRelation.Friend)
        assertFalse(p[cid("alice")]!!.established)
        p.day(5, ContactRelation.Friend)
        assertTrue(p[cid("alice")]!!.established)
        assertEquals(3, p[cid("alice")]!!.days.size)
    }

    @Test fun userRelationWinsAndSurvivesVotesAndPersistence() {
        val p = ContactPool()
        p.day(0, ContactRelation.Friend)
        assertTrue(p.setRelation(cid("alice"), ContactRelation.Classmate))
        repeat(5) { p.day(it + 1, ContactRelation.Colleague) }
        assertEquals(ContactRelation.Classmate, p[cid("alice")]!!.relation)
        assertTrue(p[cid("alice")]!!.userSet)
        val json = Json.encodeToString(ContactPoolData.serializer(), p.data())
        val back = ContactPool(Json.decodeFromString(ContactPoolData.serializer(), json))
        assertEquals(p.data(), back.data())
        assertEquals(ContactRelation.Classmate, back[cid("alice")]!!.relation)
        // 交回去按票数猜
        assertTrue(back.setRelation(cid("alice"), null))
        assertEquals(ContactRelation.Colleague, back[cid("alice")]!!.relation)
    }

    @Test fun typeIsTheTopVote() {
        val p = ContactPool()
        p.day(0, ContactRelation.Family, type = ContactType.Group)
        p.day(1, ContactRelation.Family, type = ContactType.Group)
        p.day(2, ContactRelation.Family, type = ContactType.Person)
        assertEquals(ContactType.Group, p[cid("alice")]!!.type)
    }

    @Test fun sameKeyNewTitleKeepsOneContactAndUpdatesTheName() {
        val p = ContactPool()
        p.day(0, ContactRelation.Friend, title = "Alice")
        p.day(1, ContactRelation.Friend, title = "Alice (work)")
        p.day(2, ContactRelation.Friend, title = " ")
        assertEquals(1, p.all().size)
        assertEquals("Alice (work)", p.all().single().name)
        // 不同的键、同样的标题:是另一个
        p.day(2, ContactRelation.Friend, key = "stranger", title = "Alice (work)")
        assertEquals(2, p.all().size)
    }

    @Test fun firstSightingWithoutAUsableNameIsNotRecorded() {
        val p = ContactPool()
        val r = p.day(0, ContactRelation.Friend, title = "")
        assertTrue(p.all().isEmpty())
        assertEquals(mapOf(ContactDrop.NoIdentity to 1), r.dropped)
        assertEquals(0, r.accepted)
    }

    // 只显示成号码的发件人不会成为联系人
    @Test fun aSenderShownOnlyAsAPhoneNumberIsNotRecorded() {
        val p = ContactPool()
        val r = p.day(0, ContactRelation.Friend, title = "13812345678")
        assertTrue(p.all().isEmpty())
        assertEquals(mapOf(ContactDrop.NoIdentity to 1), r.dropped)
        // 已经记下的联系人,某一批里标题成了号码:名字沿用旧的
        p.day(0, ContactRelation.Friend, title = "Alice")
        p.day(1, ContactRelation.Friend, title = "13812345678")
        assertEquals("Alice", p.all().single().name)
    }

    @Test fun oneEntryOverSeveralIdentitiesVotesForEach() {
        val p = ContactPool()
        val at = start + 3_600_000L
        val events = listOf(ev(0, "a", "A", at), ev(1, "b", "B", at), ev(2, "a", "A", at))
        val r = p.apply(events, listOf(ContactSignal(listOf(0, 1, 2), ContactType.Person, ContactRelation.Friend)), emptyMap(), at, utc, false, s)
        assertEquals(1, r.accepted)
        assertEquals(setOf("A", "B"), p.all().map { it.name }.toSet())
        p.all().forEach { assertEquals(listOf(20_000L), it.relationVotes.getValue(ContactRelation.Friend)) }
    }

    @Test fun partnerNeedsTheSensitiveSwitch() {
        val p = ContactPool()
        p.day(0, ContactRelation.Partner)
        assertEquals(setOf(ContactRelation.Unknown), p.all().single().relationVotes.keys)
        assertFalse(p.all().single().sensitive)
        p.day(1, ContactRelation.Partner, allowSensitive = true)
        assertTrue(p.all().single().sensitive)
        assertTrue(p.deleteSensitive())
        assertFalse(p.all().single().sensitive)
        assertEquals(setOf(ContactRelation.Unknown), p.all().single().relationVotes.keys)
    }

    @Test fun deletionBlocksRecreationButClearsDoNot() {
        val p = ContactPool()
        p.day(0, ContactRelation.Friend)
        assertTrue(p.delete(cid("alice")))
        val r = p.day(1, ContactRelation.Friend)
        assertTrue(p.all().isEmpty())
        assertEquals(mapOf(ContactDrop.Blocked to 1), r.dropped)
        assertEquals(1, p.blockedCount)
        // 「不再记录」里只有 id
        assertFalse(p.data().blocked.single().contains("alice"))
        assertTrue(p.allowAgain())
        p.day(2, ContactRelation.Friend)
        assertEquals(1, p.all().size)

        // 清掉某个 App 的、清空全部:是重来,不挡
        p.day(2, ContactRelation.Friend, key = "bob", title = "Bob", pkg = "com.other")
        assertTrue(p.clearApp("com.chat"))
        assertEquals(listOf("Bob"), p.all().map { it.name })
        p.day(3, ContactRelation.Friend)
        assertEquals(2, p.all().size)
        assertTrue(p.clear())
        p.day(4, ContactRelation.Friend)
        assertEquals(1, p.all().size)
        assertEquals(0, p.blockedCount)
    }

    @Test fun topicLinksAreMechanicalBoundedAndPruned() {
        val p = ContactPool()
        p.day(0, ContactRelation.Friend, topics = mapOf(0 to setOf("k1", "k2")))
        assertEquals(listOf("k1", "k2"), p.all().single().topics.sorted())
        // 没有联系人归类,但通知属于已有的联系人:话题照样连上
        val at = start + day
        p.apply(listOf(ev(0, "alice", "Alice", at), ev(1, "shop", "Promo", at)), emptyList(), mapOf(0 to setOf("k3"), 1 to setOf("k4")), at, utc, false, s)
        assertEquals(listOf("k1", "k2", "k3"), p.all().single().topics.sorted())
        assertEquals(1, p.all().size)
        (10 until 30).forEach { p.day(1, ContactRelation.Friend, topics = mapOf(0 to setOf("k$it"))) }
        assertEquals(LearnTuning.CONTACT_TOPICS, p.all().single().topics.size)
        assertEquals("k29", p.all().single().topics.last())
        assertTrue(p.forgetTopics(setOf("k29")))
        assertFalse("k29" in p.all().single().topics)
        assertTrue(p.maintain(at, liveTopics = setOf("k28")))
        assertEquals(listOf("k28"), p.all().single().topics)
    }

    @Test fun forgettingSparesUserSetAndCapApplies() {
        val p = ContactPool()
        p.day(0, ContactRelation.Friend)
        p.day(0, ContactRelation.Friend, key = "bob", title = "Bob")
        p.setRelation(cid("bob"), ContactRelation.Family)
        // 很久没出现:猜的忘掉,用户设过的留着
        p.maintain(start + 400 * day, emptySet())
        assertEquals(listOf("Bob"), p.all().map { it.name })

        val q = ContactPool()
        repeat(LearnTuning.CONTACT_CAP + 5) { q.day(0, ContactRelation.Friend, key = "c$it", title = "C$it") }
        q.day(1, ContactRelation.Friend, key = "c0", title = "C0")
        q.maintain(start + day, emptySet())
        assertEquals(LearnTuning.CONTACT_CAP, q.all().size)
        // 强度高的(两天)留着
        assertTrue(q[cid("c0")] != null)
        assertNull(ContactPool()[cid("c0")])
    }

    // 一次回复对一个联系人最多记一天、每种关系最多一票:跨了午夜的一批、几条归类落在同一个人上,都不能凭一次判断就让关系显示出来
    @Test fun oneReplyCountsAsOneDayPerContact() {
        val at = start + 3_600_000L
        val events = listOf(ev(0, "alice", "Alice", at), ev(1, "alice", "Alice", at + day))
        fun check(signals: List<ContactSignal>) {
            val p = ContactPool()
            p.apply(events, signals, emptyMap(), at + day, utc, false, s)
            val c = p[cid("alice")]!!
            // 记在引用的通知里最新那条的日子上
            assertEquals(listOf(20_001L), c.days)
            assertEquals(mapOf(ContactRelation.Family to listOf(20_001L)), c.relationVotes)
            assertEquals(mapOf(ContactType.Person to listOf(20_001L)), c.typeVotes)
            assertEquals(ContactRelation.Unknown, c.relation)
            assertEquals(at, c.firstSeenMs)
            assertEquals(at + day, c.lastSeenMs)
        }
        check(listOf(ContactSignal(listOf(0, 1), ContactType.Person, ContactRelation.Family)))
        // 拆成两条、各引用一天的,也一样
        check(listOf(
            ContactSignal(listOf(0), ContactType.Person, ContactRelation.Family),
            ContactSignal(listOf(1), ContactType.Person, ContactRelation.Family),
        ))
    }

    // 同一个回复里对同一个人说了两种关系:各记一票,都在同一天
    @Test fun twoRelationsInOneReplyAreOneVoteEach() {
        val at = start + 3_600_000L
        val events = listOf(ev(0, "alice", "Alice", at), ev(1, "alice", "Alice", at + day))
        val p = ContactPool()
        val r = p.apply(events, listOf(
            ContactSignal(listOf(0), ContactType.Person, ContactRelation.Friend),
            ContactSignal(listOf(1), ContactType.Group, ContactRelation.Colleague),
        ), emptyMap(), at + day, utc, false, s)
        assertEquals(2, r.accepted)
        val c = p[cid("alice")]!!
        assertEquals(mapOf(ContactRelation.Friend to listOf(20_001L), ContactRelation.Colleague to listOf(20_001L)), c.relationVotes)
        assertEquals(mapOf(ContactType.Person to listOf(20_001L), ContactType.Group to listOf(20_001L)), c.typeVotes)
        assertEquals(listOf(20_001L), c.days)
    }

    // 票的日子是通知发出的那天,不是判断的那天
    @Test fun judgingOnAnotherDayAddsNoDayOrVote() {
        val at = start + 3_600_000L
        val p = ContactPool()
        val one = listOf(ContactSignal(listOf(0), ContactType.Person, ContactRelation.Family))
        p.apply(listOf(ev(0, "alice", "Alice", at)), one, emptyMap(), at, utc, false, s)
        p.apply(listOf(ev(0, "alice", "Alice", at + 60_000L)), one, emptyMap(), at + day, utc, false, s)
        val c = p[cid("alice")]!!
        assertEquals(listOf(20_000L), c.days)
        assertEquals(ContactRelation.Unknown, c.relation)
    }

    // 通知的写法说得清是群还是私聊时,类型按它算,不听模型的;说不清的(别的 App)才用模型给的
    @Test fun typeFollowsTheNotificationShapeWhenItIsKnown() {
        val at = start + 3_600_000L
        fun type(group: Boolean?, model: ContactType): ContactType {
            val p = ContactPool()
            val events = listOf(ev(0, "g", "家长群", at).copy(group = group), ev(1, "g", "家长群", at + 1).copy(group = group))
            p.apply(events, listOf(ContactSignal(listOf(0, 1), model, ContactRelation.Unknown)), emptyMap(), at + 2, utc, false, s)
            return p.all().single().type
        }
        assertEquals(ContactType.Group, type(true, ContactType.Person))
        assertEquals(ContactType.Person, type(false, ContactType.Group))
        assertEquals(ContactType.Group, type(null, ContactType.Group))
        // 引用到的几条互相不一致:用模型给的
        val p = ContactPool()
        val mixed = listOf(ev(0, "g", "家长群", at).copy(group = true), ev(1, "g", "家长群", at + 1).copy(group = false))
        p.apply(mixed, listOf(ContactSignal(listOf(0, 1), ContactType.Person, ContactRelation.Unknown)), emptyMap(), at + 2, utc, false, s)
        assertEquals(ContactType.Person, p.all().single().type)
    }
}
