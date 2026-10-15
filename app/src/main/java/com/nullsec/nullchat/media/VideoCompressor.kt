package com.nullsec.nullchat.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Trims a video to [MediaLimits.MAX_VIDEO_MILLIS] and re-encodes it to a
 * ~2 Mbps / 720p baseline H.264 + AAC MP4 so it fits the 8 MB cap.
 */
@OptIn(UnstableApi::class)
object VideoCompressor {

    data class Result(
        val file: File,
        val mime: String,
        val sizeBytes: Long,
        val durationMs: Long,
        val thumb: File?
    )

    suspend fun compress(context: Context, uri: Uri): Result {
        val (sourceMs, thumb) = probe(context, uri)
        val limitedMs = sourceMs.coerceAtMost(MediaLimits.MAX_VIDEO_MILLIS)

        val output = File(context.cacheDir, "v_${UUID.randomUUID()}.mp4")

        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(0)
                    .setEndPositionMs(limitedMs)
                    .build()
            )
            .build()

        val edited = EditedMediaItem.Builder(mediaItem)
            .setRemoveAudio(false)
            // Downscale to a 720px tall edge (aspect ratio preserved). In media3
            // 1.4.1 the encoder settings have no setResolution; sizing is done
            // with a Presentation effect instead.
            .setEffects(
                Effects(emptyList(), listOf(Presentation.createForHeight(720)))
            )
            .build()

        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder()
                    .setBitrate(2_000_000)      // ~2 Mbps
                    .build()
            )
            .build()

        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoderFactory)
            .build()

        return suspendCancellableCoroutine { cont ->
            transformer.addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, result: ExportResult) {
                    if (!cont.isActive) return
                    if (output.length() > MediaLimits.MAX_VIDEO_BYTES) {
                        output.delete()
                        cont.resumeWithException(
                            MediaException("Video is still ${output.length() / 1024} KB after compression")
                        )
                    } else {
                        cont.resume(
                            Result(output, "video/mp4", output.length(), limitedMs, thumb)
                        )
                    }
                }

                override fun onError(
                    composition: Composition,
                    result: ExportResult,
                    exception: ExportException
                ) {
                    if (!cont.isActive) return
                    output.delete()
                    cont.resumeWithException(
                        MediaException("Video compression failed: ${exception.message}")
                    )
                }
            })

            transformer.start(edited, output.absolutePath)
            cont.invokeOnCancellation {
                transformer.cancel()
                output.delete()
            }
        }
    }

    /** Reads the source duration and grabs a first-frame thumbnail. */
    private fun probe(context: Context, uri: Uri): Pair<Long, File?> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val duration = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val thumb = retriever.getFrameAtTime(0)?.let { frame ->
                val scale = MediaLimits.THUMB_MAX_DIM.toFloat() / max(frame.width, frame.height)
                val scaled = if (scale < 1f) {
                    Bitmap.createScaledBitmap(
                        frame,
                        max(1, (frame.width * scale).roundToInt()),
                        max(1, (frame.height * scale).roundToInt()),
                        true
                    ).also { if (it !== frame) frame.recycle() }
                } else {
                    frame
                }
                File(context.cacheDir, "vth_${UUID.randomUUID()}.webp").also { file ->
                    FileOutputStream(file).use {
                        scaled.compress(Bitmap.CompressFormat.WEBP, MediaLimits.THUMB_QUALITY, it)
                    }
                    scaled.recycle()
                }
            }
            duration to thumb
        } catch (_: Exception) {
            0L to null
        } finally {
            runCatching { retriever.release() }
        }
    }
}
