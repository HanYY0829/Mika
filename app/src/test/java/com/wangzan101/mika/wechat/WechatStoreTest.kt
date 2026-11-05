package com.wangzan101.mika.wechat

import com.wangzan101.mika.core.wechat.WechatAccount
import com.wangzan101.mika.settings.KeyCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WechatStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val cipher = object : KeyCipher {
        override fun encrypt(plain: ByteArray): ByteArray = plain
        override fun decrypt(data: ByteArray): ByteArray = data
    }

    @Test fun accountCursorAndPendingMessageSurviveReopen() {
        val dir = tmp.newFolder()
        val first = WechatStore(dir, cipher)
        first.saveAccount(WechatAccount("secret", "https://example.test", "bot", "owner"))
        first.setEnabled(true)
        first.rememberContext("owner", "context")
        val peer = first.peer("owner")
        assertTrue(first.journal("message-1", peer, "任务"))
        assertFalse(first.journal("message-1", peer, "任务"))
        first.saveCursor("next")

        val reopened = WechatStore(dir, cipher)
        assertEquals("secret", reopened.account()?.token)
        assertTrue(reopened.enabled())
        assertEquals("next", reopened.cursor())
        assertEquals("context", reopened.contextToken("owner"))
        assertEquals(peer, reopened.peer("owner"))
        assertEquals(listOf(PendingWechatMessage("message-1", peer, "任务")), reopened.pending())

        reopened.acknowledge("message-1")
        assertEquals(emptyList<PendingWechatMessage>(), WechatStore(dir, cipher).pending())
    }

    @Test fun unbindClearsCredentialsAndChannelState() {
        val store = WechatStore(tmp.newFolder(), cipher)
        store.saveAccount(WechatAccount("secret", "https://example.test", "bot", "owner"))
        store.setEnabled(true)
        store.peer("owner")
        store.clear()
        assertEquals(null, store.account())
        assertFalse(store.enabled())
        assertEquals("", store.cursor())
    }
}
