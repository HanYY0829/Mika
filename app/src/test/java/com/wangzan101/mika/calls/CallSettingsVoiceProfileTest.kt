package com.wangzan101.mika.calls

import com.wangzan101.mika.settings.FeatureFlagsCapsuleTest.MemoryPrefs
import org.junit.Assert.assertEquals
import org.junit.Test

class CallSettingsVoiceProfileTest {
    @Test fun activeProfileSurvivesReopeningSettings() {
        val prefs = MemoryPrefs()
        val settings = CallSettings(prefs)
        settings.save(settings.state.value.copy(activeVoiceProfileId = "work"))

        val reopened = CallSettings(prefs).state.value
        assertEquals("work", reopened.activeVoiceProfileId)
    }
}
