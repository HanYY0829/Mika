package com.wangzan101.mika.core.proactive

/**
 * 解锁后补收:通知栏里还在的通知,只取锁屏之后(且在打开功能之后)发出的。
 * 锁屏前发出的在解锁状态下已经实时收过;打开功能之前就在的积压一条都不收。
 */
object CatchUp {
    fun select(active: List<NoticeRecord>, enabledAtMs: Long, lastLockedAtMs: Long): List<NoticeRecord> {
        val since = maxOf(enabledAtMs, lastLockedAtMs)
        return active.filter { it.postedAtMs > since }.sortedBy { it.postedAtMs }
    }
}
