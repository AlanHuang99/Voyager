package com.voyagerfiles.viewmodel

import java.io.InputStream

data class UploadSource(
    val name: String,
    val size: Long? = null,
    val openInputStream: () -> InputStream,
) {
    init {
        require(name.isNotBlank()) { "Upload name must not be blank" }
        require(size == null || size >= 0) { "Upload size must not be negative" }
    }
}
