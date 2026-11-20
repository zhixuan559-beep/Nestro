package app.nestro.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class SkillDependencyResolverTest {
    private fun bundle(vararg dependencies: SkillDependency) = SkillBundle(
        name = "分析技能",
        version = "1.0.0",
        description = "",
        prompt = "分析",
        source = "test",
        permissions = emptyList(),
        dependencies = dependencies.toList()
    )

    @Test
    fun resolvesInstalledDependenciesAndVersionRanges() {
        val report = SkillDependencyResolver.inspect(
            bundle(SkillDependency("搜索", "^1.2.0"), SkillDependency("格式化", ">=2.0")),
            mapOf("搜索" to "prompt", "格式化" to "prompt"),
            mapOf("搜索" to """{"version":"1.8.0"}""", "格式化" to """{"version":"2.3.0"}""")
        )
        assertTrue(report.isSatisfied)
        assertEquals(setOf("搜索", "格式化"), report.resolved.toSet())
    }

    @Test
    fun reportsMissingAndIncompatibleDependencies() {
        val report = SkillDependencyResolver.inspect(
            bundle(SkillDependency("缺失", "*"), SkillDependency("旧版本", ">=2.0.0")),
            mapOf("旧版本" to "prompt"),
            mapOf("旧版本" to """{"version":"1.5.0"}""")
        )
        assertFalse(report.isSatisfied)
        assertEquals(2, report.issues.size)
        assertTrue(report.issues.any { it.skillName == "缺失" && it.reason == "尚未安装" })
        assertTrue(report.issues.any { it.skillName == "旧版本" && it.reason.contains("1.5.0") })
    }

    @Test
    fun detectsTransitiveDependencyCycle() {
        val report = SkillDependencyResolver.inspect(
            bundle(SkillDependency("中间技能", "*")),
            mapOf("中间技能" to "prompt", "回指技能" to "prompt"),
            mapOf(
                "中间技能" to JSONObject().put("version", "1.0.0").put(
                    "dependencies", org.json.JSONArray().put(JSONObject().put("name", "回指技能").put("version", "*"))
                ).toString(),
                "回指技能" to JSONObject().put("version", "1.0.0").put(
                    "dependencies", org.json.JSONArray().put(JSONObject().put("name", "分析技能").put("version", "*"))
                ).toString()
            )
        )
        assertFalse(report.isSatisfied)
        assertTrue(report.issues.any { it.reason == "依赖链形成循环" })
    }

    @Test
    fun rejectsOversizedVersionComponentsAndMalformedDependencyMetadata() {
        assertFalse(SkillDependencyResolver.isValidVersion("99999999999999999999.0.0"))
        val report = SkillDependencyResolver.inspect(
            bundle(SkillDependency("损坏元数据", "*")),
            mapOf("损坏元数据" to "prompt"),
            mapOf("损坏元数据" to """{"version":"1.0.0","dependencies":"not-an-array"}""")
        )
        assertFalse(report.isSatisfied)
        assertTrue(report.issues.single().reason == "依赖元数据格式无效")
    }

    @Test
    fun supportsCaretTildeAndLegacyTwoPartMinimumVersions() {
        assertTrue(SkillDependencyResolver.versionSatisfies("1.8.0", "^1.2.0"))
        assertFalse(SkillDependencyResolver.versionSatisfies("2.0.0", "^1.2.0"))
        assertTrue(SkillDependencyResolver.versionSatisfies("1.2.9", "~1.2.0"))
        assertFalse(SkillDependencyResolver.versionSatisfies("1.3.0", "~1.2.0"))
        assertTrue(SkillDependencyResolver.versionSatisfies("1.4.0", ">=1.0"))
        assertFalse(SkillDependencyResolver.isValidConstraint("latest"))
    }
}
