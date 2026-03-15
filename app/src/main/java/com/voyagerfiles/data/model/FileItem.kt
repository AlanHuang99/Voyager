package com.voyagerfiles.data.model

import android.webkit.MimeTypeMap
import java.text.DecimalFormat
import java.util.Date

data class FileItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val lastModified: Date = Date(),
    val isHidden: Boolean = false,
    val permissions: String? = null,
    val owner: String? = null,
    val source: FileSource = FileSource.LOCAL,
) {
    val extension: String
        get() = if (isDirectory) "" else name.substringAfterLast('.', "")

    val mimeType: String
        get() = if (isDirectory) "inode/directory"
        else MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"

    val isImage: Boolean
        get() = mimeType.startsWith("image/")

    val isVideo: Boolean
        get() = mimeType.startsWith("video/")

    val isAudio: Boolean
        get() = mimeType.startsWith("audio/")

    val isText: Boolean
        get() = mimeType.startsWith("text/") || extension in textExtensions

    val isArchive: Boolean
        get() = extension.lowercase() in archiveExtensions

    val isApk: Boolean
        get() = extension.equals("apk", ignoreCase = true)

    val formattedSize: String
        get() = formatFileSize(size)

    companion object {
        private val textExtensions = setOf(
            "json", "xml", "yaml", "yml", "toml", "ini", "cfg", "conf",
            "properties", "md", "rst", "log", "csv", "tsv", "sh", "bash",
            "zsh", "fish", "py", "kt", "java", "js", "ts", "html", "css",
            "sql", "gradle", "kts",
        )

        private val archiveExtensions = setOf(
            "zip", "tar", "gz", "bz2", "xz", "7z", "rar", "zst",
        )

        fun formatFileSize(size: Long): String {
            if (size <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
            val df = DecimalFormat("#,##0.#")
            return "${df.format(size / Math.pow(1024.0, digitGroups.toDouble()))} ${units[digitGroups]}"
        }
    }
}

enum class FileSource {
    LOCAL,
    SFTP,
    FTP,
    SMB,
    WEBDAV,
}

enum class SortBy {
    NAME,
    SIZE,
    DATE,
    TYPE,
}

enum class SortOrder {
    ASCENDING,
    DESCENDING,
}

data class BrowseState(
    val currentPath: String = "/",
    val files: List<FileItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val selectedFiles: Set<String> = emptySet(),
    val sortBy: SortBy = SortBy.NAME,
    val sortOrder: SortOrder = SortOrder.ASCENDING,
    val showHidden: Boolean = false,
    val viewMode: ViewMode = ViewMode.LIST,
    val source: FileSource = FileSource.LOCAL,
)

enum class ViewMode {
    LIST,
    GRID,
}
