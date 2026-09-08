package com.voyagerfiles.viewmodel

import com.voyagerfiles.data.repository.RootTextDocument

data class RootTextEditorState(
    val path: String,
    val document: RootTextDocument? = null,
    val text: String = "",
    val busy: Boolean = true,
    val error: String? = null,
) {
    val changed: Boolean get() = document != null && document.text != text
}
