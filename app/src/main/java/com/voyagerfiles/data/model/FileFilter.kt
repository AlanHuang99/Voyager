package com.voyagerfiles.data.model

import androidx.annotation.StringRes
import com.voyagerfiles.R

enum class FileTypeFilter(@StringRes val labelRes: Int) {
    ALL(R.string.filter_all),
    FOLDERS(R.string.filter_folders),
    IMAGES(R.string.filter_images),
    VIDEOS(R.string.filter_videos),
    AUDIO(R.string.filter_audio),
    DOCUMENTS(R.string.filter_documents),
    ARCHIVES(R.string.filter_archives),
    APPS(R.string.filter_apps),
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
