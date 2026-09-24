package com.niki914.zafiro.chat

import com.niki914.zafiro.chat.agentic.shell.CappedBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A4：collector 缓冲上限 —— 长驻命令输出不再无界增长；DELTA 偏移随头部裁剪
 * 同步回退，SNAPSHOT 只暴露保留的尾部。
 */
class CappedBufferTest {

    @Test
    fun `below cap everything is retained`() {
        val buffer = CappedBuffer(100)
        buffer.append("hello")
        assertEquals("hello", buffer.snapshot())
        assertEquals("hello", buffer.readDelta())
        assertEquals("", buffer.readDelta()) // consumed
    }

    @Test
    fun `exceeding cap trims from head and keeps tail`() {
        val buffer = CappedBuffer(100)
        buffer.append("HEADMARKER")
        repeat(40) { buffer.append("0123456789") } // 410 chars written
        val snapshot = buffer.snapshot()
        assertTrue("snapshot must be capped at 100", snapshot.length <= 100)
        assertTrue("tail must be preserved", snapshot.endsWith("0123456789"))
        assertTrue("earliest output must be evicted", !snapshot.contains("HEADMARKER"))
    }

    @Test
    fun `delta offset survives head trimming`() {
        val buffer = CappedBuffer(100)
        repeat(40) { buffer.append("0123456789") }
        val first = buffer.readDelta()
        // Everything buffered so far is "unconsumed" from the DELTA reader's view
        // (no read happened before the append storm), and equals the snapshot.
        assertEquals(buffer.snapshot(), first)
        // New output after the read is delivered as a new delta.
        buffer.append("TAIL")
        assertEquals("TAIL", buffer.readDelta())
        assertEquals("", buffer.readDelta())
    }

    @Test
    fun `tiny cap still makes progress`() {
        val buffer = CappedBuffer(4)
        buffer.append("abcdefgh")
        assertTrue(buffer.snapshot().length <= 4)
        assertEquals(buffer.snapshot(), buffer.readDelta())
        buffer.append("xy")
        assertEquals("xy", buffer.readDelta())
    }
}
