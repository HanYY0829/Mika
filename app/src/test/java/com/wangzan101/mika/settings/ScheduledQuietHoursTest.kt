package com.wangzan101.mika.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class ScheduledQuietHoursTest {
    @Test fun preferencesRoundTripTheWindowAndKeepItWhenDisabled() {
        val prefs = FeatureFlagsCapsuleTest.MemoryPrefs()
        val custom = ScheduledQuietHours(true, LocalTime.of(22, 15), LocalTime.of(7, 30))
        assertEquals(ScheduledQuietHours.DEFAULT, FeatureFlags(prefs).scheduledQuietHours())
        FeatureFlags(prefs).setScheduledQuietHours(custom)
        assertEquals(custom, FeatureFlags(prefs).scheduledQuietHours())
        FeatureFlags(prefs).setScheduledQuietHours(custom.copy(enabled = false))
        assertEquals(custom.copy(enabled = false), FeatureFlags(prefs).scheduledQuietHours())
    }

    @Test fun overnightWindowIncludesMidnightAndExcludesItsEnd() {
        val hours = ScheduledQuietHours.DEFAULT.copy(enabled = true)
        assertFalse(hours.isQuietAt(LocalTime.of(22, 59)))
        assertTrue(hours.isQuietAt(LocalTime.of(23, 0)))
        assertTrue(hours.isQuietAt(LocalTime.MIDNIGHT))
        assertTrue(hours.isQuietAt(LocalTime.of(7, 59)))
        assertFalse(hours.isQuietAt(LocalTime.of(8, 0)))
    }

    @Test fun sameDayWindowUsesTheSameBoundaries() {
        val hours = ScheduledQuietHours(true, LocalTime.of(12, 0), LocalTime.of(14, 0))
        assertFalse(hours.isQuietAt(LocalTime.of(11, 59)))
        assertTrue(hours.isQuietAt(LocalTime.NOON))
        assertFalse(hours.isQuietAt(LocalTime.of(14, 0)))
    }

    @Test fun disabledOrEmptyWindowNeverSilencesNotifications() {
        val hours = ScheduledQuietHours.DEFAULT
        assertFalse(hours.isQuietAt(LocalTime.MIDNIGHT))
        assertFalse(hours.copy(enabled = true, end = hours.start).isQuietAt(hours.start))
    }
}
