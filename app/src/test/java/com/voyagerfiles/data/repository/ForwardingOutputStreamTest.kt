package com.voyagerfiles.data.repository

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForwardingOutputStreamTest {

    @Test
    fun arrayWritesReachTheWrappedStreamAsOneCall() {
        val delegate = RecordingOutputStream()
        val payload = ByteArray(64 * 1024) { index -> (index % 251).toByte() }

        ForwardingOutputStream(delegate).use { stream ->
            stream.write(payload, 0, payload.size)
        }

        assertEquals(1, delegate.arrayWrites)
        assertEquals(0, delegate.singleByteWrites)
        assertEquals(payload.toList(), delegate.bytes.toByteArray().toList())
    }

    @Test
    fun partialArrayWritesForwardTheRequestedRange() {
        val delegate = RecordingOutputStream()
        val payload = "0123456789".toByteArray()

        ForwardingOutputStream(delegate).use { stream ->
            stream.write(payload, 2, 5)
        }

        assertEquals(1, delegate.arrayWrites)
        assertEquals("23456", String(delegate.bytes.toByteArray()))
    }

    @Test
    fun singleByteWritesStillReachTheWrappedStream() {
        val delegate = RecordingOutputStream()

        ForwardingOutputStream(delegate).use { stream ->
            stream.write('a'.code)
            stream.write('b'.code)
        }

        assertEquals(2, delegate.singleByteWrites)
        assertEquals("ab", String(delegate.bytes.toByteArray()))
    }

    @Test
    fun closeRunsSubclassBehaviorAfterFlushingAndClosingTheWrappedStream() {
        val delegate = RecordingOutputStream()
        val events = mutableListOf<String>()
        val stream = object : ForwardingOutputStream(delegate) {
            override fun close() {
                super.close()
                events += "subclass"
            }
        }

        stream.write("payload".toByteArray())
        stream.close()

        assertTrue(delegate.closed)
        assertEquals(listOf("subclass"), events)
        assertEquals("payload", String(delegate.bytes.toByteArray()))
    }

    private class RecordingOutputStream : OutputStream() {
        val bytes = ByteArrayOutputStream()
        var arrayWrites = 0
        var singleByteWrites = 0
        var closed = false

        override fun write(b: Int) {
            singleByteWrites++
            bytes.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            arrayWrites++
            bytes.write(b, off, len)
        }

        override fun close() {
            closed = true
        }
    }
}
