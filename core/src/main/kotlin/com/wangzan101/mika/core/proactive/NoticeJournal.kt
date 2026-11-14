package com.wangzan101.mika.core.proactive

import kotlinx.serialization.Serializable

/**
 * 收到过的一段通知文字。[title] 是会话的名字(人或群,或「到账通知」这类),[sender] 是群里说话的人(没有就是 null),
 * [text] 是那句话。文字都是别的 App、别人写的。
 *
 * [keyId] 是通知键的哈希([NoticeDedup.idOf],倒推不出原来的键),[sig] 是这一段内容的指纹,[styled] 说它来自带会话样式的通知:
 * 这三样只用来认「这条通知的这段话记过没有」,存下来之后重启也认得。
 */
@Serializable
data class NoticeEntry(
    val packageName: String,
    val atMs: Long,
    val title: String,
    val sender: String? = null,
    val text: String,
    val keyId: String = "",
    val sig: Int = 0,
    val styled: Boolean = false,
    /** 这一段已经做过分段归纳(第 1 级,见 [NoticeNotes]);归纳没成的也算做过,不再发。 */
    val noted: Boolean = false,
    /** 群聊(true)、私聊(false),看不出来为 null(见 [NoticeRecord.group])。 */
    val group: Boolean? = null,
)

/**
 * 通知日志存下来的样子:[sinceMs] 是这一份从什么时候开始记的,[entries] 由旧到新;
 * [cutMs] 是因为条数满了被挤掉的里面最晚的一段的时间(没挤掉过为 0)。
 */
@Serializable
data class NoticeJournalData(val version: Int = 1, val sinceMs: Long, val entries: List<NoticeEntry> = emptyList(), val cutMs: Long = 0)

/**
 * 通知日志(当日消息归纳的第 0 级):收进来的每一段通知文字按时间记下(已经过了白名单、筛选、去重、清洗、拆分)。
 * 和 [NoticeInbox] 不同,用户点开、划掉通知之后这里的还在——「今天都收到了什么」读的是它。
 *
 * 纯逻辑,不是线程安全的(在 ProactiveRuntime 那条串行协程上用)。存不存盘、什么时候存由调用方定([data] 给出要存的样子,
 * 构造时给回来就接着上次的)。锁屏期间不读通知(解锁后只补得回还留在通知栏里的),所以 [sinceMs] 记着这一份是从什么时候开始记的,
 * 读的人要把「从几点起、可能不全」说清楚。
 *
 * 只留最近 [keepMs] 的([expire];没有新通知来也要有人调),最多 [capacity] 条,满了丢最旧的。
 *
 * 同一条通知里同样的内容只记一次:带会话样式的通知每次重发都带着前面几条消息,按(谁说的、文字、时间)认;
 * 别的通知(微信、QQ 每来一句把同一条通知重发一遍)按会话名、说话人、正文认,和这条通知上一次的一样就不记。
 * 认的依据([NoticeEntry.keyId]、[NoticeEntry.sig])跟着条目存着,重启之后同一条通知原样重发也不会再记一遍。
 */
class NoticeJournal(
    data: NoticeJournalData? = null,
    private val capacity: Int = CAPACITY,
    private val keepMs: Long = KEEP_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val entries = ArrayDeque<NoticeEntry>()

    /** 这一份从什么时候开始记(第一次建出来、或上次 [clear] 的时间)。 */
    var sinceMs: Long = data?.sinceMs ?: clock()
        private set

    /** 条数满了被挤掉的里面最晚的一段的时间;没挤掉过为 0。 */
    private var cutMs: Long = data?.cutMs ?: 0L

    /** 从什么时候起这一份是全的:开始记的时间,条数满了挤掉过旧的就是被挤掉的那些之后。 */
    val completeSinceMs: Long get() = maxOf(sinceMs, cutMs)

    /**
     * 读进来的那份里有没有过了保留期的(建出来时已经从内存里去掉)。有的话文件里还留着它们:
     * 读的人要马上存一次,不然之后 [expire] 再也不会说「有改动」,过期的原文就一直躺在文件里。
     */
    val expiredOnLoad: Boolean

    init {
        expiredOnLoad = if (data != null) {
            entries.addAll(data.entries)
            expire()
        } else false
    }

    val size: Int get() = entries.size

    fun data(): NoticeJournalData = NoticeJournalData(sinceMs = sinceMs, entries = entries.toList(), cutMs = cutMs)

    /** 记下一条已清洗、拆好的记录里新的内容。返回新记了几段。 */
    fun add(record: NoticeRecord): Int {
        val keyId = NoticeDedup.idOf(record.key)
        var added = 0
        if (record.messages.isNotEmpty()) {
            val seen = entries.filterTo(ArrayList()) { it.styled && it.keyId == keyId }
            for (m in record.messages) {
                if (m.text.isBlank()) continue
                val sig = listOf(m.sender, m.text, m.atMs).hashCode()
                // 指纹只有 32 位,不同的两句会撞上:撞上了再比一遍原文(日志里留着原文),真一样才算记过
                if (seen.any { it.sig == sig && it.sender == m.sender && it.text == m.text }) continue
                entries += NoticeEntry(
                    // 消息自己带的时间是发通知的 App 填的:没有就用通知的发出时间,比通知还晚的(填错、故意填到将来)也按通知的算,
                    // 不然它过不了期,还会被算到将来的某一天里
                    record.packageName, m.atMs.takeIf { NoticeDedup.trusted(it, record.postedAtMs) }?.coerceAtMost(record.postedAtMs) ?: record.postedAtMs, record.title, m.sender, m.text,
                    keyId = keyId, sig = sig, styled = true, group = record.group,
                )
                seen += entries.last()
                added++
            }
        } else if (record.title.isNotBlank() || record.text.isNotBlank()) {
            val sig = listOf(record.title, record.sender, record.text).hashCode()
            val last = entries.lastOrNull { !it.styled && it.keyId == keyId }
            // 和这条通知上一次的一样就不记(每来一句把同一条通知原样重发一遍的 App)。「一样」要原文真一样(指纹只有 32 位),
            // 而且隔得不久:隔了半天以上的是新的一次(第二天又发来的同一句话),要记,不然那一天的归纳里就没有它
            val repeat = last != null && last.sig == sig && last.title == record.title && last.sender == record.sender &&
                last.text == record.text && record.postedAtMs - last.atMs < LearnTuning.REPEAT_WINDOW_MS
            if (!repeat) {
                entries += NoticeEntry(
                    record.packageName, record.postedAtMs, record.title, record.sender, record.text, keyId = keyId, sig = sig, group = record.group,
                )
                added++
            }
        }
        if (added > 0) {
            expire()
            while (entries.size > capacity) cutMs = maxOf(cutMs, entries.removeFirst().atMs)
        }
        return added
    }

    /** [fromMs](含)到 [toMs](不含)之间的,由旧到新。 */
    fun between(fromMs: Long, toMs: Long): List<NoticeEntry> = entries.filter { it.atMs in fromMs until toMs }.sortedBy { it.atMs }

    /**
     * 一句分段归纳([DigestNote])是照着哪些通知写的:它只记了 App、会话名和起止时间,没有记是哪几段——所以给的是
     * 这个 App 在 [fromMs] 到 [toMs](都含)之间的通知;[title] 不为 null 时只要这个会话的(按存句子时同样的办法从标题算出
     * 会话名来比,见 [ContactIdentity.displayName])。可能比当时依据的多几段(同一段时间里没被引用的)。由旧到新。
     */
    fun around(packageName: String, title: String?, fromMs: Long, toMs: Long): List<NoticeEntry> = entries
        .filter { it.packageName == packageName && it.atMs in fromMs..toMs && (title == null || ContactIdentity.displayName(it.title) == title) }
        .sortedBy { it.atMs }

    // ---- 分段归纳(第 1 级)要用的:哪些还没归纳过 ----

    /** 还没归纳过的有几段。 */
    val pendingCount: Int get() = entries.count { !it.noted }

    /**
     * 现在该归纳哪个 App:只看 [eligible] 认的 App 里还没归纳过的;攒够 [dueCount] 段,或最旧的一段已过了 [dueAgeMs],就算到期。
     * 几个都到期时先做最旧的那个。没有到期的为 null。不计时:由调用方在有事发生时来问。
     */
    fun due(nowMs: Long, eligible: (String) -> Boolean, dueCount: Int, dueAgeMs: Long): String? = entries
        .filter { !it.noted && eligible(it.packageName) }
        .groupBy { it.packageName }
        .filter { (_, list) -> list.size >= dueCount || nowMs - list.minOf { it.atMs } >= dueAgeMs }
        .minByOrNull { (_, list) -> list.minOf { it.atMs } }
        ?.key

    /** [fromMs](含)到 [toMs](不含)之间还有没有没归纳过的([eligible] 认的 App 里):有的话这段时间的当日归纳先等等。 */
    fun hasPending(fromMs: Long, toMs: Long, eligible: (String) -> Boolean): Boolean =
        entries.any { !it.noted && it.atMs in fromMs until toMs && eligible(it.packageName) }

    /** 这个 App 还没归纳过的,由旧到新,最多 [limit] 段。 */
    fun pending(packageName: String, limit: Int): List<NoticeEntry> =
        entries.filter { !it.noted && it.packageName == packageName }.sortedBy { it.atMs }.take(limit)

    /** [done] 这几段做过分段归纳了(成没成都算)。有改动返回 true。 */
    fun markNoted(done: List<NoticeEntry>): Boolean {
        if (done.isEmpty()) return false
        val ids = done.mapTo(HashSet()) { identity(it) }
        var changed = false
        for (i in entries.indices) {
            val e = entries[i]
            if (!e.noted && identity(e) in ids) {
                entries[i] = e.copy(noted = true)
                changed = true
            }
        }
        return changed
    }

    private fun identity(e: NoticeEntry) = listOf(e.packageName, e.keyId, e.sig, e.atMs, e.styled)

    /** 过了保留期的去掉。有去掉的返回 true。不只在来新通知时调:没有新通知,到期的也得删。 */
    fun expire(): Boolean = removeBefore(clock() - keepMs)

    /** [beforeMs] 之前的去掉(过了保留期的、已经归纳完的那一天)。有去掉的返回 true。 */
    fun removeBefore(beforeMs: Long): Boolean = entries.removeAll { it.atMs < beforeMs }

    /** 某个 App 不再归纳(取消勾选、被禁用、不从它归纳、清掉它的)时,它的记录一并去掉。有去掉的返回 true。 */
    fun removePackages(keep: (String) -> Boolean): Boolean = entries.removeAll { !keep(it.packageName) }

    /** 现在留着哪些 App 的通知原文(只有包名,不带内容)。 */
    fun packages(): Set<String> = entries.mapTo(LinkedHashSet()) { it.packageName }

    /** 全部清掉,从现在重新开始记。 */
    fun clear() {
        entries.clear()
        sinceMs = clock()
        cutMs = 0L
    }

    companion object {
        const val CAPACITY = 2_000
        /** 原文留多久:今天和昨天。 */
        const val KEEP_MS = 48 * 3_600_000L
    }
}
