package app.djhx.memo

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CreateReplayTest {
    private class ReplayBridge : CoreBridge {
        var saved: JSONObject? = null
        var requests = mutableListOf<String>()
        var payloads = mutableListOf<String>()
        override suspend fun call(action: String, vararg fields: Pair<String, Any?>): Any {
            val args = fields.toMap()
            return when (action) {
                "state" -> JSONObject().put("profile", JSONObject().put("id", "local").put("read_only", false))
                    .put("event_seq", requests.size.toString()).put("redirects", JSONObject())
                "list" -> JSONArray().apply { saved?.let { put(it) } }
                "create" -> {
                    requests += args["request_id"].toString()
                    payloads += args["content"].toString()
                    if (saved == null) {
                        saved = JSONObject().put("id", "created").put("generation", "1").put("content", payloads.first())
                            .put("deleted", false)
                        error("fixture committed but response unavailable")
                    }
                    JSONObject(saved!!.toString())
                }
                "save" -> {
                    assertEquals("created", args["id"])
                    assertEquals(saved!!.getString("generation"), args["expected_generation"])
                    saved = JSONObject(saved!!.toString()).put("content", args["content"])
                        .put("generation", (saved!!.getString("generation").toInt() + 1).toString())
                    JSONObject(saved!!.toString())
                }
                "get" -> JSONObject(saved!!.toString())
                else -> error("unexpected fixture action")
            }
        }
    }
    private fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private fun waitFor(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue("create replay did not settle", condition())
    }
    private fun exercise(latest: String) {
        val bridge = ReplayBridge()
        val store = ViewModelStore()
        lateinit var model: MemoModel
        main {
            model = MemoModel(ApplicationProvider.getApplicationContext<Application>(), bridge)
            store.put("fixture", model)
            model.newNote()
        }
        try {
            waitFor { model.editorOpen && !model.busy }
            main { model.edit("首次内容") }
            waitFor { model.localStatus.startsWith("保存失败") }
            assertTrue(model.pending)
            assertNull(model.selected)
            main {
                model.edit(latest)
                model.backToList()
            }
            waitFor { !model.editorOpen && !model.busy }
            assertFalse(model.pending)
            assertEquals("已保存到本机", model.localStatus)
            assertEquals(latest, bridge.saved!!.getString("content"))
            assertEquals("2", bridge.saved!!.getString("generation"))
            assertEquals(2, bridge.requests.size)
            assertEquals(1, bridge.requests.distinct().size)
            assertEquals(listOf("首次内容", "首次内容"), bridge.payloads)
        } finally { main { store.clear() } }
    }

    @Test fun committedCreateWithLostResponseDrainsContinuedInputBeforeSwitch() = exercise("确认未知后继续输入 中文😀")

    @Test fun committedCreateWithLostResponsePreservesUserClearingContent() = exercise("")
}
