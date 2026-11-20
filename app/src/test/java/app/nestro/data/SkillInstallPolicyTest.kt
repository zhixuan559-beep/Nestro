package app.nestro.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SkillInstallPolicyTest {
    private fun bundle(version: String = "1.0.0", dependencies: List<SkillDependency> = emptyList()) =
        SkillBundle("社区助手", version, "测试", "执行测试", "测试目录", emptyList(), dependencies)

    @Test
    fun rejectsCommunityOverwriteOfLocalSkill() {
        val result = SkillInstallPolicy.inspectCommunityRelease(
            bundle(), mapOf("社区助手" to "本地指令"),
            mapOf("社区助手" to JSONObject().put("version", "1.0.0").put("source", "本地文件").toString())
        )
        assertFalse(result.canInstall)
        assertTrue(result.issues.any { it.contains("不得覆盖") })
    }

    @Test
    fun allowsOnlyStrictlyNewerCommunityUpdate() {
        val skills = mapOf("社区助手" to "旧指令")
        val metadata = mapOf("社区助手" to JSONObject().put("version", "1.2.0")
            .put("source", SkillInstallPolicy.VERIFIED_COMMUNITY_SOURCE).toString())
        assertTrue(SkillInstallPolicy.inspectCommunityRelease(bundle("1.3.0"), skills, metadata).canInstall)
        assertFalse(SkillInstallPolicy.inspectCommunityRelease(bundle("1.2.0"), skills, metadata).canInstall)
        assertFalse(SkillInstallPolicy.inspectCommunityRelease(bundle("1.1.9"), skills, metadata).canInstall)
    }

    @Test
    fun rejectsMissingOrDisabledDependenciesBeforeInstall() {
        val required = bundle(dependencies = listOf(SkillDependency("搜索组件", "^2.0.0")))
        val missing = SkillInstallPolicy.inspectCommunityRelease(required, emptyMap(), emptyMap())
        assertFalse(missing.canInstall)
        assertTrue(missing.issues.any { it.contains("尚未安装") })

        val installed = mapOf("搜索组件" to "prompt")
        val metadata = mapOf("搜索组件" to JSONObject().put("version", "2.1.0").toString())
        val disabled = SkillInstallPolicy.inspectCommunityRelease(required, installed, metadata, setOf("搜索组件"))
        assertFalse(disabled.canInstall)
    }

    @Test
    fun acceptsReleaseWhenDependencyClosureIsInstalledAndEnabled() {
        val required = bundle(dependencies = listOf(SkillDependency("搜索组件", "^2.0.0")))
        val result = SkillInstallPolicy.inspectCommunityRelease(
            required,
            mapOf("搜索组件" to "prompt"),
            mapOf("搜索组件" to JSONObject().put("version", "2.1.0").put("dependencies", JSONArray()).toString())
        )
        assertTrue(result.issues.toString(), result.canInstall)
    }
}
