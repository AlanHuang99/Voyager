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

    /** Whether [candidate] is [path] itself or lies above it, following [parentOf] up from [path]. */
    fun isSameOrAncestor(candidate: String, path: String, parentOf: (String) -> String?): Boolean {
        val target = normalizePath(candidate)
        var current: String? = path
        val visited = mutableSetOf<String>()
        while (current != null && visited.add(current)) {
            if (normalizePath(current) == target) return true
            val parent = parentOf(current)
            current = if (parent == current) null else parent
        }
        return false
    }
}
