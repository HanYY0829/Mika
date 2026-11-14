package com.wangzan101.mika.core.proactive

import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DigestPoolTest {
    private val utc = ZoneOffset.UTC
    private val day = LearnTuning.DAY_MS
    private val t0 = 20_000 * day + 9 * 3_600_000L
    private var n = 0
    private fun pool(data: DigestData = DigestData()) = DigestPool(data) { "d${++n}" }
    private fun entry(at: Long, title: String = "家长群", pkg: String = "com.chat") = NoticeEntry(pkg, at, title, "王老师", "x", keyId = "k", sig = at.toInt())

    @Test fun notesCarryTheirGroundsNotTheText() {
        val p = pool()
        val entries = listOf(entry(t0), entry(t0 + 60_000), entry(t0 + 120_000, title = "另一个群"))
        assertEquals(2, p.add("com.chat", entries, listOf(NoteSignal(listOf(0, 1), "王老师通知明天开会"), NoteSignal(listOf(1, 2, 9), "两个群都在说春游")), utc))
        val (a, b) = p.notes(20_000)
        assertEquals(DigestNote("d1", "com.chat", 20_000, t0, t0 + 60_000, 2, "家长群", "王老师通知明天开会"), a)
        // 依据的几段不是同一个会话:不写会话名
        assertNull(b.title)
        assertEquals(3 - 1, b.count)
        // 没有依据、没有文字的不记
        assertEquals(0, p.add("com.chat", entries, listOf(NoteSignal(listOf(7), "x"), NoteSignal(listOf(0), " ")), utc))
    }

    // 日子取依据的通知里最新那段的;会话名要过和联系人显示名一样的检查(号码、网址不当名字存)
    @Test fun dayAndTitle() {
        val p = pool()
        p.add("com.chat", listOf(entry(t0 + day - 9 * 3_600_000L - 1), entry(t0 + day)), listOf(NoteSignal(listOf(0, 1), "跨了午夜")), utc)
        assertEquals(20_001L, p.all().single().day)
        p.add("com.sms", listOf(entry(t0, title = "13812345678", pkg = "com.sms")), listOf(NoteSignal(listOf(0), "有人发来短信")), utc)
        assertNull(p.all().last().title)
    }

    @Test fun purgeExpireAndRoundTrip() {
        val p = pool()
        p.add("com.chat", listOf(entry(t0)), listOf(NoteSignal(listOf(0), "a")), utc)
        p.add("com.mail", listOf(entry(t0 + day, pkg = "com.mail")), listOf(NoteSignal(listOf(0), "b")), utc)
        assertEquals(p.all(), pool(p.data()).all())
        assertFalse(p.expire(20_000 + LearnTuning.NOTES_KEEP_DAYS))
        assertTrue(p.expire(20_001 + LearnTuning.NOTES_KEEP_DAYS))
        assertEquals(listOf("b"), p.all().map { it.text })
        assertTrue(p.removePackages { it != "com.mail" })
        assertTrue(p.all().isEmpty())
        assertFalse(p.clear())
        p.add("com.chat", listOf(entry(t0)), listOf(NoteSignal(listOf(0), "c")), utc)
        assertTrue(p.delete(p.all().single().id))
    }

    private fun seeded(): DigestPool = pool().also { p ->
        p.add("com.chat", listOf(entry(t0)), listOf(NoteSignal(listOf(0), "聊天的事")), utc)
        p.add("com.mail", listOf(entry(t0 + 60_000, pkg = "com.mail")), listOf(NoteSignal(listOf(0), "邮件的事")), utc)
        p.add("com.chat", listOf(entry(t0 + day)), listOf(NoteSignal(listOf(0), "第二天的事")), utc)
    }

    // 该做哪一天:今天之前、有句子、还没有完整归纳、那天的通知都做过分段归纳的最早一天
    @Test fun dueDayIsTheEarliestSettledPastDayWithoutAFullDigest() {
        val p = seeded()
        assertNull(p.dueDay(20_000) { true })
        assertEquals(20_000L, p.dueDay(20_001) { true })
        assertNull(p.dueDay(20_001) { false })
        assertEquals(20_000L, p.dueDay(20_005) { true })
        // 「到现在为止」的那份不算做完;完整的做过之后轮到下一天;一句都没有的也算做过
        p.setDay(20_000, p.dayInput(20_000), listOf(NoteSignal(listOf(0), "x")), t0, partial = true)
        assertEquals(20_000L, p.dueDay(20_005) { true })
        p.setDay(20_000, p.dayInput(20_000), emptyList(), t0, partial = false)
        assertEquals(20_001L, p.dueDay(20_005) { true })
        assertEquals(emptyList<DigestLine>(), p.day(20_000)!!.lines)
        // 那天的通知还有没做过分段归纳的:先等等,不跳到后一天去
        assertNull(p.dueDay(20_005) { it != 20_001L })
    }

    // 当日归纳的每句记着依据的句子和它们来自哪些 App;指不上依据的不记
    @Test fun dayLinesKeepTheirGrounds() {
        val p = seeded()
        val input = p.dayInput(20_000)
        p.setDay(20_000, input, listOf(NoteSignal(listOf(0, 1), "两件事"), NoteSignal(listOf(7), "没依据"), NoteSignal(listOf(1), " ")), t0 + 5, partial = false)
        val d = p.day(20_000)!!
        assertEquals(listOf(DigestLine("两件事", input.map { it.id }, listOf("com.chat", "com.mail"))), d.lines)
        assertEquals(2, d.noteCount)
        assertEquals(t0 + 5, d.madeAtMs)
        assertEquals(listOf(20_000L), p.days().map { it.day })
        assertEquals(p.data(), pool(p.data()).data())
    }

    // 不再归纳的 App:它的句子去掉,当日归纳里依据过它的那几句也去掉(哪怕同时依据了别的 App)
    @Test fun purgingAnAppRemovesTheDayLinesThatDrewOnIt() {
        val p = seeded()
        val input = p.dayInput(20_000)
        p.setDay(20_000, input, listOf(NoteSignal(listOf(0, 1), "两个 App 的"), NoteSignal(listOf(1), "只有邮件的")), t0, partial = false)
        assertTrue(p.removePackages { it != "com.chat" })
        assertEquals(listOf("只有邮件的"), p.day(20_000)!!.lines.map { it.text })
        assertEquals(listOf("邮件的事"), p.all().map { it.text })
        assertFalse(p.removePackages { it != "com.chat" })
    }

    @Test fun daysExpireAndCanBeDeleted() {
        val p = seeded()
        p.setDay(20_000, p.dayInput(20_000), listOf(NoteSignal(listOf(0), "x")), t0, partial = false)
        assertTrue(p.expire(20_000 + LearnTuning.NOTES_KEEP_DAYS + 1))
        assertEquals(1, p.days().size)
        assertTrue(p.expire(20_000 + LearnTuning.DAYS_KEEP + 1))
        assertTrue(p.days().isEmpty())
        p.setDay(20_010, emptyList(), emptyList(), t0, partial = false)
        assertTrue(p.deleteDay(20_010))
        assertFalse(p.deleteDay(20_010))
        p.setDay(20_011, emptyList(), emptyList(), t0, partial = false)
        assertTrue(p.clear())
        assertTrue(p.days().isEmpty())
    }

    // 解锁时先做了昨天的归纳,昨晚的通知之后才补收进来、写成了句子:那一天要重做一份,而且只重做一次
    @Test fun aDayIsRedoneWhenNotesAreWrittenAfterItsDigest() {
        val p = seeded()
        p.setDay(20_000, p.dayInput(20_000), listOf(NoteSignal(listOf(0), "早上做的")), 1_000, partial = false)
        assertEquals(20_001L, p.dueDay(20_005) { true })
        p.add("com.chat", listOf(entry(t0 + 3_600_000)), listOf(NoteSignal(listOf(0), "后来才补收到的")), utc, nowMs = 2_000)
        assertEquals(20_000L, p.dueDay(20_005) { true })
        p.setDay(20_000, p.dayInput(20_000), listOf(NoteSignal(listOf(2), "重做的")), 3_000, partial = false)
        assertEquals(20_001L, p.dueDay(20_005) { true })
        // 句子多过一次能放进请求的数量时也不会一直重做(看的是有没有新写下的句子,不是数量对不对得上)
        val busy = pool()
        repeat(LearnTuning.DAY_NOTES_MAX + 5) { i -> busy.add("com.chat", listOf(entry(t0 + i)), listOf(NoteSignal(listOf(0), "n$i")), utc, nowMs = 10) }
        busy.setDay(20_000, busy.dayInput(20_000), emptyList(), 20, partial = false)
        assertNull(busy.dueDay(20_005) { true })
    }

    // 四级留存是同一个算法:从管的最后一天算起留满 N 天,第 N+1 天去掉
    @Test fun everyLevelIsKeptTheSameWayCountedFromItsLastDay() {
        val today = 20_000L
        fun line() = listOf(DigestLine("x", apps = listOf("com.chat")))
        val week = DigestPool.weekOf(today - 300)
        val month = DigestPool.monthOf(today - 800)
        val monthEnd = java.time.LocalDate.ofEpochDay(month).plusMonths(1).toEpochDay() - 1
        val data = DigestData(
            notes = listOf(DigestNote("n", "com.chat", today, 1L, 1L, 1, text = "x", madeAtMs = 1L)),
            days = listOf(DayDigest(today, 1L, line())),
            weeks = listOf(SpanDigest(week, 1L, line())),
            months = listOf(SpanDigest(month, 1L, line())),
        )
        // 每一级:最后一天之后第 N 天还在,第 N+1 天没了
        fun alive(at: Long) = pool(data).also { it.expire(at) }
        assertEquals(1, alive(today + LearnTuning.NOTES_KEEP_DAYS).all().size)
        assertEquals(0, alive(today + LearnTuning.NOTES_KEEP_DAYS + 1).all().size)
        assertEquals(1, alive(today + LearnTuning.DAYS_KEEP).days().size)
        assertEquals(0, alive(today + LearnTuning.DAYS_KEEP + 1).days().size)
        assertEquals(1, alive(week + 6 + LearnTuning.WEEKS_KEEP_DAYS).spans(SpanKind.Week).size)
        assertEquals(0, alive(week + 6 + LearnTuning.WEEKS_KEEP_DAYS + 1).spans(SpanKind.Week).size)
        // 月归纳从那个月的最后一天算(从月初算的话,早删将近一个月)
        assertEquals(1, alive(monthEnd + LearnTuning.MONTHS_KEEP_DAYS).spans(SpanKind.Month).size)
        assertEquals(0, alive(monthEnd + LearnTuning.MONTHS_KEEP_DAYS + 1).spans(SpanKind.Month).size)
        assertEquals(1, alive(month + LearnTuning.MONTHS_KEEP_DAYS + 1).spans(SpanKind.Month).size)
    }

    // 一天的句子多过一次请求放得下的:各个 App 轮流取、每个 App 留新的(话多的 App 不把别的挤光),没放进去的记数;
    // 记了数不等于要重做(那样会一直重做)
    @Test fun aDayWithTooManyNotesKeepsEveryAppAndCountsWhatWasLeftOut() {
        val p = pool()
        val max = LearnTuning.DAY_NOTES_MAX
        // 上午:别的 App 各一句;之后聊天 App 一口气写了比上限还多的句子
        p.add("com.mail", listOf(entry(t0, pkg = "com.mail")), listOf(NoteSignal(listOf(0), "上午的邮件")), utc, nowMs = 10)
        p.add("com.bank", listOf(entry(t0 + 1, pkg = "com.bank")), listOf(NoteSignal(listOf(0), "上午的到账")), utc, nowMs = 10)
        repeat(max + 10) { i -> p.add("com.chat", listOf(entry(t0 + 100 + i)), listOf(NoteSignal(listOf(0), "聊天 $i")), utc, nowMs = 10) }
        val input = p.dayInput(20_000)
        assertEquals(max, input.size)
        assertTrue(input.any { it.text == "上午的邮件" } && input.any { it.text == "上午的到账" })
        // 聊天 App 留的是新的那些;整份按时间由旧到新
        assertTrue(input.any { it.text == "聊天 ${max + 9}" })
        assertFalse(input.any { it.text == "聊天 0" })
        assertEquals(input.sortedBy { it.fromMs }, input)
        p.setDay(20_000, input, listOf(NoteSignal(listOf(0), "这一天")), 20, partial = false)
        assertEquals(12, p.day(20_000)!!.leftOut)
        assertEquals(max, p.day(20_000)!!.noteCount)
        assertNull(p.dueDay(20_005) { true })
        // 放得下的一天:没有漏的
        val small = seeded()
        small.setDay(20_000, small.dayInput(20_000), emptyList(), 20, partial = false)
        assertEquals(0, small.day(20_000)!!.leftOut)
    }

    // 周(月)归纳的输入放不下时同样记数
    @Test fun aSpanRecordsHowManyLowerLinesDidNotFit() {
        val long = "长".repeat(LearnTuning.SPAN_INPUT_CHARS / 2 - 10)
        val week = DigestPool.weekOf(20_000)
        val p = pool(DigestData(days = (0..2).map { d -> DayDigest(week + d, 1L, listOf(DigestLine("$d$long", apps = listOf("com.chat")))) }))
        val input = p.spanInput(SpanKind.Week, week, withPartial = false)
        assertEquals(2, input.size)
        p.setSpan(SpanKind.Week, week, input, listOf(NoteSignal(listOf(0), "这一周")), 5, partial = false)
        assertEquals(1, p.spans(SpanKind.Week).single().leftOut)
        // 这一周的句子都放得下,但其中一天的当日归纳自己做的时候漏了 7 句:这一周也不算看全了;月归纳接着往上带
        val month = DigestPool.monthOfWeek(week)
        val q = pool(DigestData(days = listOf(
            DayDigest(week, 1L, listOf(DigestLine("周一", apps = listOf("com.chat"))), leftOut = 7),
            DayDigest(week + 1, 1L, listOf(DigestLine("周二", apps = listOf("com.chat")))),
        )))
        q.setSpan(SpanKind.Week, week, q.spanInput(SpanKind.Week, week, withPartial = false), listOf(NoteSignal(listOf(0, 1), "这一周")), 5, partial = false)
        assertEquals(7, q.spans(SpanKind.Week).single().leftOut)
        q.setSpan(SpanKind.Month, month, q.spanInput(SpanKind.Month, month, withPartial = false), listOf(NoteSignal(listOf(0), "这个月")), 6, partial = false)
        assertEquals(7, q.spans(SpanKind.Month).single().leftOut)
    }

    // 只在周、月归纳里还有它的句子的 App(当日的那几句已经过期):清掉它时这些句子也去掉,别的 App 的留着
    @Test fun purgingAnAppAlsoDropsWeekAndMonthLinesThatCiteOnlyThem() {
        val p = pool(DigestData(
            weeks = listOf(SpanDigest(19_999, 1L, listOf(DigestLine("聊天的一周", apps = listOf("com.chat")), DigestLine("邮件的一周", apps = listOf("com.mail"))))),
            months = listOf(SpanDigest(19_980, 1L, listOf(DigestLine("两个合在一起的", apps = listOf("com.chat", "com.mail"))))),
        ))
        assertTrue(p.removePackages { it != "com.chat" })
        assertEquals(listOf("邮件的一周"), p.spans(SpanKind.Week).single().lines.map { it.text })
        assertTrue(p.spans(SpanKind.Month).single().lines.isEmpty())
        assertFalse(p.removePackages { it != "com.chat" })
    }

    // 请求在途时某个 App 被清掉了:合并了它的那一句整句不要(文字里还带着它的事),不是只把它从依据里划掉
    @Test fun aLineThatDrewOnAPurgedNoteIsDroppedWhole() {
        val p = seeded()
        val input = p.dayInput(20_000)
        p.removePackages { it != "com.mail" }
        p.setDay(20_000, input, listOf(NoteSignal(listOf(0, 1), "聊天和邮件合在一起的"), NoteSignal(listOf(0), "只有聊天的")), t0, partial = false)
        assertEquals(listOf("只有聊天的"), p.day(20_000)!!.lines.map { it.text })
        assertEquals(listOf("com.chat"), p.day(20_000)!!.lines.single().apps)
    }

    // 用户删掉一句分段归纳:当日归纳里依据过它的句子也去掉(哪怕同时依据了别的句子);别的句子留着,这一天也不因此重做
    @Test fun deletingANoteRemovesTheDayLinesBuiltOnIt() {
        val p = pool()
        p.add("com.chat", listOf(entry(t0), entry(t0 + 1), entry(t0 + 2)),
            listOf(NoteSignal(listOf(0), "第一句"), NoteSignal(listOf(1), "第二句"), NoteSignal(listOf(2), "第三句")), utc, nowMs = t0 + 10)
        val input = p.dayInput(20_000)
        p.setDay(20_000, input, listOf(NoteSignal(listOf(0, 1), "合了一二"), NoteSignal(listOf(2), "只有三")), t0 + 20, partial = false)
        assertTrue(p.delete(input[0].id))
        assertEquals(listOf("只有三"), p.day(20_000)!!.lines.map { it.text })
        assertEquals(2, p.notes(20_000).size)
        assertNull(p.dueDay(20_001) { true })
        // 没有这一句:什么都不动
        assertFalse(p.delete("nope"))
    }


    // ---- 一份归纳的一句概述 ----

    // 记下、读回;老的存盘里没有这个字段,读出来是空的
    @Test fun theBriefIsStoredWithTheDigestAndSurvivesARoundTrip() {
        val p = seeded()
        val input = p.dayInput(20_000)
        p.setDay(20_000, input, listOf(NoteSignal(listOf(0), "聊天的"), NoteSignal(listOf(1), "邮件的")), t0, partial = false, brief = "聊天和邮件各一件")
        assertEquals("聊天和邮件各一件", p.day(20_000)!!.brief)
        assertEquals("聊天和邮件各一件", pool(p.data()).day(20_000)!!.brief)
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val old = json.decodeFromString(DigestData.serializer(), """{"days":[{"day":20000,"madeAtMs":1,"lines":[{"text":"x"}]}]}""")
        assertEquals("", pool(old).day(20_000)!!.brief)
        // 没给的、重做时没给的:空的(不留着上一份的)
        p.setDay(20_000, input, listOf(NoteSignal(listOf(0), "聊天的")), t0 + 1, partial = false)
        assertEquals("", p.day(20_000)!!.brief)
    }

    // 收下的句子比给的少(依据不在了、指不上依据)、或一句都没有:概述不留
    @Test fun theBriefIsNotKeptWhenALineWasNotStored() {
        val p = seeded()
        val input = p.dayInput(20_000)
        p.setDay(20_000, input, listOf(NoteSignal(listOf(0), "聊天的"), NoteSignal(listOf(7), "没依据")), t0, partial = false, brief = "两件事")
        assertEquals("", p.day(20_000)!!.brief)
        p.setDay(20_000, input, emptyList(), t0, partial = false, brief = "没什么事")
        assertEquals("", p.day(20_000)!!.brief)
        // 请求在途时那个 App 被清掉了:依据过它的那句整句不要,概述也不要
        p.removePackages { it != "com.mail" }
        p.setDay(20_000, input, listOf(NoteSignal(listOf(0), "聊天的"), NoteSignal(listOf(1), "邮件的")), t0, partial = false, brief = "聊天和邮件")
        assertEquals(listOf("聊天的"), p.day(20_000)!!.lines.map { it.text })
        assertEquals("", p.day(20_000)!!.brief)
    }

    // 这一份里有句子被去掉:概述一并清掉(删了的事不能还留在概述里);没动到的那一份不清
    @Test fun removingALineClearsThatDigestsBrief() {
        fun fresh() = seeded().also { p ->
            p.setDay(20_000, p.dayInput(20_000), listOf(NoteSignal(listOf(0), "聊天的"), NoteSignal(listOf(1), "邮件的")), t0, partial = false, brief = "头一天")
            p.setDay(20_001, p.dayInput(20_001), listOf(NoteSignal(listOf(0), "第二天的")), t0 + day, partial = false, brief = "第二天")
        }
        // 删一句分段归纳
        fresh().let { p ->
            assertTrue(p.delete(p.notes(20_000).first().id))
            assertEquals(listOf("邮件的"), p.day(20_000)!!.lines.map { it.text })
            assertEquals("", p.day(20_000)!!.brief)
            assertEquals("第二天", p.day(20_001)!!.brief)
        }
        // 清掉一个 App
        fresh().let { p ->
            assertTrue(p.removePackages { it != "com.mail" })
            assertEquals("", p.day(20_000)!!.brief)
            assertEquals("第二天", p.day(20_001)!!.brief)
        }
        // 删掉的那句没有被哪一句存着的句子依据过(模型写多了、没存下来的那几句可能说过它):概述照样清
        seeded().let { p ->
            p.setDay(20_000, p.dayInput(20_000), listOf(NoteSignal(listOf(0), "只写了聊天的")), t0, partial = false, brief = "聊天和邮件")
            val mail = p.notes(20_000).single { it.packageName == "com.mail" }
            assertTrue(p.delete(mail.id))
            assertEquals(listOf("只写了聊天的"), p.day(20_000)!!.lines.map { it.text })
            assertEquals("", p.day(20_000)!!.brief)
        }
        seeded().let { p ->
            p.setDay(20_000, p.dayInput(20_000), listOf(NoteSignal(listOf(0), "只写了聊天的")), t0, partial = false, brief = "聊天和邮件")
            assertTrue(p.removePackages { it != "com.mail" })
            assertEquals(1, p.day(20_000)!!.lines.size)
            assertEquals("", p.day(20_000)!!.brief)
        }
        // 请求在途时删了一句,回复里存下的句子一句没少(都只引了还在的那句),概述却是照着全部写的:不要
        seeded().let { p ->
            val sent = p.dayInput(20_000)
            assertTrue(p.delete(sent.single { it.packageName == "com.mail" }.id))
            val chat = sent.indexOfFirst { it.packageName == "com.chat" }
            p.setDay(20_000, sent, listOf(NoteSignal(listOf(chat), "只写了聊天的")), t0, partial = false, brief = "聊天和邮件")
            assertEquals(listOf("只写了聊天的"), p.day(20_000)!!.lines.map { it.text })
            assertEquals("", p.day(20_000)!!.brief)
        }
        // 周归纳的请求在途时删了其中一天的当日归纳:同样不要概述(删周归纳时在途的月归纳也一样)
        seeded().let { p ->
            p.setDay(20_000, p.dayInput(20_000), listOf(NoteSignal(listOf(0), "第一天的事")), t0, partial = false)
            p.setDay(20_001, p.dayInput(20_001), listOf(NoteSignal(listOf(0), "第二天的事")), t0 + LearnTuning.DAY_MS, partial = false)
            val week = DigestPool.weekOf(20_000)
            val sent = p.spanInput(SpanKind.Week, week, true)
            assertEquals(2, sent.size)
            assertTrue(p.deleteDay(20_000))
            val kept = sent.indexOfFirst { it.line.text == "第二天的事" }
            p.setSpan(SpanKind.Week, week, sent, listOf(NoteSignal(listOf(kept), "只写了第二天")), t0 + 2 * LearnTuning.DAY_MS, partial = true, brief = "两天的事")
            assertEquals(listOf("只写了第二天"), p.span(SpanKind.Week, week)!!.lines.map { it.text })
            assertEquals("", p.span(SpanKind.Week, week)!!.brief)
        }
        // 分段归纳到期之后再清 App:那几天有过哪些 App 已经查不到了,存下的句子也没引过它(模型写多了的那几句引过)。
        // 概述自己记着是照着哪些 App 的句子写的,照样清;别的 App 不受影响
        seeded().let { p ->
            p.setDay(20_000, p.dayInput(20_000), listOf(NoteSignal(listOf(0), "只写了聊天的")), t0, partial = false, brief = "聊天和邮件")
            p.setDay(20_001, p.dayInput(20_001), listOf(NoteSignal(listOf(0), "第二天的")), t0 + day, partial = false, brief = "第二天")
            assertEquals(listOf("com.chat", "com.mail"), p.day(20_000)!!.briefApps)
            assertTrue(p.expire(20_000 + LearnTuning.NOTES_KEEP_DAYS + 2))
            assertTrue(p.all().isEmpty())
            assertFalse("和这几份都无关的 App:什么都不动", p.removePackages { it != "com.other" })
            assertTrue(p.removePackages { it != "com.mail" })
            assertEquals(listOf("只写了聊天的"), p.day(20_000)!!.lines.map { it.text })
            assertEquals("", p.day(20_000)!!.brief)
            assertEquals("第二天", p.day(20_001)!!.brief)
        }
        // 周归纳的概述也记着来源
        seeded().let { p ->
            p.setDay(20_000, p.dayInput(20_000), listOf(NoteSignal(listOf(0), "聊天的"), NoteSignal(listOf(1), "邮件的")), t0, partial = false)
            val week = DigestPool.weekOf(20_000)
            val sent = p.spanInput(SpanKind.Week, week, true)
            val chat = sent.indexOfFirst { it.line.apps == listOf("com.chat") }
            p.setSpan(SpanKind.Week, week, sent, listOf(NoteSignal(listOf(chat), "只写了聊天的")), t0 + day, partial = true, brief = "聊天和邮件")
            // 没有哪句还存着的周归纳句子引过邮件;当日归纳里的那句会被去掉,周归纳的概述按来源清
            assertTrue(p.removePackages { it != "com.mail" })
            assertEquals(listOf("只写了聊天的"), p.span(SpanKind.Week, week)!!.lines.map { it.text })
            assertEquals("", p.span(SpanKind.Week, week)!!.brief)
        }
        // 没记来源的概述(加这一项之前存下的):清任何一个 App 时一并清掉
        pool(DigestData(days = listOf(DayDigest(20_000, t0, listOf(DigestLine("聊天的", apps = listOf("com.chat"))), brief = "以前存下的概述")))).let { p ->
            assertTrue(p.removePackages { it != "com.mail" })
            assertEquals("", p.day(20_000)!!.brief)
            assertEquals(1, p.day(20_000)!!.lines.size)
        }
        // 分段归纳到期只是划掉句子上记的依据,句子还在:概述留着
        fresh().let { p ->
            assertTrue(p.expire(20_000 + LearnTuning.NOTES_KEEP_DAYS + 1))
            assertEquals(2, p.day(20_000)!!.lines.size)
            assertEquals("头一天", p.day(20_000)!!.brief)
        }
    }

    // ---- 今天「到现在为止」的那一份自动做 ----

    /** 给今天(20_000)记下 [n] 句分段归纳,都是 [madeAt] 写下的。 */
    private fun today(p: DigestPool, n: Int, madeAt: Long) {
        val entries = (0 until n).map { entry(t0 + it) }
        p.add("com.chat", entries, entries.indices.map { NoteSignal(listOf(it), "第 ${p.all().size + it + 1} 句") }, utc, nowMs = madeAt)
    }

    // 句子攒够了、或者攒了两句以上并且等得够久了,才自动做;一句不做
    @Test fun todaysDigestIsDueOnceEnoughNotesHaveGatheredOrWaited() {
        val hour = 3_600_000L
        val p = pool()
        assertFalse(p.todayAutoDue(20_000, t0))
        today(p, 1, t0)
        assertFalse("只有一句", p.todayAutoDue(20_000, t0 + 10 * hour))
        today(p, 1, t0 + hour)
        assertFalse("两句,最早的才等了一个多小时", p.todayAutoDue(20_000, t0 + LearnTuning.TODAY_AUTO_WAIT_MS - 1))
        assertTrue("两句,最早的等够了", p.todayAutoDue(20_000, t0 + LearnTuning.TODAY_AUTO_WAIT_MS))
        today(p, LearnTuning.TODAY_AUTO_READY_NOTES - 2, t0 + hour)
        assertTrue("够数了就不用等", p.todayAutoDue(20_000, t0 + hour))
        // 别的日子的句子不算今天的
        assertFalse(p.todayAutoDue(20_001, t0 + 10 * hour))
    }

    // 做过一份之后:要有新句子,而且离上一份够久
    @Test fun afterOneDigestTheNextWaitsForNewNotesAndTheInterval() {
        val hour = 3_600_000L
        val p = pool()
        today(p, LearnTuning.TODAY_AUTO_READY_NOTES, t0)
        p.setDay(20_000, p.dayInput(20_000), listOf(NoteSignal(listOf(0), "到现在为止")), t0 + hour, partial = true)
        assertFalse("没有新句子", p.todayAutoDue(20_000, t0 + 20 * hour))
        today(p, LearnTuning.TODAY_AUTO_READY_NOTES, t0 + 2 * hour)
        assertFalse("有新句子,但离上一份还不够久", p.todayAutoDue(20_000, t0 + hour + LearnTuning.TODAY_AUTO_INTERVAL_MS - 1))
        assertTrue(p.todayAutoDue(20_000, t0 + hour + LearnTuning.TODAY_AUTO_INTERVAL_MS))
        // 用户删掉了今天的:不自动做回来
        assertTrue(p.deleteDay(20_000))
        assertFalse(p.todayAutoDue(20_000, t0 + 20 * hour))
    }
}
