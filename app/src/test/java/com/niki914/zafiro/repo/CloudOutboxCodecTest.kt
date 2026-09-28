package com.niki914.zafiro.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CloudOutboxCodec 编解码往返与排序语义（纯 JVM）。 */
class CloudOutboxCodecTest {

    @Test
    fun parse_defaultJson_returnsEmpty() {
        assertTrue(CloudOutboxCodec.parse("""{"items":[]}""").isEmpty())
        assertTrue(CloudOutboxCodec.parse("{}").isEmpty())
    }

    @Test
    fun encode_thenParse_roundTripsItems() {
        val items = listOf(
            CloudOutboxItem(
                id = "outbox-a",
                type = CloudOutboxType.SUBMIT_TASK,
                payloadJson = """{"taskId":"t1","task":"reply"}""",
                createdAtMs = 1000L,
                attempts = 2,
                lastError = "timeout",
            ),
            CloudOutboxItem(
                id = "outbox-b",
                type = CloudOutboxType.POST_RESULTS,
                payloadJson = """{"actionId":"a1"}""",
                createdAtMs = 2000L,
            ),
        )

        val parsed = CloudOutboxCodec.parse(CloudOutboxCodec.encode(items))

        assertEquals(items, parsed)
    }

    @Test
    fun parse_sortsByCreatedAtAscending() {
        val json = CloudOutboxCodec.encode(
            listOf(
                CloudOutboxItem("b", CloudOutboxType.SUBMIT_TASK, "{}", 2000L),
                CloudOutboxItem("a", CloudOutboxType.SUBMIT_TASK, "{}", 1000L),
            )
        )

        val parsed = CloudOutboxCodec.parse(json)

        assertEquals(listOf("a", "b"), parsed.map { it.id })
    }

    @Test
    fun parse_skipsBlankPayloadEntries() {
        val json = """{"items":[
            {"id":"x","type":"SUBMIT_TASK","payloadJson":"  ","createdAtMs":1},
            {"id":"y","type":"POST_RESULTS","payloadJson":"{}","createdAtMs":2}
        ]}"""

        val parsed = CloudOutboxCodec.parse(json)

        assertEquals(listOf("y"), parsed.map { it.id })
        assertEquals(1, parsed.size)
    }

    @Test
    fun parse_unknownTypeFallsBackToSubmitTask() {
        val json = """{"items":[
            {"id":"x","type":"WHATEVER","payloadJson":"{}","createdAtMs":1}
        ]}"""

        val parsed = CloudOutboxCodec.parse(json)

        assertEquals(CloudOutboxType.SUBMIT_TASK, parsed.single().type)
    }
}
