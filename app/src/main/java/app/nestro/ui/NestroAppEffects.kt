package app.nestro.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import app.nestro.data.ConversationListItem
import app.nestro.data.ConversationRecord
import app.nestro.data.ProjectRecord
import app.nestro.data.UserMemoryEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Loads project-scoped automatic memories only while the memory page is active. */
@Composable
internal fun MemoryPageLoadEffect(
    page: String,
    activeProjectId: String,
    loadMemories: suspend (String) -> List<UserMemoryEntity>,
    onMemoriesLoaded: (List<UserMemoryEntity>) -> Unit,
    onResetEdits: () -> Unit
) {
    LaunchedEffect(page, activeProjectId) {
        if (page == "memory") {
            onMemoriesLoaded(loadMemories(activeProjectId))
            onResetEdits()
        }
    }
}

/** Owns the debounce, loading flags and scroll reset for conversation history paging. */
@Composable
internal fun ConversationPageLoadEffect(
    page: String,
    activeProjectId: String,
    query: String,
    refreshKey: Int,
    listState: LazyListState,
    onLoadingChanged: (Boolean) -> Unit,
    onItemsLoaded: (List<ConversationListItem>) -> Unit,
    onHasMoreChanged: (Boolean) -> Unit,
    onLoadFailure: (Throwable) -> Unit,
    loadPage: suspend (projectId: String, query: String, limit: Int, offset: Int) -> List<ConversationListItem>
) {
    LaunchedEffect(page, activeProjectId, query, refreshKey) {
        if (page != "conversations") return@LaunchedEffect
        onLoadingChanged(true)
        onItemsLoaded(emptyList())
        onHasMoreChanged(false)
        listState.scrollToItem(0)
        try {
            delay(250)
            val result = loadPage(activeProjectId, query.trim().take(120), 30, 0)
            onItemsLoaded(result)
            onHasMoreChanged(result.size == 30)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            onLoadFailure(failure)
        } finally {
            if (currentCoroutineContext().isActive) onLoadingChanged(false)
        }
    }
}

/** Persists a consistent snapshot whenever conversation or project state changes. */
@Composable
internal fun ConversationSnapshotPersistenceEffect(
    databaseReady: Boolean,
    conversations: List<ConversationRecord>,
    projects: List<ProjectRecord>,
    persistSnapshot: suspend (List<ConversationRecord>, List<ProjectRecord>) -> Unit,
    onFailure: (Throwable) -> Unit
) {
    LaunchedEffect(conversations, projects, databaseReady) {
        if (!databaseReady) return@LaunchedEffect
        runCatching { persistSnapshot(conversations.toList(), projects.toList()) }
            .onFailure(onFailure)
    }
}
