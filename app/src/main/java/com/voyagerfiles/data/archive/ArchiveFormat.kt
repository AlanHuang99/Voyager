package com.voyagerfiles.data.archive

enum class ArchiveFormat(
    private val suffixes: List<String>,
    val canExtract: Boolean = true,
) {
    ZIP(listOf(".zip")),
    TAR(listOf(".tar")),
    TAR_GZIP(listOf(".tar.gz", ".tgz")),
    TAR_BZIP2(listOf(".tar.bz2", ".tbz2")),
    GZIP(listOf(".gz")),
    BZIP2(listOf(".bz2")),
    RAR_UNSUPPORTED(listOf(".rar"), canExtract = false),
    ;

    val isRecognizedArchive: Boolean
        get() = true

    fun stem(fileName: String): String {
        val matchedSuffix = matchingSuffix(fileName)
        val stem = matchedSuffix
            ?.let { fileName.dropLast(it.length) }
            ?.trim()
            .orEmpty()
        return stem.ifBlank { "archive" }
    }

    private fun matchingSuffix(fileName: String): String? =
        suffixes.firstOrNull { suffix -> fileName.endsWith(suffix, ignoreCase = true) }

    companion object {
        private val detectionOrder = entries.sortedByDescending { format ->
            format.suffixes.maxOf(String::length)
        }

        fun detect(fileName: String): ArchiveFormat? =
            detectionOrder.firstOrNull { it.matchingSuffix(fileName) != null }
    }
}
