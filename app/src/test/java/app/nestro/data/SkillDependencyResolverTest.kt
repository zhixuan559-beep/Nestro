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
    @Test
    fun storedSkillExecutionRequiresAnEnabledValidDependencyClosure() {
        val installed = mapOf(
            "根技能" to "root prompt",
            "中间技能" to "middle prompt",
            "底层技能" to "leaf prompt"
        )
        val metadata = mapOf(
            "根技能" to JSONObject().put("version", "1.0.0").put(
                "dependencies", org.json.JSONArray().put(JSONObject().put("name", "中间技能").put("version", "^1.0.0"))
            ).toString(),
            "中间技能" to JSONObject().put("version", "1.0.0").put(
                "dependencies", org.json.JSONArray().put(JSONObject().put("name", "底层技能").put("version", "*"))
            ).toString(),
            "底层技能" to JSONObject().put("version", "1.0.0").toString()
        )
        assertTrue(SkillDependencyResolver.inspectInstalledSkill("根技能", "root prompt", installed, metadata).isSatisfied)
        assertFalse(SkillDependencyResolver.inspectInstalledSkill("根技能", "root prompt", installed, metadata, setOf("底层技能")).isSatisfied)
        assertEquals(setOf("底层技能"), SkillDependencyResolver.filterSatisfiedSkills(installed, metadata, setOf("中间技能")).keys)
    }

    @Test
    fun malformedStoredDependencyMetadataFailsClosed() {
        val installed = mapOf("损坏技能" to "prompt")
        val report = SkillDependencyResolver.inspectInstalledSkill(
            "损坏技能", "prompt", installed, mapOf("损坏技能" to "{not-json")
        )
        assertFalse(report.isSatisfied)
        assertTrue(report.issues.any { it.reason.contains("元数据损坏") })
        assertFalse(SkillDependencyResolver.filterSatisfiedSkills(installed, mapOf("损坏技能" to "{not-json")).containsKey("损坏技能"))
    }

    @Test
    fun rejectsNonSemverLeadingZerosAndExcessiveDependencyDepth() {
        assertFalse(SkillDependencyResolver.isValidVersion("01.0.0"))
        assertFalse(SkillDependencyResolver.isValidVersion("1.0.0-01"))
        assertTrue(SkillDependencyResolver.isValidVersion("1.0.0-rc.1"))

        val installed = linkedMapOf<String, String>()
        val metadata = linkedMapOf<String, String>()
        for (index in 1..35) {
            val name = "链路$index"
            installed[name] = "prompt"
            val dependency = if (index == 35) org.json.JSONArray()
                else org.json.JSONArray().put(JSONObject().put("name", "链路${index + 1}").put("version", "*"))
            metadata[name] = JSONObject().put("version", "1.0.0").put("dependencies", dependency).toString()
        }
        val report = SkillDependencyResolver.inspect(
            bundle(SkillDependency("链路1", "*")), installed, metadata
        )
        assertFalse(report.isSatisfied)
        assertTrue(report.issues.any { it.reason.contains("依赖深度超过") })
    }

    @Test
    fun detectsDependentsThatWouldBreakAfterVersionReplacement() {
        val installed = mapOf("目标技能" to "prompt", "下游技能" to "child prompt")
        val metadata = mapOf(
            "目标技能" to """{"version":"1.0.0"}""",
            "下游技能" to JSONObject().put("version", "1.0.0").put(
                "dependencies", org.json.JSONArray().put(
                    JSONObject().put("name", "目标技能").put("version", "^1.0.0")
                )
            ).toString()
        )
        assertEquals(listOf("下游技能"), SkillDependencyResolver.findIncompatibleDependents("目标技能", "2.0.0", installed, metadata))
        assertTrue(SkillDependencyResolver.findIncompatibleDependents("目标技能", "1.5.0", installed, metadata).orEmpty().isEmpty())
        assertEquals(null, SkillDependencyResolver.findIncompatibleDependents("目标技能", "2.0.0", installed, metadata + ("下游技能" to "{bad-json")))
    }

}
