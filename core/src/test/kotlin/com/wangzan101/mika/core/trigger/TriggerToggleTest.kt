package com.wangzan101.mika.core.trigger

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggerToggleTest {
    private val now = 1_000_000L

    private fun plan(enabled: Boolean = false) =
        TriggerDefinition("plan", "thread", "goal", now + 60_000, enabled = enabled)

    private fun window(dueAtMs: Long, state: TriggerWindowState = TriggerWindowState.Delivered) =
        TriggerWindow("plan", dueAtMs, dueAtMs, state = state)

    private fun engine(plan: TriggerDefinition, window: TriggerWindow?): TriggerEngine {
        val store = MemoryTriggerStore(TriggerSnapshot(listOf(plan), listOfNotNull(window)))
        return TriggerEngine(store, {}, dispatch = { _, _ -> })
    }

    /** 手动运行只记在“现在”这个时段,不会占掉计划自己的下一次时段,所以暂停后还能再打开。 */
    @Test fun manualRunDoesNotBlockResumingAOneShot() {
        val plan = plan()
        val manual = window(now)

        assertTrue(plan.canToggle(listOf(manual)))
        assertTrue(engine(plan, manual).setEnabled(plan.id, true, now))
    }

    /** 一次性计划按计划时间跑过之后不能再开,要再跑得改时间或立即执行;开关和引擎的判断要一致。 */
    @Test fun aOneShotThatUsedItsOwnSlotCannotBeResumed() {
        val plan = plan()
        val used = window(plan.nextAtMs)

        assertFalse(plan.canToggle(listOf(used)))
        assertFalse(engine(plan, used).setEnabled(plan.id, true, now))
    }

    @Test fun enabledPlansCanAlwaysBePaused() {
        val plan = plan(enabled = true)
        assertTrue(plan.canToggle(listOf(window(plan.nextAtMs))))
    }

    @Test fun repeatingPlansStayToggleable() {
        val plan = plan().copy(intervalMs = 60_000)

        assertTrue(plan.canToggle(emptyList()))
        assertTrue(engine(plan, null).setEnabled(plan.id, true, now))
    }
}
