package com.voyagerfiles.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectoryLoadGuardTest {

    @Test
    fun onlyLatestRequestForSessionMayCommit() {
        val guard = DirectoryLoadGuard()

        val first = guard.nextRequest("local")
        val second = guard.nextRequest("local")

        assertFalse(guard.isCurrent(first, "local"))
        assertTrue(guard.isCurrent(second, "local"))
    }

    @Test
    fun switchingSessionsInvalidatesPreviousRequest() {
        val guard = DirectoryLoadGuard()

        val local = guard.nextRequest("local")
        val remote = guard.nextRequest("remote")

        assertFalse(guard.isCurrent(local, "local"))
        assertTrue(guard.isCurrent(remote, "remote"))
        assertFalse(guard.isCurrent(remote, "local"))
    }
}
