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

    @Test fun defaultIsKeyAndTitle() {
        assertEquals(ContactIdStrategy.KeyAndTitle, LearnTuning.CONTACT_ID)
        assertEquals(id("0|com.chat|1|null|1", "Alice", ContactIdStrategy.KeyAndTitle), ContactIdentity.of(pkg, "0|com.chat|1|null|1", "Alice")!!.id)
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
