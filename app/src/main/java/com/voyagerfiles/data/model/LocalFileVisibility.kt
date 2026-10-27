package com.voyagerfiles.data.model

object LocalFileVisibility {
    fun targetName(file: FileItem): String? {
        if (file.source != FileSource.LOCAL || file.name.isBlank()) return null
        val target = if (file.name.startsWith('.')) file.name.trimStart('.') else "." + file.name
        return target.takeUnless {
            it.isBlank() || it == "." || it == ".." || '/' in it || it.any(Char::isISOControl)
        }
    }
}
