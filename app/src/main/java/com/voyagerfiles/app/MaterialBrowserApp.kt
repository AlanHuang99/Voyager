package com.voyagerfiles.app

import android.app.Application
import android.content.Intent
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
        TransferOperationController(startForeground = {
            ContextCompat.startForegroundService(this, Intent(this, TransferService::class.java))
        })
    }
}
