package com.wangzan101.mika.settings

import com.wangzan101.mika.capsule.CapsuleCardScale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class FeatureFlagsCapsuleCardScaleTest {
    private fun flags() = FeatureFlags(FeatureFlagsCapsuleTest.MemoryPrefs())

    @Test fun defaultsToNormal() {
        assertEquals(CapsuleCardScale.Normal, flags().capsuleCardScale())
    }

    @Test fun choiceIsKeptAndUnknownFallsBack() {
        val prefs = FeatureFlagsCapsuleTest.MemoryPrefs()
        val flags = FeatureFlags(prefs)
        flags.setCapsuleCardScale(CapsuleCardScale.XLarge)
        assertEquals(CapsuleCardScale.XLarge, FeatureFlags(prefs).capsuleCardScale())
        prefs.edit().putInt("capsule_card_scale", 101).commit()
        assertEquals(CapsuleCardScale.Normal, flags.capsuleCardScale())
    }

    @Test fun changesEmitCurrentThenEachChange() = runTest {
        val flags = flags()
        val seen = mutableListOf<CapsuleCardScale>()
        val job = launch { flags.capsuleCardScaleChanges().take(2).toList(seen) }
        runCurrent()
        flags.setCapsuleCardScale(CapsuleCardScale.Small)
        job.join()
        assertEquals(listOf(CapsuleCardScale.Normal, CapsuleCardScale.Small), seen)
    }

    @Test fun changesEmitCurrentFirst() = runTest {
        assertEquals(CapsuleCardScale.Normal, flags().capsuleCardScaleChanges().first())
    }
}
