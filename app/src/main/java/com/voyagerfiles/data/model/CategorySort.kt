package com.voyagerfiles.data.model

data class CategorySort(
    val by: SortBy = SortBy.NAME,
    val order: SortOrder = SortOrder.ASCENDING,
) {
    fun applyTo(files: List<FileItem>): List<FileItem> {
        val primary = when (by) {
            SortBy.NAME -> compareBy<FileItem, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
            SortBy.SIZE -> compareBy { it.size }
            SortBy.DATE -> compareBy { it.lastModified }
            SortBy.TYPE -> compareBy<FileItem, String>(String.CASE_INSENSITIVE_ORDER) { it.extension }
        }
        val directed = if (order == SortOrder.DESCENDING) primary.reversed() else primary
        return files.sortedWith(directed
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            .thenBy { it.name }
            .thenBy { it.path })
    }
}
