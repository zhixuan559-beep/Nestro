package app.nestro.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import app.nestro.data.ConversationRecord
import app.nestro.data.ProjectRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Debounces Room snapshot writes while keeping the newest callbacks.
 *
 * A changed snapshot cancels the previous pending write. Persistence remains owned by
 * ConversationPersistenceService; this effect only coordinates when immutable snapshots
 * are handed to that service.
 */
@Composable
internal fun ConversationSnapshotPersistenceEffect(
    databaseReady: Boolean,
    conversations: List<ConversationRecord>,
    projects: List<ProjectRecord>,
    persistSnapshot: suspend (List<ConversationRecord>, List<ProjectRecord>) -> Unit,
    onFailure: (Throwable) -> Unit
) {
    val persistLatest = rememberUpdatedState(persistSnapshot)
    val failureLatest = rememberUpdatedState(onFailure)

    LaunchedEffect(databaseReady, conversations, projects) {
        if (!databaseReady) return@LaunchedEffect

        val conversationSnapshot = conversations.toList()
        val projectSnapshot = projects.toList()
        delay(300L)

        try {
            persistLatest.value(conversationSnapshot, projectSnapshot)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            failureLatest.value(error)
        }
    }
}
