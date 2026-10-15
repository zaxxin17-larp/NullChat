package com.nullsec.nullchat.ui.chat

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.nullsec.nullchat.media.MediaKind
import com.nullsec.nullchat.media.MediaPreparer
import com.nullsec.nullchat.media.MediaUploader
import com.nullsec.nullchat.model.Message
import java.util.Date
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

private const val TTL_DAYS = 365L

class ChatViewModel(private val chatId: String) : ViewModel() {

    private val db = FirebaseFirestore.getInstance()
    private val uid: String get() = FirebaseAuth.getInstance().currentUser?.uid ?: ""

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _uploading = MutableStateFlow(false)
    val uploading: StateFlow<Boolean> = _uploading.asStateFlow()

    private val _progress = MutableStateFlow(0.0)
    val progress: StateFlow<Double> = _progress.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private fun messagesCollection() =
        db.collection("chats").document(chatId).collection("messages")

    init {
        viewModelScope.launch {
            messagesFlow().collect { _messages.value = it }
        }
    }

    private fun messagesFlow() = callbackFlow {
        val registration = messagesCollection()
            .orderBy("createdAt", Query.Direction.ASCENDING)
            .limitToLast(200)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    _error.value = error.message
                    return@addSnapshotListener
                }
                val list = snapshot?.documents?.mapNotNull { doc ->
                    doc.toObject(Message::class.java)?.copy(id = doc.id)
                } ?: emptyList()
                trySend(list)
            }
        awaitClose { registration.remove() }
    }

    fun sendText(text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                messagesCollection().add(
                    Message(
                        chatId = chatId,
                        senderId = uid,
                        type = com.nullsec.nullchat.model.MessageType.TEXT,
                        text = body,
                        createdAt = Timestamp.now(),
                        expireAt = expiry()
                    )
                ).await()
            }.onFailure { _error.value = it.message }
        }
    }

    /** Compress -> validate -> dedupe/quota -> upload -> write message. */
    fun sendMedia(context: Context, uri: Uri, kind: MediaKind) {
        if (_uploading.value) return
        viewModelScope.launch {
            _uploading.value = true
            _progress.value = 0.0
            _error.value = null
            try {
                val prepared = MediaPreparer.prepare(context, uri, kind)
                val uploaded = MediaUploader.upload(
                    uid = uid,
                    kind = kind,
                    file = prepared.file,
                    mime = prepared.mime,
                    thumb = prepared.thumb,
                    width = prepared.width,
                    height = prepared.height,
                    durationMs = prepared.durationMs
                ) { _progress.value = it }

                val doc = uploaded.let {
                    mapOf(
                        "chatId" to chatId,
                        "senderId" to uid,
                        "type" to kind.name,
                        "mediaUrl" to it.mediaUrl,
                        "thumbUrl" to it.thumbUrl,
                        "storagePath" to it.storagePath,
                        "thumbPath" to it.thumbPath,
                        "mediaId" to it.mediaId,
                        "mime" to it.mime,
                        "sizeBytes" to it.sizeBytes,
                        "width" to it.width,
                        "height" to it.height,
                        "durationMs" to it.durationMs,
                        "sha256" to it.sha256,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "expireAt" to expiry()
                    )
                }
                messagesCollection().add(doc).await()

                prepared.file.delete()
                prepared.thumb?.delete()
            } catch (e: Exception) {
                _error.value = e.message ?: "Upload failed"
            } finally {
                _uploading.value = false
                _progress.value = 0.0
            }
        }
    }

    fun report(messageId: String, reason: String) {
        viewModelScope.launch {
            runCatching {
                db.collection("reports").add(
                    mapOf(
                        "chatId" to chatId,
                        "messageId" to messageId,
                        "reporterId" to uid,
                        "reason" to reason,
                        "status" to "open",
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                ).await()
                messagesCollection().document(messageId).update("reported", true).await()
            }.onFailure { _error.value = it.message }
        }
    }

    fun toggleFavorite(mediaId: String, favorite: Boolean) {
        if (uid.isEmpty() || mediaId.isEmpty()) return
        viewModelScope.launch {
            runCatching { MediaUploader.setFavorite(uid, mediaId, favorite) }
                .onFailure { _error.value = it.message }
        }
    }

    fun consumeError() { _error.value = null }

    private fun expiry(): Timestamp =
        Timestamp(Date(System.currentTimeMillis() + TTL_DAYS * 24 * 60 * 60 * 1000))
}
