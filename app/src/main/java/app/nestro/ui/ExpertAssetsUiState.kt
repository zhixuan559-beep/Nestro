package app.nestro.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import app.nestro.data.ExpertTemplate
import app.nestro.data.SkillBundle

/**
 * UI state for expert templates and the skill library. The existing loaders,
 * persistence format and import/export operations remain unchanged.
 */
@Stable
internal class ExpertAssetsUiState(
    initialSkills: Map<String, String>,
    initialSkillMetadata: Map<String, String>,
    initialDisabledSkills: Set<String>
) {
    val showExpertTemplatesDialog: MutableState<Boolean> = mutableStateOf(false)
    val templateNameDraft: MutableState<String> = mutableStateOf("")
    val templateSearchDraft: MutableState<String> = mutableStateOf("")
    val templateCategoryFilter: MutableState<String> = mutableStateOf("全部")
    val templateStatus: MutableState<String> = mutableStateOf("")
    val templateExportJson: MutableState<String> = mutableStateOf("")
    val pendingTemplateBundle: MutableState<List<ExpertTemplate>?> = mutableStateOf(null)
    val showExpertHistoryDialog: MutableState<Boolean> = mutableStateOf(false)
    val historyExpertName: MutableState<String> = mutableStateOf("")
    val historyCompareVersion: MutableState<String> = mutableStateOf("")
    val skills: MutableState<Map<String, String>> = mutableStateOf(initialSkills)
    val skillMetadata: MutableState<Map<String, String>> = mutableStateOf(initialSkillMetadata)
    val disabledSkills: MutableState<Set<String>> = mutableStateOf(initialDisabledSkills)
    val editingSkillName: MutableState<String> = mutableStateOf("")
    val skillDialogError: MutableState<String> = mutableStateOf("")
    val pendingSkillBundle: MutableState<SkillBundle?> = mutableStateOf(null)
    val showSkillImportDialog: MutableState<Boolean> = mutableStateOf(false)
    val skillImportStatus: MutableState<String> = mutableStateOf("")
    val skillExportStatus: MutableState<String> = mutableStateOf("")
    val skillExportJson: MutableState<String> = mutableStateOf("")
    val selectedSkill: MutableState<String> = mutableStateOf("")
    val skillDraftName: MutableState<String> = mutableStateOf("")
    val skillDraftPrompt: MutableState<String> = mutableStateOf("")
    val showSkillDialog: MutableState<Boolean> = mutableStateOf(false)
}
