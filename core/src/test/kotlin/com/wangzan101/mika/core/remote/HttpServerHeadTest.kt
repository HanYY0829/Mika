package com.wangzan101.mika.core.remote

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit

class HttpServerHeadTest {
    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    @Test fun headPreservesHeadersAndClosesWithoutSendingTheBody() {
        withServer({ HttpResponse(200, "你好".toByteArray(Charsets.UTF_8), mapOf("Content-Type" to "text/plain; charset=utf-8")) }) { port ->
            val reply = exchange(port, "HEAD / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
            assertEquals(
                "HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\n" +
                    "Content-Length: 6\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n",
                reply,
            )
        }
    }

    @Test fun headThenGetOnTheSameSocketKeepsResponseBoundaries() {
        withServer({ HttpResponse(200, it.path.toByteArray(Charsets.UTF_8)) }) { port ->
            val reply = exchange(port,
                "HEAD /first HTTP/1.1\r\nHost: localhost\r\n\r\n" +
                    "GET /second HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n",
            )
            assertEquals(
                "HTTP/1.1 200 OK\r\nContent-Length: 6\r\nCache-Control: no-store\r\n\r\n" +
                    "HTTP/1.1 200 OK\r\nContent-Length: 7\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n/second",
                reply,
            )
        }
    }

    @Test fun clientCanReuseConnectionAfterUnauthorizedHead() {
        val access = RemoteAccess(token = { "head-test-token" })
        withServer({ req -> access.handle(req) { HttpResponse(200, it.path.toByteArray(Charsets.UTF_8)) } }) { port ->
            val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).retryOnConnectionFailure(false)
                .callTimeout(2, TimeUnit.SECONDS).build()
            try {
                val url = "http://127.0.0.1:$port/api/v1"
                client.newCall(Request.Builder().url(url).head().build()).execute().use { response ->
                    assertEquals(401, response.code)
                    assertEquals("Bearer", response.header("WWW-Authenticate"))
                    assertEquals("", response.body!!.string())
                }
                client.newCall(Request.Builder().url(url).header("Authorization", "Bearer head-test-token").build())
                    .execute().use { response ->
                        assertEquals(200, response.code)
                        assertEquals("/api/v1", response.body!!.string())
                    }
            } finally {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdownNow()
            }
        }
    }

    private fun withServer(handler: (HttpRequest) -> HttpResponse, block: (Int) -> Unit) {
        val server = MiniHttpServer(InetSocketAddress(loopback, 0), handler)
        server.start()
        try {
            block(server.port)
        } finally {
            server.stop()
        }
    }

    private fun exchange(port: Int, request: String): String = Socket().use { socket ->
        socket.connect(InetSocketAddress(loopback, port), 2_000)
        socket.soTimeout = 2_000
        socket.getOutputStream().write(request.toByteArray(Charsets.ISO_8859_1))
        // 读取至连接关闭，同时验证 HEAD 没有正文和后续 GET 的完整响应。
        socket.getInputStream().bufferedReader(Charsets.UTF_8).readText()
    }
}
