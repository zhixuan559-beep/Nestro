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

    @Test fun rejectsMissingDependenciesBeforeMutation() {
        val dependent = bundle("1.0.0", "prompt").copy(
            dependencies = listOf(SkillDependency("MissingDependency", "1.0.0"))
        )
        assertFalse(runCatching { store.install(dependent) }.isSuccess)
        assertFalse(org.json.JSONObject(preferences.getString("skills", "{}")!!).has("CommunitySkill"))
    }
}
