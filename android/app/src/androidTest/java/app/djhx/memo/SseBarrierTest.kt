package app.djhx.memo

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.djhx_memo_core.CredentialStore
import uniffi.djhx_memo_core.MemoApp
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the packaged JNI core with a logged-in, continuously heartbeating HTTP stream. */
@RunWith(AndroidJUnit4::class)
class SseBarrierTest {
    @Test fun liveAccountEventsDoNotPreventNativeCheckpoint() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val credentials = object : CredentialStore {
            override fun load(profileId: String): String? = null
            override fun save(profileId: String, token: String): Boolean = false
            override fun remove(profileId: String) = Unit
        }
        EventsFixture().use { fixture ->
            MemoApp(
                context.filesDir.resolve("live-sse-${UUID.randomUUID()}").absolutePath,
                credentials,
                true
            ).use { core ->
                fun call(request: JSONObject): JSONObject {
                    val result = JSONObject(core.invoke(request.toString()))
                    assertTrue(result.optBoolean("ok"))
                    return result.getJSONObject("data")
                }
                call(JSONObject().put("action", "foreground").put("active", false))
                call(
                    JSONObject().put("action", "login").put("server_url", fixture.url)
                        .put("username", "fixture").put("password", "local fixture password")
                        .put("device_name", "SSE checkpoint fixture")
                )
                val note = call(
                    JSONObject().put("action", "create").put("request_id", UUID.randomUUID().toString())
                        .put("content", "实时连接中的合成笔记😀")
                )
                call(JSONObject().put("action", "foreground").put("active", true))
                val deadline = SystemClock.elapsedRealtime() + 5000
                while (fixture.beats.get() < 3 && SystemClock.elapsedRealtime() < deadline) Thread.sleep(20)
                assertTrue("Packaged JNI never opened the live SSE stream", fixture.beats.get() >= 3)
                val started = SystemClock.elapsedRealtime()
                core.pauseForUpdate()
                try {
                    assertTrue("Checkpoint waited for network lifetime", SystemClock.elapsedRealtime() - started < 5000)
                    val blocked = JSONObject(core.invoke("{\"action\":\"state\"}"))
                    assertEquals("UPDATE_BUSY", blocked.getJSONObject("error").getString("code"))
                } finally {
                    core.resumeAfterUpdate()
                }
                val saved = call(JSONObject().put("action", "get").put("id", note.getString("id")))
                assertEquals(note.getString("content"), saved.getString("content"))
                assertEquals(note.getString("generation"), saved.getString("generation"))
            }
        }
    }
}

private class EventsFixture : AutoCloseable {
    private val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
    private val workers = Executors.newCachedThreadPool()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val user = UUID.randomUUID().toString()
    private val epoch = UUID.randomUUID().toString()
    val url = "http://127.0.0.1:${server.localPort}"
    val beats = AtomicInteger()
    init {
        workers.execute {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                sockets.add(socket)
                workers.execute {
                    runCatching { handle(socket) }
                    sockets.remove(socket)
                }
            }
        }
    }
    private fun handle(socket: Socket) = socket.use {
        socket.soTimeout = 2000
        val input = socket.getInputStream().bufferedReader(Charsets.UTF_8)
        val request = input.readLine() ?: return@use
        var length = 0
        while (true) {
            val line = input.readLine() ?: return@use
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
        }
        val body = CharArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(body, read, length - read)
            if (count < 0) return@use
            read += count
        }
        val output = socket.getOutputStream()
        if (request.startsWith("POST /v1/auth/login ")) {
            val response = JSONObject().put("user_id", user).put("epoch", epoch).put("token", "fixture-session")
                .put("device_id", JSONObject(String(body)).getString("device_id")).put("protocol_version", 2)
                .toString().toByteArray()
            val headers = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                "Content-Length: ${response.size}\r\nConnection: close\r\n\r\n"
            output.write(headers.toByteArray())
            output.write(response)
        } else if (request.startsWith("GET /v1/events ")) {
            output.write(
                "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray()
            )
            while (!server.isClosed) {
                output.write(": heartbeat\n\n".toByteArray())
                output.flush()
                beats.incrementAndGet()
                Thread.sleep(30)
            }
        } else {
            output.write(
                "HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()
            )
        }
    }
    override fun close() {
        server.close()
        sockets.forEach { runCatching { it.close() } }
        workers.shutdownNow()
        assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS))
    }
}
