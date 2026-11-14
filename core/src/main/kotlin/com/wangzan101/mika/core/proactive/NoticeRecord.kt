package com.wangzan101.mika.core.proactive

/**
 * 一条通知的本机记录。所有文字都来自发通知的 App(任何人都能给用户发消息),只当数据看:
 * 不解析链接、不当指令、不渲染格式。进 [NoticeInbox] 之前先经 [NoticeFilter.sanitize] 清洗。
 *
 * [key] 是系统给的通知键(里面带着 App 自定的 tag,可能含任意字符);[postedAtMs] 是系统记下的发出时间,
 * 不是 App 自己填的显示时间。后面几项是筛选要用的通知特征。
 */
data class NoticeRecord(
    val key: String,
    val packageName: String,
    val postedAtMs: Long,
    val title: String,
    val text: String,
    val messages: List<NoticeMessage> = emptyList(),
    /** 带会话样式(MessagingStyle)的通知。 */
    val conversation: Boolean = false,
    val ongoing: Boolean = false,
    val groupSummary: Boolean = false,
    val foregroundService: Boolean = false,
    val clearable: Boolean = true,
    val category: String? = null,
    /**
     * 这句话是谁说的:只在通知自己把「谁」写在正文里、又和会话的名字不是同一个时才有(群里的某个人)。
     * 由 [NoticeShapes] 按 App 的写法拆出来;拆不出来、私聊、不是聊天的通知都是 null。带会话样式的通知看 [messages] 里各条的发送人。
     */
    val sender: String? = null,
    /** 这是群聊(true)还是和一个人的私聊(false);看不出来、不是聊天的为 null。同样由 [NoticeShapes] 定。 */
    val group: Boolean? = null,
) {
    /** 给人和模型看的那一句:群里的带上是谁说的(「张三: 明天见」),别的就是正文。 */
    val said: String get() = if (sender.isNullOrBlank()) text else "$sender: $text"
}

/** 会话样式里的一条消息;[sender] 可能没有(自己发的或 App 没给)。 */
data class NoticeMessage(val sender: String?, val text: String, val atMs: Long)
