package com.nullsec.nullchat.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Transcodes an animated GIF into a short, muted, looping MP4. MP4/H.264 is
 * typically 10-50x smaller than the source GIF and autoplays everywhere.
 *
 * Length is capped at [MediaLimits.MAX_GIF_MILLIS]. The result is muted (`-an`)
 * and the player is expected to loop it.
 *
 * NOTE: ffmpeg-kit was retired in April 2025 — swap the dependency for a
 * maintained fork, or do this server-side, if you cannot ship the native lib.
 */
object GifTranscoder {

    data class Result(
        val file: File,
        val mime: String,
        val sizeBytes: Long,
        val durationMs: Long,
        val thumb: File?
    )

    suspend fun toMp4(context: Context, source: File): Result =
        suspendCancellableCoroutine { cont ->
            val output = File(context.cacheDir, "gif_${UUID.randomUUID()}.mp4")
            val seconds = MediaLimits.MAX_GIF_MILLIS / 1000.0

            // -t caps the length; fps/even-dimensions keep H.264 happy; crf 30 is
            // plenty for short loops; faststart moves the moov atom to the front.
            val command = buildString {
                append("-y -i '${source.absolutePath}' ")
                append("-t $seconds ")
                append("-vf scale=trunc(iw/2)*2:trunc(ih/2)*2,fps=15 ")
                append("-an ")
                append("-c:v libx264 -profile:v baseline -level 3.1 ")
                append("-crf 30 -preset veryfast -pix_fmt yuv420p ")
                append("-movflags +faststart ")
                append("'${output.absolutePath}'")
            }

            val session = FFmpegKit.executeAsync(command) { completed ->
                if (!cont.isActive) return@executeAsync
                val code = completed.returnCode
                if (ReturnCode.isSuccess(code) && output.exists()) {
                    if (output.length() > MediaLimits.MAX_VIDEO_BYTES) {
                        output.delete()
                        cont.resumeWithException(
                            MediaException("GIF is still ${output.length() / 1024} KB after conversion")
                        )
                    } else {
                        cont.resume(
                            Result(
                                file = output,
                                mime = "video/mp4",
                                sizeBytes = output.length(),
                                durationMs = MediaLimits.MAX_GIF_MILLIS,
                                thumb = firstFrameThumb(context, source)
                            )
                        )
                    }
                } else {
                    output.delete()
                    cont.resumeWithException(
                        MediaException("GIF conversion failed ($code)")
                    )
                }
            }

            cont.invokeOnCancellation {
                FFmpegKit.cancel(session.sessionId)
                output.delete()
            }
        }

    /** Best-effort first-frame thumbnail (BitmapFactory reads the first GIF frame). */
    private fun firstFrameThumb(context: Context, source: File): File? = try {
        val frame = BitmapFactory.decodeFile(source.absolutePath) ?: return null
        val scale = MediaLimits.THUMB_MAX_DIM.toFloat() / max(frame.width, frame.height)
        val thumb = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                frame,
                max(1, (frame.width * scale).roundToInt()),
                max(1, (frame.height * scale).roundToInt()),
                true
            ).also { if (it !== frame) frame.recycle() }
        } else {
            frame
        }
        val file = File(context.cacheDir, "gth_${UUID.randomUUID()}.webp")
        FileOutputStream(file).use {
            thumb.compress(Bitmap.CompressFormat.WEBP, MediaLimits.THUMB_QUALITY, it)
        }
        thumb.recycle()
        file
    } catch (_: Exception) {
        null
    }
}
