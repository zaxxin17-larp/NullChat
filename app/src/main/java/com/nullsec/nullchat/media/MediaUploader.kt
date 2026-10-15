package com.nullsec.nullchat.media

import android.net.Uri
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.nullsec.nullchat.util.HashUtils
import java.io.File
import java.util.UUID
import kotlinx.coroutines.tasks.await

/** Everything needed to describe an uploaded (or reused) media asset. */
data class UploadedMedia(
    val mediaId: String,
    val mediaUrl: String,
    val thumbUrl: String?,
    val storagePath: String,
    val thumbPath: String?,
    val sha256: String,
    val sizeBytes: Long,
    val mime: String,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val reused: Boolean = false
)

/**
 * Uploads a prepared (already compressed) media file plus its thumbnail.
 *
 * Order of operations:
 *   1. hash the bytes
 *   2. look for an identical file this user already owns -> reuse it
 *   3. reserve quota + daily slot (UploadLimiter)
 *   4. upload media + thumbnail to Storage
 *   5. write a media-index doc (used for favorites, dedupe and orphan sweeps)
 *
 * On any failure after step 3 the reservation is refunded.
 */
object MediaUploader {

    private val storage: FirebaseStorage get() = FirebaseStorage.getInstance()
    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()

    suspend fun upload(
        uid: String,
        kind: MediaKind,
        file: File,
        mime: String,
        thumb: File?,
        width: Int? = null,
        height: Int? = null,
        durationMs: Long? = null,
        onProgress: (Double) -> Unit = {}
    ): UploadedMedia {
        MediaValidator.assertSize(kind, file.length())
        val hash = HashUtils.sha256(file)

        // --- Dedupe: reuse an identical asset this user already uploaded ---
        val existing = db.collection("user_media_index").document(uid)
            .collection("items")
            .whereEqualTo("sha256", hash)
            .whereEqualTo("kind", kind.name)
            .limit(1)
            .get()
            .await()

        if (!existing.isEmpty) {
            val doc = existing.documents.first()
            doc.reference.update(
                "refCount", FieldValue.increment(1),
                "lastUsedAt", FieldValue.serverTimestamp()
            ).await()
            return UploadedMedia(
                mediaId = doc.id,
                mediaUrl = doc.getString("url") ?: error("index doc missing url"),
                thumbUrl = doc.getString("thumbUrl"),
                storagePath = doc.getString("storagePath") ?: error("index doc missing path"),
                thumbPath = doc.getString("thumbPath"),
                sha256 = hash,
                sizeBytes = doc.getLong("sizeBytes") ?: file.length(),
                mime = doc.getString("mime") ?: mime,
                width = doc.getLong("width")?.toInt(),
                height = doc.getLong("height")?.toInt(),
                durationMs = doc.getLong("durationMs"),
                reused = true
            )
        }

        // --- Reserve quota + daily slot with the exact final size ---
        UploadLimiter.reserve(uid, file.length())

        val mediaId = UUID.randomUUID().toString()
        val storagePath = "chat_media/$uid/${kind.folder}/$mediaId.${extensionFor(mime)}"
        val thumbPath = thumb?.let { "chat_thumbs/$uid/$mediaId.webp" }

        try {
            val mediaUrl = putFile(storagePath, file, mime, mediaId, onProgress)
            val thumbUrl = thumb?.let { putFile(thumbPath!!, it, "image/webp", mediaId) {} }

            db.collection("user_media_index").document(uid)
                .collection("items").document(mediaId)
                .set(
                    mapOf(
                        "uid" to uid,
                        "mediaId" to mediaId,
                        "sha256" to hash,
                        "kind" to kind.name,
                        "storagePath" to storagePath,
                        "thumbPath" to thumbPath,
                        "url" to mediaUrl,
                        "thumbUrl" to thumbUrl,
                        "sizeBytes" to file.length(),
                        "mime" to mime,
                        "width" to width,
                        "height" to height,
                        "durationMs" to durationMs,
                        "refCount" to 1L,
                        "favorite" to false,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "lastUsedAt" to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                )
                .await()

            return UploadedMedia(
                mediaId, mediaUrl, thumbUrl, storagePath, thumbPath,
                hash, file.length(), mime, width, height, durationMs, reused = false
            )
        } catch (e: Exception) {
            UploadLimiter.release(uid, file.length())
            // Best-effort cleanup of a partially uploaded object.
            runCatching { storage.getReference(storagePath).delete().await() }
            if (thumbPath != null) runCatching { storage.getReference(thumbPath).delete().await() }
            throw e
        }
    }

    /** Marks an existing owned asset as a favorite (pinned, never swept). */
    suspend fun setFavorite(uid: String, mediaId: String, favorite: Boolean) {
        db.collection("user_media_index").document(uid)
            .collection("items").document(mediaId)
            .update("favorite", favorite)
            .await()
    }

    /** Reuses a favorite in a new message without re-uploading or re-charging quota. */
    suspend fun touchAndBuildMediaMessage(
        ownerId: String,
        media: UploadedMedia,
        kind: MediaKind,
        chatId: String,
        senderId: String
    ): Map<String, Any?> {
        db.collection("user_media_index").document(ownerId)
            .collection("items").document(media.mediaId)
            .update(
                "refCount", FieldValue.increment(1),
                "lastUsedAt", FieldValue.serverTimestamp()
            )
            .await()

        return mapOf(
            "chatId" to chatId,
            "senderId" to senderId,
            "type" to kind.name,
            "mediaUrl" to media.mediaUrl,
            "thumbUrl" to media.thumbUrl,
            "storagePath" to media.storagePath,
            "thumbPath" to media.thumbPath,
            "mediaId" to media.mediaId,
            "mime" to media.mime,
            "sizeBytes" to media.sizeBytes,
            "width" to media.width,
            "height" to media.height,
            "durationMs" to media.durationMs,
            "sha256" to media.sha256,
            "createdAt" to FieldValue.serverTimestamp()
        )
    }

    private suspend fun putFile(
        path: String,
        file: File,
        mime: String,
        mediaId: String,
        onProgress: (Double) -> Unit
    ): String {
        val ref = storage.reference.child(path)
        val metadata = StorageMetadata.Builder()
            .setContentType(mime)
            .setCustomMetadata("mediaId", mediaId)                 // used by storageSweep
            .setCacheControl("public, max-age=31536000, immutable") // UUID paths -> safe to cache forever
            .build()

        val task = ref.putFile(Uri.fromFile(file), metadata)
        task.addOnProgressListener { snapshot ->
            if (snapshot.totalByteCount > 0) {
                onProgress(snapshot.bytesTransferred.toDouble() / snapshot.totalByteCount)
            }
        }
        task.await()
        return ref.downloadUrl.await().toString()
    }

    private fun extensionFor(mime: String): String = when {
        mime.contains("webp") -> "webp"
        mime.contains("png") -> "png"
        mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
        mime.contains("mp4") -> "mp4"
        mime.contains("webm") -> "webm"
        mime.contains("m4a") -> "m4a"
        mime.contains("mpeg") -> "mp3"
        else -> "bin"
    }
}
