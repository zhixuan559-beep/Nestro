package app.nestro.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import app.nestro.data.ChatMessage
import app.nestro.data.ConversationRecallSource

/** UI-facing state for a single chat request and its revision/recall flow. */
@Stable
internal class ChatExecutionUiState {
    val draft: MutableState<String> = mutableStateOf("")
    val busy: MutableState<Boolean> = mutableStateOf(false)
    val stopRequested: MutableState<Boolean> = mutableStateOf(false)
    val activeRequestId: MutableState<String> = mutableStateOf("")
    val reuseLastUserOnSend: MutableState<Boolean> = mutableStateOf(false)
    val pendingAssistantRevision: MutableState<ChatMessage?> = mutableStateOf(null)
    val status: MutableState<String> = mutableStateOf("尚未配置模型")
    val recallSources: MutableState<List<ConversationRecallSource>> = mutableStateOf(emptyList())
    val showRecallDialog: MutableState<Boolean> = mutableStateOf(false)
}
