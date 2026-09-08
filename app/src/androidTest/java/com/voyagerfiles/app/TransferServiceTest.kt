package com.voyagerfiles.app

import android.Manifest
import android.app.NotificationManager
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.rule.GrantPermissionRule
import com.voyagerfiles.data.repository.FileProvider
import com.voyagerfiles.data.repository.LocalFileProvider
import com.voyagerfiles.ui.text.UiText
import com.voyagerfiles.viewmodel.FileOperationCoordinator
import com.voyagerfiles.viewmodel.OperationState
import com.voyagerfiles.viewmodel.TransferProgress
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TransferServiceTest {
    @get:Rule
    val notifications: GrantPermissionRule = GrantPermissionRule.grant(
        *if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray(),
    )

    private val app get() = ApplicationProvider.getApplicationContext<VoyagerApp>()

    @Test
    fun recreationAndBackgroundKeepTransferAndNotificationAlive() {
        val root = File(app.cacheDir, "transfer-${UUID.randomUUID()}").apply { mkdirs() }
        val source = root.resolve("source.bin")
        val bytes = ByteArray(4 * 1024 * 1024) { (it % 251).toByte() }
        source.writeBytes(bytes)
        val destination = root.resolve("destination").apply { mkdir() }
        val viewModel = app.browserViewModel
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity {
                    launchCopy(source, destination, move = false)
                }
                waitUntil { notifications().isNotEmpty() }
                scenario.recreate()
                scenario.onActivity { assertSame(viewModel, app.browserViewModel) }
                scenario.moveToState(Lifecycle.State.CREATED)
                assertTrue(app.transfers.state.value is OperationState.Running)
                waitUntil { app.transfers.state.value == OperationState.Idle }
                assertArrayEquals(bytes, destination.resolve(source.name).readBytes())
                assertArrayEquals(bytes, source.readBytes())
                waitUntil { notifications().isEmpty() }
            }
        } finally {
            app.transfers.cancel()
            waitUntil { app.transfers.state.value == OperationState.Idle }
            root.deleteRecursively()
        }
    }

    @Test
    fun notificationCancelRemovesPartialMoveAndPreservesSource() {
        val root = File(app.cacheDir, "cancel-${UUID.randomUUID()}").apply { mkdirs() }
        val source = root.resolve("source.bin")
        val bytes = ByteArray(4 * 1024 * 1024) { (it % 251).toByte() }
        source.writeBytes(bytes)
        val destination = root.resolve("destination").apply { mkdir() }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { launchCopy(source, destination, move = true) }
                waitUntil { destination.resolve(source.name).length() > 0 && notifications().isNotEmpty() }
                val notification = notifications().single().notification
                notification.actions.single().actionIntent.send()
                waitUntil { app.transfers.state.value == OperationState.Idle }
                assertFalse(destination.resolve(source.name).exists())
                assertArrayEquals(bytes, source.readBytes())
                waitUntil { notifications().isEmpty() }
            }
        } finally {
            app.transfers.cancel()
            waitUntil { app.transfers.state.value == OperationState.Idle }
            root.deleteRecursively()
        }
    }

    private fun launchCopy(source: File, destination: File, move: Boolean) {
        val label = UiText.Dynamic("Test transfer")
        app.transfers.launch(label, {}, {}) {
            val progress: (com.voyagerfiles.data.repository.StreamTransferProgress) -> Unit = {
                app.transfers.update(TransferProgress(label, copiedBytes = it.bytesTransferred, totalBytes = it.totalBytes))
            }
            if (move) {
                FileOperationCoordinator.movePath(SlowProvider(), LocalFileProvider(), source.path, destination.path, progress).getOrThrow()
            } else {
                FileOperationCoordinator.copyPath(SlowProvider(), LocalFileProvider(), source.path, destination.path, progress).getOrThrow()
            }
        }
    }

    private fun notifications() = app.getSystemService(NotificationManager::class.java)
        .activeNotifications.filter { it.notification.channelId == "file_transfers" }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue("Timed out waiting for transfer", condition())
    }

    private class SlowProvider(private val delegate: LocalFileProvider = LocalFileProvider()) : FileProvider by delegate {
        override suspend fun getInputStream(path: String): Result<InputStream> = delegate.getInputStream(path).map { input ->
            object : FilterInputStream(input) {
                override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
                    Thread.sleep(60)
                    return super.read(buffer, offset, count)
                }
            }
        }
    }
}
