package com.nullsec.nullchat.media

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Resizes an image so its longest side is [MediaLimits.MAX_IMAGE_DIM] and encodes
 * it as WebP at [MediaLimits.IMAGE_QUALITY]. Produces a small thumbnail too.
 *
 * Runs on a background dispatcher (caller's responsibility, e.g. Dispatchers.IO).
 */
object ImageCompressor {

    data class Result(
        val file: File,
        val mime: String,
        val width: Int,
        val height: Int,
        val sizeBytes: Long,
        val thumb: File
    )

    fun compress(context: Context, uri: Uri): Result {
        val cr = context.contentResolver

        // 1. Bounds only -> compute an inSampleSize so we never hold a huge bitmap.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw MediaException("That image could not be read")
        }

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MediaLimits.MAX_IMAGE_DIM)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bitmap = cr.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw MediaException("That image could not be decoded")

        // 2. Exact fit to the longest side.
        val scale = MediaLimits.MAX_IMAGE_DIM.toFloat() / max(bitmap.width, bitmap.height)
        if (scale < 1f) {
            val w = max(1, (bitmap.width * scale).roundToInt())
            val h = max(1, (bitmap.height * scale).roundToInt())
            val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
            if (scaled !== bitmap) bitmap.recycle()
            bitmap = scaled
        }

        // 3. Honour EXIF orientation.
        bitmap = applyExifRotation(cr, uri, bitmap)

        // 4. Encode, backing off quality until we fit the 2 MB cap.
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }

        var quality = MediaLimits.IMAGE_QUALITY
        var out = writeWebp(context, bitmap, format, quality)
        while (out.length() > MediaLimits.MAX_IMAGE_BYTES && quality > 40) {
            quality -= 10
            out.delete()
            out = writeWebp(context, bitmap, format, quality)
        }

        // 5. Thumbnail (guaranteed < THUMB_MAX_BYTES by the encoder settings).
        val thumb = makeThumb(context, bitmap)

        val width = bitmap.width
        val height = bitmap.height
        bitmap.recycle()

        if (out.length() > MediaLimits.MAX_IMAGE_BYTES) {
            out.delete()
            throw MediaException("Image is still ${out.length() / 1024} KB after compression")
        }

        return Result(out, "image/webp", width, height, out.length(), thumb)
    }

    private fun writeWebp(
        context: Context,
        bitmap: Bitmap,
        format: Bitmap.CompressFormat,
        quality: Int
    ): File {
        val file = File(context.cacheDir, "img_${UUID.randomUUID()}.webp")
        FileOutputStream(file).use { bitmap.compress(format, quality, it) }
        return file
    }

    private fun makeThumb(context: Context, source: Bitmap): File {
        val scale = MediaLimits.THUMB_MAX_DIM.toFloat() / max(source.width, source.height)
        val thumb = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                source,
                max(1, (source.width * scale).roundToInt()),
                max(1, (source.height * scale).roundToInt()),
                true
            )
        } else {
            source
        }
        val file = File(context.cacheDir, "th_${UUID.randomUUID()}.webp")
        FileOutputStream(file).use {
            thumb.compress(Bitmap.CompressFormat.WEBP, MediaLimits.THUMB_QUALITY, it)
        }
        if (thumb !== source) thumb.recycle()
        return file
    }

    private fun sampleSize(width: Int, height: Int, maxDim: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= maxDim || height / (sample * 2) >= maxDim) sample *= 2
        return sample
    }

    private fun applyExifRotation(cr: ContentResolver, uri: Uri, bitmap: Bitmap): Bitmap {
        val degrees = cr.openInputStream(uri).use { input ->
            if (input == null) return bitmap
            when (
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            ) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }
        if (degrees == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }
}
