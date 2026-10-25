package com.moon.eatandsport

import java.util.UUID

data class Attachment(val id: String, val fileName: String, val mime: String, val displayName: String, val size: Long) {
    init {
        require(id.isNotBlank() && id.length <= 128)
        require(validFileName(fileName))
        require(mime in extensions)
        require(fileName.endsWith("." + extensions.getValue(mime)))
        require(displayName.isNotBlank() && displayName.length <= 255)
        require(size in 1..MAX_FILE_SIZE)
    }
    companion object {
        const val MAX_FILE_SIZE = 500L * 1024 * 1024
        val extensions = mapOf(
            "image/jpeg" to "jpg", "image/png" to "png", "image/webp" to "webp",
            "image/gif" to "gif", "image/heic" to "heic", "image/heif" to "heif",
            "video/mp4" to "mp4", "video/webm" to "webm", "video/3gpp" to "3gp",
            "video/quicktime" to "mov", "video/x-matroska" to "mkv",
        )
        fun validFileName(name: String) = Regex("[a-fA-F0-9-]{36}\\.(jpg|png|webp|gif|heic|heif|mp4|webm|3gp|mov|mkv)").matches(name) &&
            runCatching { UUID.fromString(name.substringBefore('.')) }.isSuccess
        fun newFileName(mime: String) = UUID.randomUUID().toString() + "." + extensions.getValue(mime)
    }
}
