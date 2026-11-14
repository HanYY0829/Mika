package com.wangzan101.mika.core.proactive

import org.junit.Assert.assertEquals
import org.junit.Test

class CatchUpTest {
    private fun rec(at: Long) = NoticeRecord("k$at", "p", at, "t", "x")
    private val active = listOf(rec(300), rec(100), rec(200), rec(50))

    @Test fun onlyPostsAfterTheLock() =
        assertEquals(listOf(200L, 300L), CatchUp.select(active, enabledAtMs = 0, lastLockedAtMs = 150).map { it.postedAtMs })

    @Test fun firstEnableIngestsNoBacklog() =
        assertEquals(emptyList<NoticeRecord>(), CatchUp.select(active, enabledAtMs = 300, lastLockedAtMs = 0))

    @Test fun enableTimeWinsWhenLater() =
        assertEquals(listOf(300L), CatchUp.select(active, enabledAtMs = 250, lastLockedAtMs = 100).map { it.postedAtMs })

    @Test fun boundaryIsExclusive() =
        assertEquals(listOf(300L), CatchUp.select(active, enabledAtMs = 0, lastLockedAtMs = 200).map { it.postedAtMs })
}
