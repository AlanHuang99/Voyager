package com.voyagerfiles.util

sealed interface FileNameValidationResult {
    data class Valid(val name: String) : FileNameValidationResult

    data class Invalid(val message: String) : FileNameValidationResult
}

object FileNameValidator {
    fun validate(name: String): FileNameValidationResult {
        val trimmed = name.trim()
        return when {
            trimmed.isEmpty() -> FileNameValidationResult.Invalid("Enter a name")
            trimmed == "." || trimmed == ".." -> FileNameValidationResult.Invalid("Choose a different name")
            '/' in trimmed -> FileNameValidationResult.Invalid("Names cannot contain /")
            trimmed.any(Char::isISOControl) -> FileNameValidationResult.Invalid("Names cannot contain control characters")
            else -> FileNameValidationResult.Valid(trimmed)
        }
    }
}
