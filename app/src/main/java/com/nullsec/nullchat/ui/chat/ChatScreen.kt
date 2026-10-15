package com.nullsec.nullchat.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Gif
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nullsec.nullchat.media.MediaKind
import com.nullsec.nullchat.media.VoiceRecorder
import com.nullsec.nullchat.model.Message
import com.nullsec.nullchat.model.MessageType
import kotlinx.coroutines.delay

@Composable
fun ChatScreen(chatId: String, currentUid: String) {
    val context = LocalContext.current
    val vm: ChatViewModel = viewModel(key = chatId) { ChatViewModel(chatId) }

    val messages by vm.messages.collectAsStateWithLifecycle()
    val uploading by vm.uploading.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()

    val imageLoader = remember { buildMediaImageLoader(context) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(error) {
        error?.let { snackbar.showSnackbar(it); vm.consumeError() }
    }

    // --- media pickers ---
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { vm.sendMedia(context, it, MediaKind.IMAGE) }
    }
    val pickGif = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { vm.sendMedia(context, it, MediaKind.GIF) }
    }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { vm.sendMedia(context, it, MediaKind.VIDEO) }
    }

    // --- voice recording ---
    val recorder = remember { VoiceRecorder(context) }
    var recording by remember { mutableStateOf(false) }

    val recordPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) { recorder.start(); recording = true } }

    LaunchedEffect(recording) {
        while (recording) {
            if (recorder.shouldAutoStop()) {
                val file = runCatching { recorder.stop() }.getOrNull()
                recording = false
                if (file != null) vm.sendMedia(context, Uri.fromFile(file), MediaKind.VOICE)
                break
            }
            delay(200)
        }
    }

    var draft by remember { mutableStateOf("") }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
                if (uploading) {
                    LinearProgressIndicator(
                        progress = { progress.toFloat() },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Uploading… ${(progress * 100).toInt()}%",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { pickImage.launch("image/*") }) {
                        Icon(Icons.Filled.Image, "Send image", tint = Color(0xFF3FD6E8))
                    }
                    IconButton(onClick = { pickGif.launch("image/gif") }) {
                        Icon(Icons.Filled.Gif, "Send GIF", tint = Color(0xFF3FD6E8))
                    }
                    IconButton(onClick = { pickVideo.launch("video/*") }) {
                        Icon(Icons.Filled.Movie, "Send video", tint = Color(0xFF3FD6E8))
                    }
                    IconButton(onClick = {
                        val granted = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                        if (granted) { recorder.start(); recording = true }
                        else recordPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }) {
                        Icon(
                            if (recording) Icons.Filled.Stop else Icons.Filled.Mic,
                            "Record voice note",
                            tint = if (recording) Color(0xFFEF5B5B) else Color(0xFF3FD6E8)
                        )
                    }
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Message") },
                        maxLines = 4
                    )
                    IconButton(
                        onClick = { vm.sendText(draft); draft = "" },
                        enabled = draft.isNotBlank()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = Color(0xFF3FD6E8))
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                reverseLayout = false,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    MessageRow(
                        message = message,
                        isMine = message.senderId == currentUid,
                        imageLoader = imageLoader,
                        onReport = { vm.report(message.id, "user_report") },
                        onToggleFavorite = { vm.toggleFavorite(message.mediaId ?: "", true) }
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageRow(
    message: Message,
    isMine: Boolean,
    imageLoader: coil.ImageLoader,
    onReport: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    val alignment = if (isMine) Alignment.End else Alignment.Start
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        when (message.type) {
            MessageType.TEXT -> TextBubble(message.text.orEmpty(), isMine)
            else -> MediaBubble(
                message = message,
                imageLoader = imageLoader,
                onReport = onReport,
                onToggleFavorite = onToggleFavorite
            )
        }
    }
}

@Composable
private fun TextBubble(text: String, isMine: Boolean) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (isMine) Color(0xFF1D7F8C) else Color(0xFF131D26))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(text, color = Color(0xFFDBE8EC))
    }
}
