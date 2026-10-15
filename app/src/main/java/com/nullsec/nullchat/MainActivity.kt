package com.nullsec.nullchat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.google.firebase.auth.FirebaseAuth
import com.nullsec.nullchat.ui.chat.ChatScreen
import com.nullsec.nullchat.ui.theme.NullChatTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NullChatTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var uid by remember { mutableStateOf(FirebaseAuth.getInstance().currentUser?.uid) }
                    LaunchedEffect(Unit) {
                        if (uid == null) {
                            FirebaseAuth.getInstance().signInAnonymously()
                                .addOnSuccessListener { result -> uid = result.user?.uid }
                        }
                    }
                    val current = uid
                    if (current == null) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else {
                        ChatScreen(chatId = "nullchat-room", currentUid = current)
                    }
                }
            }
        }
    }
}
