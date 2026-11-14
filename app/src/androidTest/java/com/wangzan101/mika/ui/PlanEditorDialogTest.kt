package com.wangzan101.mika.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.core.trigger.TriggerDefinition
import com.wangzan101.mika.core.trigger.TriggerWindowState
import com.wangzan101.mika.task.FileTriggerStore
import com.wangzan101.mika.task.TriggerRuntime
import com.wangzan101.mika.ui.theme.MikaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PlanEditorDialogTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()
    @get:Rule val compose = createComposeRule()

    private fun show(original: TriggerDefinition? = null, saved: MutableList<TriggerDefinition>) {
        compose.setContent {
            MikaTheme(dark = false, reduceMotion = true) {
                PlanEditorDialog(original, "new-thread", onDismiss = {}, onSave = saved::add)
            }
        }
    }

    @Test fun multipleDailyClocksShowThreeOccurrencesAndSurviveEditing() {
        val original = TriggerDefinition("multi", "owner", "每天提醒", System.currentTimeMillis() + 3_600_000,
            calendarTime = "08:00", calendarZone = "Asia/Shanghai", reminderText = "每天提醒")
        val saved = mutableListOf<TriggerDefinition>()
        show(original, saved)
        compose.onNodeWithTag("plan-editor-clock").performScrollTo().performTextClearance()
        compose.onNodeWithTag("plan-editor-clock").performTextInput("08:00, 12:30, 20:00")
        compose.onNodeWithTag("plan-editor-preview-2").performScrollTo().assertIsDisplayed()
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(context.getExternalFilesDir(null), "schedule357-preview.png").outputStream().use {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        image.recycle()
        compose.onNodeWithTag("plan-editor-save").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("multi", saved.single().id)
        assertEquals("owner", saved.single().threadId)
        assertEquals(listOf("08:00", "12:30", "20:00"), saved.single().calendarClocks())
    }

    @Test fun createsFromVisibleFormWithThreePreviewTimes() {
        val saved = mutableListOf<TriggerDefinition>()
        show(saved = saved)

        compose.onNodeWithTag("plan-editor-content").performTextInput("检查天气")
        compose.onNodeWithTag("plan-editor-preview-0").assertExists()
        compose.onNodeWithTag("plan-editor-interval").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("plan-editor-interval-minutes").performScrollTo().performTextClearance()
        compose.onNodeWithTag("plan-editor-interval-minutes").performTextInput("90")
        compose.onNodeWithTag("plan-editor-preview-2").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("plan-editor-save").performSemanticsAction(SemanticsActions.OnClick)

        val plan = saved.single()
        assertEquals("检查天气", plan.goal)
        assertEquals("new-thread", plan.threadId)
        assertNotEquals("new-thread", plan.id)
        assertEquals(90 * 60_000L, plan.intervalMs)
    }

    @Test fun editsExistingPlanWithoutChangingOwnership() {
        val original = TriggerDefinition(
            "existing-id", "existing-thread", "旧任务",
            Instant.parse("2099-01-01T00:00:00Z").toEpochMilli(),
            conditionGoal = "已经准备好",
        )
        val saved = mutableListOf<TriggerDefinition>()
        show(original, saved)

        compose.onNodeWithTag("plan-editor-content").performTextClearance()
        compose.onNodeWithTag("plan-editor-content").performTextInput("新任务")
        compose.onNodeWithTag("plan-editor-daily").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("plan-editor-clock").performScrollTo().performTextClearance()
        compose.onNodeWithTag("plan-editor-clock").performTextInput("09:30")
        compose.onNodeWithTag("plan-editor-save").performSemanticsAction(SemanticsActions.OnClick)

        val plan = saved.single()
        assertEquals("existing-id", plan.id)
        assertEquals("existing-thread", plan.threadId)
        assertEquals("新任务", plan.goal)
        assertEquals("09:30", plan.calendarTime)
        assertEquals("已经准备好", plan.conditionGoal)
    }

    @Test fun switchingToFixedReminderRemovesCondition() {
        val original = TriggerDefinition(
            "existing-id", "existing-thread", "旧任务",
            Instant.parse("2099-01-01T00:00:00Z").toEpochMilli(),
            conditionGoal = "已经准备好",
        )
        val saved = mutableListOf<TriggerDefinition>()
        show(original, saved)

        compose.onNodeWithTag("plan-editor-reminder").performClick()
        compose.onNodeWithTag("plan-editor-condition").assertDoesNotExist()
        compose.onNodeWithTag("plan-editor-save").performClick()

        val plan = saved.single()
        assertEquals("旧任务", plan.reminderText)
        assertEquals("旧任务", plan.goal)
        assertNull(plan.conditionGoal)
    }

    @Test fun invalidTimeStaysInEditorUntilCorrected() {
        val saved = mutableListOf<TriggerDefinition>()
        show(saved = saved)
        compose.onNodeWithTag("plan-editor-content").performTextInput("测试")
        compose.onNodeWithTag("plan-editor-first-at").performTextClearance()
        compose.onNodeWithTag("plan-editor-first-at").performTextInput("2020-01-01 00:00")
        compose.onNodeWithTag("plan-editor-save").performSemanticsAction(SemanticsActions.OnClick)

        compose.onNodeWithTag("plan-editor-error").assertExists()
        assertTrue(saved.isEmpty())

        compose.onNodeWithTag("plan-editor-first-at").performTextClearance()
        compose.onNodeWithTag("plan-editor-first-at").performTextInput("2099-01-01 00:00")
        compose.onNodeWithTag("plan-editor-save").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, saved.size)
    }

    @Test fun plansPageHasManualCreationEntry() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            MikaTheme(dark = false, reduceMotion = true) {
                PlansScreen(context, onBack = {}, onOpenConversation = {})
            }
        }

        compose.onNodeWithContentDescription("新建定时任务").performClick()
        compose.onNodeWithTag("plan-editor-content").assertIsDisplayed()
    }

    @Test fun plansPageShowsRecordedRunsInTheSelectedPlansMenu() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val goal = "执行记录测试 $id"
        val now = System.currentTimeMillis()
        val store = FileTriggerStore(File(context.filesDir, "triggers-v3.json"))
        try {
            store.upsert(TriggerDefinition(id, "history-$id", goal, now + 3_600_000, intervalMs = 60_000))
            val earlier = checkNotNull(store.claimNow(id, now - 3_600_000))
            store.mark(earlier.key, TriggerWindowState.Failed)
            val latest = checkNotNull(store.claimNow(id, now))
            store.mark(latest.key, TriggerWindowState.Delivered)
            compose.setContent {
                MikaTheme(dark = false, reduceMotion = true) {
                    PlansScreen(context, onBack = {}, onOpenConversation = {})
                }
            }

            compose.onNodeWithText(goal).performScrollTo().performClick()
            compose.onNodeWithText("执行记录").performClick()
            compose.onNodeWithText("知道了").assertIsDisplayed()
            compose.onNodeWithText("没成功").assertIsDisplayed()
        } finally {
            TriggerRuntime.remove(context, id)
        }
    }

    @Test fun copyFromPlanMenuSavesAnIndependentPausedPlan() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val goal = "复制计划测试 $id"
        val original = TriggerDefinition(id, "copy-test-$id", goal, System.currentTimeMillis() + 86_400_000)
        try {
            TriggerRuntime.schedule(context, original)
            compose.setContent {
                MikaTheme(dark = false, reduceMotion = true) {
                    PlansScreen(context, onBack = {}, onOpenConversation = {})
                }
            }
            compose.onNodeWithText(goal).performScrollTo().performClick()
            compose.onNodeWithText("复制为新计划").performClick()
            compose.onNodeWithTag("plan-editor-save").performSemanticsAction(SemanticsActions.OnClick)

            val plans = TriggerRuntime.list(context).filter { it.threadId == original.threadId }
            assertEquals(original, plans.single { it.id == id })
            val copy = plans.single { it.id != id }
            assertEquals(goal, copy.goal)
            assertEquals(false, copy.enabled)
        } finally {
            TriggerRuntime.list(context).filter { it.threadId == original.threadId }
                .forEach { TriggerRuntime.remove(context, it.id) }
        }
    }
}
