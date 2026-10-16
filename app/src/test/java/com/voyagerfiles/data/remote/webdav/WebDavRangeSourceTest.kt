package com.voyagerfiles.data.remote.webdav

import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavRangeSourceTest {
    private val servers = mutableListOf<MockWebServer>()

    @After
    fun tearDown() {
        servers.forEach(MockWebServer::shutdown)
    }

    @Test
    fun authenticatesProbesAndReadsExactRanges() {
        val bytes = ByteArray(10) { it.toByte() }
        val server = serverWithRanges(bytes)
        val source = source(server)

        assertEquals(10L, source.probe().getOrThrow().size)
        assertArrayEquals(byteArrayOf(3, 4, 5, 6), source.read(3, 4).getOrThrow())

        assertEquals("bytes=0-0", server.takeRequest().getHeader("Range"))
        val readRequest = server.takeRequest()
        assertEquals("bytes=3-6", readRequest.getHeader("Range"))
        assertEquals(AUTHORIZATION, readRequest.getHeader("Authorization"))
    }

    @Test
    fun acceptsAnEmptyFileOnlyThroughAZeroTotalRangeResponse() {
        val server = enqueue(
            MockResponse()
                .setResponseCode(416)
                .setHeader("Content-Range", "bytes */0"),
        )

        assertEquals(0L, source(server).probe().getOrThrow().size)
    }

    @Test
    fun rejectsServersThatIgnoreRangeRequests() {
        val server = enqueue(MockResponse().setResponseCode(200).setBody("0123456789"))

        assertTrue(source(server).probe().isFailure)
    }

    @Test
    fun rejectsMalformedProbeTotalsAndBodies() {
        val malformed = enqueue(
            MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-0/unknown").setBody("0"),
        )
        val truncated = enqueue(
            MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-0/10").setBody(""),
        )

        assertTrue(source(malformed).probe().isFailure)
        assertTrue(source(truncated).probe().isFailure)
    }

    @Test
    fun rejectsMismatchedAndTruncatedReadResponses() {
        val mismatched = enqueue(
            validProbe(),
            MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 2-5/10").setBody("2345"),
        )
        val mismatchedSource = source(mismatched)
        mismatchedSource.probe().getOrThrow()
        val truncated = enqueue(
            validProbe(),
            MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 3-6/10").setBody("345"),
        )
        val truncatedSource = source(truncated)
        truncatedSource.probe().getOrThrow()

        assertTrue(mismatchedSource.read(3, 4).isFailure)
        assertTrue(truncatedSource.read(3, 4).isFailure)
    }

    @Test
    fun rejectsReadsBeforeProbeAndInvalidBounds() {
        val source = source(serverWithRanges(ByteArray(10) { it.toByte() }))

        assertTrue(source.read(0, 1).isFailure)
        source.probe().getOrThrow()
        assertTrue(source.read(-1, 1).isFailure)
        assertTrue(source.read(10, 1).isFailure)
        assertTrue(source.read(0, 0).isFailure)
        assertTrue(source.read(0, -1).isFailure)
    }

    @Test
    fun clipsAReadAtEndOfFile() {
        val source = source(serverWithRanges(ByteArray(10) { it.toByte() }))
        source.probe().getOrThrow()

        assertArrayEquals(byteArrayOf(8, 9), source.read(8, 100).getOrThrow())
    }

    @Test
    fun repeatsTheExactRangeAfterAnInterruptedResponseBody() {
        val payload = "0123456789".repeat(1_000)
        val response = MockResponse()
            .setResponseCode(206)
            .setHeader("Content-Range", "bytes 100-10099/20000")
            .setBody(payload)
        val server = enqueue(
            validProbe().setHeader("Content-Range", "bytes 0-0/20000"),
            response.clone().setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
            response,
        )
        val source = source(server)
        source.probe().getOrThrow()

        assertArrayEquals(payload.toByteArray(), source.read(100, payload.length).getOrThrow())
        server.takeRequest()
        repeat(2) {
            val request = server.takeRequest()
            assertEquals("bytes=100-10099", request.getHeader("Range"))
            assertEquals(AUTHORIZATION, request.getHeader("Authorization"))
        }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun stopsAfterThreeInterruptedResponseAttempts() {
        val interrupted = MockResponse()
            .setResponseCode(206)
            .setHeader("Content-Range", "bytes 0-9999/20000")
            .setBody("x".repeat(10_000))
            .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        val server = enqueue(
            validProbe().setHeader("Content-Range", "bytes 0-0/20000"),
            interrupted.clone(), interrupted.clone(), interrupted,
        )
        val source = source(server)
        source.probe().getOrThrow()

        assertTrue(source.read(0, 10_000).isFailure)
        assertEquals(4, server.requestCount)
    }

    @Test
    fun doesNotRetryAuthenticationOrInvalidRangeResponses() {
        listOf(
            MockResponse().setResponseCode(401),
            MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 2-5/10").setBody("2345"),
        ).forEach { response ->
            val server = enqueue(validProbe(), response)
            val source = source(server)
            source.probe().getOrThrow()

            assertTrue(source.read(3, 4).isFailure)
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun closingASourceCancelsTheReadWithoutRetrying() {
        val server = enqueue(validProbe(), MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val source = source(server)
        source.probe().getOrThrow()
        server.takeRequest()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val read = executor.submit<Result<ByteArray>> { source.read(3, 4) }
            assertEquals("bytes=3-6", server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Range"))

            source.close()

            assertTrue(read.get(5, TimeUnit.SECONDS).isFailure)
            assertEquals(2, server.requestCount)
        } finally {
            source.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun closeRejectsLaterReads() {
        val source = source(serverWithRanges(ByteArray(10) { it.toByte() }))
        source.probe().getOrThrow()

        source.close()

        assertTrue(source.read(0, 1).isFailure)
        assertTrue(source.probe().isFailure)
    }

    private fun source(server: MockWebServer) = WebDavRangeSource(
        client = OkHttpClient(),
        url = server.url("/media.bin"),
        authorization = AUTHORIZATION,
    )

    private fun serverWithRanges(bytes: ByteArray): MockWebServer = MockWebServer().also { server ->
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.getHeader("Authorization") != AUTHORIZATION) return MockResponse().setResponseCode(401)
                val range = checkNotNull(request.getHeader("Range"))
                val match = checkNotNull(Regex("bytes=(\\d+)-(\\d+)").matchEntire(range))
                val start = match.groupValues[1].toInt()
                val end = minOf(match.groupValues[2].toInt(), bytes.lastIndex)
                if (bytes.isEmpty() || start >= bytes.size) {
                    return MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */${bytes.size}")
                }
                return MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes $start-$end/${bytes.size}")
                    .setBody(okio.Buffer().write(bytes, start, end - start + 1))
            }
        }
        server.start()
        servers += server
    }

    private fun enqueue(vararg responses: MockResponse): MockWebServer = MockWebServer().also { server ->
        responses.forEach(server::enqueue)
        server.start()
        servers += server
    }

    private fun validProbe() = MockResponse()
        .setResponseCode(206)
        .setHeader("Content-Range", "bytes 0-0/10")
        .setBody("0")

    private companion object {
        val AUTHORIZATION: String = Credentials.basic("tester", "secret")
    }
}
