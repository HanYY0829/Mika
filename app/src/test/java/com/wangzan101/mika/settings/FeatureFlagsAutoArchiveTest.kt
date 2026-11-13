package com.wangzan101.mika.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class FeatureFlagsAutoArchiveTest {
    @Test fun offByDefault() {
        assertEquals(0, FeatureFlags(FeatureFlagsCapsuleTest.MemoryPrefs()).autoArchiveDays())
    }

    @Test fun onlyTheOfferedDayCountsAreKept() {
        val prefs = FeatureFlagsCapsuleTest.MemoryPrefs()
        FeatureFlags(prefs).setAutoArchiveDays(30)
        assertEquals(30, FeatureFlags(prefs).autoArchiveDays())
        prefs.edit().putInt("auto_archive_days", 15).commit()
        assertEquals(0, FeatureFlags(prefs).autoArchiveDays())
        FeatureFlags(prefs).setAutoArchiveDays(-7)
        assertEquals(0, FeatureFlags(prefs).autoArchiveDays())
    }
}
