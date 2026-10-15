package com.nullsec.nullchat.ui.chat

import android.media.MediaPlayer
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.ImageLoader
import coil.compose.AsyncImage
import com.nullsec.nullchat.model.Message
import com.nullsec.nullchat.model.MessageType
import androidx.compose.foundation.layout.aspectRatio

@Composable
fun MediaBubble(
    message: Message,
    imageLoader: ImageLoader,
    onReport: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    var favorited by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f)) {
            when (message.type) {
                MessageType.IMAGE -> ImageContent(message, imageLoader)
                MessageType.GIF -> GifContent(message)
                MessageType.VIDEO -> VideoContent(message, imageLoader)
                MessageType.VOICE -> VoiceContent(message)
                MessageType.TEXT -> Unit
            }
        }

        Column {
            IconButton(onClick = onReport) {
                Icon(Icons.Filled.Flag, contentDescription = "Report", tint = Color(0xFFEF5B5B))
            }
            if (message.type == MessageType.GIF) {
                IconButton(onClick = { favorited = !favorited; onToggleFavorite() }) {
                    Icon(
                        if (favorited) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = "Save to favorites",
                        tint = Color(0xFF3FD6E8)
                    )
                }
            }
        }
    }
}

/* ------------------------------- images ------------------------------- */

@Composable
private fun ImageContent(message: Message, imageLoader: ImageLoader) {
    // Load the ~20 KB thumbnail instantly; swap to the full image on tap.
    var showFull by remember(message.id) { mutableStateOf(false) }
    val ratio = (message.width ?: 4).toFloat() / (message.height ?: 3).toFloat().coerceAtLeast(0.1f)

    AsyncImage(
        model = if (showFull) message.mediaUrl else message.thumbUrl,
        imageLoader = imageLoader,
        contentDescription = null,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF0C1219))
            .clickable { showFull = true }
    )
}

/* ------------------------------ gif/video ----------------------------- */

@Composable
private fun GifContent(message: Message) {
    // GIFs are stored as muted looping MP4s -> autoplay immediately.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black)
    ) {
        VideoPlayer(url = message.mediaUrl.orEmpty(), muted = true, loop = true)
    }
}

@Composable
private fun VideoContent(message: Message, imageLoader: ImageLoader) {
    var playing by remember(message.id) { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black)
    ) {
        if (playing) {
            VideoPlayer(url = message.mediaUrl.orEmpty(), muted = false, loop = false)
        } else {
            AsyncImage(
                model = message.thumbUrl,
                imageLoader = imageLoader,
                contentDescription = "Video thumbnail",
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clickable { playing = true }
            )
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = "Play",
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(56.dp)
                    .clickable { playing = true }
            )
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoPlayer(url: String, muted: Boolean, loop: Boolean) {
    val context = LocalContext.current
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            volume = if (muted) 0f else 1f
            repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }

    AndroidView(
        factory = { ctx -> PlayerView(ctx).apply { useController = !muted } },
        update = { view -> view.player = player },
        modifier = Modifier.fillMaxWidth()
    )
}

/* -------------------------------- voice ------------------------------- */

@Composable
private fun VoiceContent(message: Message) {
    val context = LocalContext.current
    var playing by remember(message.id) { mutableStateOf(false) }
    var player by remember(message.id) { mutableStateOf<MediaPlayer?>(null) }

    DisposableEffect(message.id) {
        onDispose { player?.release(); player = null }
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF0C1219))
            .padding(horizontal = 8.dp)
            .clickable {
                if (playing) {
                    player?.release(); player = null; playing = false
                } else {
                    val mp = MediaPlayer().apply {
                        setDataSource(message.mediaUrl.orEmpty())
                        setOnPreparedListener { start(); playing = true }
                        setOnCompletionListener { playing = false }
                        setOnErrorListener { _, _, _ -> playing = false; true }
                        prepareAsync()
                    }
                    player = mp
                }
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.PlayArrow,
            contentDescription = if (playing) "Pause" else "Play",
            tint = Color(0xFF3FD6E8),
            modifier = Modifier.size(36.dp).padding(6.dp)
        )
        Text(
            text = "Voice · ${((message.durationMs ?: 0) / 1000)}s",
            color = Color(0xFFDBE8EC),
            modifier = Modifier.padding(end = 12.dp)
        )
    }
}
