package com.wangzan101.mika.settings

import java.time.LocalTime

data class ScheduledQuietHours(val enabled: Boolean, val start: LocalTime, val end: LocalTime) {
    fun isQuietAt(time: LocalTime): Boolean = enabled && when {
        start < end -> time >= start && time < end
        start > end -> time >= start || time < end
        else -> false
    }

    companion object {
        val DEFAULT = ScheduledQuietHours(false, LocalTime.of(23, 0), LocalTime.of(8, 0))
    }
}
