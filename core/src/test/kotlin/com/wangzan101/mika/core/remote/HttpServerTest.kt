package com.wangzan101.mika.core.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

class HttpServerTest {
    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    private fun request(chunks: String) =
        "POST /mcp HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n$chunks"

    private fun read(chunks: String) = MiniHttpServer.readRequest(request(chunks).byteInputStream(), loopback)!!

    private fun chunk(body: String) = "${body.length.toString(16)}\r\n$body\r\n"

    private fun assertTooLarge(chunks: String) {
        assertEquals(413, assertThrows(HttpProtocolException::class.java) { read(chunks) }.status)
    }

    @Test fun rejectsChunkSizeThatWouldOverflowTheAccumulatedLength() {
        // No huge body is needed: reject the declared size before allocating or reading it.
        assertTooLarge("1\r\nx\r\n7fffffff\r\n")
    }

    @Test fun rejectsOneChunkAboveTheBodyLimit() {
        assertTooLarge("${(MiniHttpServer.MAX_BODY + 1).toString(16)}\r\n")
    }

    @Test fun rejectsMultipleChunksWhoseCombinedLengthExceedsTheBodyLimit() {
        val full = "x".repeat(MiniHttpServer.MAX_BODY)
        assertTooLarge(chunk(full) + "1\r\n")
    }

    @Test fun acceptsMultipleChunksAtExactlyTheBodyLimit() {
        val first = "a".repeat(MiniHttpServer.MAX_BODY / 2)
        val second = "b".repeat(MiniHttpServer.MAX_BODY - first.length)
        assertEquals(first + second, read(chunk(first) + chunk(second) + "0\r\n\r\n").body.toString(Charsets.UTF_8))
    }

    @Test fun acceptsAnEmptyChunkedBody() {
        assertEquals(0, read("0\r\n\r\n").body.size)
    }

    @Test fun rejectsUnauthenticatedOverflowBeforeTheHandlerAndKeepsServing() {
        val handled = AtomicInteger()
        val server = MiniHttpServer(InetSocketAddress(loopback, 0), handler = { req ->
            assertNull(req.header("authorization"))
            handled.incrementAndGet()
            HttpResponse.empty(200)
        })
        server.start()
        try {
            Socket(loopback, server.port).use { socket ->
                socket.soTimeout = 2_000
                socket.getOutputStream().write(request("1\r\nx\r\n7fffffff\r\n").toByteArray())
                val reply = socket.getInputStream().bufferedReader().readText()
                assertTrue(reply, reply.startsWith("HTTP/1.1 413 "))
                assertTrue(reply, reply.contains("Connection: close\r\n"))
            }
            assertEquals(0, handled.get())
            Socket(loopback, server.port).use { socket ->
                socket.soTimeout = 2_000
                socket.getOutputStream().write("GET /mcp HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray())
                val reply = socket.getInputStream().bufferedReader().readText()
                assertTrue(reply, reply.startsWith("HTTP/1.1 200 "))
            }
            assertEquals(1, handled.get())
        } finally {
            server.stop()
        }
    }
}
