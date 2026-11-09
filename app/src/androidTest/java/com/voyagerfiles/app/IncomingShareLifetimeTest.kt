package com.voyagerfiles.app

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.voyagerfiles.data.model.ConnectionProtocol
import com.voyagerfiles.data.model.RemoteConnection
import com.voyagerfiles.data.remote.saf.SafFileProvider
import com.voyagerfiles.ui.text.resolve
import com.voyagerfiles.viewmodel.OperationOutcome
import com.voyagerfiles.viewmodel.OperationState
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class IncomingShareLifetimeTest {
    private val app get() = ApplicationProvider.getApplicationContext<VoyagerApp>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val browser get() = app.browserViewModel
    private val session = UUID.randomUUID().toString()
    private val sources get() = listOf("first.bin", "second.bin", "empty.bin").map { IncomingShareSourceProvider.uri(session, it) }
    private val control = Uri.parse("content://${IncomingShareControlProvider.AUTHORITY}")
    private lateinit var root: File
    private var receivingActivity: MainActivity? = null

    @Before fun setup() {
        removeTasks()
        control("reset")
        root = File(app.cacheDir, "incoming-share-$session").apply { mkdirs() }
        val provider = app.packageManager.resolveContentProvider(IncomingShareSourceProvider.AUTHORITY, 0)!!
        assertNotEquals("Sender must have a separate UID", Process.myUid(), provider.applicationInfo.uid)
        assertEquals(PackageManager.PERMISSION_DENIED, readPermission(sources[1]))
        assertThrows(SecurityException::class.java) { app.contentResolver.openInputStream(sources[1]) }
    }

    @After fun cleanup() {
        control("release")
        main { browser.cancelOperation() }
        waitUntil { browser.operationState.value == OperationState.Idle }
        main { browser.sessions.value.map { it.id }.forEach(browser::closeSession) }
        waitUntil { browser.sessions.value.isEmpty() }
        removeTasks()
        control("clear")
        root.deleteRecursively()
    }

    @Test fun senderGrantsExpireWithReceivingTaskWhenNoTransferStarts() {
        receiveShare()
        removeTasks()
        waitForGrantRelease()
        assertEquals(0, control("status").getInt("firstOpens"))
    }

    @Test fun localCopyReadsLaterSourcesAfterReceivingTaskRemovalAndReleasesServiceGrants() {
        receiveShare()
        main { browser.openLocalRoot(root.path) }
        waitUntil { browser.browseState.value.currentPath == root.path && !browser.browseState.value.isLoading }
        startAndRemoveTask()
        control("release")
        waitForCompletion(OperationOutcome.COMPLETED)
        sources.forEach { uri -> assertArrayEquals(IncomingShareSourceProvider.payload(uri.lastPathSegment), root.resolve(uri.lastPathSegment!!).readBytes()) }
        waitForGrantRelease()
    }

    @Test fun safCopyKeepsExactBytesAfterTaskRemoval() = runBlocking {
        val endpoint = Uri.parse("content://${TransferDocumentsProvider.AUTHORITY}")
        app.contentResolver.call(endpoint, "reset", null, null)
        val tree = DocumentsContract.buildTreeDocumentUri(TransferDocumentsProvider.AUTHORITY, "root")
        receiveShare()
        main { browser.openSafRoot(tree) }
        val provider = SafFileProvider(app, tree)
        waitUntil { browser.browseState.value.currentPath == provider.rootPath && !browser.browseState.value.isLoading }
        main { browser.navigateTo(DocumentsContract.buildDocumentUriUsingTree(tree, "destination").toString()) }
        waitUntil { browser.browseState.value.currentPath.endsWith("destination") && !browser.browseState.value.isLoading }
        startAndRemoveTask()
        control("release")
        waitForCompletion(OperationOutcome.COMPLETED)
        val files = provider.listFiles(browser.browseState.value.currentPath).getOrThrow()
        assertEquals(3, files.size)
        files.forEach { file -> assertArrayEquals(IncomingShareSourceProvider.payload(file.name), provider.getInputStream(file.path).getOrThrow().use { it.readBytes() }) }
        waitForGrantRelease()
    }

    @Test fun remoteCopyRetainsCapturedDestinationWhenSessionClosesAfterTaskRemoval() {
        val files = ConcurrentHashMap<String, ByteArray>()
        val authorization = Credentials.basic("share", "fixture")
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.getHeader("Authorization") != authorization) return MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", "Basic realm=share")
                    val path = request.requestUrl!!.encodedPath
                    return when (request.method) {
                        "PROPFIND" -> MockResponse().setResponseCode(207).setHeader("Content-Type", "application/xml").setBody(listing(files))
                        "PUT" -> { files[path] = request.body.readByteArray(); MockResponse().setResponseCode(201) }
                        "DELETE" -> { files.remove(path); MockResponse().setResponseCode(204) }
                        "HEAD" -> MockResponse().setResponseCode(if (files.containsKey(path)) 200 else 404)
                        else -> MockResponse().setResponseCode(405)
                    }
                }
            }
            start()
        }
        try {
            receiveShare()
            main { browser.connectToRemote(RemoteConnection(
                id = System.nanoTime(), name = "Incoming share fixture", protocol = ConnectionProtocol.WEBDAV,
                host = "127.0.0.1", port = server.port, useTls = false, username = "share", password = "fixture",
            )) }
            waitUntil { browser.activeSession.value?.source == com.voyagerfiles.data.model.FileSource.WEBDAV && !browser.browseState.value.isLoading }
            startAndRemoveTask()
            main { browser.closeActiveSession() }
            waitUntil { browser.activeSession.value == null }
            control("release")
            try {
                waitForCompletion(OperationOutcome.COMPLETED)
            } catch (error: AssertionError) {
                val requests = List(server.requestCount) { server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS)?.let { "${it.method} ${it.path}" } }
                throw AssertionError("${error.message}\nDAV requests=$requests\n${listing(files)}", error)
            }
            sources.forEach { uri -> assertArrayEquals(IncomingShareSourceProvider.payload(uri.lastPathSegment), files["/${uri.lastPathSegment}"]) }
            waitForGrantRelease()
        } finally { server.shutdown() }
    }

    @Test fun cancellationWhileSenderIsStalledRemovesPartialFileAndReleasesGrants() {
        receiveShare()
        main { browser.openLocalRoot(root.path) }
        waitUntil { browser.browseState.value.currentPath == root.path && !browser.browseState.value.isLoading }
        startAndRemoveTask()
        main { browser.cancelOperation() }
        // The sender remains stalled; cancellation must not depend on it resuming or closing.
        waitForCompletion(OperationOutcome.CANCELLED, 5_000)
        assertTrue(root.listFiles().orEmpty().isEmpty())
        assertEquals(0, control("status").getInt("secondOpens"))
        waitForGrantRelease()
    }

    @Test fun revokedLaterSourceReportsFailureAndPreservesSuccessfulFiles() {
        receiveShare()
        main { browser.openLocalRoot(root.path) }
        waitUntil { browser.browseState.value.currentPath == root.path && !browser.browseState.value.isLoading }
        startAndRemoveTask()
        control("revoke")
        control("release")
        waitForCompletion(OperationOutcome.FAILED)
        assertArrayEquals(IncomingShareSourceProvider.payload("first.bin"), root.resolve("first.bin").readBytes())
        assertFalse(root.resolve("second.bin").exists())
        assertEquals(0, root.resolve("empty.bin").length())
        assertTrue(root.resolve("empty.bin").exists())
        assertEquals(2, browser.lastOperationResult.value!!.progress.completedItems)
        waitForGrantRelease()
    }

    @Test fun revokedGrantBeforeSaveFailsWithoutOpeningOrCreatingFiles() {
        receiveShare()
        main { browser.openLocalRoot(root.path) }
        waitUntil { browser.browseState.value.currentPath == root.path && !browser.browseState.value.isLoading }
        control("revoke")
        main { browser.uploadDocuments(sources) }
        waitForCompletion(OperationOutcome.FAILED)
        assertEquals(0, control("status").getInt("firstOpens"))
        assertTrue(root.listFiles().orEmpty().isEmpty())
        removeTasks()
        waitForGrantRelease()
    }

    private fun receiveShare() {
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        try {
            app.startActivity(Intent().setComponent(ComponentName("${app.packageName}.test", IncomingShareSenderActivity::class.java.name))
                .putExtra("session", session).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            receivingActivity = instrumentation.waitForMonitorWithTimeout(monitor, 10_000) as? MainActivity
            assertNotNull("Sender must launch MainActivity", receivingActivity)
            waitUntil { readPermission(sources[1]) == PackageManager.PERMISSION_GRANTED }
        } finally { instrumentation.removeMonitor(monitor) }
    }

    private fun startAndRemoveTask() {
        main { browser.uploadDocuments(sources) }
        waitUntil { control("status").getBoolean("blocked") }
        assertEquals(0, control("status").getInt("secondOpens"))
        assertTrue(browser.operationState.value is OperationState.Running)
        removeTasks()
        assertTrue(app.getSystemService(ActivityManager::class.java).appTasks.isEmpty())
        assertEquals("Service must retain the unread source grant", PackageManager.PERMISSION_GRANTED, readPermission(sources[1]))
    }

    private fun waitForGrantRelease() {
        try {
            waitUntil { sources.all { uri ->
                try {
                    app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.close()
                    false
                } catch (_: SecurityException) { true }
            } }
        } catch (error: AssertionError) {
            val permissionDump = shell("dumpsys activity permissions").lines()
            val fixtureGrants = permissionDump.indices.filter { permissionDump[it].contains(IncomingShareSourceProvider.AUTHORITY) }.joinToString("\n") { index ->
                permissionDump.drop(index).takeWhile { line -> line == permissionDump[index] || !line.contains("UriPermission{") }
                    .filter { line -> line.contains(IncomingShareSourceProvider.AUTHORITY) || line.contains("targetPkg=${app.packageName}") || line.trim().startsWith("mode=") || line.contains("Owners:") || line.contains("StartItem{") || line.contains("ActivityRecord{") }
                    .joinToString("\n")
            }
            throw AssertionError("Source access still allowed; checkUriPermission=${sources.map(::readPermission)}\n$fixtureGrants\n${shell("dumpsys activity services ${app.packageName}")}", error)
        }
    }
    private fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
        .bufferedReader().use { it.readText() }
    private fun readPermission(uri: Uri) = app.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION)
    private fun removeTasks() {
        main { app.getSystemService(ActivityManager::class.java).appTasks.forEach { it.finishAndRemoveTask() } }
        waitUntil {
            var removed = false
            main { removed = app.getSystemService(ActivityManager::class.java).appTasks.isEmpty() && receivingActivity?.isDestroyed != false }
            removed
        }
    }
    private fun control(method: String) = app.contentResolver.call(control, method, session, null)!!
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun waitForCompletion(outcome: OperationOutcome, timeout: Long = 15_000) {
        waitUntil(timeout) { browser.operationState.value == OperationState.Idle && browser.lastOperationResult.value != null }
        assertEquals(browser.snackbarMessage.value?.resolve(app.resources), outcome, browser.lastOperationResult.value!!.outcome)
    }
    private fun waitUntil(timeout: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeout * 1_000_000
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue("Timed out waiting for incoming share", condition())
    }

    private fun listing(files: Map<String, ByteArray>): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <D:multistatus xmlns:D="DAV:">
          <D:response><D:href>/</D:href><D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
          ${files.entries.joinToString("") { (path, bytes) -> "<D:response><D:href>$path</D:href><D:propstat><D:prop><D:resourcetype/><D:getcontentlength>${bytes.size}</D:getcontentlength></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>" }}
        </D:multistatus>
    """.trimIndent()
}
