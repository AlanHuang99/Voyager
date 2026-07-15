package com.voyagerfiles.util

import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.data.model.FileSource

enum class ShareIntentKind {
    SINGLE,
    MULTIPLE,
}

data class ShareIntentPlan(
    val kind: ShareIntentKind,
    val mimeType: String,
) {
    companion object {
        fun forFiles(files: List<FileItem>): ShareIntentPlan? {
            if (files.isEmpty()) return null
            if (files.any { it.isDirectory || it.source !in shareableSources }) return null

            val mimeTypes = files.map(FileItem::mimeType).distinct()
            val topLevelTypes = mimeTypes.map { it.substringBefore('/') }.distinct()
            val mimeType = when {
                mimeTypes.size == 1 -> mimeTypes.single()
                topLevelTypes.size == 1 -> "${topLevelTypes.single()}/*"
                else -> "*/*"
            }
            return ShareIntentPlan(
                kind = if (files.size == 1) ShareIntentKind.SINGLE else ShareIntentKind.MULTIPLE,
                mimeType = mimeType,
            )
        }

        private val shareableSources = setOf(FileSource.LOCAL, FileSource.SAF)
    }
}
