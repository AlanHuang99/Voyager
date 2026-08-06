package com.voyagerfiles.viewmodel

import kotlin.math.roundToInt

data class TransferProgress(
    val label: String,
    val completedItems: Int = 0,
    val totalItems: Int? = null,
    val currentItemName: String? = null,
    val copiedBytes: Long = 0,
    val totalBytes: Long? = null,
) {
    init {
        require(label.isNotBlank()) { "Progress label must not be blank" }
        require(completedItems >= 0) { "Completed item count must not be negative" }
        require(totalItems == null || totalItems >= 0) { "Total item count must not be negative" }
        require(copiedBytes >= 0) { "Copied byte count must not be negative" }
        require(totalBytes == null || totalBytes >= 0) { "Total byte count must not be negative" }
    }

    val fraction: Float?
        get() = when {
            totalBytes != null && totalBytes > 0 -> {
                (copiedBytes.toDouble() / totalBytes.toDouble())
                    .coerceIn(0.0, 1.0)
                    .toFloat()
            }

            totalItems != null && totalItems > 0 -> {
                (completedItems.toDouble() / totalItems.toDouble())
                    .coerceIn(0.0, 1.0)
                    .toFloat()
            }

            else -> null
        }

    val itemProgressText: String?
        get() = totalItems?.takeIf { it > 0 }?.let { "$completedItems of $it" }

    val percentageText: String?
        get() = fraction?.let { "${(it * 100).roundToInt()}%" }

    val detailText: String?
        get() = buildList {
            currentItemName?.takeIf { it.isNotBlank() }?.let(::add)
            itemProgressText?.let(::add)
            percentageText?.let(::add)
        }.takeIf { it.isNotEmpty() }?.joinToString(" • ")

    val stateDescription: String
        get() = buildList {
            add(label)
            currentItemName?.takeIf { it.isNotBlank() }?.let(::add)
            itemProgressText?.let(::add)
            percentageText?.let(::add)
        }.joinToString(", ")
}
