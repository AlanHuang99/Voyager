package com.voyagerfiles.app

import android.app.Application
import android.content.Intent
import android.content.ClipData
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.voyagerfiles.viewmodel.FileBrowserViewModel
import com.voyagerfiles.viewmodel.TransferOperationController

class VoyagerApp : Application(), ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
    val browserViewModel: FileBrowserViewModel
        get() = ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory.getInstance(this))[FileBrowserViewModel::class.java]
    val transfers by lazy {
        TransferOperationController(startForeground = { readUris ->
            val intent = Intent(this, TransferService::class.java)
            if (readUris.isNotEmpty()) {
                intent.clipData = ClipData.newRawUri("Transfer sources", Uri.parse(readUris.first())).apply {
                    readUris.drop(1).forEach { addItem(ClipData.Item(Uri.parse(it))) }
                }
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            // Android owns these read grants until this service stops, including after task removal.
            ContextCompat.startForegroundService(this, intent)
        })
    }
}
