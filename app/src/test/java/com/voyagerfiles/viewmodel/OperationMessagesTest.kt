package com.voyagerfiles.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.FileNotFoundException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class OperationMessagesTest {

    @Test
    fun conflictSuggestsHowToRecover() {
        val error = DestinationConflictException("/target/report.pdf")

        assertEquals(
            "Paste failed: An item named report.pdf already exists in this folder. Rename or remove it, then try again.",
            OperationMessages.failure("Paste", error),
        )
    }

    @Test
    fun permissionFailurePointsToStorageAccess() {
        assertEquals(
            "Delete failed: Permission denied. Grant access to this location and try again.",
            OperationMessages.failure("Delete", SecurityException()),
        )
    }

    @Test
    fun networkFailuresDistinguishAddressAndTimeoutProblems() {
        assertEquals(
            "Connect failed: Server unavailable. Check the address and network connection.",
            OperationMessages.failure("Connect", UnknownHostException()),
        )
        assertEquals(
            "Connect failed: Connection timed out. Check the server and try again.",
            OperationMessages.failure("Connect", SocketTimeoutException()),
        )
    }

    @Test
    fun missingFileSuggestsRefresh() {
        assertEquals(
            "Open failed: The item is no longer available. Refresh the folder and try again.",
            OperationMessages.failure("Open", FileNotFoundException()),
        )
    }

    @Test
    fun partialFailureIncludesCountAndFirstActionableCause() {
        assertEquals(
            "1 of 3 items could not be pasted. An item named photo.jpg already exists in this folder. Rename or remove it, then try again.",
            OperationMessages.partial(
                failed = 1,
                total = 3,
                action = "pasted",
                error = DestinationConflictException("/target/photo.jpg"),
            ),
        )
    }
}
