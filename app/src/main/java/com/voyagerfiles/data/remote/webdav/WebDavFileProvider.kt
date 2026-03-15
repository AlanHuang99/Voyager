package com.voyagerfiles.data.remote.webdav

import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.data.model.FileSource
import com.voyagerfiles.data.model.RemoteConnection
import com.voyagerfiles.data.repository.FileProvider
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Date

class WebDavFileProvider(private val connection: RemoteConnection) : FileProvider {

    private var sardine: OkHttpSardine? = null

    private fun baseUrl(): String {
        val scheme = if (connection.port == 443) "https" else "http"
        val portPart = if (connection.port == 443 || connection.port == 80) "" else ":${connection.port}"
        return "$scheme://${connection.host}$portPart"
    }

    private fun toUrl(path: String): String {
        val cleanPath = if (path.startsWith("/")) path else "/$path"
        return "${baseUrl()}$cleanPath"
    }

    private fun ensureConnected() {
        if (sardine != null) return
        val s = OkHttpSardine()
        s.setCredentials(connection.username, connection.password)
        sardine = s
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
                        isDirectory = resource.isDirectory,
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
                object : ByteArrayOutputStream() {
                    override fun close() {
                        super.close()
                        sardine?.put(toUrl(path), toByteArray())
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
                    isDirectory = resource.isDirectory,
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
        sardine = null
    }
}
