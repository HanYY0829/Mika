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

    // ---- 身份只看会话名(现在的默认):通知键变了还是同一个联系人 ----

    private val old = ContactIdStrategy.KeyAndTitle
    private fun oldId(key: String, title: String, pkg: String = "com.chat") = ContactIdentity.of(pkg, key, title, old)!!.id
    private fun nowId(title: String, pkg: String = "com.chat") = ContactIdentity.of(pkg, "", title)!!.id

    /** 默认的算法下,某一天这个会话来了一条(通知键是 [key])。 */
    private fun ContactPool.seen(d: Int, key: String, title: String, relation: ContactRelation = ContactRelation.Unknown, pkg: String = "com.chat", group: Boolean? = true) {
        val at = start + d * day + 3_600_000L
        val e = LearnEvent(1, key, pkg, at, title, "text", group = group)
        apply(listOf(e), listOf(ContactSignal(listOf(0), ContactType.Group, relation)), emptyMap(), at + 1, utc)
    }

    // 有的聊天 App 的通知 id 顺着往上加:同一个群三天里换了三个键,还是一个联系人,日子攒得起来
    @Test fun theSameConversationUnderChangingNotificationKeysIsOneContact() {
        val p = ContactPool()
        p.seen(0, "0|com.chat|512|null|1", "三年2班家长群(3条新消息)")
        p.seen(1, "0|com.chat|517|null|1", "三年2班家长群(99+条新消息)")
        p.seen(2, "0|com.chat|521|null|1", "三年2班家长群")
        val c = p.all().single()
        assertEquals(nowId("三年2班家长群"), c.id)
        assertEquals(listOf(20_000L, 20_001L, 20_002L), c.days)
        assertTrue(c.established)
        assertEquals("三年2班家长群", c.name)
        // 另一个群、另一个 App 里同名的群:各是各的
        p.seen(2, "0|com.chat|521|null|1", "三年3班家长群")
        p.seen(2, "0|com.other|521|null|1", "三年2班家长群", pkg = "com.other")
        assertEquals(3, p.all().size)
    }

    // 以前按「键 + 标题」存下来的:同一个群每换一次键就多存了一条。换成现在的身份时并回一条
    @Test fun storedDuplicatesOfOneConversationAreMergedIntoOne() {
        fun stored(key: String, d: Long, last: Long, name: String = "三年2班家长群", topics: List<String> = emptyList(),
                   relation: ContactRelation? = null, votes: Map<ContactRelation, List<Long>> = emptyMap()) =
            ObservedContact(oldId(key, name), "com.chat", name, firstSeenMs = last - 10, lastSeenMs = last, days = listOf(d),
                typeVotes = mapOf(ContactType.Group to listOf(d)), relationVotes = votes, topics = topics, userRelation = relation)
        val p = ContactPool(ContactPoolData(contacts = listOf(
            stored("k512", 20_000, start + 100, topics = listOf("t1", "t2"), votes = mapOf(ContactRelation.Classmate to listOf(20_000L))),
            stored("k517", 20_001, start + day + 100, topics = listOf("t2", "t3"), relation = ContactRelation.Family),
            stored("k521", 20_002, start + 2 * day + 100, votes = mapOf(ContactRelation.Classmate to listOf(20_002L), ContactRelation.Unknown to listOf(20_002L))),
            stored("k600", 20_002, start + 2 * day, name = "另一个群"),
            ObservedContact(oldId("k1", "张三", "com.mail"), "com.mail", "张三", 1, 2, days = listOf(20_000L)),
        )))
        assertTrue(p.adoptIdentities())
        assertEquals(3, p.all().size)
        val c = p[nowId("三年2班家长群")]!!
        assertEquals(listOf(20_000L, 20_001L, 20_002L), c.days)
        assertEquals(mapOf(ContactType.Group to listOf(20_000L, 20_001L, 20_002L)), c.typeVotes)
        assertEquals(listOf(20_000L, 20_002L), c.relationVotes[ContactRelation.Classmate])
        assertEquals(listOf(20_002L), c.relationVotes[ContactRelation.Unknown])
        assertEquals(start + 90, c.firstSeenMs)
        assertEquals(start + 2 * day + 100, c.lastSeenMs)
        // 用户定过的关系不丢(最近的那一条没定过,用定过的那一条的)
        assertEquals(ContactRelation.Family, c.userRelation)
        assertEquals(listOf("t1", "t2", "t3"), c.topics)
        assertTrue(c.established)
        assertEquals("com.mail", p[nowId("张三", "com.mail")]!!.packageName)
        // 再来新通知,落在并好的这一条上
        p.seen(3, "0|com.chat|530|null|1", "三年2班家长群(2条新消息)")
        assertEquals(4, p[nowId("三年2班家长群")]!!.days.size)
        assertEquals(3, p.all().size)
        // 已经都是现在的身份:不再动
        assertFalse(p.adoptIdentities())
        // 两条都定过关系的:用最近出现过的那一条的
        val q = ContactPool(ContactPoolData(contacts = listOf(
            stored("a", 20_000, start, relation = ContactRelation.Friend), stored("b", 20_001, start + day, relation = ContactRelation.Colleague))))
        assertTrue(q.adoptIdentities())
        assertEquals(ContactRelation.Colleague, q.all().single().userRelation)
    }

    // 「不再记录」的名单里是按以前的算法记的 id:换了算法也不能让删掉的联系人回来
    @Test fun theBlockListSurvivesTheChangeOfIdentity() {
        val key = "0|com.chat|7|null|1"
        // 以前删过的(联系人已经不在了,只剩名单里的旧 id):同一个键、同一个名字再来,认得出来,名单里换成现在的 id
        val p = ContactPool(ContactPoolData(blocked = listOf(oldId(key, "张三"))))
        val first = p.apply(listOf(LearnEvent(1, key, "com.chat", start, "张三", "text", group = false)),
            listOf(ContactSignal(listOf(0), ContactType.Person, ContactRelation.Unknown)), emptyMap(), start + 1, utc)
        assertTrue(p.all().isEmpty())
        // 名单变了:算改动(调用方据此存盘)。存下来的名单里是现在的 id,旧的那个换掉了(一次删除只占一个位置)
        assertTrue(first.changed)
        assertEquals(listOf(nowId("张三")), p.data().blocked)
        // 再来一次没有新的可记:不算改动
        assertFalse(p.apply(listOf(LearnEvent(2, key, "com.chat", start + 5, "张三", "text", group = false)),
            listOf(ContactSignal(listOf(0), ContactType.Person, ContactRelation.Unknown)), emptyMap(), start + 6, utc).changed)
        // 之后换了键也不回来
        p.seen(1, "0|com.chat|8|null|1", "张三(2条新消息)", group = false)
        assertTrue(p.all().isEmpty())
        // 补做一次以前没存上的删除(删的是旧 id 的那条):同名的另外几条不能借着合并回来
        val q = ContactPool(ContactPoolData(contacts = listOf(
            ObservedContact(oldId("k1", "家长群"), "com.chat", "家长群", 1, 2, days = listOf(20_000L)),
            ObservedContact(oldId("k2", "家长群"), "com.chat", "家长群", 3, 4, days = listOf(20_001L)),
            ObservedContact(oldId("k3", "别的群"), "com.chat", "别的群", 3, 4, days = listOf(20_001L)),
        )))
        assertTrue(q.delete(oldId("k1", "家长群")))
        assertTrue(q.adoptIdentities())
        assertEquals(listOf("别的群"), q.all().map { it.name })
        q.seen(2, "0|com.chat|9|null|1", "家长群")
        assertEquals(listOf("别的群"), q.all().map { it.name })
        // 重新允许之后才会再记
        assertTrue(q.allowAgain())
        q.seen(3, "0|com.chat|9|null|1", "家长群")
        assertEquals(2, q.all().size)
    }

    // 以前同一个群存成了好几条,用户删掉了几条重复的、留着一条。留着的那条换成了现在的身份;之后删掉的某个通知键又来了:
    // 不能因此把留着的这条记进「不再记录」——那样它还在页面上,却再也不动了
    @Test fun aKeptContactIsNotBlockedByADeletedDuplicateOfIt() {
        val p = ContactPool(ContactPoolData(
            contacts = listOf(ObservedContact(oldId("k1", "家长群"), "com.chat", "家长群", 1, 2, days = listOf(19_999L))),
            blocked = listOf(oldId("k2", "家长群"), oldId("k3", "家长群")),
        ))
        assertTrue(p.adoptIdentities())
        p.seen(0, "k2", "家长群")
        val c = p.all().single()
        assertEquals(nowId("家长群"), c.id)
        assertEquals(listOf(19_999L, 20_000L), c.days)
        assertEquals(2, p.blockedCount)
        // 用户这时删掉它:按会话名记,之后换什么键都不再记
        assertTrue(p.delete(c.id))
        p.seen(1, "k9", "家长群(3条新消息)")
        assertTrue(p.all().isEmpty())
    }

    // 补做记着的删除:联系人不在了(读出来的是换身份之前的文件)也要记进「不再记录」,接着换身份时同名的才不会回来
    @Test fun aRememberedDeleteBlocksTheIdEvenWhenTheContactIsNotThere() {
        val p = ContactPool(ContactPoolData(contacts = listOf(
            ObservedContact(oldId("k1", "家长群"), "com.chat", "家长群", 1, 2, days = listOf(20_000L)),
            ObservedContact(oldId("k2", "家长群"), "com.chat", "家长群", 3, 4, days = listOf(20_001L)),
            ObservedContact(oldId("k3", "别的群"), "com.chat", "别的群", 3, 4, days = listOf(20_001L)),
        )))
        assertTrue(p.forget(nowId("家长群")))
        // 已经记着的不算改动
        assertFalse(p.forget(nowId("家长群")))
        assertEquals(3, p.all().size)
        assertTrue(p.adoptIdentities())
        assertEquals(listOf("别的群"), p.all().map { it.name })
        // 还在的照常删
        assertTrue(p.forget(nowId("别的群")))
        assertTrue(p.all().isEmpty())
    }

    // 「不再记录」的名单满着(都是以前的 id)。换成现在的 id 是替换,不是再添一个:不然换一个就挤掉一个别的、还没换过来的删除,
    // 用户一次都没多删,删掉的联系人却回来了。页面上的个数也还是那么多个会话
    @Test fun carryingABlockedIdentityOverDoesNotPushOutAnotherDeletedContact() {
        val cap = LearnTuning.CONTACT_BLOCKED_CAP
        val p = ContactPool(ContactPoolData(blocked = (0 until cap).map { oldId("key-$it", "联系人$it") }))
        p.seen(0, "key-${cap - 1}", "联系人${cap - 1}", group = false)
        assertTrue(p.all().isEmpty())
        assertEquals(cap, p.blockedCount)
        p.seen(0, "key-0", "联系人0", group = false)
        assertTrue(p.all().isEmpty())
        assertEquals(cap, p.blockedCount)
        assertTrue(nowId("联系人0") in p.data().blocked)
        assertFalse(oldId("key-0", "联系人0") in p.data().blocked)
    }

    // 以前的几条并成一条时,连着的话题超出上限留最近的:较新那一条也连着的话题不能因为在较旧那一条里排得靠前就被挤掉
    @Test fun mergingStoredDuplicatesKeepsTheTopicsTheNewerOneStillLinks() {
        val cap = LearnTuning.CONTACT_TOPICS
        val older = ObservedContact(oldId("k1", "工作群"), "com.chat", "工作群", 1, 100, days = listOf(1), topics = (1..cap).map { "topic$it" })
        val newer = ObservedContact(oldId("k2", "工作群"), "com.chat", "工作群", 101, 200, days = listOf(2), topics = listOf("topic1", "topic${cap + 1}"))
        val p = ContactPool(ContactPoolData(contacts = listOf(older, newer)))
        assertTrue(p.adoptIdentities())
        assertEquals((3..cap).map { "topic$it" } + listOf("topic1", "topic${cap + 1}"), p.all().single().topics)
    }

    // 名字是空的、算不出现在的身份的旧条目:原样留着(等它淡忘),不丢也不乱并
    @Test fun storedEntriesWithoutAUsableNameAreLeftAsTheyAre() {
        val odd = ObservedContact("b0123456789abcdef", "com.chat", " ", 1, 2, days = listOf(20_000L))
        val p = ContactPool(ContactPoolData(contacts = listOf(odd, ObservedContact(oldId("k", "张三"), "com.chat", "张三", 1, 2))))
        assertTrue(p.adoptIdentities())
        assertEquals(setOf("b0123456789abcdef", nowId("张三")), p.all().map { it.id }.toSet())
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

    // 话题被用户并进了另一个:连着它的改连那一个,位置不变;两个都连着的只留一个
    @Test fun aMergedTopicsLinksMoveToTheTopicItWentInto() {
        val p = ContactPool()
        p.day(0, ContactRelation.Friend, topics = mapOf(0 to setOf("k1")))
        p.day(1, ContactRelation.Friend, topics = mapOf(0 to setOf("k2")))
        p.day(2, ContactRelation.Friend, topics = mapOf(0 to setOf("k3")))
        p.day(2, ContactRelation.Friend, key = "bob", title = "Bob", topics = mapOf(0 to setOf("k3")))
        fun topics(name: String) = p.all().single { it.name == name }.topics
        assertEquals(listOf("k1", "k2", "k3"), topics("Alice"))
        assertTrue(p.remapTopic("k1", "k9"))
        assertEquals(listOf("k9", "k2", "k3"), topics("Alice"))
        assertEquals(listOf("k3"), topics("Bob"))
        assertTrue(p.remapTopic("k2", "k3"))
        assertEquals(listOf("k9", "k3"), topics("Alice"))
        // 没有谁连着它:没有改动
        assertFalse(p.remapTopic("k1", "k3"))
    }

    // 没有会话名的群聊:通知标题是发言人,不是会话。不记联系人——记了的话群里每个人各成一条,还会和同名的私聊混成一个
    @Test fun aGroupWhoseTitleIsOnlyTheSpeakerIsNotRecorded() {
        val p = ContactPool()
        val at = start + 3_600_000L
        val events = listOf(
            LearnEvent(1, "g", "com.chat", at, "张三", "明天见", group = true, titleIsSpeaker = true),
            LearnEvent(2, "p", "com.chat", at, "张三", "在吗", group = false),
        )
        val r = p.apply(events, listOf(ContactSignal(listOf(0), ContactType.Group, ContactRelation.Friend),
            ContactSignal(listOf(1), ContactType.Person, ContactRelation.Friend)), mapOf(0 to setOf("k1"), 1 to setOf("k2")), at + 1, utc)
        assertEquals(1, r.accepted)
        assertEquals(1, r.dropped[ContactDrop.NoIdentity])
        val c = p.all().single()
        assertEquals(ContactType.Person, c.type)
        // 群里那条的话题也不连到私聊的这个人上
        assertEquals(listOf("k2"), c.topics)
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
