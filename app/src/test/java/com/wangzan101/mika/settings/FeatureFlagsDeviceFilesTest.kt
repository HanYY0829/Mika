package com.wangzan101.mika.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureFlagsDeviceFilesTest {
    @Test fun defaultsOffAndPersistsChanges() {
        val prefs = FeatureFlagsCapsuleTest.MemoryPrefs()
        val flags = FeatureFlags(prefs)
        assertFalse(flags.deviceFiles())
        flags.setDeviceFiles(true)
        assertTrue(FeatureFlags(prefs).deviceFiles())
        flags.setDeviceFiles(false)
        assertFalse(FeatureFlags(prefs).deviceFiles())
    }
}
