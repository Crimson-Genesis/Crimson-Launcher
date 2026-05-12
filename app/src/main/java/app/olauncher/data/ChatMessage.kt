package app.olauncher.data

import java.util.UUID

data class ChatMessage(
    val id: Long = 0,
    val uuid: String = UUID.randomUUID().toString(),
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val mediaUri: String? = null,
    val mediaType: String? = null, // "IMAGE" or "VIDEO"
    val previewUri: String? = null
)
