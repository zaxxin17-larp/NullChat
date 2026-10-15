package com.nullsec.nullchat.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId

enum class MessageType { TEXT, IMAGE, GIF, VIDEO, VOICE }

/**
 * A chat message. Media messages store ONLY the download URLs + metadata —
 * never the bytes. `storagePath`/`thumbPath` are kept so the cleanup trigger can
 * find and delete the objects when the message is removed.
 */
data class Message(
    @DocumentId val id: String = "",
    val chatId: String = "",
    val senderId: String = "",
    val type: MessageType = MessageType.TEXT,
    val text: String? = null,

    // Media pointers + metadata
    val mediaUrl: String? = null,
    val thumbUrl: String? = null,
    val storagePath: String? = null,
    val thumbPath: String? = null,
    val mediaId: String? = null,
    val mime: String? = null,
    val sizeBytes: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val sha256: String? = null,

    val createdAt: Timestamp? = null,
    val expireAt: Timestamp? = null,    // TTL field (see README)

    // Moderation / UX
    val reported: Boolean = false,
    val hidden: Boolean = false
)

/** Compact record used for dedupe, favorites and orphan sweeping. */
data class MediaIndexItem(
    @DocumentId val id: String = "",
    val uid: String = "",
    val mediaId: String = "",
    val sha256: String = "",
    val kind: String = "",
    val storagePath: String = "",
    val thumbPath: String? = null,
    val url: String = "",
    val thumbUrl: String? = null,
    val sizeBytes: Long = 0,
    val mime: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val refCount: Long = 0,
    val favorite: Boolean = false,
    val createdAt: Timestamp? = null,
    val lastUsedAt: Timestamp? = null
)
