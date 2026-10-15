package com.nullsec.nullchat.ui.chat

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.decode.GifDecoder
import coil.util.DebugLogger

/**
 * Builds a single Coil [ImageLoader] with an aggressive disk cache so media is
 * never re-downloaded across sessions. Because Storage URLs are UUID-based and
 * served with `max-age=31536000, immutable`, caching forever is correct.
 */
fun buildMediaImageLoader(context: Context): ImageLoader =
    ImageLoader.Builder(context)
        .memoryCache {
            MemoryCache.Builder(context)
                .maxSizePercent(0.20)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(context.cacheDir.resolve("media_cache"))
                .maxSizeBytes(256L * 1024 * 1024)   // 256 MB
                .build()
        }
        .components { add(GifDecoder.Factory()) }
        .apply {
            if (com.nullsec.nullchat.BuildConfig.DEBUG) logger(DebugLogger())
        }
        .build()
