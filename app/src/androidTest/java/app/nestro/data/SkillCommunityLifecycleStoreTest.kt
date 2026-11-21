package app.nestro.data

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SkillCommunityLifecycleStoreTest {
    private lateinit var preferences: android.content.SharedPreferences
    private lateinit var store: SkillCommunityLifecycleStore

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        preferences = context.getSharedPreferences("skill-community-lifecycle-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        store = SkillCommunityLifecycleStore(preferences)
    }

    private fun bundle(version: String, prompt: String) = SkillBundle(
        name = "CommunitySkill",
        version = version,
        description = "test",
        prompt = prompt,
        source = "已验证签名社区目录",
        permissions = emptyList(),
        dependencies = emptyList()
    )

    @Test fun installUpdateAndRollbackRestoresPreviousVersion() {
        store.install(bundle("1.0.0", "old prompt"))
        assertTrue((0 until org.json.JSONArray(preferences.getString("disabled_skills_v1", "[]")!!).length())
            .any { org.json.JSONArray(preferences.getString("disabled_skills_v1", "[]")!!).optString(it) == "CommunitySkill" })
        store.install(bundle("1.1.0", "new prompt"))
        assertEquals("new prompt", org.json.JSONObject(preferences.getString("skills", "{}")!!).getString("CommunitySkill"))
        assertTrue(store.canRollback("CommunitySkill"))
        store.rollback("CommunitySkill")
        assertEquals("old prompt", org.json.JSONObject(preferences.getString("skills", "{}")!!).getString("CommunitySkill"))
        assertTrue(store.canRollback("CommunitySkill"))
        store.rollback("CommunitySkill")
        assertFalse(org.json.JSONObject(preferences.getString("skills", "{}")!!).has("CommunitySkill"))
    }

    @Test fun uninstallRemovesOnlyCommunitySkillAndKeepsRollbackHistory() {
        store.install(bundle("1.0.0", "community prompt"))
        store.uninstall("CommunitySkill")
        assertFalse(org.json.JSONObject(preferences.getString("skills", "{}")!!).has("CommunitySkill"))
        store.rollback("CommunitySkill")
        assertEquals("community prompt", org.json.JSONObject(preferences.getString("skills", "{}")!!).getString("CommunitySkill"))
        assertTrue((0 until org.json.JSONArray(preferences.getString("disabled_skills_v1", "[]")!!).length())
            .any { org.json.JSONArray(preferences.getString("disabled_skills_v1", "[]")!!).optString(it) == "CommunitySkill" })
    }

    @Test fun refusesToOverwriteSameNamedLocalSkill() {
        preferences.edit()
            .putString("skills", org.json.JSONObject().put("CommunitySkill", "local prompt").toString())
            .putString("skill_metadata_v1", org.json.JSONObject().put("CommunitySkill", SkillBundleParser.metadataJson(
                SkillBundle("CommunitySkill", "1.0.0", "", "local prompt", "本地文件", emptyList(), emptyList())
            )).toString())
            .commit()
        assertFalse(runCatching { store.install(bundle("1.1.0", "remote prompt")) }.isSuccess)
        assertEquals("local prompt", org.json.JSONObject(preferences.getString("skills", "{}")!!).getString("CommunitySkill"))
    }

    @Test fun atomicallyInstallsCompleteDependencyPlanAndBlocksUninstallOfUsedDependency() {
        val base = SkillBundle("BaseSkill", "1.2.0", "base", "base prompt", "已验证签名社区目录", emptyList(), emptyList())
        val dependent = bundle("1.0.0", "dependent prompt").copy(
            dependencies = listOf(SkillDependency("BaseSkill", "^1.0.0"))
        )
        store.installAll(listOf(base, dependent))
        val installed = org.json.JSONObject(preferences.getString("skills", "{}")!!)
        assertTrue(installed.has("BaseSkill"))
        assertTrue(installed.has("CommunitySkill"))
        assertFalse(runCatching { store.uninstall("BaseSkill") }.isSuccess)
        assertEquals("base prompt", installed.getString("BaseSkill"))
    }

    @Test fun acceptsInstalledDependencyWhenVersionSatisfiesRange() {
        val base = SkillBundle("BaseSkill", "1.2.0", "base", "base prompt", "local", emptyList(), emptyList())
        preferences.edit()
            .putString("skills", org.json.JSONObject().put("BaseSkill", "base prompt").toString())
            .putString("skill_metadata_v1", org.json.JSONObject().put("BaseSkill", SkillBundleParser.metadataJson(base)).toString())
            .commit()
        val dependent = bundle("1.0.0", "dependent prompt").copy(
            dependencies = listOf(SkillDependency("BaseSkill", ">=1.0.0"))
        )
        store.install(dependent)
        assertTrue(org.json.JSONObject(preferences.getString("skills", "{}")!!).has("CommunitySkill"))
    }

    @Test fun rejectsDependencyCycleBeforeInstall() {
        val other = SkillBundle(
            "OtherSkill", "1.0.0", "other", "other prompt", "local", emptyList(),
            listOf(SkillDependency("CommunitySkill", "*"))
        )
        preferences.edit()
            .putString("skills", org.json.JSONObject().put("OtherSkill", "other prompt").toString())
            .putString("skill_metadata_v1", org.json.JSONObject().put("OtherSkill", SkillBundleParser.metadataJson(other)).toString())
            .commit()
        val cyclic = bundle("1.0.0", "community prompt").copy(
            dependencies = listOf(SkillDependency("OtherSkill", "*"))
        )
        assertFalse(runCatching { store.install(cyclic) }.isSuccess)
        assertFalse(org.json.JSONObject(preferences.getString("skills", "{}")!!).has("CommunitySkill"))
    }

    @Test fun refusesCommunityUpdateThatBreaksAnInstalledDependent() {
        store.install(bundle("1.0.0", "base prompt"))
        val dependent = SkillBundle(
            "DependentSkill", "1.0.0", "dependent", "dependent prompt", "local", emptyList(),
            listOf(SkillDependency("CommunitySkill", "1.0.0"))
        )
        val skills = org.json.JSONObject(preferences.getString("skills", "{}")!!)
            .put("DependentSkill", "dependent prompt")
        val metadata = org.json.JSONObject(preferences.getString("skill_metadata_v1", "{}")!!)
            .put("DependentSkill", SkillBundleParser.metadataJson(dependent))
        preferences.edit().putString("skills", skills.toString()).putString("skill_metadata_v1", metadata.toString()).commit()
        assertFalse(runCatching { store.install(bundle("1.1.0", "updated prompt")) }.isSuccess)
        assertEquals("base prompt", org.json.JSONObject(preferences.getString("skills", "{}")!!).getString("CommunitySkill"))
    }

    @Test fun rejectsMissingDependenciesBeforeMutation() {
        val dependent = bundle("1.0.0", "prompt").copy(
            dependencies = listOf(SkillDependency("MissingDependency", "1.0.0"))
        )
        assertFalse(runCatching { store.install(dependent) }.isSuccess)
        assertFalse(org.json.JSONObject(preferences.getString("skills", "{}")!!).has("CommunitySkill"))
    }
}
