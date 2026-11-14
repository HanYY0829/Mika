package com.wangzan101.mika.core.trigger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PlanEditorTest {
    private val now = Instant.parse("2026-11-06T00:00:00Z").toEpochMilli()
    private val zone = "Asia/Shanghai"

    private fun draft() = newPlanDraft(now, java.time.ZoneId.of(zone)).copy(content = "check the weather")

    @Test fun copyingWeeklyPlanKeepsRulesButStartsPausedWithFreshHistory() {
        val original = TriggerDefinition(
            "plan", "thread", "goal", now, conditionGoal = "condition", idleOnly = true,
            calendarTime = "08:00", calendarTimes = listOf("08:00", "20:00"),
            calendarDays = listOf(1, 5), calendarZone = zone, notifyWhen = ConditionNotify.EachTimeMet,
            cooldownMs = 300_000, lastConditionMet = true, lastValue = "observed", baselineValue = "base",
            baselineAtMs = now, lastFiredAtMs = now, source = TriggerSource.External,
        )
        val copy = editedTrigger(original.toCopyPlanDraft(now), null, "copy", original.threadId, now)

        assertEquals("copy", copy.id)
        assertEquals(original.threadId, copy.threadId)
        assertEquals(original.goal, copy.goal)
        assertEquals(original.conditionGoal, copy.conditionGoal)
        assertEquals(original.calendarClocks(), copy.calendarClocks())
        assertEquals(original.calendarDays, copy.calendarDays)
        assertEquals(original.calendarZone, copy.calendarZone)
        assertEquals(original.notifyWhen, copy.notifyWhen)
        assertEquals(original.cooldownMs, copy.cooldownMs)
        assertEquals(TriggerSource.User, copy.source)
        assertTrue(copy.idleOnly)
        assertFalse(copy.enabled)
        assertNull(copy.lastConditionMet)
        assertNull(copy.lastValue)
        assertNull(copy.baselineValue)
        assertNull(copy.baselineAtMs)
        assertNull(copy.lastFiredAtMs)
    }

    @Test fun copyingAnExpiredReminderSuggestsAFutureTime() {
        val original = TriggerDefinition("plan", "thread", "remind me", now - 1, reminderText = "remind me")
        val copy = editedTrigger(original.toCopyPlanDraft(now), null, "copy", original.threadId, now)

        assertTrue(copy.nextAtMs > now)
        assertEquals(original.reminderText, copy.reminderText)
        assertFalse(copy.enabled)
    }

    @Test fun copyingAFutureIntervalKeepsItsFirstTimeAndCadence() {
        val original = TriggerDefinition("plan", "thread", "goal", now + 180_000, intervalMs = 900_000)
        val copy = editedTrigger(original.toCopyPlanDraft(now), null, "copy", original.threadId, now)

        assertEquals(original.nextAtMs, copy.nextAtMs)
        assertEquals(original.intervalMs, copy.intervalMs)
        assertFalse(copy.enabled)
    }

    /** 复制页面的「首次执行」要显示成当地时间,不能把 UTC 的 ISO 字符串塞进 yyyy-MM-dd HH:mm 输入框。 */
    @Test fun copyingAFutureOncePlanShowsLocalTimeInsteadOfIso() {
        val original = TriggerDefinition("plan", "thread", "goal", now + 600_000)
        val draft = original.toCopyPlanDraft(now)

        assertEquals(formatPlanEditorTime(original.nextAtMs, draft.zone), draft.firstAt)
        assertFalse(draft.firstAt.contains('T'))
        val copy = editedTrigger(draft, null, "copy", original.threadId, now)
        assertEquals(original.nextAtMs, copy.nextAtMs)
    }

    /** 带秒的计划向上取整到下一分钟:截掉秒后不能落到当前时刻之前,否则保存会被判成「时间不在将来」。 */
    @Test fun copyingAPlanWithSecondsRoundsUpToAWholeLocalMinute() {
        val original = TriggerDefinition("plan", "thread", "goal", now + 30_000)
        val draft = original.toCopyPlanDraft(now)
        val copy = editedTrigger(draft, null, "copy", original.threadId, now)

        assertTrue(copy.nextAtMs > now)
        assertTrue(copy.nextAtMs <= now + 90_000)
        assertEquals(formatPlanEditorTime(copy.nextAtMs, draft.zone), draft.firstAt)
        assertFalse(draft.firstAt.contains('T'))
    }

    /** 过期的固定间隔计划从下一次排期开始,同样不能把 ISO 字符串塞进输入框。 */
    @Test fun copyingAnExpiredIntervalStartsAtItsNextOccurrenceInLocalTime() {
        val original = TriggerDefinition("plan", "thread", "goal", now - 60_000, intervalMs = 300_000)
        val draft = original.toCopyPlanDraft(now)
        val copy = editedTrigger(draft, null, "copy", original.threadId, now)

        assertEquals(now + 240_000, copy.nextAtMs)
        assertEquals(original.intervalMs, copy.intervalMs)
        assertEquals(formatPlanEditorTime(copy.nextAtMs, draft.zone), draft.firstAt)
        assertFalse(draft.firstAt.contains('T'))
    }

    @Test fun createsOneShotWithIndependentIdentityAndMatchingPreview() {
        val input = draft().copy(firstAt = "2026-11-07 08:30")
        val plan = editedTrigger(input, null, "new-plan", "new-thread", now)

        assertEquals("new-plan", plan.id)
        assertEquals("new-thread", plan.threadId)
        assertEquals("check the weather", plan.goal)
        assertEquals(Instant.parse("2026-11-07T00:30:00Z").toEpochMilli(), plan.nextAtMs)
        assertNull(plan.intervalMs)
        assertNull(plan.calendarTime)
        assertTrue(plan.enabled)
        assertEquals(listOf(plan.nextAtMs), upcoming(plan, now, 3))
    }

    @Test fun createsIntervalAndCalendarRulesInTheirOwnZone() {
        val interval = editedTrigger(draft().copy(
            repeat = PlanRepeat.Interval,
            firstAt = "2026-11-07 08:30",
            intervalMinutes = "90",
        ), null, "interval", "thread", now)
        assertEquals(90 * 60_000L, interval.intervalMs)
        assertEquals(
            listOf(interval.nextAtMs, interval.nextAtMs + 90 * 60_000L, interval.nextAtMs + 180 * 60_000L),
            upcoming(interval, now, 3),
        )

        val weekly = editedTrigger(draft().copy(
            repeat = PlanRepeat.Weekly,
            clock = "09:15",
            weekdays = setOf(1, 5),
            zone = "America/New_York",
        ), null, "weekly", "thread", now)
        assertEquals("09:15", weekly.calendarTime)
        assertEquals("America/New_York", weekly.calendarZone)
        assertEquals(listOf(1, 5), weekly.calendarDays)
        assertEquals(3, upcoming(weekly, now, 3).size)
        assertNull(weekly.intervalMs)
    }

    @Test fun editsWithoutChangingOwnershipOrUnrelatedConditionHistory() {
        val original = TriggerDefinition(
            "plan", "thread", "old", now + 60_000,
            conditionGoal = "price is below 10",
            enabled = false,
            idleOnly = true,
            lastConditionMet = false,
            lastValue = "12",
            baselineValue = "11",
            baselineAtMs = 123,
            lastFiredAtMs = 100,
            cooldownMs = 300_000,
        )
        val edited = editedTrigger(original.toPlanDraft().copy(
            content = "new goal",
            firstAt = "2026-11-08 10:00",
        ), original, "ignored", "ignored", now)

        assertEquals("plan", edited.id)
        assertEquals("thread", edited.threadId)
        assertEquals("new goal", edited.goal)
        assertFalse(edited.enabled)
        assertTrue(edited.idleOnly)
        assertEquals(false, edited.lastConditionMet)
        assertEquals("12", edited.lastValue)
        assertEquals("11", edited.baselineValue)
        assertEquals(123L, edited.baselineAtMs)
        assertEquals(100L, edited.lastFiredAtMs)
    }

    @Test fun changedConditionStartsWithFreshComparisonState() {
        val original = TriggerDefinition(
            "plan", "thread", "goal", now + 60_000,
            conditionGoal = "old condition",
            lastConditionMet = true,
            lastValue = "old",
            baselineValue = "base",
            baselineAtMs = 123,
            lastFiredAtMs = 100,
        )
        val edited = editedTrigger(original.toPlanDraft().copy(condition = "new condition"), original, "x", "y", now)

        assertEquals("new condition", edited.conditionGoal)
        assertNull(edited.lastConditionMet)
        assertNull(edited.lastValue)
        assertNull(edited.baselineValue)
        assertNull(edited.baselineAtMs)
        assertNull(edited.lastFiredAtMs)
    }

    @Test fun fixedReminderUsesOneTextAndRejectsCondition() {
        val reminder = editedTrigger(draft().copy(
            content = "take a break",
            reminder = true,
            firstAt = "2026-11-07 08:30",
        ), null, "reminder", "thread", now)
        assertEquals("take a break", reminder.goal)
        assertEquals("take a break", reminder.reminderText)
        assertNull(reminder.conditionGoal)

        assertInvalid(draft().copy(reminder = true, condition = "something changed"), PlanValidation.ReminderWithCondition)
    }

    @Test fun rejectsInvalidTimesRangesAndWeekdays() {
        assertInvalid(draft().copy(content = " "), PlanValidation.MissingContent)
        assertInvalid(draft().copy(firstAt = "2026-02-30 08:30"), PlanValidation.InvalidFirstTime)
        assertInvalid(draft().copy(firstAt = "2026-11-05 08:30"), PlanValidation.TimeNotFuture)
        assertInvalid(draft().copy(zone = "not/a zone"), PlanValidation.InvalidZone)
        assertInvalid(draft().copy(repeat = PlanRepeat.Interval, intervalMinutes = "0"), PlanValidation.InvalidInterval)
        assertInvalid(draft().copy(repeat = PlanRepeat.Daily, clock = "25:00"), PlanValidation.InvalidClock)
        assertInvalid(draft().copy(repeat = PlanRepeat.Weekly, weekdays = emptySet()), PlanValidation.InvalidWeekdays)
        assertInvalid(draft().copy(condition = "ready", cooldownMinutes = "-1"), PlanValidation.InvalidCooldown)
    }

    @Test fun conditionOptionsAreClearedWithCondition() {
        val original = TriggerDefinition(
            "plan", "thread", "goal", now + 60_000,
            conditionGoal = "ready",
            notifyWhen = ConditionNotify.EachTimeMet,
            cooldownMs = 600_000,
            lastValue = "yes",
        )
        val edited = editedTrigger(original.toPlanDraft().copy(condition = ""), original, "x", "y", now)

        assertNull(edited.conditionGoal)
        assertNull(edited.cooldownMs)
        assertNull(edited.lastValue)
    }

    private fun assertInvalid(input: PlanDraft, expected: PlanValidation) {
        val error = runCatching { editedTrigger(input, null, "id", "thread", now) }.exceptionOrNull()
        assertTrue("expected $expected but got $error", error is PlanValidationException)
        assertEquals(expected, (error as PlanValidationException).reason)
    }
}
