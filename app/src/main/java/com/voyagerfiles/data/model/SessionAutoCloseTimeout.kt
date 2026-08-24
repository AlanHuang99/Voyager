package com.voyagerfiles.data.model

import androidx.annotation.StringRes
import com.voyagerfiles.R

enum class SessionAutoCloseTimeout(
    val minutes: Long,
    @StringRes val labelRes: Int,
) {
    FIVE_MINUTES(5L, R.string.session_timeout_5_minutes),
    FIFTEEN_MINUTES(15L, R.string.session_timeout_15_minutes),
    THIRTY_MINUTES(30L, R.string.session_timeout_30_minutes),
    ONE_HOUR(60L, R.string.session_timeout_1_hour),
    ;

    val durationMillis: Long
        get() = minutes * 60_000L

    companion object {
        fun fromName(name: String?): SessionAutoCloseTimeout =
            entries.firstOrNull { it.name == name } ?: FIFTEEN_MINUTES
    }
}
