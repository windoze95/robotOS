package dev.r1ptt.workspace

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class HubHttpTest {
    @Test fun pairedCredentialsAreHeaderOnlyAndNeverFollowRedirects() {
        MockWebServer().use { destination -> MockWebServer().use { server ->
            destination.start(); server.start()
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", destination.url("/stolen")))
            val error = assertThrows(HubException::class.java) { HubHttp().json(server.url("/").toString(), "device-secret", "GET", "/notes") }
            assertEquals(302, error.status)
            val request = server.takeRequest()
            assertEquals("/api/v1/notes", request.path)
            assertEquals("Bearer device-secret", request.getHeader("Authorization"))
            assertEquals(0, destination.requestCount)
            assertFalse(error.message.orEmpty().contains("device-secret"))
        } }
    }
    @Test fun staleLeaseResponsePreservesActionableCodeWithoutLeakingServerBody() {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setResponseCode(409).setBody("{\"code\":\"stale_revision\",\"error\":\"secret source body\"}"))
            val error = assertThrows(HubException::class.java) { HubHttp().json(server.url("/").toString(), "device-token", "PUT", "/prompts/p1", JSONObject().put("base_revision", 7).put("lease_token", "lease")) }
            assertEquals("stale_revision", error.code)
            assertTrue(error.message.orEmpty().contains("local draft is preserved"))
            assertFalse(error.message.orEmpty().contains("secret source body"))
        }
    }
    @Test fun retriedMediaUploadUsesSameIdentityAndSameOriginalBytes() {
        MockWebServer().use { server ->
            server.start(); repeat(2) { server.enqueue(MockResponse().setBody("{\"id\":\"media-1\",\"size\":4}")) }
            val file = Files.createTempFile("workspace-audio", ".wav").toFile().apply { writeBytes(byteArrayOf(1,2,3,4)) }
            try {
                val transport = HubHttp()
                repeat(2) { transport.upload(server.url("/").toString(), "device-token", "stable-note-id", file, "audio/wav") }
                val first = server.takeRequest(); val second = server.takeRequest()
                assertEquals("stable-note-id", first.getHeader("X-Upload-Id")); assertEquals(first.getHeader("X-Upload-Id"), second.getHeader("X-Upload-Id"))
                assertArrayEquals(first.body.readByteArray(), second.body.readByteArray())
                assertTrue(file.exists())
            } finally { file.delete() }
        }
    }
}
