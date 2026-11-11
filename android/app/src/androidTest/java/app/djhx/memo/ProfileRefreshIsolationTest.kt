package app.djhx.memo

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Delay one atomic read across a profile switch; equal IDs must not cross the profile boundary. */
@RunWith(AndroidJUnit4::class)
class ProfileRefreshIsolationTest {
    private class DelayedPurgeBridge : CoreBridge {
        var profile = "account-a"
        var event = 1
        var stateReads = 0
        var delayNextRead = false
        var otherContent = "account B committed text"
        val delayedReadStarted = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()

        fun note(owner: String, purged: Boolean = false) = JSONObject()
            .put("id", "shared-note-id").put("generation", "1")
            .put("content", if (purged) "" else if (owner == "account-a") "account A text" else otherContent)
            .put("title", "isolated fixture").put("deleted", purged)
            .put("purge_state", if (purged) "purged" else "none")
            .put("owner", owner).put("save_state", "saved").put("sync_state", "synced")

        override suspend fun call(action: String, vararg fields: Pair<String, Any?>): Any {
            val args = fields.toMap()
            return when (action) {
                "state" -> {
                    stateReads++
                    JSONObject().put("profile", JSONObject().put("id", profile).put("read_only", false))
                        .put("event_seq", event.toString()).put("redirects", JSONObject())
                }
                "list" -> JSONArray().put(note(args["profile_id"].toString()))
                "get" -> {
                    val owner = args["profile_id"].toString()
                    if (owner == "account-a" && delayNextRead) {
                        delayNextRead = false
                        delayedReadStarted.complete(Unit)
                        releaseRead.await()
                        note(owner, purged = true)
                    } else {
                        note(owner)
                    }
                }
                "switch" -> {
                    profile = args["profile_id"].toString()
                    event++
                    JSONObject()
                }
                "save" -> {
                    check(args["profile_id"] == "account-b")
                    otherContent = args["content"].toString()
                    note("account-b")
                }
                else -> error("unexpected isolation fixture action: $action")
            }
        }
    }

    private fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun waitFor(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10000
        var settled = false
        while (!settled && SystemClock.elapsedRealtime() < deadline) {
            main { settled = condition() }
            if (!settled) SystemClock.sleep(25)
        }
        assertTrue("profile isolation fixture did not settle", settled)
    }

    private fun delayedPurgeAcrossProfileSwitch(editAfterSwitch: Boolean) {
        val store = ViewModelStore()
        val bridge = DelayedPurgeBridge()
        lateinit var model: MemoModel
        main {
            model = MemoModel(ApplicationProvider.getApplicationContext<Application>(), bridge)
            store.put("isolation-fixture", model)
        }
        try {
            waitFor { model.state.has("profile") }
            main { model.open(bridge.note("account-a")) }
            waitFor { model.editorOpen && !model.busy }
            main {
                bridge.delayNextRead = true
                bridge.event++
            }
            waitFor { bridge.delayedReadStarted.isCompleted }
            main { model.switchProfile("account-b") }
            waitFor { model.state.getJSONObject("profile").getString("id") == "account-b" && !model.busy }
            main { model.open(bridge.note("account-b")) }
            waitFor { model.editorOpen && !model.busy }
            val expected = if (editAfterSwitch) "account B new input" else "account B committed text"
            var stateReadsAtRelease = 0
            main {
                if (editAfterSwitch) model.edit(expected)
                stateReadsAtRelease = bridge.stateReads
                bridge.releaseRead.complete(Unit)
            }
            // The next poll starts only after the delayed refreshSelected has returned.
            waitFor { bridge.stateReads > stateReadsAtRelease }
            main {
                assertEquals("account-b", model.state.getJSONObject("profile").getString("id"))
                assertEquals("account-b", model.selected!!.getString("owner"))
                assertEquals("none", model.selected!!.getString("purge_state"))
                assertEquals(expected, model.content)
                assertFalse(model.saveFailed)
                assertFalse(model.message?.resource == R.string.note_purged)
                assertEquals(expected, bridge.otherContent)
            }
        } finally {
            main {
                bridge.releaseRead.complete(Unit)
                store.clear()
            }
        }
    }

    @Test fun delayedPurgeCannotClearSameIdNoteInAnotherProfile() = delayedPurgeAcrossProfileSwitch(false)

    @Test fun delayedPurgeCannotLockNewInputInAnotherProfile() = delayedPurgeAcrossProfileSwitch(true)
}
