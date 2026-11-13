package com.wangzan101.mika.core.trigger

import org.junit.Assert.assertEquals
import org.junit.Test

/** 编辑计划时预览「接下来几次」:恰好落在间隔整倍数上的那次也要算进去。 */
class UpcomingIntervalTest {
    private fun plan() = TriggerDefinition("p", "t", "goal", 1_000, intervalMs = 1_000)

    @Test fun aTriggerExactlyAtAfterMsIsTheFirstPreviewedOne() {
        assertEquals(listOf(5_000L, 6_000L, 7_000L), upcoming(plan(), 5_000, 3))
        assertEquals(listOf(2_000L, 3_000L), upcoming(plan(), 2_000, 2))
    }

    @Test fun justBeforeAndJustAfterABoundary() {
        assertEquals(listOf(5_000L, 6_000L), upcoming(plan(), 4_500, 2))
        assertEquals(listOf(6_000L, 7_000L), upcoming(plan(), 5_001, 2))
    }

    @Test fun aPlanAlreadyInTheFutureStartsAtItsOwnTime() {
        assertEquals(listOf(9_000L, 10_000L), upcoming(plan().copy(nextAtMs = 9_000), 1_000, 2))
        assertEquals(listOf(9_000L), upcoming(plan().copy(intervalMs = null, nextAtMs = 9_000), 1_000, 3))
    }
}
