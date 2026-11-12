package com.wangzan101.mika.calls

import com.wangzan101.mika.settings.FeatureFlagsCapsuleTest.MemoryPrefs
import org.junit.Assert.assertEquals
import org.junit.Test

class CallSettingsGreetingTest {
    @Test fun customAndExplicitlyEmptyGreetingsSurviveReopeningSettings() {
        val prefs = MemoryPrefs()
        val settings = CallSettings(prefs)
        assertEquals(CallPolicy.DEFAULT_GREETING, settings.state.value.greeting)

        settings.save(settings.state.value.copy(greeting = "你好", systemPrompt = "你好"))
        assertEquals("你好", prefs.getString("greeting", null))
        val reopened = CallSettings(prefs)
        assertEquals("你好", reopened.state.value.greeting)
        assertEquals("你好", reopened.state.value.systemPrompt)

        reopened.save(reopened.state.value.copy(greeting = ""))
        assertEquals("", prefs.getString("greeting", null))
        val cleared = CallSettings(prefs).state.value
        assertEquals("", cleared.greeting)
        assertEquals("你好", cleared.systemPrompt)
    }
}
