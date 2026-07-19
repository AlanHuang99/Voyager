package com.voyagerfiles.data.model

enum class SessionAutoCloseTimeout(
    val minutes: Long,
    val label: String,
) {
    FIVE_MINUTES(5L, "5 minutes"),
    FIFTEEN_MINUTES(15L, "15 minutes"),
    THIRTY_MINUTES(30L, "30 minutes"),
    ONE_HOUR(60L, "1 hour"),
    ;

    val durationMillis: Long
        get() = minutes * 60_000L

    companion object {
        fun fromName(name: String?): SessionAutoCloseTimeout =
            entries.firstOrNull { it.name == name } ?: FIFTEEN_MINUTES
    }
}
