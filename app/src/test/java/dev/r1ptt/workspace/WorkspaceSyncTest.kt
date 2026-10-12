package dev.r1ptt.workspace

import android.os.Looper
import dev.r1ptt.App
import dev.r1ptt.data.ConfigBlobStorage
import dev.r1ptt.data.ConfigCipher
import dev.r1ptt.data.ConfigCodec
import dev.r1ptt.data.EncryptedConfigPersistence
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = App::class)
class WorkspaceSyncTest {
    private val app get() = RuntimeEnvironment.getApplication() as App
    @After fun close() { app.deviceApi.stop(); app.history.close() }

    private fun configured(base: String) {
        val identity = JSONObject().put("base_url", base).put("device_id", "r1-a")
            .put("device_token", "workspace-only").put("recovery_token", "recovery-only")
        val disk = object : ConfigBlobStorage { var value: String? = null; override fun read() = value; override fun write(blob: String) { value = blob } }
        val cipher = object : ConfigCipher { override fun seal(plain: String, allowKeyCreation: Boolean) = plain; override fun open(blob: String) = blob }
        val codec = object : ConfigCodec<JSONObject> { override fun encode(value: JSONObject) = value.toString(); override fun decode(plain: String) = JSONObject(plain) }
        app.workspace.identity.javaClass.getDeclaredField("persistence").apply { isAccessible = true }
            .set(app.workspace.identity, EncryptedConfigPersistence(identity, disk, cipher, codec))
        app.workspace.recoveryConfirmed(app.workspace.recoveryTicket())
    }

    private fun sync(): Result<Unit> {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<Result<Unit>> { runBlocking { runCatching { app.workspace.sync() } } }
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!future.isDone && System.nanoTime() < until) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
            return future.get(1, TimeUnit.SECONDS)
        } finally { executor.shutdownNow() }
    }

    /** A host with a change log like the hub's: rows after a cursor, a head, and resets for pruned history. */
    private class Host(private val feed: Boolean = true) : Dispatcher() {
        private val log = CopyOnWriteArrayList<String>()
        private val paths = CopyOnWriteArrayList<String>()
        @Volatile var prunedThrough = 0
        @Volatile var failing: String? = null
        fun changed(kind: String) { log += kind }
        /** Collections fetched since the last call. */
        fun fetched(): List<String> = paths.filter { it in COLLECTIONS }.also { paths.clear() }
        private fun ok(body: JSONObject) = MockResponse().setBody(body.toString())
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path!!.removePrefix("/api/v1")
            paths += path
            val head = log.size
            return when {
                path == "/devices/self/check-in" -> ok(JSONObject().put("device_id", "r1-a").put("lost", false).put("revoked", false)
                    .put("settings_revision", 0).put("settings", JSONObject()).put("commands", JSONArray()))
                path.startsWith("/changes") && !feed -> MockResponse().setResponseCode(400).setBody("""{"code":"invalid_cursor"}""")
                path == "/changes?after=latest" -> ok(JSONObject().put("cursor", head).put("more", false).put("items", JSONArray()))
                path.startsWith("/changes?after=") -> {
                    val after = path.substringAfter('=').toInt()
                    if (after < prunedThrough) ok(JSONObject().put("cursor", head).put("more", false).put("reset", true).put("items", JSONArray()))
                    else ok(JSONObject().put("cursor", head).put("more", false).put("items", JSONArray(log.drop(after).mapIndexed { i, kind ->
                        JSONObject().put("seq", after + i + 1).put("kind", kind) })))
                }
                path == failing -> MockResponse().setResponseCode(503)
                path in COLLECTIONS && path != "/resume" -> ok(JSONObject().put("items", JSONArray()))
                path == "/resume" -> ok(JSONObject().put("revision", 0))
                else -> MockResponse().setResponseCode(404)
            }
        }
        companion object { val COLLECTIONS = setOf("/notes", "/prompts", "/runs", "/resume") }
    }

    private val everything = listOf("/notes", "/prompts", "/runs", "/resume")

    @Test fun syncFetchesOnlyWhatTheHostReportsChanged() {
        MockWebServer().use { server ->
            val host = Host(); server.dispatcher = host; server.start()
            configured(server.url("/").toString())
            sync().getOrThrow()
            assertEquals(everything, host.fetched())
            sync().getOrThrow()
            assertEquals(emptyList<String>(), host.fetched())
            host.changed("note")
            sync().getOrThrow()
            assertEquals(listOf("/notes"), host.fetched())
            host.changed("run"); host.changed("resume")
            sync().getOrThrow()
            assertEquals(listOf("/runs", "/resume"), host.fetched())
            assertEquals(3L, app.workspace.store.changeCursor)
        }
    }

    @Test fun anR1BehindPrunedHistoryReloadsEverything() {
        MockWebServer().use { server ->
            val host = Host(); server.dispatcher = host; server.start()
            configured(server.url("/").toString())
            sync().getOrThrow()
            host.fetched()
            repeat(5) { host.changed("prompt") }
            host.prunedThrough = 4
            sync().getOrThrow()
            assertEquals(everything, host.fetched())
            assertEquals(5L, app.workspace.store.changeCursor)
        }
    }

    @Test fun aFailedFetchIsRetriedOnTheNextPass() {
        MockWebServer().use { server ->
            val host = Host(); server.dispatcher = host; server.start()
            configured(server.url("/").toString())
            sync().getOrThrow()
            host.fetched()
            host.changed("note")
            host.failing = "/notes"
            assertTrue(sync().isFailure)
            assertEquals(0L, app.workspace.store.changeCursor)
            host.failing = null
            host.fetched()
            sync().getOrThrow()
            assertEquals(listOf("/notes"), host.fetched())
        }
    }

    @Test fun aHostWithoutAChangeFeedStillGetsAFullSync() {
        MockWebServer().use { server ->
            val host = Host(feed = false); server.dispatcher = host; server.start()
            configured(server.url("/").toString())
            sync().getOrThrow()
            sync().getOrThrow()
            assertEquals(everything + everything, host.fetched())
            assertNull(app.workspace.store.changeCursor)
        }
    }
}
