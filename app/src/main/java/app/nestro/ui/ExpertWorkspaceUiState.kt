package app.nestro.ui

import android.content.SharedPreferences
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import app.nestro.data.ExpertBundle
import app.nestro.data.ExpertProjectPlan
import app.nestro.data.ExpertTeamBundle
import app.nestro.data.ExpertTeamCollaborationEvent
import app.nestro.data.ExpertTeamRunHistoryCodec
import app.nestro.data.ExpertTeamRunResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

internal class ExpertTeamPauseController {
    private val resumeSignal = AtomicReference<CompletableDeferred<Unit>?>(null)

    fun isPaused(): Boolean = resumeSignal.get() != null

    fun pause(): Boolean =
        resumeSignal.compareAndSet(null, CompletableDeferred())

    fun resume(): Boolean {
        val gate = resumeSignal.getAndSet(null) ?: return false
        gate.complete(Unit)
        return true
    }

    suspend fun awaitResume(shouldStop: () -> Boolean = { false }) {
        while (true) {
            if (shouldStop()) throw CancellationException("用户停止专家项目")
            val gate = resumeSignal.get() ?: return
            withTimeoutOrNull(100) { gate.await() }
        }
    }
}

internal const val EXPERT_TEAM_EVENT_PAGE_SIZE = 16
internal const val EXPERT_TEAM_EVENT_MAX_VISIBLE = 100

/** Returns the requested recent collaboration events, newest first. */
internal fun visibleExpertTeamEvents(
    events: List<ExpertTeamCollaborationEvent>,
    visibleCount: Int
): List<ExpertTeamCollaborationEvent> =
    events.takeLast(visibleCount.coerceIn(1, EXPERT_TEAM_EVENT_MAX_VISIBLE)).asReversed()

/** One row of the live expert-team plan checklist (Manus-style). */
data class ProjectPlanChecklistItem(
    val packageId: String,
    val memberName: String,
    val title: String,
    val status: String
)

/**
 * Derives the live plan-checklist rows for an expert project plan:
 * one row per work package, titled by the first line of its instruction,
 * with the live status from [statuses] ("waiting" when not started yet).
 */
internal fun projectPlanChecklistItems(
    plan: ExpertProjectPlan,
    statuses: Map<String, String>
): List<ProjectPlanChecklistItem> =
    plan.packages.map { item ->
        ProjectPlanChecklistItem(
            packageId = item.id,
            memberName = item.memberName,
            title = item.instruction.lineSequence().firstOrNull()?.trim()?.take(60).orEmpty().ifBlank { item.id },
            status = statuses[item.id] ?: "waiting"
        )
    }

/**
 * Owns expert editor, import/export, team editor and team-run presentation state.
 * Existing preference formats and domain execution remain unchanged.
 */
@Stable
internal class ExpertWorkspaceUiState(preferences: SharedPreferences) {
    val expertDraft: MutableState<String> = mutableStateOf("")
    val editingExpertName: MutableState<String> = mutableStateOf("")
    val expertPromptDraft: MutableState<String> = mutableStateOf("")
    val expertGroupDraft: MutableState<String> = mutableStateOf("默认")
    val expertAvatarDraft: MutableState<String> = mutableStateOf("✦")
    // 改版第 b 步：创建/编辑专家时可自定义的吉祥物形象（"capybara" / ""）。
    val expertMascotDraft: MutableState<String> = mutableStateOf("capybara")
    val expertEnabledDraft: MutableState<Boolean> = mutableStateOf(true)
    val expertSkillDraft: MutableState<Set<String>> = mutableStateOf(emptySet())
    val expertTemperatureDraft: MutableState<String> = mutableStateOf("")
    val expertMaxTokensDraft: MutableState<String> = mutableStateOf("")
    val expertDescriptionDraft: MutableState<String> = mutableStateOf("")
    val showExpertDialog: MutableState<Boolean> = mutableStateOf(false)
    val expertDialogError: MutableState<String> = mutableStateOf("")
    val pendingExpertBundle: MutableState<ExpertBundle?> = mutableStateOf(null)
    val expertImportStatus: MutableState<String> = mutableStateOf("")
    val expertExportStatus: MutableState<String> = mutableStateOf("")
    val expertExportJson: MutableState<String> = mutableStateOf("")

    val expertTeams: MutableState<List<ExpertTeam>> = mutableStateOf(loadExpertTeams(preferences))
    val selectedTeamId: MutableState<String> = mutableStateOf(
        (preferences.getString("selected_team_id", "") ?: "")
            .takeIf { id -> expertTeams.value.any { it.id == id } } ?: ""
    )
    val showTeamDialog: MutableState<Boolean> = mutableStateOf(false)
    val editingTeamId: MutableState<String> = mutableStateOf("")
    val teamDraftName: MutableState<String> = mutableStateOf("")
    val teamDraftDescription: MutableState<String> = mutableStateOf("")
    val teamDraftMembers: MutableState<Set<String>> = mutableStateOf(emptySet())
    val pendingTeamBundle: MutableState<ExpertTeamBundle?> = mutableStateOf(null)
    val teamImportStatus: MutableState<String> = mutableStateOf("")
    val teamExportStatus: MutableState<String> = mutableStateOf("")
    val teamExportJson: MutableState<String> = mutableStateOf("")
    val teamRunStatus: MutableState<String> = mutableStateOf("")
    val expertTeamRunHistory: MutableState<List<ExpertTeamRunResult>> = mutableStateOf(
        ExpertTeamRunHistoryCodec.decode(preferences.getString("expert_team_runs_v1", "[]") ?: "[]")
    )
    val teamMemberStatuses: MutableState<Map<String, String>> = mutableStateOf(emptyMap())
    val activeProjectPlan: MutableState<ExpertProjectPlan?> = mutableStateOf(null)
    val activeProjectPlanConversationId: MutableState<String> = mutableStateOf("")
}
