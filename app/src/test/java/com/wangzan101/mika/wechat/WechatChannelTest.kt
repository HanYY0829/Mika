package com.wangzan101.mika.wechat

import com.wangzan101.mika.core.run.ChannelInput
import com.wangzan101.mika.core.run.ChannelInputSink
import com.wangzan101.mika.core.run.Delivery
import com.wangzan101.mika.core.run.SendOutcome
import com.wangzan101.mika.core.wechat.WechatAccount
import com.wangzan101.mika.core.wechat.WechatApi
import com.wangzan101.mika.settings.KeyCipher
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetAddress

class WechatChannelTest {
    @get:Rule val tmp = TemporaryFolder()
    private val cipher = object : KeyCipher {
        override fun encrypt(plain: ByteArray) = plain
        override fun decrypt(data: ByteArray) = data
    }

    private fun response(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    @Test fun pollingJournalsOnlyOwnerTextAndAcknowledgesAfterTaskRegistration() = runTest {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val store = WechatStore(tmp.newFolder(), cipher)
            store.saveAccount(WechatAccount("token", server.url("/").toString(), "bot", "owner"))
            store.setEnabled(true)
            val channel = WechatChannel(store, WechatApi(OkHttpClient(), "0.1.35"), log = {})
            val received = mutableListOf<ChannelInput>()
            channel.start(ChannelInputSink { received += it })
            server.enqueue(response("""{"get_updates_buf":"next","msgs":[
                {"message_id":1,"from_user_id":"owner","message_type":1,"context_token":"ctx","item_list":[{"type":1,"text_item":{"text":"任务"}}]},
                {"message_id":2,"from_user_id":"stranger","message_type":1,"item_list":[{"type":1,"text_item":{"text":"不收"}}]}
            ]}"""))

            channel.pollOnce()

            val input = received.single() as ChannelInput.Text
            assertEquals("1", input.messageId)
            assertEquals("任务", input.text)
            assertEquals("wechat", input.peer.channelId)
            assertEquals("next", store.cursor())
            assertEquals("ctx", store.contextToken("owner"))
            assertEquals(1, store.pending().size)
            channel.acknowledge("1")
            assertEquals(emptyList<PendingWechatMessage>(), store.pending())
        }
    }

    @Test fun acceptanceNoticeIsSentAsARealMessage() = runTest {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val store = WechatStore(tmp.newFolder(), cipher)
            store.saveAccount(WechatAccount("token", server.url("/").toString(), "bot", "owner"))
            store.setEnabled(true)
            val channel = WechatChannel(store, WechatApi(OkHttpClient(), "0.1.35"), log = {})
            val peer = store.peer("owner")
            server.enqueue(response("""{"ret":0}"""))

            assertEquals(SendOutcome.Delivered,
                channel.send(peer, Delivery.Notice("accepted:1", peer.threadId, "收到，任务已登记。")))
            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("accepted:1"))
            assertTrue(body.contains("收到，任务已登记。"))
        }
    }

    @Test fun sendDistinguishesRejectionFromUncertainNetworkResult() = runTest {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val store = WechatStore(tmp.newFolder(), cipher)
            store.saveAccount(WechatAccount("token", server.url("/").toString(), "bot", "owner"))
            store.setEnabled(true)
            val channel = WechatChannel(store, WechatApi(OkHttpClient(), "0.1.35"), log = {})
            val peer = store.peer("owner")
            val delivery = Delivery.Result("result:1", peer.threadId, "完成")
            server.enqueue(response("""{"ret":-1,"errmsg":"rejected"}"""))
            assertEquals(SendOutcome.Failed, channel.send(peer, delivery))
            server.enqueue(response("""{"ret":0}""").setResponseCode(500))
            assertEquals(SendOutcome.Uncertain, channel.send(peer, delivery))
        }
    }
}
