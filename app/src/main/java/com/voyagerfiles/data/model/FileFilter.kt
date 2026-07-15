package com.voyagerfiles.data.model

enum class FileTypeFilter(val label: String) {
    ALL("All"),
    FOLDERS("Folders"),
    IMAGES("Images"),
    VIDEOS("Videos"),
    AUDIO("Audio"),
    DOCUMENTS("Documents"),
    ARCHIVES("Archives"),
    APPS("Apps"),
    ;

    fun matches(file: FileItem): Boolean = when (this) {
        ALL -> true
        FOLDERS -> file.isDirectory
        IMAGES -> !file.isDirectory && file.isImage
        VIDEOS -> !file.isDirectory && file.isVideo
        AUDIO -> !file.isDirectory && file.isAudio
        DOCUMENTS -> !file.isDirectory && (file.isText || file.extension.lowercase() in documentExtensions)
        ARCHIVES -> !file.isDirectory && file.isArchive
        APPS -> !file.isDirectory && file.isApk
    }

    companion object {
        private val documentExtensions = setOf(
            "pdf", "doc", "docx", "odt", "rtf", "xls", "xlsx", "ods", "ppt", "pptx", "odp", "epub",
        )
    }
}
