package com.nullsec.nullchat.media

/** The four kinds of media the chat supports, and where each is stored. */
enum class MediaKind(val folder: String) {
    IMAGE("image"),
    GIF("gif"),
    VIDEO("video"),
    VOICE("voice")
}

/**
 * Single source of truth for every hard limit. These numbers are mirrored in
 * `firebase/storage.rules` (size caps) and shown to the user in error messages.
 */
object MediaLimits {
    // Hard byte caps
    const val MAX_IMAGE_BYTES = 2L * 1024 * 1024   // 2 MB
    const val MAX_VIDEO_BYTES = 8L * 1024 * 1024   // 8 MB (also GIF)
    const val MAX_VOICE_BYTES = 1L * 1024 * 1024   // 1 MB

    // Compression targets
    const val MAX_IMAGE_DIM = 1080                  // longest side, px
    const val IMAGE_QUALITY = 80                    // WebP/JPEG quality
    const val THUMB_MAX_DIM = 200
    const val THUMB_QUALITY = 70
    const val THUMB_MAX_BYTES = 64L * 1024          // must match storage.rules

    // Duration caps
    const val MAX_GIF_MILLIS = 6_000L
    const val MAX_VIDEO_MILLIS = 30_000L
    const val MAX_VOICE_MILLIS = 60_000L

    // Per-user quotas (enforced in Firestore via UploadLimiter)
    const val DAILY_UPLOAD_LIMIT = 20
    const val USER_QUOTA_BYTES = 100L * 1024 * 1024 // 100 MB
}

/** Size cap for a kind; used both by the validator and Storage rules. */
fun MediaKind.maxBytes(): Long = when (this) {
    MediaKind.IMAGE -> MediaLimits.MAX_IMAGE_BYTES
    MediaKind.GIF, MediaKind.VIDEO -> MediaLimits.MAX_VIDEO_BYTES
    MediaKind.VOICE -> MediaLimits.MAX_VOICE_BYTES
}

fun MediaKind.humanCap(): String = when (this) {
    MediaKind.IMAGE -> "2 MB"
    MediaKind.GIF, MediaKind.VIDEO -> "8 MB"
    MediaKind.VOICE -> "1 MB"
}

/** A user-facing failure. Message is safe to show directly in the UI. */
class MediaException(message: String) : Exception(message)

/** The account has used its whole storage quota. */
class QuotaExceededException(message: String) : Exception(message)

/** The account has hit its per-day upload count. */
class DailyLimitException(message: String) : Exception(message)
