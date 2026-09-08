package com.voyagerfiles.data.duplicates

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class DuplicateRemovalResult(val removed: Set<String>, val failures: Map<String, String>)

suspend fun removeVerifiedDuplicates(
    groups: List<DuplicateGroup>,
    selected: Set<String>,
    scanner: DuplicateScanner = DuplicateScanner(),
    remove: suspend (String) -> Unit,
): DuplicateRemovalResult {
    require(selected.isNotEmpty()) { "Select duplicate files to remove." }
    require(selected.all { path -> groups.any { group -> group.files.any { it.path == path } } }) {
        "The selection no longer matches the scan. Scan again."
    }
    require(groups.none { group -> group.files.all { it.path in selected } }) { "Keep at least one copy of each file." }
    val removed = mutableSetOf<String>()
    val failures = mutableMapOf<String, String>()
    for (group in groups) {
        for (file in group.files.filter { it.path in selected }) {
            currentCoroutineContext().ensureActive()
            try {
                scanner.verifyRemoval(group, selected, file)
                remove(file.path)
                removed += file.path
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failures[file.path] = error.message ?: "File could not be removed."
            }
        }
    }
    return DuplicateRemovalResult(removed, failures)
}
