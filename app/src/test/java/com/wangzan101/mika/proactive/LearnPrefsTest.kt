package com.wangzan101.mika.proactive

import com.wangzan101.mika.core.proactive.TriageConsent
import com.wangzan101.mika.settings.FeatureFlagsCapsuleTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LearnPrefsTest {
    // 没同意过;敏感类推断默认关;调成「低」的 App 默认没有
    @Test fun defaultsAreOff() {
        val p = ProactivePrefs(FeatureFlagsCapsuleTest.MemoryPrefs())
        assertNull(p.consent)
        assertFalse(p.learnSensitive)
        assertFalse(p.learnDigest)
        assertEquals(emptySet<String>(), p.learnOptOut)
    }

    @Test fun switchesAndTiersSurvive() {
        val raw = FeatureFlagsCapsuleTest.MemoryPrefs()
        val p = ProactivePrefs(raw)
        p.learnSensitive = true
        p.learnOptOut = setOf("com.tencent.mm")
        val back = ProactivePrefs(raw)
        assertTrue(back.learnSensitive)
        assertEquals(setOf("com.tencent.mm"), back.learnOptOut)
    }

    // 判定和归纳共用的那一份同意:记下、读回、清掉
    @Test fun consentSurvivesAndCanBeCleared() {
        val raw = FeatureFlagsCapsuleTest.MemoryPrefs()
        val p = ProactivePrefs(raw)
        p.consent = TriageConsent("p1", "m1")
        assertEquals(TriageConsent("p1", "m1"), ProactivePrefs(raw).consent)
        p.consent = null
        assertNull(ProactivePrefs(raw).consent)
    }

    // 没做成的删除要记到下一次进程里
    @Test fun pendingPurgesSurvive() {
        val raw = FeatureFlagsCapsuleTest.MemoryPrefs()
        val p = ProactivePrefs(raw)
        assertEquals(emptySet<String>(), p.topicPurges)
        assertEquals(emptySet<String>(), p.contactPurges)
        assertEquals(emptySet<String>(), p.digestPurges)
        p.topicPurges = setOf(LearnRunner.PURGE_SENSITIVE, "com.chat")
        p.contactPurges = setOf("com.chat")
        p.digestPurges = setOf("com.chat", "com.shop")
        val back = ProactivePrefs(raw)
        assertEquals(setOf(LearnRunner.PURGE_SENSITIVE, "com.chat"), back.topicPurges)
        assertEquals(setOf("com.chat"), back.contactPurges)
        assertEquals(setOf("com.chat", "com.shop"), back.digestPurges)
        back.topicPurges = emptySet()
        assertEquals(emptySet<String>(), ProactivePrefs(raw).topicPurges)
    }
}
