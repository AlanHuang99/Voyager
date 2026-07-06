package com.voyagerfiles.viewmodel

internal object BrowserNavigationBounds {

    fun normalizePath(path: String): String {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return "/"
        if (trimmed.contains("://")) return trimmed.removeSuffix("/")

        val withLeadingSlash = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
        val collapsed = withLeadingSlash.replace(Regex("/{2,}"), "/")
        return collapsed.removeSuffix("/").ifEmpty { "/" }
    }

    fun canNavigateToParent(
        currentPath: String,
        parentPath: String?,
        sessionRootPath: String?,
    ): Boolean {
        if (parentPath == null) return false
        if (sessionRootPath == null) return true
        if (isAtSessionRoot(currentPath, sessionRootPath)) return false
        return isPathAtOrInsideRoot(parentPath, sessionRootPath)
    }

    fun isAtSessionRoot(currentPath: String, sessionRootPath: String): Boolean =
        normalizePath(currentPath) == normalizePath(sessionRootPath)

    fun isPathAtOrInsideRoot(path: String, rootPath: String): Boolean {
        val normalizedPath = normalizePath(path)
        val normalizedRoot = normalizePath(rootPath)
        if (normalizedRoot == "/") return true
        return normalizedPath == normalizedRoot ||
            normalizedPath.startsWith("$normalizedRoot/")
    }
}
