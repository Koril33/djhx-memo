package app.djhx.memo

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.djhx_memo_core.CredentialStore
import uniffi.djhx_memo_core.MemoApp
import java.util.UUID

/** 模拟器 core 调用性能样本，不等同于 arm64 真机的应用冷启动验收。 */
@RunWith(AndroidJUnit4::class)
class CorePerformanceTest {
    private class NoCredentials : CredentialStore {
        override fun load(profileId: String): String? = null
        override fun save(profileId: String, token: String): Boolean = false
        override fun remove(profileId: String) = Unit
    }
    private fun call(app: MemoApp, action: String, vararg fields: Pair<String, Any>): Any {
        val request = JSONObject().put("action", action).put("profile_id", "local")
        fields.forEach { (key, value) -> request.put(key, value) }
        val result = JSONObject(app.invoke(request.toString()))
        assertTrue("performance fixture action failed: $action", result.optBoolean("ok"))
        return result.get("data")
    }
    private fun p95(values: List<Double>) = values.sorted()[(values.size * 0.95).toInt().coerceAtMost(values.size - 1)]

    @Test fun thousandNotesOpenAndShortChineseSearchThirtySamples() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = context.filesDir.resolve("performance-${UUID.randomUUID()}")
        val app = MemoApp(root.absolutePath, NoCredentials(), true)
        try {
            var noteId = ""
            repeat(1000) { index ->
                val text = "# 性能样本 $index\n短词检索 中文😀\n" + "abcdefgh".repeat(250)
                val note = call(
                    app,
                    "create",
                    "request_id" to UUID.randomUUID().toString(),
                    "content" to text
                ) as JSONObject
                noteId = note.getString("id")
            }
            val opening = mutableListOf<Double>()
            val searches = mutableListOf<Double>()
            repeat(30) {
                var start = SystemClock.elapsedRealtimeNanos()
                assertEquals(noteId, (call(app, "get", "id" to noteId) as JSONObject).getString("id"))
                opening += (SystemClock.elapsedRealtimeNanos() - start) / 1000000.0
                start = SystemClock.elapsedRealtimeNanos()
                assertEquals(1000, (call(app, "list", "query" to "短词") as JSONArray).length())
                searches += (SystemClock.elapsedRealtimeNanos() - start) / 1000000.0
            }
            InstrumentationRegistry.getInstrumentation().sendStatus(
                0,
                Bundle().apply {
                    putString(
                        "core_performance",
                        "1000 notes ~2KiB; n=30; open_p95_ms=${p95(opening)}; short_search_p95_ms=${p95(searches)}"
                    )
                }
            )
        } finally {
            app.destroy()
            root.deleteRecursively()
        }
    }
}
