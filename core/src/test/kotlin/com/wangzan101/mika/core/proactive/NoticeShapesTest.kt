package com.wangzan101.mika.core.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeShapesTest {
    private fun rec(pkg: String, title: String, text: String) = NoticeRecord("k", pkg, 1, title, text)
    private fun shaped(pkg: String, title: String, text: String) = NoticeShapes.apply(rec(pkg, title, text))

    // 真机上看到的 QQ 群通知:标题「群名(13条新消息)」,正文「某人: 那句话」
    @Test fun qqGroup() {
        for (title in listOf("三年2班家长群(13条新消息)", "三年2班家长群", "三年2班家长群 (99条以上新消息)")) {
            val r = shaped("com.tencent.mobileqq", title, "王老师: 明天下午三点开家长会")
            assertEquals("三年2班家长群", r.title)
            assertEquals("王老师", r.sender)
            assertEquals("明天下午三点开家长会", r.text)
            assertEquals(true, r.group)
            assertEquals("王老师: 明天下午三点开家长会", r.said)
        }
        assertEquals("王老师", shaped("com.tencent.tim", "家长群(2条新消息)", "王老师: 好的").sender)
    }

    // QQ 私聊:正文没有「某人: 」,标题就是对方
    @Test fun qqPrivate() {
        val r = shaped("com.tencent.mobileqq", "张三(2条新消息)", "明天来吗")
        assertEquals("张三", r.title)
        assertNull(r.sender)
        assertEquals("明天来吗", r.text)
        assertEquals(false, r.group)
        assertEquals("明天来吗", r.said)
        // 正文带着的名字就是标题的,也是私聊
        val same = shaped("com.tencent.mobileqq", "张三", "张三: 明天来吗")
        assertNull(same.sender)
        assertEquals("明天来吗", same.text)
        assertEquals(false, same.group)
    }

    // 微信:正文「[3条]某人: 那句话」;某人就是标题的是私聊,不是的是群
    @Test fun wechat() {
        val private = shaped("com.tencent.mm", "张三", "[3条]张三: 明天来吗")
        assertEquals("张三", private.title)
        assertNull(private.sender)
        assertEquals("明天来吗", private.text)
        assertEquals(false, private.group)
        val group = shaped("com.tencent.mm", "家长群", "[12条]王老师: 明天开会")
        assertEquals("王老师", group.sender)
        assertEquals("明天开会", group.text)
        assertEquals(true, group.group)
        // 没有条数的第一条、没有「某人: 」的(看不出是不是群):只去掉条数,别的不动
        assertEquals("王老师", shaped("com.tencent.mm", "家长群", "王老师: 明天开会").sender)
        val plain = shaped("com.tencent.mm", "微信支付", "[2条]你有一笔新的收款")
        assertEquals("你有一笔新的收款", plain.text)
        assertNull(plain.sender)
        assertNull(plain.group)
    }

    // 拆不出来就原样留着:名字太长、名字里有冒号、冒号后面没有空格、冒号后面是空的
    @Test fun textThatIsNotSomeoneSayingSomethingIsLeftAlone() {
        val pkg = "com.tencent.mobileqq"
        for (text in listOf("x".repeat(41) + ": 你好", "网址 http://a.b: 看看", "时间:明天", "王老师: ", "只有一句话")) {
            val r = shaped(pkg, "家长群", text)
            assertEquals(text, text.trim(), r.text.trim())
            assertNull(text, r.sender)
        }
    }

    // 别的 App 的通知不动;带会话样式、没有会话名的(私聊)也不动
    @Test fun otherAppsAndMessagingStyleAreUntouched() {
        val other = rec("com.shop", "订单(2条新消息)", "客服: 已发货")
        assertSame(other, NoticeShapes.apply(other))
        val styled = NoticeRecord("k", "com.tencent.mobileqq", 1, "群(2条新消息)", "甲: 乙", listOf(NoticeMessage("甲", "乙", 1)), conversation = true)
        assertSame(styled, NoticeShapes.apply(styled))
    }

    // 会话样式的群聊(任何 App):系统把标题写成「会话名: 发言人」,会话名另给。标题换成会话名——不然同一个群里每个人说话都成了另一个会话
    @Test fun messagingStyleGroupsUseTheSystemsConversationName() {
        fun styled(title: String, conv: String?, group: Boolean?, sender: String) = NoticeShapes.apply(NoticeFilter.sanitize(NoticeRecord(
            "k", "org.chat.any", 1, title, "那句话", listOf(NoticeMessage(sender, "那句话", 1)), conversation = true, group = group, conversationTitle = conv)))
        val a = styled("周末爬山群: 张三", "周末爬山群", true, "张三")
        val b = styled("周末爬山群：李四", "周末爬山群", true, "李四")
        assertEquals("周末爬山群", a.title)
        assertEquals(a.title, b.title)
        assertEquals(true, a.group)
        assertEquals("张三", a.messages.single().sender)
        // 系统没说是不是群:有会话名就当群
        assertEquals(true, styled("周末爬山群: 张三", "周末爬山群", null, "张三").group)
        // 标题里的发言人留着,给人和模型看的那一句带上他
        assertEquals("张三", a.sender)
        assertEquals("李四", b.sender)
        assertEquals("张三: 那句话", a.said)
        assertNull(styled("周末爬山群", "周末爬山群", true, "张三").sender)
        // 发言人就是会话名的(以对方的名字当会话名的私聊):不另记
        assertNull(styled("张三: 张三", "张三", false, "张三").sender)
        // 系统说不是群、却有会话名:看不出来(老写法只设会话名不另说是群,新写法也能给私聊起会话名),不硬说是私聊
        val unsure = styled("备注名: 张三", "备注名", false, "张三")
        assertNull(unsure.group)
        assertEquals("备注名", unsure.title)
        assertEquals(false, unsure.titleIsSpeaker)
        // 群聊却没有会话名:标题是发言人,认不了会话,记上一笔;标题不动
        val nameless = styled("张三", null, true, "张三")
        assertEquals("张三", nameless.title)
        assertTrue(nameless.titleIsSpeaker)
        assertEquals(true, nameless.group)
        // 私聊:没有会话名,标题就是对方
        val p = styled("张三", null, false, "张三")
        assertEquals("张三", p.title)
        assertEquals(false, p.group)
        // 会话名只有空白、控制字符的当没有
        assertEquals("周末爬山群: 张三", styled("周末爬山群: 张三", " \u200B ", true, "张三").title)
    }

    // 已知的误判(写在说明里):QQ 私聊里对方发来「时间: 明天」这种话,会被当成群里叫「时间」的人说的;会话还是算在对方头上
    @Test fun knownMisreadKeepsTheConversation() {
        val r = shaped("com.tencent.mobileqq", "张三", "时间: 明天")
        assertEquals("张三", r.title)
        assertEquals("时间", r.sender)
    }

    // 拆好之后各处看到的:判定的输入、提醒、归纳的事件、收件箱里先后的几段,群里的都带着是谁说的,标题是干净的会话名
    @Test fun shapedRecordIsWhatEveryConsumerSees() {
        val r = NoticeShapes.apply(NoticeRecord("k", "com.tencent.mobileqq", 10, "家长群(3条新消息)", "王老师: 明天开会"))
        val input = NoticeTriage.buildInput(listOf(TriageItem(r)), "English")
        assertTrue(input, "家长群" in input && "王老师: 明天开会" in input && "条新消息" !in input)
        // 群聊还是私聊写给模型看,在不可信那一块外面
        assertTrue(input, input.lines().contains("chat: group"))
        val hints = NoticeHints()
        hints.sync(listOf(r), mapOf("k" to TriageMark.Done(10, TriageVerdict(TriageDecision.Surface, "why"))), true)
        assertEquals("家长群" to "王老师: 明天开会", hints.state.value.list.single().let { it.title to it.text })
        val buffer = LearnBuffer()
        buffer.capture(r)
        val event = buffer.begin("com.tencent.mobileqq").single()
        assertEquals("家长群" to "王老师: 明天开会", event.title to event.text)
        assertEquals(true, event.group)
        assertTrue(NoticeLearn.buildInput("com.tencent.mobileqq", listOf(event), emptyList(), "English").lines().contains("chat: group"))
        val inbox = NoticeInbox()
        inbox.add(r)
        inbox.add(NoticeShapes.apply(NoticeRecord("k", "com.tencent.mobileqq", 20, "家长群(4条新消息)", "李老师: 收到")))
        assertEquals(listOf("王老师: 明天开会", "李老师: 收到"), inbox.snapshot().history.getValue("k").map { it.text })
        assertEquals(listOf("家长群", "家长群"), inbox.snapshot().history.getValue("k").map { it.title })
        // 同一个群不同条数的标题算出同一个联系人
        assertEquals(
            ContactIdentity.of("com.tencent.mobileqq", "k", "家长群(3条新消息)")!!.id,
            ContactIdentity.of(r.packageName, r.key, r.title)!!.id,
        )
    }
}
