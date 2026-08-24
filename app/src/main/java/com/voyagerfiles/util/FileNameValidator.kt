package com.voyagerfiles.util

import androidx.annotation.StringRes
import com.voyagerfiles.R

sealed interface FileNameValidationResult {
    data class Valid(val name: String) : FileNameValidationResult

    data class Invalid(@param:StringRes val messageRes: Int) : FileNameValidationResult
}

object FileNameValidator {
    fun validate(name: String): FileNameValidationResult {
        val trimmed = name.trim()
        return when {
            trimmed.isEmpty() -> FileNameValidationResult.Invalid(R.string.validation_name_required)
            trimmed == "." || trimmed == ".." -> FileNameValidationResult.Invalid(R.string.validation_name_different)
            '/' in trimmed -> FileNameValidationResult.Invalid(R.string.validation_name_no_slash)
            trimmed.any(Char::isISOControl) -> FileNameValidationResult.Invalid(R.string.validation_name_no_controls)
            else -> FileNameValidationResult.Valid(trimmed)
        }
    }
}
