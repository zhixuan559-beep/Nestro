package app.nestro.data

/**
 * UI-independent chat message model shared by persistence, request policy and transport.
 */
internal data class ChatMessage(
    val id: String,
    val role: String,
    val content: String,
    val versions: List<String> = emptyList(),
    val activeVersion: Int = 0
)
