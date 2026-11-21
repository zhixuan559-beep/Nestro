package app.nestro.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import app.nestro.data.ChatMessage
import app.nestro.data.ConversationRecord
import app.nestro.data.ConversationListItem

/** Owns conversation list, selection, editing and paging UI state. */
@Stable
internal class ConversationUiState {
    val messages: MutableState<List<ChatMessage>> = mutableStateOf(emptyList())
    val conversations: MutableState<List<ConversationRecord>> = mutableStateOf(emptyList())
    val activeConversationId: MutableState<String> = mutableStateOf("")
    val conversationSearch: MutableState<String> = mutableStateOf("")
    val conversationPageItems: MutableState<List<ConversationListItem>> = mutableStateOf(emptyList())
    val conversationPageLoading: MutableState<Boolean> = mutableStateOf(false)
    val conversationPageHasMore: MutableState<Boolean> = mutableStateOf(false)
    val conversationPageRefreshKey = mutableIntStateOf(0)
    val showRenameConversation: MutableState<Boolean> = mutableStateOf(false)
    val showEditMessageDialog: MutableState<Boolean> = mutableStateOf(false)
    val editMessageId: MutableState<String> = mutableStateOf("")
    val editMessageDraft: MutableState<String> = mutableStateOf("")
    val renameConversationId: MutableState<String> = mutableStateOf("")
    val renameConversationDraft: MutableState<String> = mutableStateOf("")
}
