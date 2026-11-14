package com.wangzan101.mika.core.proactive

/**
 * 认出「状态显示」:同一条通知被 App 一遍遍重发,每次换几个字——导航的「300 米后右转」、配送还有几站、比分、倒计时。
 * 它们不是一件件的事,每换一次字就收一条的话,几分钟就是上百条没用的东西,判定、归纳和通知原文里全是它。
 *
 * 常驻的、前台服务的、不可清除的、类别写着导航或进度的,[NoticeFilter] 已经不收了。这里管的是那些什么都没标的:
 * 真机上看到的一个地图 App 的导航播报就是一条普通的可清除通知,没有类别,不出声,每两三秒重发一次。
 * 能认它的只剩「重发得有多密」这一样,所以只看这个,不看是哪个 App、标题里写了什么:
 * - 不出声的通知([NoticeRecord.silent]):一分钟里重发到第 [SILENT_POSTS] 次;
 * - 会出声的通知:一分钟里重发到第 [LOUD_POSTS] 次(人发消息到不了这么密)。
 * 认出来之后这个键的都不收,一直到它安静了 [QUIET_MS]。
 *
 * 聊天不算:一个会话的通知本来就是来一句更新一次。带会话样式的、类别是消息/邮件/社交的、已经按聊天的写法拆开了的
 * ([NoticeRecord.group] 不为空)都不看。没有这些标记、又没测过的聊天 App,要一个键上一分钟来 [LOUD_POSTS] 条才会被当成状态显示
 * (它的通知被用户设成了不出声时是 [SILENT_POSTS] 条)。
 *
 * 只在内存里,不落盘;按通知键认,最多记 [capacity] 个键。不是线程安全的:在 ProactiveRuntime 那条串行协程上用。
 */
class LiveUpdates(private val capacity: Int = 200) {
    enum class Verdict {
        /** 照常收。 */
        Normal,
        /** 这一次重发让它被认成了状态显示:这一条不收,它前面刚收进来的那几条也该去掉。 */
        BecameLive,
        /** 已经认成状态显示了:不收。 */
        Live,
    }

    private class Track(var lastPostedAtMs: Long = Long.MIN_VALUE, val posts: ArrayDeque<Long> = ArrayDeque(), var liveUntilMs: Long = Long.MIN_VALUE)

    private val tracks = object : LinkedHashMap<String, Track>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Track>) = size > capacity
    }

    /** [record] 是清洗、拆好的。同一次发出(发出时间一样)再看一遍不多算一次。 */
    fun observe(record: NoticeRecord): Verdict {
        if (conversation(record)) return Verdict.Normal
        val at = record.postedAtMs
        val t = tracks.getOrPut(record.key) { Track() }
        // 钟被往回拨了很多:以前记的没法比,重来
        if (at < t.lastPostedAtMs - WINDOW_MS) { t.posts.clear(); t.liveUntilMs = Long.MIN_VALUE; t.lastPostedAtMs = Long.MIN_VALUE }
        val live = at <= t.liveUntilMs
        if (at != t.lastPostedAtMs) {
            t.lastPostedAtMs = maxOf(t.lastPostedAtMs, at)
            t.posts.addLast(at)
            while (t.posts.isNotEmpty() && t.posts.first() < at - WINDOW_MS) t.posts.removeFirst()
            while (t.posts.size > LOUD_POSTS) t.posts.removeFirst()
        }
        if (live) {
            t.liveUntilMs = maxOf(t.liveUntilMs, at + QUIET_MS)
            return Verdict.Live
        }
        if (t.posts.size >= if (record.silent) SILENT_POSTS else LOUD_POSTS) {
            t.liveUntilMs = at + QUIET_MS
            return Verdict.BecameLive
        }
        return Verdict.Normal
    }

    /**
     * 用户点开、划掉了这个键上的通知:这一条到此为止,同一个键的下一条从头数(原来被认成状态显示的,也重新认)。
     * 不这样的话:很多 App 所有推送共用一个通知键,用户一条条处理掉的几条各不相干的通知,也会被数成「同一条在重发」。
     * App 自己撤回再发的不算(那多半就是在重发),只有用户动的手才调这里。真的状态显示被划掉后接着重发的,数到第几次照样再认出来。
     */
    fun ended(key: String) {
        tracks.remove(key)
    }

    fun clear() = tracks.clear()

    companion object {
        /** 数重发次数的窗口。 */
        const val WINDOW_MS = 60_000L
        /** 不出声的通知:窗口里重发到第几次就算状态显示。 */
        const val SILENT_POSTS = 4
        /** 会出声的通知:窗口里重发到第几次才算。 */
        const val LOUD_POSTS = 12
        /** 认成状态显示之后,安静这么久才重新照常收。 */
        const val QUIET_MS = 5 * 60_000L

        private val CONVERSATION_CATEGORIES = setOf("msg", "email", "social")

        /** 这是一条会话的通知(来一句更新一次是它本来的样子)。 */
        internal fun conversation(r: NoticeRecord): Boolean =
            r.conversation || r.messages.isNotEmpty() || r.group != null || r.category in CONVERSATION_CATEGORIES
    }
}
