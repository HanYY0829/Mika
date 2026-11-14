package com.wangzan101.mika.core.proactive

/**
 * 用户点开提醒时,Mika 写进对话的那条消息:把判为值得提醒的几条通知列出来。纯函数,不调模型;固定的几句话([Copy])由 App 按界面语言给。
 *
 * 这条消息会按 Markdown 显示(对话页和悬浮窗都是)。通知里的字是别人写的:一律先清洗、截短([NoticeFilter.clean]),
 * 再把 Markdown 里有意义的符号转义掉([plain]),并且放在引号里、不在行首:通知里写的链接、图片、标题、列表都只是字,
 * 点不了、也排不了版。它交给模型时怎么对待不在这里管(见 [com.wangzan101.mika.core.agent.NoticeTurn])。
 */
object NoticeTalk {
    /** 一条消息里最多列几条(新的在前);多出来的只说还有几条。 */
    const val ITEMS_MAX = 6
    const val APP_MAX = 40
    const val TITLE_MAX = 60
    const val TEXT_MAX = 200
    const val REASON_MAX = 80

    /**
     * [app] 是按包名从系统读的 App 名,[time] 是 App 排好的时间;[title](会话的名字)、[text](那句话,群里的带着谁说的)
     * 来自通知,[reason] 是模型写的理由。
     */
    data class Item(val app: String, val time: String, val title: String, val text: String, val reason: String)

    /** 固定的几句话:开头([intro],参数是列出的条数)、理由那一行([reason],参数是理由)、没列出的([more],参数是条数)、结尾。 */
    class Copy(val intro: (Int) -> String, val reason: (String) -> String, val more: (Int) -> String, val outro: String)

    /**
     * 写好的那条消息,和里面真列出来的条数([shown]:交进来的那些里的前几条)。只有列出来的才能记为看过:
     * 没列出来的要留着,下次点开接着列。条数由写消息的这里给,不让用的人另算一遍。
     */
    class Composed(val text: String, val shown: Int)

    /** [items] 新的在前,列出前 [ITEMS_MAX] 条;空的时候返回 null(没有可说的)。 */
    fun compose(items: List<Item>, copy: Copy): Composed? {
        if (items.isEmpty()) return null
        val shown = items.take(ITEMS_MAX)
        val text = buildString {
            append(copy.intro(shown.size))
            shown.forEachIndexed { i, it ->
                append("\n\n")
                // 第一行:哪个 App、哪个会话(通知的标题:人、群,或「到账通知」这类)、几点
                val title = field(it.title, TITLE_MAX)
                append("**").append(i + 1).append(". ").append(field(it.app, APP_MAX))
                if (title.isNotEmpty()) append(" · ").append(title)
                if (it.time.isNotBlank()) append(" · ").append(field(it.time, APP_MAX))
                append("**")
                // 第二行:那句话(群里的带着是谁说的)
                val text = field(it.text, TEXT_MAX)
                if (text.isNotEmpty()) append('\n').append('“').append(text).append('”')
                val reason = field(it.reason, REASON_MAX)
                if (reason.isNotEmpty()) append('\n').append(copy.reason(reason))
            }
            if (items.size > shown.size) append("\n\n").append(copy.more(items.size - shown.size))
            append("\n\n").append(copy.outro)
        }
        return Composed(text, shown.size)
    }

    private fun field(s: String, max: Int): String = plain(NoticeFilter.clean(s, max))

    // Markdown 里会起作用的符号(CommonMark 加上表格、删除线):前面加反斜杠就只是字
    private const val SPECIAL = "\\`*_[]<>&~#|"

    /** 把 [s] 里 Markdown 有意义的符号转义掉;[s] 应当已经是一行(清洗过)。 */
    fun plain(s: String): String = buildString(s.length + 8) {
        for (ch in s) {
            if (ch in SPECIAL) append('\\')
            append(ch)
        }
    }
}
