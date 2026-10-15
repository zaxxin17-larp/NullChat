package com.nullsec.nullchat.media

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID

/**
 * Routes a picked Uri (or a freshly recorded voice file) through the correct
 * compressor and returns a ready-to-upload, already-validated asset.
 */
object MediaPreparer {

    data class Prepared(
        val file: File,
        val mime: String,
        val kind: MediaKind,
        val width: Int? = null,
        val height: Int? = null,
        val durationMs: Long? = null,
        val thumb: File? = null
    )

    /** Guesses the media kind from the content resolver's MIME type. */
    fun detectKind(context: Context, uri: Uri): MediaKind? {
        val mime = context.contentResolver.getType(uri) ?: return null
        return when {
            mime == "image/gif" -> MediaKind.GIF
            mime.startsWith("image/") -> MediaKind.IMAGE
            mime.startsWith("video/") -> MediaKind.VIDEO
            mime.startsWith("audio/") -> MediaKind.VOICE
            else -> null
        }
    }

    suspend fun prepare(context: Context, uri: Uri, kind: MediaKind): Prepared = when (kind) {
        MediaKind.IMAGE -> {
            val r = ImageCompressor.compress(context, uri)
            MediaValidator.assertSize(MediaKind.IMAGE, r.sizeBytes)
            Prepared(r.file, r.mime, MediaKind.IMAGE, r.width, r.height, null, r.thumb)
        }

        MediaKind.GIF -> {
            val source = copyToCache(context, uri, "gif_src")
            val r = GifTranscoder.toMp4(context, source)
            source.delete()
            MediaValidator.assertSize(MediaKind.GIF, r.sizeBytes)
            Prepared(r.file, r.mime, MediaKind.GIF, null, null, r.durationMs, r.thumb)
        }

        MediaKind.VIDEO -> {
            val r = VideoCompressor.compress(context, uri)
            MediaValidator.assertDuration(MediaKind.VIDEO, r.durationMs)
            MediaValidator.assertSize(MediaKind.VIDEO, r.sizeBytes)
            Prepared(r.file, r.mime, MediaKind.VIDEO, null, null, r.durationMs, r.thumb)
        }

        MediaKind.VOICE -> {
            // Voice comes from VoiceRecorder as an already-compressed cache file.
            val source = copyToCache(context, uri, "voice_src", "m4a")
            MediaValidator.assertSize(MediaKind.VOICE, source.length())
            Prepared(source, "audio/mp4", MediaKind.VOICE, null, null, null, null)
        }
    }

    private fun copyToCache(
        context: Context,
        uri: Uri,
        prefix: String,
        extension: String = "bin"
    ): File {
        val out = File(context.cacheDir, "${prefix}_${UUID.randomUUID()}.$extension")
        context.contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        } ?: throw MediaException("Could not open the selected file")
        return out
    }

    /** Human name of a picked Uri (for error messages). */
    fun displayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
}
