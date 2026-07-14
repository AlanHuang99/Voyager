package com.voyagerfiles.data.remote.webdav

import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.data.model.FileSource
import com.voyagerfiles.data.model.RemoteConnection
import com.voyagerfiles.data.repository.FileProvider
import com.thegrizzlylabs.sardineandroid.DavResource
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Date

class WebDavFileProvider(
    private val connection: RemoteConnection,
    private val temporaryDirectory: File,
) : FileProvider {

    private var sardine: OkHttpSardine? = null
    private var httpClient: OkHttpClient? = null

    private fun baseUrl(): String {
        return webDavBaseUrl(connection)
    }

    private fun toUrl(path: String): String {
        val cleanPath = if (path.startsWith("/")) path else "/$path"
        return "${baseUrl()}$cleanPath"
    }

    private fun ensureConnected() {
        if (sardine != null) return
        val client = OkHttpClient.Builder()
            .apply {
                if (connection.username.isNotEmpty()) {
                    authenticator { _, response ->
                        val credential = Credentials.basic(connection.username, connection.password)
                        if (response.request.header("Authorization") == credential) {
                            null
                        } else {
                            response.request.newBuilder()
                                .header("Authorization", credential)
                                .build()
                        }
                    }
                }
            }
            .build()
        httpClient = client
        sardine = OkHttpSardine(client)
    }

    override suspend fun listFiles(path: String): Result<List<FileItem>> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureConnected()
                val url = toUrl(path)
                sardine!!.list(url).drop(1).map { resource -> // drop(1) to skip self
                    val name = resource.name.removeSuffix("/")
                    val filePath = if (path.endsWith("/")) "$path$name" else "$path/$name"
                    FileItem(
                        name = name,
                        path = filePath,
                        isDirectory = resource.isDirectoryResource(),
                        size = resource.contentLength,
                        lastModified = resource.modified ?: Date(),
                        isHidden = name.startsWith("."),
                        source = FileSource.WEBDAV,
                    )
                }
            }
        }

    override suspend fun createDirectory(path: String, name: String): Result<FileItem> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureConnected()
                val fullPath = if (path.endsWith("/")) "$path$name" else "$path/$name"
                sardine!!.createDirectory(toUrl(fullPath))
                FileItem(name = name, path = fullPath, isDirectory = true, source = FileSource.WEBDAV)
            }
        }

    override suspend fun createFile(path: String, name: String): Result<FileItem> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureConnected()
                val fullPath = if (path.endsWith("/")) "$path$name" else "$path/$name"
                sardine!!.put(toUrl(fullPath), ByteArray(0))
                FileItem(name = name, path = fullPath, isDirectory = false, source = FileSource.WEBDAV)
            }
        }

    override suspend fun delete(path: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureConnected()
                sardine!!.delete(toUrl(path))
            }
        }

    override suspend fun rename(oldPath: String, newName: String): Result<FileItem> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureConnected()
                val parent = oldPath.substringBeforeLast("/")
                val newPath = "$parent/$newName"
                sardine!!.move(toUrl(oldPath), toUrl(newPath))
                FileItem(name = newName, path = newPath, isDirectory = false, source = FileSource.WEBDAV)
            }
        }

    override suspend fun copy(sourcePath: String, destPath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureConnected()
                val name = sourcePath.substringAfterLast("/")
                sardine!!.copy(toUrl(sourcePath), toUrl("$destPath/$name"))
            }
        }

    override suspend fun move(sourcePath: String, destPath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureConnected()
                val name = sourcePath.substringAfterLast("/")
                sardine!!.move(toUrl(sourcePath), toUrl("$destPath/$name"))
            }
        }

    override suspend fun getInputStream(path: String): Result<InputStream> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureConnected()
                sardine!!.get(toUrl(path))
            }
        }

    override suspend fun getOutputStream(path: String): Result<OutputStream> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureConnected()
                val client = httpClient!!
                check(temporaryDirectory.isDirectory || temporaryDirectory.mkdirs()) {
                    "Could not prepare temporary storage for the upload"
                }
                val temporaryFile = File.createTempFile(
                    "voyager-webdav-",
                    ".upload",
                    temporaryDirectory,
                )
                object : FilterOutputStream(FileOutputStream(temporaryFile)) {
                    private var closed = false

                    override fun close() {
                        if (closed) return
                        closed = true
                        try {
                            super.close()
                            val request = Request.Builder()
                                .url(toUrl(path))
                                .put(temporaryFile.asRequestBody("application/octet-stream".toMediaType()))
                                .build()
                            client.newCall(request).execute().use { response ->
                                if (!response.isSuccessful) {
                                    throw IOException("WebDAV upload failed: HTTP ${response.code}")
                                }
                            }
                        } finally {
                            temporaryFile.delete()
                        }
                    }
                } as OutputStream
            }
        }

    override suspend fun exists(path: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                ensureConnected()
                sardine!!.exists(toUrl(path))
            } catch (_: Exception) {
                false
            }
        }

    override suspend fun getFileInfo(path: String): Result<FileItem> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureConnected()
                val resources = sardine!!.list(toUrl(path))
                val resource = resources.firstOrNull()
                    ?: throw IllegalArgumentException("Not found: $path")
                FileItem(
                    name = resource.name.removeSuffix("/"),
                    path = path,
                    isDirectory = resource.isDirectoryResource(),
                    size = resource.contentLength,
                    lastModified = resource.modified ?: Date(),
                    source = FileSource.WEBDAV,
                )
            }
        }

    override fun getParentPath(path: String): String? {
        if (path == "/" || path.isEmpty()) return null
        val parent = path.removeSuffix("/").substringBeforeLast("/")
        return parent.ifEmpty { "/" }
    }

    override suspend fun disconnect() {
        httpClient?.dispatcher?.cancelAll()
        httpClient?.connectionPool?.evictAll()
        httpClient = null
        sardine = null
    }

    private fun DavResource.isDirectoryResource(): Boolean =
        isDirectory || href?.path?.endsWith("/") == true
}

internal fun webDavBaseUrl(connection: RemoteConnection): String {
    val scheme = if (connection.useTls) "https" else "http"
    val defaultPort = if (connection.useTls) 443 else 80
    val portPart = if (connection.port == defaultPort) "" else ":${connection.port}"
    return "$scheme://${connection.host}$portPart"
}
