package com.wangzan101.mika.proactive

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.wangzan101.mika.MikaApp

/**
 * 主动能力的通知监听。组件默认是停用的,功能打开时才由 [ProactiveRuntime] 启用(系统「通知使用权」列表里才有 Mika)。
 * 这里只把系统回调转给 [ProactiveRuntime],连上之前什么都不做。
 */
class NoticeListenerService : NotificationListenerService() {
    private val runtime get() = (application as MikaApp).proactive

    override fun onListenerConnected() {
        runtime.onConnected(this)
    }

    override fun onListenerDisconnected() {
        // 断开之后不能再调这个监听的任何方法;之后只能由 [ProactiveRuntime] 经静态的 requestRebind 请系统重绑
        runtime.onDisconnected(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        sbn?.let(runtime::onPosted)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?, reason: Int) {
        // 用户点开、划掉、清空:已经看过了,从收件箱里去掉;App 自己撤回、超时等不算
        if (sbn != null && reason in SEEN_REASONS) runtime.onSeen(sbn.key, reason)
    }

    /** 请系统解绑;只在连着时有效,不在连着时调用会抛异常,这里吞掉。返回请求有没有发出去。 */
    fun unbindQuietly(): Boolean = runCatching { requestUnbind() }.isSuccess

    /**
     * 通知栏里现有的通知;读不出来为 null——系统可能给 null,也可能抛异常(还没连上、刚断开)。
     * 读不出来和「通知栏是空的」要分开:前者这次补收等于没做,还得再试。
     */
    fun activeOrNull(): Array<StatusBarNotification>? = runCatching { activeNotifications }.getOrNull()

    companion object {
        val SEEN_REASONS = setOf(REASON_CLICK, REASON_CANCEL, REASON_CANCEL_ALL)
    }
}
