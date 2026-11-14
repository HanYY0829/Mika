package com.wangzan101.mika.proactive

import android.app.Notification
import android.os.Bundle
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.wangzan101.mika.core.proactive.NoticeMessage
import com.wangzan101.mika.core.proactive.NoticeRecord

/** 从系统通知里原样取出的几项(都还没清洗);[RawNotice.toRecord] 是纯函数,可以在电脑上测。 */
data class RawNotice(
    val key: String,
    val packageName: String,
    /** 系统记下的发出时间(StatusBarNotification.postTime),不用 App 自己填的 `when`。 */
    val postTime: Long,
    val title: String?,
    val bigText: String?,
    val text: String?,
    val messages: List<NoticeMessage>,
    val messagingStyle: Boolean,
    val flags: Int,
    val ongoing: Boolean,
    val clearable: Boolean,
    val category: String?,
    /** 会话样式里系统另给的会话名(群名);没有为 null。 */
    val conversationTitle: String? = null,
    /** 会话样式里系统说的「这是不是群聊」;没说为 null。 */
    val groupConversation: Boolean? = null,
    /** 系统给这条通知排的重要程度(NotificationManager.IMPORTANCE_*);拿不到为 null。 */
    val importance: Int? = null,
) {
    /** 正文优先取展开后的长文本,没有再取一行的正文;有会话样式或带消息列表的算会话。 */
    fun toRecord(): NoticeRecord = NoticeRecord(
        key = key,
        packageName = packageName,
        postedAtMs = postTime,
        title = title.orEmpty(),
        text = bigText?.takeIf { it.isNotBlank() } ?: text.orEmpty(),
        messages = messages,
        conversation = messagingStyle || messages.isNotEmpty(),
        ongoing = ongoing || flags and Notification.FLAG_ONGOING_EVENT != 0,
        groupSummary = flags and Notification.FLAG_GROUP_SUMMARY != 0,
        foregroundService = flags and Notification.FLAG_FOREGROUND_SERVICE != 0,
        clearable = clearable,
        category = category,
        // 只有带会话样式的才信这两项:别的通知里这两个键没有约定的意思
        group = groupConversation.takeIf { messagingStyle || messages.isNotEmpty() },
        conversationTitle = conversationTitle?.takeIf { messagingStyle || messages.isNotEmpty() },
        // 「默认」以下(低、最低)是不出声的;拿不到重要程度时当会出声
        silent = importance != null && importance < IMPORTANCE_DEFAULT,
    )

    private companion object {
        /** NotificationManager.IMPORTANCE_DEFAULT。 */
        const val IMPORTANCE_DEFAULT = 3
    }
}

/**
 * StatusBarNotification → [RawNotice]。通知的 extras 是别的 App 塞进来的,读的时候可能抛任何异常:
 * 调用方([read])整个兜住,读不了就当没有这条。
 */
object NoticeMapper {
    fun read(sbn: StatusBarNotification): RawNotice? = runCatching { readUnsafe(sbn) }.getOrNull()

    private fun readUnsafe(sbn: StatusBarNotification): RawNotice {
        val n = sbn.notification
        val extras = n.extras ?: Bundle.EMPTY
        val style = runCatching { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n) }.getOrNull()
        val messages = style?.messages?.map { m ->
            NoticeMessage(m.person?.name?.toString(), m.text?.toString().orEmpty(), m.timestamp)
        } ?: legacyMessages(extras)
        return RawNotice(
            key = sbn.key,
            packageName = sbn.packageName,
            postTime = sbn.postTime,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            messages = messages,
            messagingStyle = style != null,
            flags = n.flags,
            ongoing = sbn.isOngoing,
            clearable = sbn.isClearable,
            category = n.category,
            conversationTitle = (style?.conversationTitle ?: extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE))?.toString(),
            groupConversation = if (extras.containsKey(Notification.EXTRA_IS_GROUP_CONVERSATION)) extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION)
                else style?.isGroupConversation?.takeIf { it },
        )
    }

    /** 没能取出会话样式时,直接读 EXTRA_MESSAGES 里的每条消息(键名与系统 Notification.MessagingStyle.Message 一致)。 */
    @Suppress("DEPRECATION")
    private fun legacyMessages(extras: Bundle): List<NoticeMessage> =
        runCatching { extras.getParcelableArray(Notification.EXTRA_MESSAGES) }.getOrNull().orEmpty().mapNotNull { p ->
            val b = p as? Bundle ?: return@mapNotNull null
            val text = b.getCharSequence("text")?.toString() ?: return@mapNotNull null
            NoticeMessage(b.getCharSequence("sender")?.toString(), text, b.getLong("time"))
        }
}
