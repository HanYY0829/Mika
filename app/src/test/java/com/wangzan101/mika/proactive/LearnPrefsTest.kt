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
        // 记着的合并(两个话题 id,中间一个 >)也原样存、原样读
        val merge = ":merge:k0123456789ab>kba9876543210"
        p.topicPurges = setOf(LearnRunner.PURGE_SENSITIVE, "com.chat", merge)
        p.contactPurges = setOf("com.chat", merge)
        p.digestPurges = setOf("com.chat", "com.shop")
        val back = ProactivePrefs(raw)
        assertEquals(setOf(LearnRunner.PURGE_SENSITIVE, "com.chat", merge), back.topicPurges)
        assertEquals(setOf("com.chat", merge), back.contactPurges)
        assertEquals(setOf("com.chat", "com.shop"), back.digestPurges)
        back.topicPurges = emptySet()
        assertEquals(emptySet<String>(), ProactivePrefs(raw).topicPurges)
    }

    // 话题、联系人上记着的要照先后补做:先后也得存下来(偏好里的字符串集合自己不保先后)
    @Test fun pendingPurgesKeepTheirOrder() {
        val raw = FeatureFlagsCapsuleTest.MemoryPrefs()
        val p = ProactivePrefs(raw)
        val marks = (0 until 40).map { ":topic:k%012d".format((it * 7919) % 1000) } + listOf("com.chat", ":merge:kaaaaaaaaaaaa>kbbbbbbbbbbbb")
        var set = emptySet<String>()
        for (m in marks) { set = set - m + m; p.topicPurges = set }
        assertEquals(marks, ProactivePrefs(raw).topicPurges.toList())
        // 划掉几个之后剩下的先后不变
        val struck = setOf(marks[3], marks[17])
        p.topicPurges = p.topicPurges - struck
        assertEquals(marks - struck, ProactivePrefs(raw).topicPurges.toList())
        // 只认集合的旧版本写的(没有先后那一项):照常读得出来,只是不分先后
        val old = FeatureFlagsCapsuleTest.MemoryPrefs()
        old.edit().putStringSet("learn_purge_topics", setOf("com.a", ":topic:k1")).commit()
        assertEquals(setOf("com.a", ":topic:k1"), ProactivePrefs(old).topicPurges)
        // 旧版本后来又改过集合、先后那一项对不上了:只认集合
        raw.edit().putStringSet("learn_purge_topics", setOf("com.z")).commit()
        assertEquals(setOf("com.z"), ProactivePrefs(raw).topicPurges)
    }

    // 一次合并两份都要记:一次写上,排在各自的末尾
    @Test fun aMergeIsRecordedOnBothListsAtOnce() {
        val raw = FeatureFlagsCapsuleTest.MemoryPrefs()
        val p = ProactivePrefs(raw)
        p.topicPurges = setOf(":topic:k1")
        p.contactPurges = setOf("com.chat")
        val merge = ":merge:k0123456789ab>kba9876543210"
        p.addToTopicsAndContacts(merge)
        val back = ProactivePrefs(raw)
        assertEquals(listOf(":topic:k1", merge), back.topicPurges.toList())
        assertEquals(listOf("com.chat", merge), back.contactPurges.toList())
    }
}
