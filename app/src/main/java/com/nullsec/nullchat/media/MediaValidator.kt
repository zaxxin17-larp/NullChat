package com.nullsec.nullchat.media

/**
 * Cheap, pre-upload validation. Runs before compression (fast reject for absurd
 * files) and again after compression (exact final size).
 */
object MediaValidator {

    fun assertSize(kind: MediaKind, sizeBytes: Long) {
        if (sizeBytes <= 0) throw MediaException("Empty file")
        if (sizeBytes > kind.maxBytes()) {
            throw MediaException(
                "${label(kind)} is ${format(sizeBytes)} — the maximum is ${kind.humanCap()}"
            )
        }
    }

    fun assertDuration(kind: MediaKind, durationMs: Long) {
        val cap = when (kind) {
            MediaKind.GIF -> MediaLimits.MAX_GIF_MILLIS
            MediaKind.VIDEO -> MediaLimits.MAX_VIDEO_MILLIS
            MediaKind.VOICE -> MediaLimits.MAX_VOICE_MILLIS
            MediaKind.IMAGE -> return
        }
        if (durationMs > cap + 500) {
            throw MediaException("${label(kind)} is too long — maximum ${cap / 1000}s")
        }
    }

    private fun label(kind: MediaKind) = when (kind) {
        MediaKind.IMAGE -> "Image"
        MediaKind.GIF -> "GIF"
        MediaKind.VIDEO -> "Video"
        MediaKind.VOICE -> "Voice note"
    }

    private fun format(bytes: Long): String = when {
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        else -> "${bytes / 1024} KB"
    }
}
