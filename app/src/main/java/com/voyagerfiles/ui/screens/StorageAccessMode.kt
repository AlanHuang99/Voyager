package com.voyagerfiles.ui.screens

enum class StorageAccessMode {
    FULL,
    LIMITED,
    NEEDS_DECISION,
}

fun storageAccessMode(
    hasAllFilesAccess: Boolean,
    limitedAccessAccepted: Boolean,
): StorageAccessMode = when {
    hasAllFilesAccess -> StorageAccessMode.FULL
    limitedAccessAccepted -> StorageAccessMode.LIMITED
    else -> StorageAccessMode.NEEDS_DECISION
}
