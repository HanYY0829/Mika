package com.wangzan101.mika.proactive

import com.wangzan101.mika.core.proactive.NoticeDedup
import com.wangzan101.mika.core.proactive.NoticeRecord
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.settings.FeatureFlagsCapsuleTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactivePrefsTest {
    @Test fun featureIsOffByDefault() {
        val prefs = FeatureFlagsCapsuleTest.MemoryPrefs()
        assertFalse(FeatureFlags(prefs).proactive())
        FeatureFlags(prefs).setProactive(true)
        assertEquals(true, FeatureFlags(prefs).proactive())
    }

    @Test fun timesAndDedupSurvive() {
        val raw = FeatureFlagsCapsuleTest.MemoryPrefs()
        val p = ProactivePrefs(raw)
        assertEquals(0L, p.enabledAtMs)
        assertNull(p.dedup)
        p.enabledAtMs = 5; p.lastLockedAtMs = 7
        val d = NoticeDedup()
        d.accept(NoticeRecord("k", "p", 1, "t", "x"))
        p.dedup = d.encode()
        val back = ProactivePrefs(raw)
        assertEquals(5L, back.enabledAtMs)
        assertEquals(7L, back.lastLockedAtMs)
        assertFalse(NoticeDedup.decode(back.dedup).accept(NoticeRecord("k", "p", 1, "t", "x")))
        back.clearDedup()
        assertNull(ProactivePrefs(raw).dedup)
    }

    @Test fun unsavedRemovalsSurvive() {
        val raw = FeatureFlagsCapsuleTest.MemoryPrefs()
        assertTrue(ProactivePrefs(raw).allowRevoked.isEmpty())
        ProactivePrefs(raw).allowRevoked = setOf("com.chat")
        assertEquals(setOf("com.chat"), ProactivePrefs(raw).allowRevoked)
        ProactivePrefs(raw).allowRevoked = emptySet()
        assertTrue(ProactivePrefs(raw).allowRevoked.isEmpty())
    }
}
