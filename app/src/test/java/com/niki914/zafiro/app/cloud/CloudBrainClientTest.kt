package com.niki914.zafiro.app.cloud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** CloudBrainClient 线协议测试（MockWebServer，纯 JVM）。 */
class CloudBrainClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: CloudBrainClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = CloudBrainClient(
            workerUrl = server.url("/").toString().trimEnd('/'),
            secret = "test-secret",
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun snapshotJson(): String {
        return """
            {
              "session_id": "s1",
              "status": "waiting_phone",
              "cursor": 7,
              "pending_actions": [
                {"id": "a1", "tool": "terminal", "arguments_json": "{\"command\":\"id\"}"}
              ],
              "new_events": [{"kind": "step", "text": "calling terminal"}],
              "unknown_future_field": true
            }
        """.trimIndent()
    }

    @Test
    fun submitTask_postsAndParses() {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(snapshotJson())
        )

        val snapshot = client.submitTask(
            CloudTaskSubmit(
                deviceId = "dev-1",
                task = "reply to message",
                context = "title: hi",
                allowedTools = listOf("terminal"),
            )
        )

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/tasks", recorded.path)
        assertEquals("Bearer test-secret", recorded.getHeader("Authorization"))
        val sent = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals("dev-1", sent["device_id"]?.toString()?.trim('"'))

        assertEquals("s1", snapshot.sessionId)
        assertEquals(CloudSessionSnapshot.STATUS_WAITING_PHONE, snapshot.status)
        assertEquals(7L, snapshot.cursor)
        assertEquals(1, snapshot.pendingActions.size)
        assertEquals("terminal", snapshot.pendingActions[0].tool)
        assertEquals("{\"command\":\"id\"}", snapshot.pendingActions[0].argumentsJson)
    }

    @Test
    fun poll_includesCursorAndLongPollWait() {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(snapshotJson())
        )

        client.poll(sessionId = "s1", cursor = 5)

        val recorded = server.takeRequest()
        assertEquals("/v1/sessions/s1?cursor=5&wait=25", recorded.path)
        assertEquals("GET", recorded.method)
    }

    @Test
    fun postResults_sendsResultsBody() {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(snapshotJson())
        )

        client.postResults(
            "s1",
            CloudResultsPost(listOf(CloudActionResult("a1", ok = true, message = "done"))),
        )

        val recorded = server.takeRequest()
        assertEquals("/v1/sessions/s1/results", recorded.path)
        assertTrue(recorded.body.readUtf8().contains("\"id\":\"a1\""))
    }

    @Test
    fun ping_httpError_reportsNotOk() {
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"unauthorized"}""")
        )

        val ping = client.ping()

        assertFalse(ping.ok)
        assertTrue(ping.message.contains("401"))
    }

    @Test
    fun ping_ok() {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"status":"ok","sessions":2}""")
        )

        val ping = client.ping()

        assertEquals("/v1/health", server.takeRequest().path)
        assertTrue(ping.ok)
        assertEquals("ok", ping.message)
    }
}
