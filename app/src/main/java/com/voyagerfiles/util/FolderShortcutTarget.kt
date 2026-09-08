package com.voyagerfiles.util

import java.io.File

object FolderShortcutTarget {
    fun resolve(path: String, storageRoots: List<File>): File {
        require(path.startsWith('/') && '\u0000' !in path) { "Invalid folder path" }
        val folder = File(path).canonicalFile
        require(storageRoots.any { root ->
            val canonicalRoot = root.canonicalFile
            folder == canonicalRoot || folder.path.startsWith(canonicalRoot.path.trimEnd('/') + "/")
        }) { "Folder is outside shared storage" }
        require(folder.isDirectory && folder.canRead()) { "Folder is missing or unreadable" }
        return folder
    }
}
