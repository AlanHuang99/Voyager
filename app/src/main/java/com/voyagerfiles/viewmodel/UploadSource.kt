package com.voyagerfiles.viewmodel

import java.io.InputStream

data class UploadSource(
    val name: String,
    val openInputStream: () -> InputStream,
)
