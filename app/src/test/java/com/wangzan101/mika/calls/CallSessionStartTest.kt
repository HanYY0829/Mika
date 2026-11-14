package com.wangzan101.mika.calls

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CallSessionStartTest {
    @Test fun outgoingWaitsForConnectionWhileIncomingPreparesBeforeAnswering() = runTest {
        for (direction in CallDirection.entries) {
            val order = mutableListOf<String>()
            val session = Any()
            val result = startCallSession(direction,
                connect = { order += "connect"; session }, awaitCarrier = { order += "carrier" })
            assertSame(session, result)
            assertEquals(if (direction == CallDirection.OUTGOING) listOf("carrier", "connect")
                else listOf("connect", "carrier"), order)
        }
    }

    @Test fun unansweredOutgoingCallNeverCreatesACloudSession() = runTest {
        var connects = 0
        val unanswered = IOException("synthetic unanswered call")
        val actual = runCatching {
            startCallSession(CallDirection.OUTGOING,
                connect = { connects++; Any() }, awaitCarrier = { throw unanswered })
        }.exceptionOrNull()
        assertSame(unanswered, actual)
        assertEquals(0, connects)
    }

    @Test fun failedIncomingCloudConnectionCannotAnswerThePhone() = runTest {
        var answers = 0
        val unavailable = IOException("synthetic cloud failure")
        val actual = runCatching {
            startCallSession(CallDirection.INCOMING,
                connect = { throw unavailable }, awaitCarrier = { answers++ })
        }.exceptionOrNull()
        assertSame(unavailable, actual)
        assertEquals(0, answers)
    }

    @Test fun cancellingOutgoingWhileWaitingNeverStartsCloud() = runTest {
        val active = CompletableDeferred<Unit>()
        var connects = 0
        val starting = async {
            startCallSession(CallDirection.OUTGOING,
                connect = { connects++; Any() }, awaitCarrier = { active.await() })
        }
        runCurrent()
        starting.cancelAndJoin()
        active.complete(Unit)
        runCurrent()
        assertTrue(starting.isCancelled)
        assertEquals(0, connects)
    }

    @Test fun cancelledIncomingConnectionKeepsCallerOwnershipAndCannotProceedToAnswer() = runTest {
        val reply = CompletableDeferred<Unit>()
        val resource = Any()
        var owned: Any? = null
        var answers = 0
        val starting = async {
            startCallSession(CallDirection.INCOMING, connect = {
                withContext(NonCancellable) { reply.await(); owned = resource; resource }
            }, awaitCarrier = { answers++ })
        }
        runCurrent()
        starting.cancel()
        reply.complete(Unit)
        starting.join()
        assertTrue(starting.isCancelled)
        assertSame("The caller still owns the late session for its existing finally cleanup", resource, owned)
        assertEquals(0, answers)
    }

    @Test fun incomingAnswerFailureLeavesTheAlreadyAdoptedSessionWithItsOwner() = runTest {
        val resource = Any()
        var owned: Any? = null
        val refused = IOException("synthetic answer failure")
        val actual = runCatching {
            startCallSession(CallDirection.INCOMING,
                connect = { owned = resource; resource }, awaitCarrier = { throw refused })
        }.exceptionOrNull()
        assertSame(refused, actual)
        assertSame(resource, owned)
    }

    @Test fun outgoingConnectCancellationRetainsTheAdoptedSessionButCannotReportSuccess() = runTest {
        val reply = CompletableDeferred<Unit>()
        val resource = Any()
        var owned: Any? = null
        var completed = false
        var carrierChecks = 0
        val starting = async {
            startCallSession(CallDirection.OUTGOING, connect = {
                withContext(NonCancellable) { reply.await(); owned = resource; resource }
            }, awaitCarrier = { carrierChecks++ })
            completed = true
        }
        runCurrent()
        starting.cancel()
        reply.complete(Unit)
        starting.join()
        assertTrue(starting.isCancelled)
        assertFalse(completed)
        assertEquals(1, carrierChecks)
        assertSame(resource, owned)
    }

    /** Actual JSON/WebSocket client, local synthetic server only; no phone or paid provider. */
    @Test fun oldRingWindowClosureIsReproducedAndNewSessionAcceptsGreetingOnlyAfterActive() = runBlocking {
        val server = MockWebServer()
        val http = OkHttpClient()
        val sockets = Channel<WebSocket>(2)
        val messages = Channel<String>(32)
        val bySocket = ConcurrentHashMap<WebSocket, CopyOnWriteArrayList<String>>()
        val listener = object : WebSocketListener() {
            override fun onOpen(socket: WebSocket, response: Response) {
                bySocket[socket] = CopyOnWriteArrayList()
                check(sockets.trySend(socket).isSuccess)
            }
            override fun onMessage(socket: WebSocket, text: String) {
                val type = Json.parseToJsonElement(text).jsonObject.getValue("type").jsonPrimitive.content
                bySocket.getValue(socket) += type
                check(messages.trySend(type).isSuccess)
                when (type) {
                    "session.create" -> socket.send("""{"type":"session.created"}""")
                    "speech_text_buffer.commit" -> {
                        socket.send("""{"type":"response.output_audio.started","response_id":"synthetic-opening"}""")
                        socket.send("""{"type":"response.output_audio.delta","response_id":"synthetic-opening","delta":"AQI="}""")
                    }
                    "session.close" -> socket.send("""{"type":"session.closed"}""")
                }
            }
            override fun onClosing(socket: WebSocket, code: Int, reason: String) { socket.close(code, null) }
        }
        repeat(2) { server.enqueue(MockResponse().withWebSocketUpgrade(listener)) }
        server.start()
        var oldSession: VolcRealtimeSession? = null
        var ownedNewSession: VolcRealtimeSession? = null
        try {
            withTimeout(10_000L) {
                val client = VolcRealtimeClient(http, server.url("/synthetic-local-only").toString(), 1_000, 500)
                val credentials = VolcRealtimeCredentials(apiKey = "synthetic-local-key")
                // Negative control: the old session already exists when the ringing transport fails.
                val old = client.connect(credentials)
                oldSession = old
                val oldSocket = sockets.receive()
                assertTrue(old.setMuted(true))
                while (messages.receive() != "input_audio_mute.commit") { }
                oldSocket.close(1000, "synthetic transport closure while waiting")
                old.awaitClosed()
                assertFalse(old.setMuted(false))
                assertFalse(old.sendGreeting("synthetic opening"))
                assertEquals(VolcRealtimeEvent.Closed, old.events.first())

                val active = CompletableDeferred<Unit>()
                val starting = async {
                    startCallSession(CallDirection.OUTGOING, connect = {
                        client.connect(credentials).also { session ->
                            ownedNewSession = session
                            assertTrue(session.setMuted(true))
                        }
                    }, awaitCarrier = { active.await() })
                }
                delay(100)
                assertFalse(starting.isCompleted)
                assertEquals("No replacement cloud connection exists during outgoing ringing", 1, server.requestCount)
                active.complete(Unit)
                val fresh = starting.await()
                val newSocket = sockets.receive()
                assertSame(ownedNewSession, fresh)
                assertTrue(fresh.setMuted(false))
                assertTrue(fresh.sendGreeting("synthetic opening"))
                val audio = fresh.events.first { it is VolcRealtimeEvent.Audio } as VolcRealtimeEvent.Audio
                assertArrayEquals(byteArrayOf(1, 2), audio.pcm)
                assertEquals(listOf("session.create", "input_audio_mute.commit", "input_audio_unmute.commit",
                    "speech_text_buffer.commit"), bySocket.getValue(newSocket).toList())
                assertEquals(2, server.requestCount)
            }
        } finally {
            oldSession?.close()
            ownedNewSession?.close()
            http.dispatcher.executorService.shutdownNow()
            http.connectionPool.evictAll()
            server.shutdown()
        }
    }
}
