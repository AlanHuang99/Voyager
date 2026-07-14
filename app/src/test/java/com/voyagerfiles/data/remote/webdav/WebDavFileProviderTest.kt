package com.voyagerfiles.data.remote.webdav

import com.voyagerfiles.data.model.ConnectionProtocol
import com.voyagerfiles.data.model.RemoteConnection
import com.voyagerfiles.data.repository.FileDownloader
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class WebDavFileProviderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val servers = mutableListOf<LocalWebDavServer>()

    @After
    fun tearDown() {
        servers.forEach { it.stop() }
    }

    @Test
    fun httpsSupportsCustomPorts() {
        val connection = RemoteConnection(
            name = "Secure WebDAV",
            protocol = ConnectionProtocol.WEBDAV,
            host = "files.example.com",
            port = 8443,
            useTls = true,
        )

        assertEquals("https://files.example.com:8443", webDavBaseUrl(connection))
    }

    @Test
    fun httpMustBeSelectedExplicitly() {
        val connection = RemoteConnection(
            name = "Local WebDAV",
            protocol = ConnectionProtocol.WEBDAV,
            host = "192.168.1.5",
            port = 8080,
            useTls = false,
        )

        assertEquals("http://192.168.1.5:8080", webDavBaseUrl(connection))
    }

    @Test
    fun listFilesFromPropfindResponse() = runBlocking {
        val server = startServer()
        Files.write(server.root.resolve("hello.txt"), "hello".toByteArray())
        val provider = createProvider(server.port)

        val files = provider.listFiles("/").getOrThrow()

        assertEquals(listOf("hello.txt"), files.map { it.name })
    }

    @Test
    fun treatsTrailingSlashHrefAsDirectoryWhenResourceTypeIsMissing() = runBlocking {
        val server = startServer(omitCollectionResourceType = true)
        Files.createDirectory(server.root.resolve("folder"))
        val provider = createProvider(server.port)

        val files = provider.listFiles("/").getOrThrow()

        assertEquals(listOf("folder"), files.map { it.name })
        assertTrue(files.single().isDirectory)
    }

    @Test
    fun outputStreamConnectsAndUploadsFile() = runBlocking {
        val server = startServer()
        val provider = createProvider(server.port)

        provider.getOutputStream("/uploaded.txt").getOrThrow().use { stream ->
            stream.write("uploaded".toByteArray())
        }

        assertTrue(Files.exists(server.root.resolve("uploaded.txt")))
        assertEquals("uploaded", String(Files.readAllBytes(server.root.resolve("uploaded.txt"))))
    }

    @Test
    fun fileDownloaderSavesFileToLocalDirectory() = runBlocking {
        val server = startServer()
        Files.write(server.root.resolve("remote.txt"), "webdav download".toByteArray())
        val provider = createProvider(server.port)
        val destination = temp.newFolder("downloads").toPath()
        val item = provider.listFiles("/").getOrThrow().single()

        val result = FileDownloader.download(provider, listOf(item), destination.toFile()).getOrThrow()

        assertEquals(1, result.downloadedFiles)
        assertEquals("webdav download", String(Files.readAllBytes(destination.resolve("remote.txt"))))
    }

    @Test
    fun copyMoveAndDeleteFile() = runBlocking {
        val server = startServer()
        Files.createDirectory(server.root.resolve("target"))
        Files.createDirectory(server.root.resolve("moved"))
        Files.write(server.root.resolve("source.txt"), "source".toByteArray())
        val provider = createProvider(server.port)

        provider.copy("/source.txt", "/target").getOrThrow()
        provider.move("/target/source.txt", "/moved").getOrThrow()
        provider.delete("/source.txt").getOrThrow()

        assertFalse(Files.exists(server.root.resolve("source.txt")))
        assertEquals("source", String(Files.readAllBytes(server.root.resolve("moved/source.txt"))))
    }

    @Test
    fun handlesFileNamesWithSpaces() = runBlocking {
        val server = startServer()
        val provider = createProvider(server.port)

        provider.getOutputStream("/space file.txt").getOrThrow().use { stream ->
            stream.write("space".toByteArray())
        }

        val files = provider.listFiles("/").getOrThrow()

        assertEquals(listOf("space file.txt"), files.map { it.name })
        assertEquals("space", String(Files.readAllBytes(server.root.resolve("space file.txt"))))
    }

    private fun createProvider(port: Int): WebDavFileProvider =
        WebDavFileProvider(
            RemoteConnection(
                name = "Local test WebDAV",
                protocol = ConnectionProtocol.WEBDAV,
                host = "127.0.0.1",
                port = port,
                username = USERNAME,
                password = PASSWORD,
            )
        )

    private fun startServer(
        omitCollectionResourceType: Boolean = false,
    ): LocalWebDavServer {
        val server = LocalWebDavServer(
            root = temp.newFolder("webdav-root-${servers.size}").toPath(),
            omitCollectionResourceType = omitCollectionResourceType,
        )
        server.start()
        servers += server
        return server
    }

    private class LocalWebDavServer(
        val root: Path,
        private val omitCollectionResourceType: Boolean,
    ) {
        private val server = MockWebServer()
        val port: Int get() = server.port

        fun start() {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    runCatching { handle(request) }
                        .getOrElse { error -> textResponse(500, error.message ?: "Server error") }
            }
            server.start(InetAddress.getByName("127.0.0.1"), 0)
        }

        fun stop() {
            server.shutdown()
        }

        private fun handle(request: RecordedRequest): MockResponse =
            when (request.method) {
                "PROPFIND" -> handlePropfind(request)
                "GET" -> handleGet(request)
                "PUT" -> handlePut(request)
                "MKCOL" -> handleMkcol(request)
                "DELETE" -> handleDelete(request)
                "COPY" -> handleCopy(request)
                "MOVE" -> handleMove(request)
                else -> textResponse(405, "Unsupported method")
            }

        private fun handlePropfind(request: RecordedRequest): MockResponse {
            val path = resolve(request.requestUrl!!.encodedPath)
            if (!Files.exists(path)) return textResponse(404, "Not found")

            val responses = buildList {
                add(path)
                if (Files.isDirectory(path)) {
                    Files.list(path).use { stream ->
                        stream.sorted().forEach { add(it) }
                    }
                }
            }

            return xmlResponse(
                207,
                """
                <d:multistatus xmlns:d="DAV:">
                    ${responses.joinToString(separator = "\n") { it.toDavResponse() }}
                </d:multistatus>
                """.trimIndent(),
            )
        }

        private fun handleGet(request: RecordedRequest): MockResponse {
            val path = resolve(request.requestUrl!!.encodedPath)
            if (!Files.isRegularFile(path)) return textResponse(404, "Not found")
            return MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/octet-stream")
                .setBody(String(Files.readAllBytes(path)))
        }

        private fun handlePut(request: RecordedRequest): MockResponse {
            val path = resolve(request.requestUrl!!.encodedPath)
            Files.createDirectories(path.parent)
            Files.write(path, request.body.readByteArray())
            return MockResponse().setResponseCode(201)
        }

        private fun handleMkcol(request: RecordedRequest): MockResponse {
            Files.createDirectory(resolve(request.requestUrl!!.encodedPath))
            return MockResponse().setResponseCode(201)
        }

        private fun handleDelete(request: RecordedRequest): MockResponse {
            val path = resolve(request.requestUrl!!.encodedPath)
            if (!Files.exists(path)) return textResponse(404, "Not found")
            path.deleteRecursively()
            return MockResponse().setResponseCode(204)
        }

        private fun handleCopy(request: RecordedRequest): MockResponse {
            val source = resolve(request.requestUrl!!.encodedPath)
            val destination = resolveDestination(request)
            if (Files.isDirectory(source)) {
                copyDirectory(source, destination)
            } else {
                Files.createDirectories(destination.parent)
                Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING)
            }
            return MockResponse().setResponseCode(201)
        }

        private fun handleMove(request: RecordedRequest): MockResponse {
            val source = resolve(request.requestUrl!!.encodedPath)
            val destination = resolveDestination(request)
            Files.createDirectories(destination.parent)
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING)
            return MockResponse().setResponseCode(201)
        }

        private fun resolveDestination(request: RecordedRequest): Path {
            val destination = request.getHeader("Destination")
                ?: throw IllegalArgumentException("Missing Destination header")
            return resolve(URI(destination).rawPath)
        }

        private fun resolve(rawPath: String): Path {
            val decoded = URLDecoder.decode(rawPath, Charsets.UTF_8.name())
            val relative = decoded.trimStart('/')
            return root.resolve(relative).normalize().also {
                require(it.startsWith(root)) { "Path escapes WebDAV root" }
            }
        }

        private fun Path.toDavResponse(): String {
            val directory = Files.isDirectory(this)
            val href = toHref(directory)
            val modified = DateTimeFormatter.RFC_1123_DATE_TIME.format(
                Instant.ofEpochMilli(Files.getLastModifiedTime(this).toMillis()).atZone(ZoneOffset.UTC)
            )
            val length = if (directory) 0L else Files.size(this)
            val resourceType = if (directory && !omitCollectionResourceType) "<d:collection />" else ""

            return """
                <d:response>
                    <d:href>$href</d:href>
                    <d:propstat>
                        <d:prop>
                            <d:resourcetype>$resourceType</d:resourcetype>
                            <d:getcontentlength>$length</d:getcontentlength>
                            <d:getlastmodified>$modified</d:getlastmodified>
                        </d:prop>
                        <d:status>HTTP/1.1 200 OK</d:status>
                    </d:propstat>
                </d:response>
            """.trimIndent()
        }

        private fun Path.toHref(directory: Boolean): String {
            val relative = root.relativize(this).joinToString("/")
            val encoded = relative.split("/")
                .filter { it.isNotEmpty() }
                .joinToString("/", prefix = "/") { segment ->
                    URLEncoder.encode(segment, Charsets.UTF_8.name()).replace("+", "%20")
                }
            return when {
                relative.isEmpty() -> "/"
                directory -> "$encoded/"
                else -> encoded
            }
        }

        private fun Path.deleteRecursively() {
            if (Files.isDirectory(this)) {
                Files.list(this).use { stream ->
                    stream.forEach { it.deleteRecursively() }
                }
            }
            Files.deleteIfExists(this)
        }

        private fun copyDirectory(source: Path, destination: Path) {
            Files.createDirectories(destination)
            Files.list(source).use { stream ->
                stream.forEach { child ->
                    val target = destination.resolve(child.fileName.toString())
                    if (Files.isDirectory(child)) {
                        copyDirectory(child, target)
                    } else {
                        Files.copy(child, target, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
        }

        private fun xmlResponse(status: Int, body: String): MockResponse =
            MockResponse()
                .setResponseCode(status)
                .setHeader("Content-Type", "application/xml; charset=utf-8")
                .setBody(body)

        private fun textResponse(status: Int, body: String): MockResponse =
            MockResponse()
                .setResponseCode(status)
                .setHeader("Content-Type", "text/plain; charset=utf-8")
                .setBody(body)
    }

    private companion object {
        const val USERNAME = "tester"
        const val PASSWORD = "secret"
    }
}
