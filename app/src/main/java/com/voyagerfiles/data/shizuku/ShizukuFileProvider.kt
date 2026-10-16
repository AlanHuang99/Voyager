package com.voyagerfiles.data.shizuku

import com.voyagerfiles.data.model.FileSource
import com.voyagerfiles.data.repository.RootFileProvider
import com.voyagerfiles.data.repository.RootShell

class ShizukuFileProvider(private val connection: ShizukuConnection) : RootFileProvider(
    RootShell(startProcess = connection::start, requireRoot = false, accessName = "Shizuku", externalInputPipe = true, temporaryDirectory = "/data/local/tmp"),
    FileSource.SHIZUKU,
) {
    override fun close() {
        try { super.close() } finally { connection.close() }
    }
}
