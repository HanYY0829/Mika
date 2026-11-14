package com.wangzan101.mika.core.proactive

import java.time.ZoneId
import java.util.UUID
import kotlinx.serialization.Serializable

/**
 * 一句分段归纳(第 1 级):哪个 App、哪一天(本地日期的纪元日,取它依据的通知里最新那段的日子)、依据的通知从几点到几点、
 * 有几段;[title] 是这几段共同的会话名(都一样时才有,取自通知本身,不是模型给的);[text] 是模型写的那句话(校验、遮过)。
 * 不存通知原文。
 */
@Serializable
data class DigestNote(
    val id: String,
    val packageName: String,
    val day: Long,
    val fromMs: Long,
    val toMs: Long,
    val count: Int,
    val title: String? = null,
    val text: String,
    /** 这句是什么时候写下的:当日归纳做完之后才写下的句子(解锁后才补收到的昨晚的通知),要让那一天重做一份。 */
    val madeAtMs: Long = 0,
)

/**
 * 当日、周、月归纳里的一句,连同它是从哪来的(一路往下记到底,删掉下面任何一样时这句跟着去掉):
 * [notes] 是它依据的分段归纳的 id(只留还在的:分段归纳到期删掉之后就从这里划掉),[apps] 是那些句子来自哪些 App,
 * [days] 是它依据的当日归纳是哪几天的(周、月归纳的句子才有),[weeks] 是它依据的周归纳是哪几周的(月归纳的句子才有)。
 */
@Serializable
data class DigestLine(
    val text: String,
    val notes: List<String> = emptyList(),
    val apps: List<String> = emptyList(),
    val days: List<Long> = emptyList(),
    val weeks: List<Long> = emptyList(),
)

/** 周归纳(第 3 级)、月归纳(第 4 级)。 */
enum class SpanKind { Week, Month }

/**
 * 一周或一个月的归纳。[start] 是这一周的周一、这个月的一号(本地日期的纪元日)。
 * [partial] 为 true 是还没过完时用户要的「到现在为止」,过完之后会重做一份完整的;[inputCount] 是做的时候放进去了几句。
 * [leftOut]:下一级的句子太多、一次请求放不下,没放进去的有几句——这一份不是照着全部写的,给任务、给用户看时要说。
 */
@Serializable
data class SpanDigest(
    val start: Long, val madeAtMs: Long, val lines: List<DigestLine>, val partial: Boolean = false, val inputCount: Int = 0,
    val leftOut: Int = 0,
    /** 这一份的一句概述(模型和句子一起给的,见 [DayDigest.brief]);没有是空的。 */
    val brief: String = "",
    /** 概述是照着哪些 App 的句子写的(见 [DayDigest.briefApps])。 */
    val briefApps: List<String> = emptyList(),
    /** 用户删掉了这一周(这个月)的归纳:只是个记号,记着「不要再自动做」。见 [DigestPool.deleteSpan]。 */
    val removed: Boolean = false,
)

/** 放进周、月归纳请求里的一句:[from] 是它出自哪一天的当日归纳(周归纳用)、哪一周的周归纳(月归纳用)。 */
class SpanInput(val from: Long, val line: DigestLine)

/**
 * 某一天的当日归纳(第 2 级)。[partial] 为 true 是这一天还没过完时用户要的「到现在为止」,过完之后会重做一份完整的;
 * [noteCount] 是做的时候放进请求的分段归纳有几句。[leftOut]:这一天的句子太多、一次请求放不下,没放进去的有几句——
 * 这一份不是照着这一天的全部写的(「完整」只是说这一天过完了),给任务、给用户看时要说;周、月归纳接着用它,也就带着这个缺口。
 */
@Serializable
data class DayDigest(
    val day: Long, val madeAtMs: Long, val lines: List<DigestLine>, val partial: Boolean = false, val noteCount: Int = 0,
    val leftOut: Int = 0,
    /**
     * 这一份的一句概述:模型和句子一起给的,给页面上的索引用,不给任务。它说的是这一份里的全部句子,不指向哪一句,
     * 所以这一份里只要有句子被去掉(删了它依据的东西、清了某个 App),概述就一并清掉——留着的话删掉的事还在概述里;
     * 删一句分段归纳、删一天(一周)的归纳、清某个 App 时,管着那一天的各份概述也都清掉(见 [DigestPool.clearBriefs])。
     * 没有是空的(模型没给、不合格、清掉了)。
     */
    val brief: String = "",
    /**
     * 概述是照着哪些 App 的句子写的:发给模型的全部句子出自的 App,不只是存下来的那几句的。清某个 App 时按它认——
     * 存下的句子上记的 App 不够用:模型写多了、没存下来的那几句里可能说过这个 App,概述跟着提了;分段归纳又只留几天,
     * 过后没法再从它们查这一天有过哪些 App。没有概述时是空的。
     */
    val briefApps: List<String> = emptyList(),
    /** 用户删掉了这一天的当日归纳:只是个记号(没有句子),记着「不要再自动做」。见 [DigestPool.deleteDay]。 */
    val removed: Boolean = false,
)

/** 当日消息归纳存下来的样子。 */
@Serializable
data class DigestData(
    val version: Int = 1,
    val notes: List<DigestNote> = emptyList(),
    val days: List<DayDigest> = emptyList(),
    val weeks: List<SpanDigest> = emptyList(),
    val months: List<SpanDigest> = emptyList(),
)

/**
 * 当日消息归纳攒下的东西:纯逻辑,不是线程安全的(在 ProactiveRuntime 那条串行协程上用)。
 * 分段归纳的句子(第 1 级)只新增,不改写,留 [LearnTuning.NOTES_KEEP_DAYS] 天;
 * 当日归纳(第 2 级)一天一份,留 [LearnTuning.DAYS_KEEP] 天;周归纳(第 3 级)一周一份,留 [LearnTuning.WEEKS_KEEP_DAYS] 天;
 * 月归纳(第 4 级)一月一份,留 [LearnTuning.MONTHS_KEEP_DAYS] 天。上一级做完,下一级的并不删:要核对时还查得到。
 *
 * 每一级只拿下一级的句子当输入(周用当日归纳,月用周归纳),只在那段时间过完之后做;下一级后来重做过,上一级跟着重做。
 * 一周是周一到周日;一周算进它结束(周日)的那个月。
 *
 * 删除往上传:删掉一句分段归纳、一天的当日归纳、一周的周归纳,或清掉某个 App 时,上面各级里依据过它的句子一并去掉
 * (合并出来的句子里还带着它说的事)。到期删掉的不往上传:上一级就是为了比下一级留得久。
 *
 * 删了就不再自动做:用户删掉某一天、某一周、某个月的归纳后,那里留一个「删掉了」的记号(没有句子),之后不管是它还欠着、
 * 又来了新句子、还是下一级重做过,都不再自动做;只有用户自己再要一次「到现在为止」才重新做(记号随之没了)。
 * 记号对谁都当它不在:读的人([days]、[day]、[spans]、[span])看不到它,上一级也不拿它当输入、不因为它重做。
 * 记号和那一级的归纳留得一样久;清空全部时一起清掉。
 */
class DigestPool(data: DigestData = DigestData(), private val newId: () -> String = { "d" + UUID.randomUUID().toString().replace("-", "").take(12) }) {
    private val notes = ArrayList<DigestNote>(data.notes)
    private val days = LinkedHashMap<Long, DayDigest>().apply { data.days.forEach { put(it.day, it) } }
    private val weeks = LinkedHashMap<Long, SpanDigest>().apply { data.weeks.forEach { put(it.start, it) } }
    private val months = LinkedHashMap<Long, SpanDigest>().apply { data.months.forEach { put(it.start, it) } }

    fun data(): DigestData = DigestData(
        notes = notes.toList(), days = days.values.sortedBy { it.day },
        weeks = weeks.values.sortedBy { it.start }, months = months.values.sortedBy { it.start },
    )

    /** 各天的当日归纳,新的在前(用户删掉的不算)。 */
    fun days(): List<DayDigest> = days.values.filter { !it.removed }.sortedByDescending { it.day }

    fun day(day: Long): DayDigest? = days[day]?.takeIf { !it.removed }

    /** 被用户删掉了当日归纳的是哪几天(页面上要说清「删掉了,不会再自动做」)。 */
    fun removedDays(): Set<Long> = days.values.filter { it.removed }.mapTo(HashSet()) { it.day }

    /** 这一天的当日归纳是不是被用户删掉了(不再自动做)。 */
    fun dayRemoved(day: Long): Boolean = days[day]?.removed == true

    /** 被用户删掉了的周(月)归纳是哪几份(页面上要说清「删掉了,不会再自动做」)。 */
    fun removedSpans(kind: SpanKind): Set<Long> = store(kind).values.filter { it.removed }.mapTo(HashSet()) { it.start }

    /**
     * 该给哪一天做当日归纳:[today] 之前、有分段归纳、[settled] 认的那些天里最早的一天,只要它还没有完整的当日归纳,
     * 或者完整的那份做完之后又写下了这一天的新句子(解锁时先做了昨天的归纳,昨晚的通知之后才补收进来:得重做,不然那些句子哪一份里都没有)。
     * [settled] 说这一天的通知是不是都做过分段归纳了(还有没做的先等等,不然归纳的是半天的)。没有为 null。
     */
    fun dueDay(today: Long, settled: (Long) -> Boolean): Long? = notes.asSequence().map { it.day }.distinct().sorted()
        .firstOrNull { it < today && needsDigest(it) && settled(it) }

    private fun needsDigest(day: Long): Boolean {
        val d = days[day] ?: return true
        // 用户删掉的:不再自动做
        if (d.removed) return false
        return d.partial || notes.any { it.day == day && it.madeAtMs > d.madeAtMs }
    }

    /**
     * 发给第 2 级的那一天的句子:按时间由旧到新,最多 [LearnTuning.DAY_NOTES_MAX] 句。多了就各个 App 轮流取、每个 App 先留新的:
     * 不让一个话多的 App 把别的 App 一整天的事都挤出去(只要最后的那些句的话,上午的事整段没了)。没放进去的由 [setDay] 记数。
     */
    fun dayInput(day: Long): List<DigestNote> {
        val all = notes(day)
        if (all.size <= LearnTuning.DAY_NOTES_MAX) return all
        val byApp = all.groupBy { it.packageName }.values.map { it.asReversed() }
        val picked = HashSet<String>()
        var round = 0
        while (picked.size < LearnTuning.DAY_NOTES_MAX && byApp.any { round < it.size }) {
            for (app in byApp) {
                if (picked.size >= LearnTuning.DAY_NOTES_MAX) break
                app.getOrNull(round)?.let { picked += it.id }
            }
            round++
        }
        return all.filter { it.id in picked }
    }

    /**
     * 记下一天的当日归纳,替换这一天原来的那份。[input] 是发出去的那些句子(下标对应 [NoteSignal.entries])。
     * 一句都没有也记(这一天做过了,没有值得说的)。
     */
    fun setDay(day: Long, input: List<DigestNote>, lines: List<NoteSignal>, nowMs: Long, partial: Boolean, brief: String? = null) {
        val alive = notes.mapTo(HashSet()) { it.id }
        val out = lines.mapNotNull { l ->
            val cited = l.entries.distinct().filter { it in input.indices }.map { input[it] }
            // 依据的句子里有已经不在了的(请求在途时那个 App 被清掉了):整句不要。只把它从依据里划掉是不够的,
            // 句子的文字里还带着那个 App 的事
            if (cited.isEmpty() || l.text.isBlank() || cited.any { it.id !in alive }) null
            else DigestLine(l.text, cited.map { it.id }, cited.map { it.packageName }.distinct())
        }
        // 做的时间不早于它依据的最后一句写下的时间:不然刚做完就又「有新句子」
        days[day] = DayDigest(
            day, maxOf(nowMs, input.maxOfOrNull { it.madeAtMs } ?: 0), out, partial, input.size,
            leftOut = (notes.count { it.day == day } - input.size).coerceAtLeast(0),
            // 发出去的句子里有请求在途时被删掉的:概述是照着全部写的,可能正说着删掉的那句——哪怕存下的句子一句没少
            // (模型写多了、没存下来的那几句里引过它,概述也跟着提了),也不要
            brief = if (input.all { it.id in alive }) kept(brief, out.size, lines.size) else "",
        ).let { d -> if (d.brief.isEmpty()) d else d.copy(briefApps = input.map { it.packageName }.distinct()) }
    }

    // ---- 周归纳(第 3 级)、月归纳(第 4 级) ----

    private fun store(kind: SpanKind) = if (kind == SpanKind.Week) weeks else months

    /** 各周(各月)的归纳,新的在前(用户删掉的不算)。 */
    fun spans(kind: SpanKind): List<SpanDigest> = store(kind).values.filter { !it.removed }.sortedByDescending { it.start }

    fun span(kind: SpanKind, start: Long): SpanDigest? = store(kind)[start]?.takeIf { !it.removed }

    /** 下一级的一份:哪一天(哪一周)的、什么时候做的、是不是「到现在为止」的、里面的句子,和它自己做的时候漏了几句。 */
    private class Lower(val key: Long, val madeAtMs: Long, val partial: Boolean, val lines: List<DigestLine>, val leftOut: Int)

    /**
     * 这一周(这个月)下一级的那几份:一周里各天的当日归纳,一个月里各周(结束在这个月的)的周归纳;由旧到新。
     * 用户删掉的那几份不算:不当输入,不算「做完了的下一级」,它的时间也不算「下一级重做过」。
     */
    private fun lower(kind: SpanKind, start: Long): List<Lower> = when (kind) {
        SpanKind.Week -> days.values.filter { !it.removed && weekOf(it.day) == start }.sortedBy { it.day }.map { Lower(it.day, it.madeAtMs, it.partial, it.lines, it.leftOut) }
        SpanKind.Month -> weeks.values.filter { !it.removed && monthOfWeek(it.start) == start }.sortedBy { it.start }.map { Lower(it.start, it.madeAtMs, it.partial, it.lines, it.leftOut) }
    }

    /** 完整的下一级做完的时间(一份都没有为空):上一级有没有、要不要重做,只看完整的。 */
    private fun settledLower(kind: SpanKind, start: Long): List<Long> = lower(kind, start).filter { !it.partial }.map { it.madeAtMs }

    /** 这一周(这个月)要不要(重)做:下一级有完整的,而这一级还没有、只有「到现在为止」的,或做完之后下一级又重做过。 */
    private fun needsSpan(kind: SpanKind, start: Long): Boolean {
        val made = settledLower(kind, start)
        if (made.isEmpty()) return false
        val d = store(kind)[start] ?: return true
        // 用户删掉的:不再自动做
        if (d.removed) return false
        return d.partial || made.any { it > d.madeAtMs }
    }

    /** 这一天还欠着当日归纳(有句子,却没有完整的、或做完之后又来了新句子)。 */
    private fun dayOwed(day: Long): Boolean = notes.any { it.day == day } && needsDigest(day)

    /** 这一周还欠着周归纳:要(重)做,或它里面还有欠着当日归纳的日子(做完之后这一周就要做了)。 */
    private fun weekOwed(week: Long): Boolean = needsSpan(SpanKind.Week, week) || (week..week + 6).any(::dayOwed)

    /**
     * 该给哪一周做周归纳:[today] 所在那一周之前、要(重)做的周里最早的一周。这一周的每一天都先问 [waitForDay]
     * (那一天、它是不是还欠着当日归纳):说要等的话这一周先不做——欠着的等它做完,试了几次都不成的就不等了,
     * 还有通知没做过分段归纳的也等。[skip] 说这一周试了几次都不成、这次不做。没有为 null。
     */
    fun dueWeek(today: Long, waitForDay: (day: Long, owed: Boolean) -> Boolean, skip: (Long) -> Boolean): Long? {
        val thisWeek = weekOf(today)
        return days.keys.asSequence().map(::weekOf).distinct().sorted().firstOrNull { w ->
            w < thisWeek && needsSpan(SpanKind.Week, w) && !skip(w) && (w..w + 6).none { waitForDay(it, dayOwed(it)) }
        }
    }

    /**
     * 该给哪个月做月归纳:[today] 所在那个月之前、要(重)做的月里最早的一个。这个月的每一周(结束在这个月的)都先问 [waitForWeek]
     * (那一周、它是不是还欠着周归纳):说要等的话这个月先不做。[skip] 说这个月这次不做。没有为 null。
     */
    fun dueMonth(today: Long, waitForWeek: (week: Long, owed: Boolean) -> Boolean, skip: (Long) -> Boolean): Long? {
        val thisMonth = monthOf(today)
        // 这个月里有东西的周:有当日归纳的、有句子的
        val known = (days.keys.asSequence() + notes.asSequence().map { it.day }).map(::weekOf).distinct().toList()
        return weeks.keys.asSequence().map(::monthOfWeek).distinct().sorted().firstOrNull { m ->
            m < thisMonth && needsSpan(SpanKind.Month, m) && !skip(m) &&
                known.none { w -> monthOfWeek(w) == m && waitForWeek(w, weekOwed(w)) }
        }
    }

    /** 今天的当日归纳是不是该先(重)做一遍再往上归纳:今天有句子,却没有当日归纳,或做完之后又来了新句子。 */
    /**
     * 今天「到现在为止」的当日归纳该不该自动做一份(门槛见 [LearnTuning.TODAY_AUTO_MIN_NOTES] 那一组)。
     * 今天的被用户删掉了的不自动做回来。
     */
    fun todayAutoDue(today: Long, nowMs: Long): Boolean {
        val d = days[today]
        if (d?.removed == true) return false
        if (d != null && nowMs - d.madeAtMs < LearnTuning.TODAY_AUTO_INTERVAL_MS) return false
        val fresh = notes.filter { it.day == today && (d == null || it.madeAtMs > d.madeAtMs) }
        if (fresh.size < LearnTuning.TODAY_AUTO_MIN_NOTES) return false
        return fresh.size >= LearnTuning.TODAY_AUTO_READY_NOTES || nowMs - fresh.minOf { it.madeAtMs } >= LearnTuning.TODAY_AUTO_WAIT_MS
    }

    fun todayStale(today: Long): Boolean {
        val d = days[today]
        // 今天的被用户删掉了:要了本周、本月的,不顺带把它做回来
        if (d?.removed == true) return false
        return notes.any { it.day == today && (d == null || it.madeAtMs > d.madeAtMs) }
    }

    /**
     * 发给周(月)归纳的输入:下一级各份的句子,按时间由旧到新。[withPartial] 为 false 时只要完整的那些份(自动做的不拿半截的当输入)。
     * 总字数不超过 [LearnTuning.SPAN_INPUT_CHARS]:超了就各份轮流取,每份留住排在前面的句子(下一级是按要紧程度排的)。
     */
    fun spanInput(kind: SpanKind, start: Long, withPartial: Boolean): List<SpanInput> {
        val groups = lower(kind, start).filter { withPartial || !it.partial }.map { it.key to it.lines.filter { l -> l.text.isNotBlank() } }
        val taken = IntArray(groups.size)
        var chars = 0
        var round = 0
        var full = false
        while (!full && groups.indices.any { round < groups[it].second.size }) {
            for (i in groups.indices) {
                val line = groups[i].second.getOrNull(round) ?: continue
                if (chars > 0 && chars + line.text.length > LearnTuning.SPAN_INPUT_CHARS) { full = true; break }
                taken[i] = round + 1
                chars += line.text.length
            }
            round++
        }
        return groups.flatMapIndexed { i, (from, lines) -> lines.take(taken[i]).map { SpanInput(from, it) } }
    }

    /**
     * 记下一周(一个月)的归纳,替换原来的那份。[input] 是发出去的那些句子(下标对应 [NoteSignal.entries])。
     * 一句都没有也记(做过了,没有值得说的)。每句往下记到底:依据的是哪几天(哪几周)、哪些分段归纳、哪些 App。
     */
    fun setSpan(kind: SpanKind, start: Long, input: List<SpanInput>, lines: List<NoteSignal>, nowMs: Long, partial: Boolean, brief: String? = null) {
        val alive = notes.mapTo(HashSet()) { it.id }
        // 发出去的那句还在不在:按文字和 App 认,不按整条记录比——请求在途时分段归纳到期,句子上记的 id 会被划掉几个,句子本身没变
        fun there(i: SpanInput): Boolean = when (kind) {
            SpanKind.Week -> days[i.from]?.lines
            SpanKind.Month -> weeks[i.from]?.lines
        }?.any { it.text == i.line.text && it.apps == i.line.apps } == true
        val out = lines.mapNotNull { l ->
            val cited = l.entries.distinct().filter { it in input.indices }.map { input[it] }
            // 依据的句子里有已经不在了的(请求在途时被删掉、它的 App 被清掉了):整句不要
            if (cited.isEmpty() || l.text.isBlank() || cited.any { !there(it) }) null
            else DigestLine(
                l.text,
                notes = cited.flatMap { it.line.notes }.distinct().filter { it in alive },
                apps = cited.flatMap { it.line.apps }.distinct(),
                days = if (kind == SpanKind.Week) cited.map { it.from }.distinct() else cited.flatMap { it.line.days }.distinct(),
                weeks = if (kind == SpanKind.Month) cited.map { it.from }.distinct() else emptyList(),
            )
        }
        // 做的时间不早于下一级任何一份做完的时间:不然刚做完就又「下一级重做过」
        val made = maxOf(nowMs, lower(kind, start).maxOfOrNull { it.madeAtMs } ?: 0)
        // 下一级一共有几句、放进去了几句:差的就是放不下的。下一级自己做的时候漏掉的也算上——
        // 照着一份不全的当日归纳写出来的周归纳,同样没有看到那一周的全部
        val used = lower(kind, start).filter { partial || !it.partial }
        val available = used.sumOf { l -> l.lines.count { it.text.isNotBlank() } }
        store(kind)[start] = SpanDigest(
            start, made, out, partial, input.size,
            leftOut = (available - input.size).coerceAtLeast(0) + used.sumOf { it.leftOut },
            // 同 [setDay]:发出去的句子里有请求在途时没了的(那一天、那一周的归纳被删了,它的 App 被清了),概述不要
            brief = if (input.all(::there)) kept(brief, out.size, lines.size) else "",
        ).let { d -> if (d.brief.isEmpty()) d else d.copy(briefApps = input.flatMap { it.line.apps }.distinct()) }
    }

    /**
     * 用户删掉某一周(某个月)的归纳:原地留一个「删掉了」的记号,不再自动做;删的是周归纳时,月归纳里依据过它的句子一并去掉。
     * 没有这一份、或已经删过了,返回 false。
     */
    fun deleteSpan(kind: SpanKind, start: Long): Boolean {
        val d = store(kind)[start]?.takeIf { !it.removed } ?: return false
        store(kind)[start] = SpanDigest(start, d.madeAtMs, emptyList(), removed = true)
        if (kind == SpanKind.Week) {
            dropLines(fromDays = false) { start in it.weeks }
            // 月归纳的概述连同清掉(周一那天就落在这一周、这个月里)
            clearBriefs(start, fromDay = false)
        }
        return true
    }

    /**
     * [day] 这一天的事被删了:这一天、它那一周、那个月的概述都清掉,不管它们有没有句子因此被去掉。
     * 概述可能提到模型写多了、没存下来的那几句([NoteDrop.OverLimit]);那样的句子没有记录可查,删的时候只能连概述一起清。
     * [fromDay] 为 false 时只清周和月的。有清掉的返回 true。
     */
    private fun clearBriefs(day: Long, fromDay: Boolean = true): Boolean {
        var changed = false
        if (fromDay) days[day]?.takeIf { it.brief.isNotEmpty() }?.let { days[day] = it.copy(brief = ""); changed = true }
        val week = weekOf(day)
        weeks[week]?.takeIf { it.brief.isNotEmpty() }?.let { weeks[week] = it.copy(brief = ""); changed = true }
        val month = monthOfWeek(week)
        months[month]?.takeIf { it.brief.isNotEmpty() }?.let { months[month] = it.copy(brief = ""); changed = true }
        return changed
    }

    /** 概述只在收下的句子一句没少、也不是一句都没有时留着(请求在途时依据没了的句子会被整句丢掉,见 [setDay])。 */
    private fun kept(brief: String?, stored: Int, given: Int): String = if (stored > 0 && stored == given) brief.orEmpty() else ""

    /**
     * 上面各级里符合 [gone] 的句子去掉:[fromDays] 为 true 从当日归纳起,否则只动周、月。有去掉的返回 true。
     * 去掉了句子的那一份,概述一并清掉(见 [DayDigest.brief])。
     */
    private fun dropLines(fromDays: Boolean, gone: (DigestLine) -> Boolean): Boolean {
        var changed = false
        if (fromDays) for ((k, d) in days.entries.toList()) {
            val left = d.lines.filterNot(gone)
            if (left.size != d.lines.size) { days[k] = d.copy(lines = left, brief = ""); changed = true }
        }
        for (m in listOf(weeks, months)) for ((k, d) in m.entries.toList()) {
            val left = d.lines.filterNot(gone)
            if (left.size != d.lines.size) { m[k] = d.copy(lines = left, brief = ""); changed = true }
        }
        return changed
    }

    fun all(): List<DigestNote> = notes.toList()

    /** 某一天的句子,按依据的通知的时间由旧到新。 */
    fun notes(day: Long): List<DigestNote> = notes.filter { it.day == day }.sortedBy { it.fromMs }

    /**
     * 把一批的分段归纳记下来。[entries] 是发出去的那一批(下标对应 [NoteSignal.entries]);App 是我们分的批的,不来自模型。
     * 返回新记了几句。
     */
    fun add(packageName: String, entries: List<NoticeEntry>, signals: List<NoteSignal>, zone: ZoneId, nowMs: Long = 0): Int {
        var added = 0
        for (s in signals) {
            val cited = s.entries.distinct().filter { it in entries.indices }.map { entries[it] }
            if (cited.isEmpty() || s.text.isBlank()) continue
            val from = cited.minOf { it.atMs }
            val to = cited.maxOf { it.atMs }
            val title = cited.map { it.title }.distinct().singleOrNull()?.takeIf(String::isNotBlank)
                ?.let { ContactIdentity.displayName(it) }
            var id = newId()
            while (notes.any { it.id == id }) id = newId()
            notes += DigestNote(id, packageName, TopicPool.dayOf(to, zone), from, to, cited.size, title, s.text, nowMs)
            added++
        }
        return added
    }

    /**
     * 留存期之外的去掉([today] 是今天的纪元日)。有去掉的返回 true。到期不往上传:上面各级的句子留着。
     *
     * 四级是同一个算法:一份东西从它管的最后一天算起,留满 N 天,第 N+1 天去掉——句子和当日归纳的最后一天就是那一天,
     * 周归纳是周日,月归纳是那个月的最后一天。(月归纳从月初算的话,会比别的三级早删将近一个月。)
     */
    fun expire(today: Long): Boolean {
        val a = notes.removeAll { it.day < today - LearnTuning.NOTES_KEEP_DAYS }
        val b = days.keys.removeAll { it < today - LearnTuning.DAYS_KEEP }
        val c = weeks.keys.removeAll { it + 6 < today - LearnTuning.WEEKS_KEEP_DAYS }
        val d = months.keys.removeAll { lastDayOfMonth(it) < today - LearnTuning.MONTHS_KEEP_DAYS }
        // 到期删掉的分段归纳,从各级句子的依据里划掉:它们已经没法被单独删掉了,留着 id 只是白占地方
        if (a) forgetDeadNotes()
        return a || b || c || d
    }

    /** [monthStart] 那个月的最后一天(纪元日)。 */
    private fun lastDayOfMonth(monthStart: Long): Long = java.time.LocalDate.ofEpochDay(monthStart).plusMonths(1).toEpochDay() - 1

    private fun forgetDeadNotes() {
        val alive = notes.mapTo(HashSet()) { it.id }
        fun trim(lines: List<DigestLine>): List<DigestLine>? =
            if (lines.none { l -> l.notes.any { it !in alive } }) null else lines.map { l -> l.copy(notes = l.notes.filter { it in alive }) }
        for ((k, d) in days.entries.toList()) trim(d.lines)?.let { days[k] = d.copy(lines = it) }
        for (m in listOf(weeks, months)) for ((k, d) in m.entries.toList()) trim(d.lines)?.let { m[k] = d.copy(lines = it) }
    }

    /** 不再归纳的 App:它的分段归纳去掉;当日、周、月归纳里依据过它的那几句也去掉(哪怕同时依据了别的 App)。有改动返回 true。 */
    fun removePackages(keep: (String) -> Boolean): Boolean {
        // 它有句子的那几天:哪怕没有存着的句子依据过它,概述也清掉(见 [clearBriefs])
        val touched = notes.filter { !keep(it.packageName) }.mapTo(HashSet()) { it.day }
        val a = notes.removeAll { !keep(it.packageName) }
        val b = dropLines(fromDays = true) { line -> !line.apps.all(keep) }
        var c = false
        for (day in touched) if (clearBriefs(day)) c = true
        // 分段归纳已经到期删掉了的那些天查不到了:按概述自己记的来源认。没记来源的概述(加这一项之前存下的)
        // 说不清照着谁写的,清 App 时一并清掉
        fun stale(brief: String, apps: List<String>) = brief.isNotEmpty() && (apps.isEmpty() || !apps.all(keep))
        for ((k, d) in days.entries.toList()) if (stale(d.brief, d.briefApps)) { days[k] = d.copy(brief = "", briefApps = emptyList()); c = true }
        for (m in listOf(weeks, months)) for ((k, d) in m.entries.toList()) {
            if (stale(d.brief, d.briefApps)) { m[k] = d.copy(brief = "", briefApps = emptyList()); c = true }
        }
        return a || b || c
    }

    /**
     * 用户删掉某一天的当日归纳:原地留一个「删掉了」的记号,不再自动做(哪怕之后又补收到这一天的通知);
     * 周、月归纳里依据过这一天的句子一并去掉。没有这一份、或已经删过了,返回 false。
     */
    fun deleteDay(day: Long): Boolean {
        val d = days[day]?.takeIf { !it.removed } ?: return false
        days[day] = DayDigest(day, d.madeAtMs, emptyList(), removed = true)
        dropLines(fromDays = false) { day in it.days }
        clearBriefs(day, fromDay = false)
        return true
    }

    /**
     * 用户删掉一句分段归纳。当日、周、月归纳里依据过它的那几句也去掉:合并出来的句子里还带着它说的事,只删这一句等于没删。
     * 有改动返回 true。
     */
    fun delete(id: String): Boolean {
        val day = notes.firstOrNull { it.id == id }?.day ?: return false
        notes.removeAll { it.id == id }
        dropLines(fromDays = true) { id in it.notes }
        clearBriefs(day)
        return true
    }

    fun clear(): Boolean {
        if (notes.isEmpty() && days.isEmpty() && weeks.isEmpty() && months.isEmpty()) return false
        notes.clear()
        days.clear()
        weeks.clear()
        months.clear()
        return true
    }

    companion object {
        /** 这一天所在那一周的周一(纪元日;1970-01-01 是周四)。 */
        fun weekOf(day: Long): Long = day - Math.floorMod(day + 3, 7L)

        /** 这一天所在那个月的一号(纪元日)。 */
        fun monthOf(day: Long): Long = java.time.LocalDate.ofEpochDay(day).withDayOfMonth(1).toEpochDay()

        /** 这一周(给的是周一)算进哪个月:它结束(周日)的那个月。 */
        fun monthOfWeek(week: Long): Long = monthOf(week + 6)

        /**
         * 这个月(给的是一号)的月归纳实际管到哪几天:算进它的第一周的周一,到算进它的最后一周的周日。
         * 所以月初可能带着上个月最后几天,月底最后几天(那一周在下个月结束)算到下个月去。
         */
        fun monthCover(month: Long): LongRange {
            val next = java.time.LocalDate.ofEpochDay(month).plusMonths(1).toEpochDay()
            val lastWeek = weekOf(next - 1).let { if (it + 6 < next) it else it - 7 }
            return weekOf(month)..lastWeek + 6
        }
    }
}
