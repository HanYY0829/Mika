package com.wangzan101.mika.core.proactive

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 周归纳(第 3 级)、月归纳(第 4 级)在 [DigestPool] 里的那部分:该做哪一份、输入、记下、删除往上传、留多久。 */
class DigestSpanTest {
    private val utc = ZoneOffset.UTC
    private val dayMs = LearnTuning.DAY_MS
    private var n = 0
    private fun pool(data: DigestData = DigestData()) = DigestPool(data) { "d${++n}" }
    private fun day(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).toEpochDay()

    // 2024-09-30 是周一;这一周到 10-06(周日),算十月的
    private val mon = day(2024, 9, 30)
    private val nextMon = mon + 7
    private val oct = day(2024, 10, 1)
    private val nov = day(2024, 11, 1)

    private val noWait = { _: Long, _: Boolean -> false }
    private val waitIfOwed = { _: Long, owed: Boolean -> owed }
    private val noSkip = { _: Long -> false }

    /** 给 [day] 这一天记下几句分段归纳(一句一段通知),不做当日归纳。返回这几句。 */
    private fun notes(p: DigestPool, day: Long, vararg texts: String, pkg: String = "com.chat", madeAt: Long = day * dayMs + 20 * 3_600_000L): List<DigestNote> {
        val before = p.all().map { it.id }.toSet()
        val entries = texts.indices.map { NoticeEntry(pkg, day * dayMs + 9 * 3_600_000L + it, "群", "甲", "x", keyId = "k", sig = it) }
        p.add(pkg, entries, texts.mapIndexed { i, t -> NoteSignal(listOf(i), "句:$t") }, utc, nowMs = madeAt)
        return p.all().filter { it.id !in before }
    }

    /** 给 [day] 这一天记下分段归纳,并做一份当日归纳:一句分段归纳一句(文字就是 [texts])。 */
    private fun dayWith(p: DigestPool, day: Long, vararg texts: String, pkg: String = "com.chat", partial: Boolean = false, madeAt: Long = day * dayMs + 23 * 3_600_000L): List<DigestNote> {
        val added = notes(p, day, *texts, pkg = pkg, madeAt = madeAt - 1)
        val input = p.dayInput(day)
        p.setDay(day, input, input.indices.map { NoteSignal(listOf(it), input[it].text.removePrefix("句:")) }, madeAt, partial)
        return added
    }

    private fun week(p: DigestPool, start: Long, at: Long, partial: Boolean = false, pick: (List<SpanInput>) -> List<NoteSignal> = { all -> all.indices.map { NoteSignal(listOf(it), "周:" + all[it].line.text) } }) {
        val input = p.spanInput(SpanKind.Week, start, withPartial = partial)
        p.setSpan(SpanKind.Week, start, input, pick(input), at, partial)
    }

    @Test fun weeksRunMondayToSundayAndBelongToTheMonthTheyEndIn() {
        assertEquals(mon, DigestPool.weekOf(mon))
        assertEquals(mon, DigestPool.weekOf(mon + 6))
        assertEquals(nextMon, DigestPool.weekOf(mon + 7))
        assertEquals(mon - 7, DigestPool.weekOf(mon - 1))
        // 1970-01-01 是周四:它那一周的周一在纪元之前
        assertEquals(-3L, DigestPool.weekOf(0))
        assertEquals(oct, DigestPool.monthOf(day(2024, 10, 31)))
        assertEquals(nov, DigestPool.monthOf(nov))
        // 9-30 开始的一周在 10-06 结束:算十月的;10-28 开始的一周在 11-03 结束:算十一月的
        assertEquals(oct, DigestPool.monthOfWeek(mon))
        assertEquals(nov, DigestPool.monthOfWeek(day(2024, 10, 28)))
    }

    // 该做哪一周:已经过完、有完整的当日归纳、还没有完整周归纳的最早一周;里面还欠着当日归纳的日子先等它
    @Test fun dueWeekIsTheEarliestFinishedWeekWhoseDaysAreDone() {
        val p = pool()
        dayWith(p, mon, "周一的事")
        dayWith(p, mon + 2, "周三的事")
        dayWith(p, nextMon, "下周一的事")
        // 这一周还没过完
        assertNull(p.dueWeek(mon + 6, waitIfOwed, noSkip))
        assertEquals(mon, p.dueWeek(nextMon, waitIfOwed, noSkip))
        // 周五有句子还没做当日归纳:先等;说了不等(试了几次都不成)就做
        notes(p, mon + 4, "周五的事")
        assertNull(p.dueWeek(nextMon, waitIfOwed, noSkip))
        assertEquals(mon, p.dueWeek(nextMon, noWait, noSkip))
        // 调用方自己也可以叫它等(那天还有通知没做过分段归纳)
        assertNull(p.dueWeek(nextMon, { d, _ -> d == mon + 5 }, noSkip))
        assertNull(p.dueWeek(nextMon, noWait) { it == mon })
        // 做完之后轮到下一周(它过完了的话)
        week(p, mon, at = nextMon * dayMs)
        assertNull(p.dueWeek(nextMon + 6, noWait, noSkip))
        assertEquals(nextMon, p.dueWeek(nextMon + 7, noWait, noSkip))
    }

    // 周归纳做完之后,它里面某一天的当日归纳又重做过(补收到了通知):这一周跟着重做一次,而且只一次
    @Test fun aWeekIsRedoneWhenOneOfItsDaysIsRedoneLater() {
        val p = pool()
        dayWith(p, mon, "周一的事")
        week(p, mon, at = nextMon * dayMs)
        assertNull(p.dueWeek(nextMon, waitIfOwed, noSkip))
        p.setDay(mon, p.dayInput(mon), listOf(NoteSignal(listOf(0), "重做的周一")), nextMon * dayMs + 5, partial = false)
        assertEquals(mon, p.dueWeek(nextMon, waitIfOwed, noSkip))
        week(p, mon, at = nextMon * dayMs + 9)
        assertNull(p.dueWeek(nextMon, waitIfOwed, noSkip))
        assertEquals(listOf("周:重做的周一"), p.span(SpanKind.Week, mon)!!.lines.map { it.text })
        // 做的时间不早于下一级:哪怕时钟往回拨了,也不会一直重做
        p.setDay(mon, p.dayInput(mon), emptyList(), nextMon * dayMs + 100, partial = false)
        week(p, mon, at = 5)
        assertNull(p.dueWeek(nextMon, waitIfOwed, noSkip))
        assertEquals(nextMon * dayMs + 100, p.span(SpanKind.Week, mon)!!.madeAtMs)
    }

    // 「到现在为止」的那份:自动做的不拿半截的当日归纳当输入;一周过完,到现在为止的周归纳要重做成完整的
    @Test fun partialsAreOnlyForTheUserAndAreRedoneOnceThePeriodEnds() {
        val p = pool()
        dayWith(p, mon, "周一的事")
        dayWith(p, mon + 1, "周二到现在的", partial = true)
        assertEquals(listOf("周一的事"), p.spanInput(SpanKind.Week, mon, withPartial = false).map { it.line.text })
        assertEquals(listOf(mon to "周一的事", mon + 1 to "周二到现在的"), p.spanInput(SpanKind.Week, mon, withPartial = true).map { it.from to it.line.text })
        week(p, mon, at = (mon + 1) * dayMs + 1, partial = true)
        assertTrue(p.span(SpanKind.Week, mon)!!.partial)
        assertEquals(2, p.span(SpanKind.Week, mon)!!.inputCount)
        // 周二过完之前,这一周还欠着当日归纳
        assertNull(p.dueWeek(nextMon, waitIfOwed, noSkip))
        p.setDay(mon + 1, p.dayInput(mon + 1), listOf(NoteSignal(listOf(0), "周二完整的")), (mon + 2) * dayMs, partial = false)
        assertEquals(mon, p.dueWeek(nextMon, waitIfOwed, noSkip))
        // 只有半截的当日归纳的一周:没有可以自动做的
        val q = pool()
        dayWith(q, mon, "到现在的", partial = true)
        assertNull(q.dueWeek(nextMon, noWait, noSkip))
    }

    // 输入太长:各天轮流取,每天留住排在前面的句子,而不是只剩最前面几天
    @Test fun longInputKeepsTheFirstLinesOfEveryDay() {
        val p = pool()
        val long = "长".repeat(280)
        for (d in 0 until 7) dayWith(p, mon + d, *(0 until 15).map { "$d-$it$long" }.toTypedArray())
        val input = p.spanInput(SpanKind.Week, mon, withPartial = false)
        assertTrue(input.sumOf { it.line.text.length } <= LearnTuning.SPAN_INPUT_CHARS)
        val perDay = input.groupBy { it.from }.mapValues { it.value.size }
        assertEquals((0 until 7).map { mon + it }.toSet(), perDay.keys)
        assertTrue(perDay.toString(), perDay.values.max() - perDay.values.min() <= 1)
        // 每天留下的是排在前面的,按天、按原来的次序排
        assertEquals(input.map { it.from }, input.map { it.from }.sorted())
        assertTrue(input.filter { it.from == mon }.mapIndexed { i, l -> l.line.text.startsWith("0-$i") }.all { it })
        // 不长的时候全放
        val q = pool()
        dayWith(q, mon, "a", "b", "c")
        assertEquals(3, q.spanInput(SpanKind.Week, mon, withPartial = false).size)
    }

    // 周归纳的每句往下记到底:哪几天、哪些分段归纳、哪些 App。指不上依据的、依据已经不在了的整句不要
    @Test fun weekLinesKeepTheirGroundsAllTheWayDown() {
        val p = pool()
        val a = dayWith(p, mon, "周一聊天的事")
        val b = dayWith(p, mon + 2, "周三邮件的事", pkg = "com.mail")
        val input = p.spanInput(SpanKind.Week, mon, withPartial = false)
        p.setSpan(SpanKind.Week, mon, input, listOf(NoteSignal(listOf(0, 1), "两天合起来"), NoteSignal(listOf(9), "没依据"), NoteSignal(listOf(1), " ")), 77, partial = false)
        val w = p.span(SpanKind.Week, mon)!!
        assertEquals(
            listOf(DigestLine("两天合起来", notes = (a + b).map { it.id }, apps = listOf("com.chat", "com.mail"), days = listOf(mon, mon + 2))),
            w.lines,
        )
        assertEquals(2, w.inputCount)
        assertEquals(listOf(mon), p.spans(SpanKind.Week).map { it.start })
        assertEquals(p.data(), pool(p.data()).data())
        // 请求在途时周三那句被删了(它依据的分段归纳被用户删掉):合了它的那句整句不要
        p.delete(b.single().id)
        p.setSpan(SpanKind.Week, mon, input, listOf(NoteSignal(listOf(0, 1), "两天合起来"), NoteSignal(listOf(0), "只有周一")), 78, partial = false)
        assertEquals(listOf("只有周一"), p.span(SpanKind.Week, mon)!!.lines.map { it.text })
    }

    /** 两周、各有周归纳,再合成十月的月归纳(每句周归纳一句)。 */
    private fun twoWeeksAndAMonth(p: DigestPool): Pair<List<DigestNote>, List<DigestNote>> {
        val first = dayWith(p, mon, "第一周聊天的事") + dayWith(p, mon + 1, "第一周邮件的事", pkg = "com.mail")
        val second = dayWith(p, nextMon, "第二周聊天的事")
        week(p, mon, at = nextMon * dayMs)
        week(p, nextMon, at = (nextMon + 7) * dayMs)
        val input = p.spanInput(SpanKind.Month, oct, withPartial = false)
        p.setSpan(SpanKind.Month, oct, input, input.indices.map { NoteSignal(listOf(it), "月:" + input[it].line.text) } +
            NoteSignal(input.indices.toList(), "月:三件事合在一起"), nov * dayMs, partial = false)
        return first to second
    }

    // 月归纳:输入是结束在这个月的各周的周归纳;每句记着哪几周、哪几天、哪些分段归纳、哪些 App
    @Test fun monthLinesAreBuiltFromTheWeeksThatEndInTheMonth() {
        val p = pool()
        val (first, second) = twoWeeksAndAMonth(p)
        val m = p.span(SpanKind.Month, oct)!!
        assertEquals(listOf("月:周:第一周聊天的事", "月:周:第一周邮件的事", "月:周:第二周聊天的事", "月:三件事合在一起"), m.lines.map { it.text })
        assertEquals(DigestLine("月:周:第一周邮件的事", notes = listOf(first[1].id), apps = listOf("com.mail"), days = listOf(mon + 1), weeks = listOf(mon)), m.lines[1])
        val all = m.lines[3]
        assertEquals(listOf(mon, nextMon), all.weeks)
        assertEquals(listOf(mon, mon + 1, nextMon), all.days)
        assertEquals((first + second).map { it.id }, all.notes)
        assertEquals(listOf("com.chat", "com.mail"), all.apps)
        assertEquals(p.data(), pool(p.data()).data())
    }

    // 该做哪个月:已经过完、有完整的周归纳、还没有完整月归纳的最早一个月;它里面还欠着周归纳的周先等
    @Test fun dueMonthWaitsForItsWeeks() {
        val p = pool()
        dayWith(p, mon, "第一周的事")
        dayWith(p, nextMon, "第二周的事")
        week(p, mon, at = nextMon * dayMs)
        // 十月还没过完
        assertNull(p.dueMonth(day(2024, 10, 31), waitIfOwed, noSkip))
        // 第二周的周归纳还没做:先等;说了不等就做
        assertNull(p.dueMonth(nov, waitIfOwed, noSkip))
        assertEquals(oct, p.dueMonth(nov, noWait, noSkip))
        assertNull(p.dueMonth(nov, noWait) { it == oct })
        week(p, nextMon, at = (nextMon + 7) * dayMs)
        assertEquals(oct, p.dueMonth(nov, waitIfOwed, noSkip))
        // 这个月里有一周只有句子、连当日归纳都还没做:也等(做完之后那一周就要做周归纳了)
        notes(p, nextMon + 7, "第三周的事")
        assertNull(p.dueMonth(nov, waitIfOwed, noSkip))
        assertEquals(oct, p.dueMonth(nov, noWait, noSkip))
        val input = p.spanInput(SpanKind.Month, oct, withPartial = false)
        p.setSpan(SpanKind.Month, oct, input, emptyList(), nov * dayMs, partial = false)
        assertNull(p.dueMonth(nov, noWait, noSkip))
        // 之后某一周重做了:这个月跟着重做
        week(p, mon, at = nov * dayMs + 5)
        assertEquals(oct, p.dueMonth(nov, noWait, noSkip))
    }

    // 删除往上传:删掉一句分段归纳,各级里依据过它的句子都去掉;别的不动
    @Test fun deletingANoteRemovesEveryLineBuiltOnIt() {
        val p = pool()
        val (first, _) = twoWeeksAndAMonth(p)
        assertTrue(p.delete(first[1].id))
        assertTrue(p.day(mon + 1)!!.lines.isEmpty())
        assertEquals(listOf("周:第一周聊天的事"), p.span(SpanKind.Week, mon)!!.lines.map { it.text })
        assertEquals(listOf("月:周:第一周聊天的事", "月:周:第二周聊天的事"), p.span(SpanKind.Month, oct)!!.lines.map { it.text })
        // 不因此重做(下一级没有重做过)
        assertNull(p.dueWeek(nov, waitIfOwed, noSkip))
        assertNull(p.dueMonth(nov + 31, waitIfOwed, noSkip))
    }

    // 删掉一天的当日归纳:周、月归纳里依据过这一天的句子去掉;删掉一周的周归纳:月归纳里依据过它的去掉
    @Test fun deletingADayOrAWeekRemovesTheLinesAbove() {
        val p = pool()
        twoWeeksAndAMonth(p)
        assertTrue(p.deleteDay(mon))
        assertEquals(listOf("周:第一周邮件的事"), p.span(SpanKind.Week, mon)!!.lines.map { it.text })
        assertEquals(listOf("月:周:第一周邮件的事", "月:周:第二周聊天的事"), p.span(SpanKind.Month, oct)!!.lines.map { it.text })
        assertTrue(p.deleteSpan(SpanKind.Week, nextMon))
        assertNull(p.span(SpanKind.Week, nextMon))
        assertEquals(listOf("月:周:第一周邮件的事"), p.span(SpanKind.Month, oct)!!.lines.map { it.text })
        assertFalse(p.deleteSpan(SpanKind.Week, nextMon))
        assertTrue(p.deleteSpan(SpanKind.Month, oct))
        assertTrue(p.spans(SpanKind.Month).isEmpty())
        // 删月归纳不动下面的
        assertEquals(1, p.spans(SpanKind.Week).size)
    }

    // 清掉某个 App:各级里依据过它的句子都去掉(哪怕同时依据了别的 App)
    @Test fun purgingAnAppReachesEveryLevel() {
        val p = pool()
        twoWeeksAndAMonth(p)
        assertTrue(p.removePackages { it != "com.mail" })
        assertEquals(listOf("周:第一周聊天的事"), p.span(SpanKind.Week, mon)!!.lines.map { it.text })
        assertEquals(listOf("月:周:第一周聊天的事", "月:周:第二周聊天的事"), p.span(SpanKind.Month, oct)!!.lines.map { it.text })
        assertFalse(p.removePackages { it != "com.mail" })
        assertTrue(p.clear())
        assertTrue(p.spans(SpanKind.Week).isEmpty() && p.spans(SpanKind.Month).isEmpty())
    }

    // 到期不往上传:下面的到期删掉了,上面的句子留着(只是不再记着已经没了的分段归纳),也不因此重做;各级各有各的期限
    @Test fun expiryDoesNotTravelUpward() {
        val p = pool()
        twoWeeksAndAMonth(p)
        assertTrue(p.expire(nextMon + LearnTuning.NOTES_KEEP_DAYS + 1))
        assertTrue(p.all().isEmpty())
        assertEquals(4, p.span(SpanKind.Month, oct)!!.lines.size)
        assertTrue((p.days().flatMap { it.lines } + p.spans(SpanKind.Week).flatMap { it.lines } + p.spans(SpanKind.Month).flatMap { it.lines }).all { it.notes.isEmpty() })
        assertTrue(p.expire(nextMon + LearnTuning.DAYS_KEEP + 1))
        assertTrue(p.days().isEmpty())
        assertEquals(2, p.spans(SpanKind.Week).size)
        assertNull(p.dueWeek(nextMon + 100, waitIfOwed, noSkip))
        assertNull(p.dueMonth(nextMon + 100, waitIfOwed, noSkip))
        // 周归纳按它结束的那天算,留 WEEKS_KEEP_DAYS 天;月归纳按那个月的最后一天算,留 MONTHS_KEEP_DAYS 天
        assertFalse(p.expire(mon + 6 + LearnTuning.WEEKS_KEEP_DAYS))
        assertTrue(p.expire(mon + 6 + LearnTuning.WEEKS_KEEP_DAYS + 1))
        assertEquals(listOf(nextMon), p.spans(SpanKind.Week).map { it.start })
        assertTrue(p.expire(nextMon + 6 + LearnTuning.WEEKS_KEEP_DAYS + 1))
        assertEquals(4, p.span(SpanKind.Month, oct)!!.lines.size)
        val octEnd = java.time.LocalDate.ofEpochDay(oct).plusMonths(1).toEpochDay() - 1
        assertFalse(p.expire(octEnd + LearnTuning.MONTHS_KEEP_DAYS))
        assertTrue(p.expire(octEnd + LearnTuning.MONTHS_KEEP_DAYS + 1))
        assertTrue(p.spans(SpanKind.Month).isEmpty())
    }

    // 往上归纳之前,今天的当日归纳要不要先(重)做:今天有句子而没有归纳,或做完之后又来了新句子
    @Test fun todayIsStaleWhenNotesCameAfterItsDigest() {
        val p = pool()
        assertFalse(p.todayStale(mon))
        notes(p, mon, "早上的事", madeAt = 100)
        assertTrue(p.todayStale(mon))
        p.setDay(mon, p.dayInput(mon), listOf(NoteSignal(listOf(0), "到现在的")), 200, partial = true)
        assertFalse(p.todayStale(mon))
        notes(p, mon, "下午的事", madeAt = 300)
        assertTrue(p.todayStale(mon))
    }

    // 旧版本存下来的(没有周、月,句子上没有 days、weeks):照样读得出来
    @Test fun olderFilesStillDecode() {
        val old = """{"version":1,"notes":[],"days":[{"day":$mon,"madeAtMs":5,"lines":[{"text":"旧的一句","notes":["d1"],"apps":["com.chat"]}],"noteCount":1}]}"""
        val data = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(DigestData.serializer(), old)
        val p = pool(data)
        assertEquals(DigestLine("旧的一句", listOf("d1"), listOf("com.chat")), p.day(mon)!!.lines.single())
        assertTrue(p.spans(SpanKind.Week).isEmpty())
        assertEquals(mon, p.dueWeek(nextMon, waitIfOwed, noSkip))
    }

    // 周归纳的请求在途时,分段归纳到期删掉了(句子上记的 id 被划掉):句子本身还在,结果照常记下,不能当成「依据没了」整份丢掉
    @Test fun notesExpiringDuringARequestDoNotDropTheResult() {
        val p = pool()
        dayWith(p, mon, "周一的事")
        val input = p.spanInput(SpanKind.Week, mon, withPartial = false)
        assertTrue(p.expire(mon + LearnTuning.NOTES_KEEP_DAYS + 1))
        assertTrue(p.day(mon)!!.lines.single().notes.isEmpty())
        p.setSpan(SpanKind.Week, mon, input, listOf(NoteSignal(listOf(0), "周:周一的事")), 5, partial = false)
        val line = p.span(SpanKind.Week, mon)!!.lines.single()
        assertEquals("周:周一的事", line.text)
        assertTrue(line.notes.isEmpty())
        assertEquals(listOf(mon), line.days)
    }

    // 月归纳实际管到哪几天:从算进它的第一周的周一,到算进它的最后一周的周日
    @Test fun aMonthCoversTheWeeksThatEndInIt() {
        // 2024 年 10 月:10-01 是周二,它那一周从 9-30 开始;10-31 是周四,它那一周在 11-03 结束,算十一月的,所以十月管到 10-27
        assertEquals(day(2024, 9, 30)..day(2024, 10, 27), DigestPool.monthCover(oct))
        // 11-30 是周六,它那一周在 12-01 结束,算十二月的:十一月管到 11-24
        assertEquals(day(2024, 10, 28)..day(2024, 11, 24), DigestPool.monthCover(nov))
        // 月底正好是周日:管到月底;一号正好是周一:从一号起
        assertEquals(day(2025, 8, 31), DigestPool.monthCover(day(2025, 8, 1)).last)
        assertEquals(day(2025, 9, 1), DigestPool.monthCover(day(2025, 9, 1)).first)
        // 每一天都正好落在一个月里,和 monthOfWeek 的说法一致
        for (d in day(2024, 1, 1)..day(2026, 12, 31)) {
            val m = DigestPool.monthOfWeek(DigestPool.weekOf(d))
            assertTrue("$d", d in DigestPool.monthCover(m))
        }
    }

    // ---- 删了就不再自动做 ----

    // 删掉一天的当日归纳:读的人看不到它;它还有句子、之后又补收到新句子,都不再自动做;存下来读回来还是这样
    @Test fun aDeletedDayIsNotMadeAgain() {
        val p = pool()
        dayWith(p, mon, "周一的事")
        assertTrue(p.deleteDay(mon))
        assertFalse(p.deleteDay(mon))
        assertNull(p.day(mon))
        assertTrue(p.days().isEmpty())
        assertTrue(p.dayRemoved(mon))
        assertEquals(1, p.notes(mon).size)
        assertNull(p.dueDay(mon + 1) { true })
        // 之后又补收到这一天的通知、写成了句子
        notes(p, mon, "后来补收到的", madeAt = (mon + 1) * dayMs + 5)
        assertNull(p.dueDay(mon + 1) { true })
        val back = pool(p.data())
        assertNull(back.dueDay(mon + 1) { true })
        assertTrue(back.dayRemoved(mon) && back.days().isEmpty())
        // 没做过的那一天没有东西可删
        assertFalse(p.deleteDay(mon + 3))
        // 用户自己再要一次:做出来,记号没了;那是「到现在为止」的,过完之后照常补成完整的
        p.setDay(mon, p.dayInput(mon), listOf(NoteSignal(listOf(0), "重新要的")), (mon + 1) * dayMs + 9, partial = true)
        assertFalse(p.dayRemoved(mon))
        assertEquals(listOf("重新要的"), p.day(mon)!!.lines.map { it.text })
        assertEquals(mon, p.dueDay(mon + 1) { true })
    }

    // 删掉的那一天对周归纳来说就是不在:不当输入,不因为它重做,只剩它的那一周也不会被记成「做过了、没东西」
    @Test fun aDeletedDayIsInvisibleToItsWeek() {
        val p = pool()
        dayWith(p, mon, "周一的事")
        dayWith(p, mon + 2, "周三的事")
        week(p, mon, at = nextMon * dayMs)
        assertTrue(p.deleteDay(mon + 2))
        assertEquals(listOf("周:周一的事"), p.span(SpanKind.Week, mon)!!.lines.map { it.text })
        assertNull(p.dueWeek(nextMon, waitIfOwed, noSkip))
        assertEquals(listOf("周一的事"), p.spanInput(SpanKind.Week, mon, withPartial = true).map { it.line.text })
        // 请求在途时那一天被删了:依据过它的句子不写回来
        val q = pool()
        dayWith(q, mon, "周一的事")
        dayWith(q, mon + 2, "周三的事")
        val input = q.spanInput(SpanKind.Week, mon, withPartial = false)
        q.deleteDay(mon + 2)
        q.setSpan(SpanKind.Week, mon, input, listOf(NoteSignal(listOf(0, 1), "两天合的"), NoteSignal(listOf(0), "只有周一")), nextMon * dayMs, partial = false)
        assertEquals(listOf("只有周一"), q.span(SpanKind.Week, mon)!!.lines.map { it.text })
        // 一周里唯一的一天被删了:这一周没有可做的,也不欠着
        val r = pool()
        dayWith(r, mon, "周一的事")
        r.deleteDay(mon)
        assertNull(r.dueWeek(nextMon, waitIfOwed, noSkip))
        assertTrue(r.spanInput(SpanKind.Week, mon, withPartial = true).isEmpty())
        assertTrue(r.spans(SpanKind.Week).isEmpty())
    }

    // 删掉一周的周归纳:不再自动做——哪怕之后它里面某一天的当日归纳又重做了;存下来读回来还是这样;用户再要一次才回来
    @Test fun aDeletedWeekIsNotMadeAgain() {
        val p = pool()
        dayWith(p, mon, "周一的事")
        week(p, mon, at = nextMon * dayMs)
        assertTrue(p.deleteSpan(SpanKind.Week, mon))
        assertFalse(p.deleteSpan(SpanKind.Week, mon))
        assertNull(p.span(SpanKind.Week, mon))
        assertTrue(p.spans(SpanKind.Week).isEmpty())
        assertEquals(setOf(mon), p.removedSpans(SpanKind.Week))
        assertNull(p.dueWeek(nextMon, waitIfOwed, noSkip))
        // 下一级后来重做过
        p.setDay(mon, p.dayInput(mon), listOf(NoteSignal(listOf(0), "重做的周一")), nextMon * dayMs + 50, partial = false)
        assertNull(p.dueWeek(nextMon, waitIfOwed, noSkip))
        val back = pool(p.data())
        assertNull(back.dueWeek(nextMon, waitIfOwed, noSkip))
        assertEquals(setOf(mon), back.removedSpans(SpanKind.Week))
        // 没做过的周归纳没有东西可删
        assertFalse(p.deleteSpan(SpanKind.Week, nextMon))
        // 用户自己再要一次
        week(p, mon, at = nextMon * dayMs + 60, partial = true)
        assertTrue(p.removedSpans(SpanKind.Week).isEmpty())
        assertEquals(listOf("周:重做的周一"), p.span(SpanKind.Week, mon)!!.lines.map { it.text })
    }

    // 删掉的那一周对月归纳来说就是不在:月归纳不等它、不拿它当输入、不因为它重做;删掉的月归纳也不再自动做
    @Test fun aDeletedWeekIsInvisibleToItsMonthAndADeletedMonthStaysDeleted() {
        val p = pool()
        twoWeeksAndAMonth(p)
        assertTrue(p.deleteSpan(SpanKind.Week, nextMon))
        assertEquals(listOf("月:周:第一周聊天的事", "月:周:第一周邮件的事"), p.span(SpanKind.Month, oct)!!.lines.map { it.text })
        assertNull(p.dueMonth(nov, waitIfOwed, noSkip))
        assertEquals(listOf(mon, mon), p.spanInput(SpanKind.Month, oct, withPartial = true).map { it.from })
        // 月归纳还没做的时候:删掉的那一周不让它一直等
        val q = pool()
        dayWith(q, mon, "第一周的事")
        dayWith(q, nextMon, "第二周的事")
        week(q, mon, at = nextMon * dayMs)
        week(q, nextMon, at = (nextMon + 7) * dayMs)
        q.deleteSpan(SpanKind.Week, nextMon)
        assertEquals(oct, q.dueMonth(nov, waitIfOwed, noSkip))
        assertEquals(listOf("周:第一周的事"), q.spanInput(SpanKind.Month, oct, withPartial = false).map { it.line.text })
        // 一个月里唯一的一周被删了:这个月没有可做的,不会被记成「做过了、没东西」
        val r = pool()
        dayWith(r, mon, "第一周的事")
        week(r, mon, at = nextMon * dayMs)
        r.deleteSpan(SpanKind.Week, mon)
        assertNull(r.dueMonth(nov, noWait, noSkip))
        assertTrue(r.spanInput(SpanKind.Month, oct, withPartial = true).isEmpty())
        // 删掉月归纳:之后某一周重做了也不再自动做;读回来还是这样
        assertTrue(p.deleteSpan(SpanKind.Month, oct))
        assertEquals(setOf(oct), p.removedSpans(SpanKind.Month))
        week(p, mon, at = nov * dayMs + 5)
        assertNull(p.dueMonth(nov, noWait, noSkip))
        assertNull(pool(p.data()).dueMonth(nov, noWait, noSkip))
        assertTrue(p.spans(SpanKind.Month).isEmpty())
    }

    // 今天的当日归纳被删了:要本周、本月到现在的,不顺带把它做回来;记号和那一级留得一样久,清空全部时一起清掉
    @Test fun deletedMarkersDoNotDragTodayBackAndExpireWithTheirLevel() {
        val p = pool()
        dayWith(p, mon, "今天的事", partial = true)
        p.deleteDay(mon)
        notes(p, mon, "后来的", madeAt = mon * dayMs + 23 * 3_600_000L + 5)
        assertFalse(p.todayStale(mon))
        assertTrue(p.dayRemoved(mon))
        assertTrue(p.expire(mon + LearnTuning.DAYS_KEEP + 1))
        assertFalse(p.dayRemoved(mon))
        val q = pool()
        dayWith(q, mon, "周一的事")
        week(q, mon, at = nextMon * dayMs)
        q.deleteSpan(SpanKind.Week, mon)
        q.deleteDay(mon)
        assertTrue(q.clear())
        assertTrue(q.removedSpans(SpanKind.Week).isEmpty() && !q.dayRemoved(mon))
    }


    // 周、月归纳的概述:记下;删掉它依据的一天(一周)、清掉一个 App 时,去掉了句子的那一份一并清掉
    @Test fun spanBriefsAreStoredAndClearedWithTheLinesTheyDescribed() {
        fun fresh() = pool().also { p ->
            dayWith(p, mon, "周一的事")
            dayWith(p, mon + 1, "周二的事", pkg = "com.mail")
            val w = p.spanInput(SpanKind.Week, mon, withPartial = false)
            p.setSpan(SpanKind.Week, mon, w, w.indices.map { NoteSignal(listOf(it), "周:" + w[it].line.text) }, (mon + 7) * dayMs, partial = false, brief = "这一周两件事")
            val m = p.spanInput(SpanKind.Month, oct, withPartial = true)
            p.setSpan(SpanKind.Month, oct, m, m.indices.map { NoteSignal(listOf(it), "月:" + m[it].line.text) }, (mon + 8) * dayMs, partial = true, brief = "这个月两件事")
        }
        fresh().let { p ->
            assertEquals("这一周两件事", p.span(SpanKind.Week, mon)!!.brief)
            assertEquals("这个月两件事", p.span(SpanKind.Month, oct)!!.brief)
            assertEquals("这一周两件事", pool(p.data()).span(SpanKind.Week, mon)!!.brief)
        }
        // 删掉一天的当日归纳:周、月里依据过它的句子去掉,概述清掉
        fresh().let { p ->
            assertTrue(p.deleteDay(mon))
            assertEquals(listOf("周:周二的事"), p.span(SpanKind.Week, mon)!!.lines.map { it.text })
            assertEquals("", p.span(SpanKind.Week, mon)!!.brief)
            assertEquals("", p.span(SpanKind.Month, oct)!!.brief)
        }
        // 删掉周归纳:月归纳里依据过它的句子去掉,概述清掉
        fresh().let { p ->
            assertTrue(p.deleteSpan(SpanKind.Week, mon))
            assertEquals("", p.span(SpanKind.Month, oct)!!.brief)
        }
        // 清掉一个 App
        fresh().let { p ->
            assertTrue(p.removePackages { it != "com.mail" })
            assertEquals("", p.span(SpanKind.Week, mon)!!.brief)
            assertEquals("", p.span(SpanKind.Month, oct)!!.brief)
        }
        // 删一句分段归纳:管着那一天的周、月概述都清掉,哪怕没有句子因此被去掉(分段归纳已经到期、句子上不再记着它的时候)
        fresh().let { p ->
            val extra = notes(p, mon + 2, "没进当日归纳的一句").single()
            assertTrue(p.delete(extra.id))
            assertEquals(2, p.span(SpanKind.Week, mon)!!.lines.size)
            assertEquals("", p.span(SpanKind.Week, mon)!!.brief)
            assertEquals("", p.span(SpanKind.Month, oct)!!.brief)
        }
        // 收下的句子比给的少:不留
        fresh().let { p ->
            val w = p.spanInput(SpanKind.Week, mon, withPartial = false)
            p.setSpan(SpanKind.Week, mon, w, listOf(NoteSignal(listOf(0), "只有一句"), NoteSignal(listOf(9), "没依据")), (mon + 9) * dayMs, partial = false, brief = "两件事")
            assertEquals("", p.span(SpanKind.Week, mon)!!.brief)
        }
    }
}
