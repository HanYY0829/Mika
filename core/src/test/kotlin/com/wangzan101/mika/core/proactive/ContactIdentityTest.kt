package com.wangzan101.mika.core.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactIdentityTest {
    private val pkg = "com.chat"
    private fun id(key: String, title: String, s: ContactIdStrategy) = ContactIdentity.of(pkg, key, title, s)!!.id

    // 现在只看会话名:通知键不是一个会话一个(有的 App 的通知 id 顺着往上加),同一个群换了键还是同一个联系人
    @Test fun defaultLooksAtTheConversationNameOnly() {
        assertEquals(ContactIdStrategy.Title, LearnTuning.CONTACT_ID)
        val ids = (512..521).map { ContactIdentity.of(pkg, "0|com.chat|$it|null|10234", "三年2班家长群(${it - 500}条新消息)")!!.id }
        assertEquals(1, ids.toSet().size)
        assertTrue(ids.first().startsWith("t"))
        // 不同的会话、不同的 App 还是分得开
        assertNotEquals(ids.first(), ContactIdentity.of(pkg, "0|com.chat|512|null|10234", "三年3班家长群")!!.id)
        assertNotEquals(ids.first(), ContactIdentity.of("com.other", "0|com.chat|512|null|10234", "三年2班家长群")!!.id)
    }

    // 身份和显示名出自同一个字符串:存着的联系人只有显示名,从它算出来的身份要和新通知的一样(很长的名字也是)
    @Test fun aStoredNameGivesTheSameIdentityAsTheLiveTitle() {
        for (title in listOf("三年2班家长群", "三年2班家长群(12条新消息)", "三年2班家长群（99+条新消息）", "  Alice\u0000  Bob  ", "很长的群名".repeat(30), "x".repeat(300) + "(3条新消息)")) {
            val live = ContactIdentity.of(pkg, "k1", title)!!
            val stored = ContactIdentity.conversationName(title)
            assertTrue(title, stored.length <= LearnTuning.CONTACT_NAME_MAX)
            assertEquals(title, live.id, ContactIdentity.ofStored(pkg, stored))
            assertEquals(title, live.id, ContactIdentity.ofStored(pkg, live.name!!))
        }
        // 以前的算法只有显示名算不出来
        assertNull(ContactIdentity.ofStored(pkg, "Alice", ContactIdStrategy.KeyAndTitle))
    }

    // 真机上看到的:会话名结尾括号里的数会变(「群名（1,234）」过一会儿是「群名（1,236）」)。认会话时不看它;显示名里留着
    @Test fun aTrailingBracketedNumberDoesNotSplitTheConversation() {
        val s = ContactIdStrategy.Title
        val ids = listOf("周末爬山群（1,234）", "周末爬山群（1,236）", "周末爬山群 (987)", "周末爬山群(99+)", "周末爬山群").map { id("k", it, s) }
        assertEquals(1, ids.toSet().size)
        assertEquals("周末爬山群（1,234）", ContactIdentity.displayName("周末爬山群（1,234）"))
        // 名字加上那个数刚好超过显示名的上限(括号被截掉一半)时也一样认
        val long = "群".repeat(LearnTuning.CONTACT_NAME_MAX - 4)
        assertEquals(id("k", long, s), id("k", "$long（1,234）", s))
        assertEquals(id("k", long, s), id("k", "$long（1,301）", s))
        // 名字本身就比上限长的:只看开头那一段,后面带不带数都一样
        val longer = "群".repeat(LearnTuning.CONTACT_NAME_MAX + 10)
        assertEquals(id("k", longer, s), id("k", "$longer（7）", s))
        // 存着的显示名(带着那个数)算出来的也是同一个
        assertEquals(ids.first(), ContactIdentity.ofStored(pkg, "周末爬山群（1,236）"))
        // 不在结尾的、不是纯数的括号还是名字的一部分
        for (title in listOf("周末爬山群(第3期)", "(3)周末爬山群", "周末爬山群(3)二队")) assertNotEquals(title, ids.first(), id("k", title, s))
        // 去掉之后什么都不剩:算不出身份
        assertNull(ContactIdentity.of(pkg, "k", "(12)", s))
    }

    // 以前的算法(键 + 标题)原样留着:按它算的 id 还存在「不再记录」的名单里,要认得出来
    @Test fun theOldKeyAndTitleIdsAreStillComputedTheSameWay() {
        val old = ContactIdStrategy.KeyAndTitle
        assertEquals("b" + NoticeDedup.idOf("contact\u0000com.chat\u0000k1\n" + TopicPool.normalize("三年2班家长群")), id("k1", "三年2班家长群(2条新消息)", old))
        // 以前不认「99+」这种写法:按以前的算法它还是名字的一部分
        assertNotEquals(id("k1", "三年2班家长群", old), id("k1", "三年2班家长群(99+条新消息)", old))
        assertNotEquals(id("k1", "Alice", old), id("k2", "Alice", old))
    }

    @Test fun keyStrategyIgnoresTheTitle() {
        val s = ContactIdStrategy.Key
        // 同一个键,标题改了:还是同一个联系人,显示名用新的
        assertEquals(id("k1", "Alice", s), id("k1", "Alice 🌸", s))
        assertEquals("Alice 🌸", ContactIdentity.of(pkg, "k1", "Alice 🌸", s)!!.name)
        // 不同的键、同样的标题:不同的联系人(陌生人改成同样的昵称也并不进来)
        assertNotEquals(id("k1", "妈妈", s), id("k2", "妈妈", s))
        // 标题是空的也有身份,只是给不出显示名
        assertNull(ContactIdentity.of(pkg, "k1", "  ", s)!!.name)
    }

    @Test fun titleStrategyIgnoresTheKey() {
        val s = ContactIdStrategy.Title
        assertEquals(id("k1", "Alice", s), id("k2", "ａｌｉｃｅ ", s))
        assertNotEquals(id("k1", "Alice", s), id("k1", "Bob", s))
        assertNull(ContactIdentity.of(pkg, "k1", " ", s))
    }

    @Test fun keyAndTitleNeedsBoth() {
        val s = ContactIdStrategy.KeyAndTitle
        assertEquals(id("k1", "Alice", s), id("k1", "Alice", s))
        assertNotEquals(id("k1", "Alice", s), id("k1", "Bob", s))
        assertNotEquals(id("k1", "Alice", s), id("k2", "Alice", s))
        assertNull(ContactIdentity.of(pkg, "k1", "", s))
    }

    @Test fun idsAreHashesPrefixedByStrategyAndScopedToTheApp() {
        val ids = ContactIdStrategy.entries.map { id("k1", "Alice", it) }
        assertEquals(3, ids.toSet().size)
        ids.forEach { assertFalse(it.contains("Alice") || it.contains("k1")) }
        assertTrue(ids[0].startsWith("k") && ids[1].startsWith("t") && ids[2].startsWith("b"))
        assertNotEquals(id("k1", "Alice", ContactIdStrategy.Title), ContactIdentity.of("com.other", "k1", "Alice", ContactIdStrategy.Title)!!.id)
    }

    @Test fun displayNameIsCleanedCappedAndFiltered() {
        assertEquals("Alice Bob", ContactIdentity.displayName(" Alice\u0000‮  Bob "))
        assertEquals(LearnTuning.CONTACT_NAME_MAX, ContactIdentity.displayName("x".repeat(100))!!.length)
        assertNull(ContactIdentity.displayName(" "))
        assertNull(ContactIdentity.displayName("验证码 482913"))
        assertNull(ContactIdentity.displayName("登录码 4829"))
    }

    // 标题就是号码的发件人、带单号或网址的推送:不当名字存(和新话题名查的是同样两样);身份照样算得出来
    @Test fun displayNameIsNotAPhoneNumberAnOrderNumberOrAUrl() {
        assertNull(ContactIdentity.displayName("13812345678"))
        assertNull(ContactIdentity.displayName("+86 138-1234-5678".replace("-", "")))
        assertNull(ContactIdentity.displayName("１３８１２３４５６７８")) // 全角数字也一样
        assertNull(ContactIdentity.displayName("订单 2026111012345 已发货"))
        assertNull(ContactIdentity.displayName("领券 example.com/x"))
        assertNull(ContactIdentity.displayName("https://t.cn/abc"))
        // 短的数字、普通的名字照常
        assertEquals("10086", ContactIdentity.displayName("10086"))
        assertEquals("三年2班家长群", ContactIdentity.displayName("三年2班家长群"))
        assertEquals("Node.js 交流", ContactIdentity.displayName("Node.js 交流"))
        val id = ContactIdentity.of(pkg, "k1", "13812345678", ContactIdStrategy.KeyAndTitle)!!
        assertNull(id.name)
    }

    // 真机上看到:QQ 把未读条数写在标题后面(「群名(2条新消息)」)。不去掉的话,同一个会话每多一条就成了另一个联系人,
    // 永远攒不够天数,删掉的也会换个样子再回来
    @Test fun unreadCountSuffixIsNotPartOfTheIdentityOrTheName() {
        val s = ContactIdStrategy.KeyAndTitle
        val base = ContactIdentity.of(pkg, "k1", "三年2班家长群", s)!!
        for (title in listOf("三年2班家长群(2条新消息)", "三年2班家长群 (12条新消息)", "三年2班家长群（3条新消息）", "三年2班家长群(99条以上新消息)")) {
            val v = ContactIdentity.of(pkg, "k1", title, s)!!
            assertEquals(title, base.id, v.id)
            assertEquals(title, "三年2班家长群", v.name)
        }
        // 条数多了的写法(「99+」):现在的算法也认
        for (title in listOf("三年2班家长群(99+条新消息)", "三年2班家长群（99+ 条新消息）")) {
            assertEquals(title, id("k1", "三年2班家长群", ContactIdStrategy.Title), id("k7", title, ContactIdStrategy.Title))
            assertEquals(title, "三年2班家长群", ContactIdentity.displayName(title))
        }
        assertEquals(id("k9", "Alice", ContactIdStrategy.Title), id("k1", "Alice(5条新消息)", ContactIdStrategy.Title))
        // 只去掉结尾这一种写法:名字里本来就有的括号、数字不动
        for (title in listOf("项目群(2)", "(2条新消息)通知", "读书会(第3期)", "3条新消息")) {
            assertNotEquals(title, base.id, ContactIdentity.of(pkg, "k1", title, s)!!.id)
            assertEquals(title, ContactIdentity.displayName(title))
        }
        // 去掉之后什么都不剩的:没有名字,按标题算的身份也算不出来
        assertNull(ContactIdentity.displayName("(2条新消息)"))
        assertNull(ContactIdentity.of(pkg, "k1", "(2条新消息)", s))
    }
}
