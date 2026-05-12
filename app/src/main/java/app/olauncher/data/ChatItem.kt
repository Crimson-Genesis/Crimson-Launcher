package app.olauncher.data

sealed class ChatItem {
    data class Message(val message: ChatMessage) : ChatItem()
    data class Header(val dateText: String) : ChatItem()
}
