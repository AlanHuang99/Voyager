package com.voyagerfiles.data.remote.smb

internal sealed interface SmbBrowsePath {
    data object VirtualRoot : SmbBrowsePath

    data class Share(
        val name: String,
        val relativePath: String,
    ) : SmbBrowsePath

    companion object {
        fun parse(path: String): SmbBrowsePath {
            require(path.startsWith('/')) { "SMB paths must start with /" }
            if (path == "/") return VirtualRoot
            val segments = path.removePrefix("/").split('/')
            require(segments.all { it.isNotEmpty() && it != "." && it != ".." }) { "Invalid SMB path" }
            return Share(
                name = segments.first(),
                relativePath = segments.drop(1).joinToString("\\"),
            )
        }

        fun parentOf(path: String): String? = when (val parsed = parse(path)) {
            VirtualRoot -> null
            is Share -> if (parsed.relativePath.isEmpty()) {
                "/"
            } else {
                path.substringBeforeLast('/').ifEmpty { "/" }
            }
        }
    }
}
