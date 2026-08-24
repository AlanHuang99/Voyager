package com.voyagerfiles.data.remote.webdav

import com.voyagerfiles.playback.PlaybackRandomAccessSource
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer

internal data class WebDavRangeMetadata(
    val size: Long,
)

internal class WebDavRangeSource(
    private val client: OkHttpClient,
    private val url: HttpUrl,
    private val authorization: String?,
) : PlaybackRandomAccessSource {
    private val lifecycleLock = Any()
    private val activeCalls = mutableSetOf<Call>()
    private var closed = false

    @Volatile
    private var probedSize: Long? = null

    fun probe(): Result<WebDavRangeMetadata> = runCatching {
        val request = requestForRange(0, 0)
        execute(request) { response ->
            val header = response.header("Content-Range")
            when {
                response.code == 416 && header?.matches(EMPTY_CONTENT_RANGE) == true -> WebDavRangeMetadata(0)
                response.code == 206 -> {
                    val parsed = parseContentRange(header)
                    require(parsed.start == 0L && parsed.end == 0L && parsed.total > 0L) {
                        "WebDAV range probe returned mismatched bounds"
                    }
                    require(readBoundedBody(response, 1).size == 1) { "WebDAV range probe returned no byte" }
                    WebDavRangeMetadata(parsed.total)
                }
                else -> error("WebDAV server did not provide a usable byte range")
            }
        }.also { probedSize = it.size }
    }

    override fun read(offset: Long, byteCount: Int): Result<ByteArray> = runCatching {
        val size = checkNotNull(probedSize) { "WebDAV source must be probed before reading" }
        require(offset >= 0) { "Read offset must not be negative" }
        require(byteCount > 0) { "Read byte count must be positive" }
        require(offset < size) { "Read offset is beyond end of file" }
        val requestedEnd = Math.addExact(offset, byteCount.toLong() - 1)
        val end = minOf(size - 1, requestedEnd)
        val expectedLength = Math.toIntExact(end - offset + 1)
        execute(requestForRange(offset, end)) { response ->
            require(response.code == 206) { "WebDAV range read returned HTTP ${response.code}" }
            val parsed = parseContentRange(response.header("Content-Range"))
            require(parsed.start == offset && parsed.end == end && parsed.total == size) {
                "WebDAV range read returned mismatched bounds"
            }
            readBoundedBody(response, expectedLength)
        }
    }

    override fun close() {
        val calls = synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            activeCalls.toList()
        }
        calls.forEach(Call::cancel)
        client.connectionPool.evictAll()
    }

    private fun requestForRange(start: Long, end: Long): Request = Request.Builder()
        .url(url)
        .header("Range", "bytes=$start-$end")
        .header("Accept-Encoding", "identity")
        .apply { authorization?.let { header("Authorization", it) } }
        .build()

    private fun <T> execute(request: Request, block: (Response) -> T): T {
        val call = client.newCall(request)
        synchronized(lifecycleLock) {
            check(!closed) { "WebDAV range source is closed" }
            activeCalls += call
        }
        return try {
            call.execute().use(block)
        } finally {
            synchronized(lifecycleLock) { activeCalls -= call }
        }
    }

    private fun readBoundedBody(response: Response, expectedLength: Int): ByteArray {
        val source = requireNotNull(response.body) { "WebDAV range response has no body" }.source()
        val buffer = Buffer()
        var remaining = expectedLength.toLong() + 1
        while (remaining > 0) {
            val read = source.read(buffer, remaining)
            if (read == -1L) break
            remaining -= read
        }
        val bytes = buffer.readByteArray()
        require(bytes.size == expectedLength) {
            "WebDAV range response length ${bytes.size} did not match $expectedLength"
        }
        return bytes
    }

    private fun parseContentRange(value: String?): ParsedContentRange {
        val match = requireNotNull(value?.let(CONTENT_RANGE::matchEntire)) {
            "WebDAV response has an invalid Content-Range"
        }
        return ParsedContentRange(
            start = match.groupValues[1].toLong(),
            end = match.groupValues[2].toLong(),
            total = match.groupValues[3].toLong(),
        )
    }

    private data class ParsedContentRange(
        val start: Long,
        val end: Long,
        val total: Long,
    )

    private companion object {
        val CONTENT_RANGE = Regex("bytes (\\d+)-(\\d+)/(\\d+)")
        val EMPTY_CONTENT_RANGE = Regex("bytes \\*/0")
    }
}
