package app.nestro.data

/**
 * Request-only media payload resolved from an asset_id marker in a user message.
 * The payload is deliberately not written to Room; project media remains the source of truth.
 */
internal data class ChatMessageMediaPart(
    val kind: String,
    val mimeType: String,
    val dataBase64: String,
    val estimatedTokens: Int = 1_024
)

/**
 * UI-independent chat message model shared by persistence, request policy and transport.
 */
internal data class ChatMessage(
    val id: String,
    val role: String,
    val content: String,
    val versions: List<String> = emptyList(),
    val activeVersion: Int = 0,
    val mediaParts: List<ChatMessageMediaPart> = emptyList()
)
