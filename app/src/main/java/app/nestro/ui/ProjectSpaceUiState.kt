package app.nestro.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import app.nestro.data.ProjectRecord

/**
 * Owns project-space screen state independently from the root navigation
 * composable. Persistence and project operations remain in repositories.
 */
@Stable
internal class ProjectSpaceUiState {
    val projects: MutableState<List<ProjectRecord>> =
        mutableStateOf(listOf(ProjectRecord("default", "默认空间", "", System.currentTimeMillis())))

    val activeProjectId: MutableState<String> = mutableStateOf("default")
    val deletingProjectId: MutableState<String> = mutableStateOf("")
    val showProjectDialog: MutableState<Boolean> = mutableStateOf(false)
    val projectDraftName: MutableState<String> = mutableStateOf("")
    val projectDraftInstructions: MutableState<String> = mutableStateOf("")
    val editingProjectId: MutableState<String> = mutableStateOf("")
}
