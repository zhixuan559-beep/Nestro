package app.nestro.ui

import android.content.SharedPreferences
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import app.nestro.data.WorkflowDefinition
import app.nestro.data.WorkflowDefinitionCodec
import app.nestro.data.WorkflowRunHistoryCodec
import app.nestro.data.WorkflowRunRecord
import app.nestro.data.WorkflowStep
import app.nestro.data.WorkflowStepKind
import app.nestro.agent.AgentApprovalRequest
import app.nestro.agent.AgentApprovalDecision
import kotlinx.coroutines.CompletableDeferred

internal data class UnifiedApprovalPrompt(
    val request: AgentApprovalRequest,
    val decision: CompletableDeferred<AgentApprovalDecision>
)

/** Owns workflow editor, run and history UI state. */
@Stable
internal class WorkflowUiState(preferences: SharedPreferences) {
    val workflowHistory: MutableState<List<WorkflowRunRecord>> =
        mutableStateOf(WorkflowRunHistoryCodec.decode(preferences.getString("workflow_runs_v1", "[]") ?: "[]"))
    val workflows: MutableState<List<WorkflowDefinition>> = mutableStateOf(
        WorkflowDefinitionCodec.decode(preferences.getString("workflows_v1", "[]") ?: "[]").ifEmpty {
            listOf(
                WorkflowDefinition(
                    name = "研究到方案",
                    description = "先拆解问题，再形成可执行方案",
                    steps = listOf(
                        WorkflowStep(kind = WorkflowStepKind.PROMPT, title = "拆解与研究", instruction = "识别目标、关键事实、约束和风险，形成结构化分析。"),
                        WorkflowStep(kind = WorkflowStepKind.PROMPT, title = "形成方案", instruction = "基于上一步结果，形成清晰、可执行、带优先级的方案。")
                    )
                )
            )
        }
    )
    val showWorkflowDialog: MutableState<Boolean> = mutableStateOf(false)
    val editingWorkflowId: MutableState<String> = mutableStateOf("")
    val workflowDraftName: MutableState<String> = mutableStateOf("")
    val workflowDraftDescription: MutableState<String> = mutableStateOf("")
    val workflowDraftSteps: MutableState<List<WorkflowStep>> = mutableStateOf(
        listOf(
            WorkflowStep(kind = WorkflowStepKind.PROMPT, title = "分析需求", instruction = "拆解用户目标，列出关键事实、约束和待解决问题。"),
            WorkflowStep(kind = WorkflowStepKind.PROMPT, title = "形成结果", instruction = "根据上一步分析，形成可直接交付的结果，并检查完整性。")
        )
    )
    val workflowTargetPickerIndex = mutableIntStateOf(-1)
    val showWorkflowTargetPicker: MutableState<Boolean> = mutableStateOf(false)
    val workflowInputDraft: MutableState<String> = mutableStateOf(preferences.getString("workflow_resume_input_v1", "") ?: "")
    val workflowRunStatus: MutableState<String> = mutableStateOf("")
    val runningWorkflowId: MutableState<String> = mutableStateOf("")
    val workflowPaused: MutableState<Boolean> = mutableStateOf(false)
    val workflowPauseGate: MutableState<CompletableDeferred<Unit>?> = mutableStateOf(null)
    val agentApprovalPrompt: MutableState<UnifiedApprovalPrompt?> = mutableStateOf(null)
    val workflowApprovalVisible: MutableState<Boolean> = mutableStateOf(false)
}
