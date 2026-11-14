package com.wangzan101.mika.proactive

import com.wangzan101.mika.proactive.ProactiveLockWatch.Reading
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProactiveLockWatchTest {
    private var interactive = false
    private var keyguardLocked = true
    private var reads = 0

    private fun TestScope.watch() = ProactiveLockWatch({ reads++; Reading(interactive, keyguardLocked) }, backgroundScope)

    private fun TestScope.ticks(n: Int) = repeat(n) {
        advanceTimeBy(ProactiveLockWatch.POLL_MS)
        runCurrent()
    }

    @Test fun pollsOnlyWhenWatchingAndScreenOnWithKeyguard() {
        for (active in listOf(true, false)) for (i in listOf(true, false)) for (k in listOf(true, false)) {
            assertEquals("$active $i $k", active && i && k, ProactiveLockWatch.shouldPoll(active, Reading(i, k)))
        }
    }

    // 熄屏时没有轮询,过多久都不读
    @Test fun noPollWhileScreenOff() = runTest {
        val w = watch()
        w.start()
        assertTrue(w.locked.value)
        assertNull(w.pollJob)
        val before = reads
        advanceTimeBy(3_600_000); runCurrent()
        assertEquals(before, reads)
        assertNull(w.pollJob)
        // 熄屏时锁屏界面不在(刚熄、还没上锁)也一样
        keyguardLocked = false
        w.refresh()
        assertTrue(w.locked.value)
        assertNull(w.pollJob)
    }

    // 亮着但锁屏界面还在:轮询,直到读到解锁就停;之后不再读
    @Test fun pollStopsOnUnlock() = runTest {
        val w = watch()
        w.start()
        interactive = true
        w.refresh() // SCREEN_ON
        assertNotNull(w.pollJob)
        ticks(3)
        assertTrue(w.locked.value)
        keyguardLocked = false // 锁屏界面退下,没有广播
        ticks(1)
        assertFalse(w.locked.value)
        assertNull(w.pollJob)
        val after = reads
        ticks(20)
        assertEquals(after, reads)
    }

    // 轮询中熄屏:下一次读到熄屏就停
    @Test fun pollStopsWhenScreenGoesOff() = runTest {
        val w = watch()
        interactive = true
        w.start()
        assertNotNull(w.pollJob)
        interactive = false // 广播还没到,轮询自己读到
        ticks(1)
        assertNull(w.pollJob)
        val after = reads
        ticks(20)
        assertEquals(after, reads)
        // 广播先到也一样立刻停
        interactive = true
        w.refresh()
        assertNotNull(w.pollJob)
        interactive = false
        w.refresh() // SCREEN_OFF
        assertNull(w.pollJob)
        val now = reads
        ticks(20)
        assertEquals(now, reads)
    }

    // 停止看守就停轮询;停着时广播来了也不开
    @Test fun stopCancelsAndKeepsPollOff() = runTest {
        val w = watch()
        interactive = true
        w.start()
        assertNotNull(w.pollJob)
        w.stop()
        assertNull(w.pollJob)
        w.refresh()
        assertNull(w.pollJob)
        val after = reads
        ticks(20)
        assertEquals(after, reads)
    }

    // 解锁 → 再锁 → 亮屏:轮询重新开起来
    @Test fun pollRestartsOnTheNextLockedScreenOn() = runTest {
        val w = watch()
        interactive = true
        w.start()
        keyguardLocked = false
        ticks(1)
        assertNull(w.pollJob)
        interactive = false; keyguardLocked = true
        w.refresh() // SCREEN_OFF
        assertNull(w.pollJob)
        interactive = true
        w.refresh() // SCREEN_ON
        assertNotNull(w.pollJob)
        assertTrue(w.pollJob!!.isActive)
        keyguardLocked = false
        ticks(1)
        assertFalse(w.locked.value)
        assertNull(w.pollJob)
    }
}
