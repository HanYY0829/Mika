package com.wangzan101.mika.settings

import com.wangzan101.mika.capsule.CapsuleMode
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class FeatureFlagsCapsuleControlsTest {
    @Test fun tileRestoresEachExistingDisplayModeAcrossInstances() {
        for (mode in listOf(CapsuleMode.Always, CapsuleMode.DuringTasks)) {
            val prefs = FeatureFlagsCapsuleTest.MemoryPrefs()
            val ui = FeatureFlags(prefs)
            val tile = FeatureFlags(prefs)
            ui.setCapsuleMode(mode)
            tile.toggleCapsule()
            assertEquals(CapsuleMode.Off, ui.capsuleMode())
            tile.toggleCapsule()
            assertEquals(mode, ui.capsuleMode())
        }
    }

    @Test fun automaticHidePreferencesDefaultOffAndPersist() {
        val prefs = FeatureFlagsCapsuleTest.MemoryPrefs()
        val flags = FeatureFlags(prefs)
        assertFalse(flags.capsuleHideFullscreen())
        assertFalse(flags.capsuleHideAtEdge())
        flags.setCapsuleHideFullscreen(true)
        flags.setCapsuleHideAtEdge(true)
        assertTrue(FeatureFlags(prefs).capsuleHideFullscreen())
        assertTrue(FeatureFlags(prefs).capsuleHideAtEdge())
    }

    @Test fun fullscreenPreferenceEmitsLiveChanges() = runTest {
        val flags = FeatureFlags(FeatureFlagsCapsuleTest.MemoryPrefs())
        val values = mutableListOf<Boolean>()
        val job = launch { flags.capsuleHideFullscreenChanges().take(3).toList(values) }
        runCurrent()
        flags.setCapsuleHideFullscreen(true)
        runCurrent()
        flags.setCapsuleHideFullscreen(false)
        job.join()
        assertEquals(listOf(false, true, false), values)
    }


    @Test fun edgePreferenceEmitsLiveChanges() = runTest {
        val flags = FeatureFlags(FeatureFlagsCapsuleTest.MemoryPrefs())
        val values = mutableListOf<Boolean>()
        val job = launch { flags.capsuleHideAtEdgeChanges().take(3).toList(values) }
        runCurrent()
        flags.setCapsuleHideAtEdge(true)
        runCurrent()
        flags.setCapsuleHideAtEdge(false)
        job.join()
        assertEquals(listOf(false, true, false), values)
    }
}
