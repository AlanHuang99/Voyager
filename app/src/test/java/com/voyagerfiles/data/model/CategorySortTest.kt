package com.voyagerfiles.data.model

import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Test

class CategorySortTest {
    private val files = listOf(
        FileItem("z.pdf", "/z/z.pdf", false, 5, Date(20)),
        FileItem("a.txt", "/b/a.txt", false, 2, Date(30)),
        FileItem("m.mp4", "/m/m.mp4", false, 8, Date(10)),
        FileItem("A.txt", "/a/A.txt", false, 2, Date(30)),
    )

    @Test fun sortsEveryFieldInBothDirectionsWithStableTies() {
        val cases = listOf(
            Triple(SortBy.NAME, SortOrder.ASCENDING, listOf("/a/A.txt", "/b/a.txt", "/m/m.mp4", "/z/z.pdf")),
            Triple(SortBy.NAME, SortOrder.DESCENDING, listOf("/z/z.pdf", "/m/m.mp4", "/a/A.txt", "/b/a.txt")),
            Triple(SortBy.SIZE, SortOrder.ASCENDING, listOf("/a/A.txt", "/b/a.txt", "/z/z.pdf", "/m/m.mp4")),
            Triple(SortBy.SIZE, SortOrder.DESCENDING, listOf("/m/m.mp4", "/z/z.pdf", "/a/A.txt", "/b/a.txt")),
            Triple(SortBy.DATE, SortOrder.ASCENDING, listOf("/m/m.mp4", "/z/z.pdf", "/a/A.txt", "/b/a.txt")),
            Triple(SortBy.DATE, SortOrder.DESCENDING, listOf("/a/A.txt", "/b/a.txt", "/z/z.pdf", "/m/m.mp4")),
            Triple(SortBy.TYPE, SortOrder.ASCENDING, listOf("/m/m.mp4", "/z/z.pdf", "/a/A.txt", "/b/a.txt")),
            Triple(SortBy.TYPE, SortOrder.DESCENDING, listOf("/a/A.txt", "/b/a.txt", "/z/z.pdf", "/m/m.mp4")),
        )
        for ((by, order, expected) in cases) {
            assertEquals("$by $order", expected, CategorySort(by, order).applyTo(files).map { it.path })
        }
        assertEquals(listOf("z.pdf", "a.txt", "m.mp4", "A.txt"), files.map { it.name })
    }

    @Test fun duplicateNamesFromDifferentVolumesHaveStablePathOrder() {
        val sameName = listOf(
            FileItem("notes.txt", "/volume-b/notes.txt", false, lastModified = Date(0)),
            FileItem("notes.txt", "/volume-a/notes.txt", false, lastModified = Date(0)),
        )
        for (by in SortBy.entries) for (order in SortOrder.entries) {
            assertEquals(listOf("/volume-a/notes.txt", "/volume-b/notes.txt"),
                CategorySort(by, order).applyTo(sameName).map { it.path })
        }
    }
}
