package app.nestro.ui

import android.content.SharedPreferences
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import app.nestro.data.ExpertCatalogRules
import app.nestro.data.ExpertProfile
import app.nestro.data.ExpertProfileCodec
import app.nestro.data.ExpertRevision
import app.nestro.data.ExpertRevisionCodec
import app.nestro.data.ExpertTemplate
import app.nestro.data.ExpertTemplateCodec

/** Owns persisted expert catalog, selection, profile revisions and user templates. */
@Stable
internal class ExpertCatalogUiState(private val preferences: SharedPreferences) {
    val experts: MutableState<List<String>> =
        mutableStateOf(listOf("文案官", "选题狐", "代码佬"))
    val expertPrompts: MutableState<Map<String, String>> =
        mutableStateOf(loadExpertPrompts(preferences))
    val expertProfiles: MutableState<Map<String, ExpertProfile>> =
        mutableStateOf(ExpertProfileCodec.decode(preferences.getString("expert_profiles_v1", "{}") ?: "{}"))
    val expertRevisions: MutableState<Map<String, List<ExpertRevision>>> =
        mutableStateOf(ExpertRevisionCodec.decode(preferences.getString("expert_versions_v1", "{}") ?: "{}"))
    val expertTemplates: MutableState<List<ExpertTemplate>> =
        mutableStateOf(ExpertTemplateCodec.decode(preferences.getString("expert_templates_v1", "[]") ?: "[]"))
    val selectedExpert: MutableState<String> = mutableStateOf(
        ExpertCatalogRules.resolveSelection(
            preferences.getString("selected_expert", "通用助手") ?: "通用助手",
            experts.value,
            expertProfiles.value
        )
    )
}
