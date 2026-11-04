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

/** 只替换桥接的原子返回值，检查 UI 不把旧缓冲的并发基准提升为副本的新代次。 */
@RunWith(AndroidJUnit4::class)
class RedirectBufferTest {
    private class StrictBridge : CoreBridge {
        var redirect = false
        var targetGeneration = "3"
        var targetContent = "独立更新"
        var saveAttempts = mutableListOf<Pair<String, String>>()
        var targetReads = 0
        private fun note(id: String, generation: String, content: String) = JSONObject()
            .put("id", id).put("generation", generation).put("content", content).put("title", content)
            .put("deleted", false).put("save_state", "saved").put("sync_state", "local")
        fun original() = note("source", "1", "原始内容")
        override suspend fun call(action: String, vararg fields: Pair<String, Any?>): Any {
            val args = fields.toMap()
            return when (action) {
                "state" -> JSONObject().put("profile", JSONObject().put("id", "local").put("read_only", false))
                    .put("event_seq", if (redirect) "2" else "1")
                    .put("redirects", if (redirect) JSONObject().put("source", "target") else JSONObject())
                "list" -> JSONArray().put(original()).put(note("target", targetGeneration, targetContent))
                "get" -> if (args["id"] == "source") {
                    original()
                } else {
                    targetReads++
                    note("target", targetGeneration, targetContent)
                }
                "save" -> {
                    val id = args["id"].toString()
                    val generation = args["expected_generation"].toString()
                    saveAttempts += id to generation
                    if (id == "source" && redirect) throw CoreFailure("LOCAL_GENERATION_CONFLICT", "副本已有更新，保留缓冲")
                    if (id == "target" && generation == targetGeneration) {
                        targetContent = args["content"].toString()
                        targetGeneration = (targetGeneration.toInt() + 1).toString()
                        note("target", targetGeneration, targetContent)
                    } else {
                        throw CoreFailure("LOCAL_GENERATION_CONFLICT", "代次不匹配")
                    }
                }
                else -> error("unexpected fixture action")
            }
        }
    }
    private fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private fun waitFor(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue("redirect fixture did not settle", condition())
    }
    private fun fixture(block: (MemoModel, StrictBridge) -> Unit) {
        val store = ViewModelStore()
        val bridge = StrictBridge()
        lateinit var model: MemoModel
        main {
            model = MemoModel(ApplicationProvider.getApplicationContext<Application>(), bridge)
            store.put("fixture", model)
            model.open(bridge.original())
        }
        try {
            waitFor { model.editorOpen && !model.busy }
            block(model, bridge)
        } finally { main { store.clear() } }
    }

    @Test fun pendingBufferKeepsOriginalGenerationWhenTargetHasIndependentUpdate() = fixture { model, bridge ->
        main {
            model.edit("尚未提交的内容")
            bridge.redirect = true
        }
        waitFor { model.state.optString("event_seq") == "2" && model.localStatus.startsWith("保存失败") }
        assertEquals("source", model.selected!!.getString("id"))
        assertEquals("1", model.selected!!.getString("generation"))
        main { model.edit("继续输入也必须使用原基准") }
        waitFor { bridge.saveAttempts.size >= 3 }
        assertTrue(bridge.saveAttempts.all { it == ("source" to "1") })
        assertEquals("独立更新", bridge.targetContent)
        assertEquals(0, bridge.targetReads)
        assertTrue(model.pending)
    }

    @Test fun composingDefersRedirectAndLaterDirtyBufferCannotAdoptFreshTargetGeneration() = fixture { model, bridge ->
        main {
            model.composing(true)
            bridge.redirect = true
        }
        waitFor { model.state.optString("event_seq") == "2" }
        assertEquals("source", model.selected!!.getString("id"))
        assertEquals(0, bridge.targetReads)
        main {
            bridge.targetGeneration = "4"
            model.edit("组合输入的新内容")
        }
        waitFor { model.localStatus.startsWith("保存失败") }
        main { model.composing(false) }
        waitFor { bridge.saveAttempts.size >= 2 }
        assertEquals("source", model.selected!!.getString("id"))
        assertEquals("1", model.selected!!.getString("generation"))
        assertEquals("组合输入的新内容", model.content)
        assertEquals("独立更新", bridge.targetContent)
        assertEquals(0, bridge.targetReads)
    }
}
