package com.voyagerfiles.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

class DirectoryScrollMemoryTest {

    @Test
    fun returningToDirectoryRestoresWhereItWasLeft() {
        val memory = DirectoryScrollMemory()
        memory.update(ScrollPosition(index = 12, offset = 40))
        memory.leave("local", "/a")

        assertEquals(ScrollPosition.TOP, memory.enter("local", "/a/b", returning = false))

        memory.update(ScrollPosition(index = 3, offset = 0))
        memory.leave("local", "/a/b")

        assertEquals(ScrollPosition(index = 12, offset = 40), memory.enter("local", "/a", returning = true))
        assertEquals(ScrollPosition(index = 12, offset = 40), memory.current)
    }

    @Test
    fun enteringDirectoryAfreshStartsAtTopAndDropsItsMemory() {
        val memory = DirectoryScrollMemory()
        memory.update(ScrollPosition(index = 7, offset = 0))
        memory.leave("local", "/a")

        assertEquals(ScrollPosition.TOP, memory.enter("local", "/a", returning = false))
        assertEquals(ScrollPosition.TOP, memory.enter("local", "/a", returning = true))
    }

    @Test
    fun returningWithoutMemoryStartsAtTop() {
        val memory = DirectoryScrollMemory()
        memory.update(ScrollPosition(index = 7, offset = 0))

        assertEquals(ScrollPosition.TOP, memory.enter("local", "/never/visited", returning = true))
        assertEquals(ScrollPosition.TOP, memory.current)
    }

    @Test
    fun positionsAreKeptPerSession() {
        val memory = DirectoryScrollMemory()
        memory.update(ScrollPosition(index = 5, offset = 0))
        memory.leave("nas", "/share")
        memory.update(ScrollPosition(index = 9, offset = 16))
        memory.leave("backup", "/share")

        assertEquals(ScrollPosition(index = 5, offset = 0), memory.enter("nas", "/share", returning = true))
        assertEquals(ScrollPosition(index = 9, offset = 16), memory.enter("backup", "/share", returning = true))
    }

    @Test
    fun forgettingSessionDropsItsDirectories() {
        val memory = DirectoryScrollMemory()
        memory.update(ScrollPosition(index = 5, offset = 0))
        memory.leave("nas", "/share")
        memory.leave("backup", "/share")

        memory.forgetSession("nas")

        assertEquals(ScrollPosition.TOP, memory.enter("nas", "/share", returning = true))
        assertEquals(ScrollPosition(index = 5, offset = 0), memory.enter("backup", "/share", returning = true))
    }
}
