package app.nestro.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillDependencyRuntimePolicyTest {
    @Test fun allowsLegacySkillsWithoutDependencyMetadata() {
        val skills = mapOf("legacy" to "prompt")
        assertEquals(skills, SkillDependencyResolver.filterSatisfiedSkills(skills, emptyMap()))
    }

    @Test fun excludesMissingDisabledAndVersionIncompatibleDependencies() {
        val skills = mapOf("root" to "root", "middle" to "middle", "disabled" to "disabled")
        val metadata = mapOf(
            "root" to """{"version":"1.0.0","dependencies":[{"name":"middle","version":"^2.0.0"}]}""",
            "middle" to """{"version":"1.0.0","dependencies":[{"name":"disabled","version":"*"}]}""",
            "disabled" to """{"version":"1.0.0"}"""
        )
        assertTrue(SkillDependencyResolver.inspectInstalledSkill("root", skills, metadata).isNotEmpty())
        assertTrue(SkillDependencyResolver.inspectInstalledSkill("middle", skills, metadata, setOf("disabled")).isNotEmpty())
        assertFalse(SkillDependencyResolver.filterSatisfiedSkills(skills, metadata, setOf("disabled")).containsKey("root"))
    }

    @Test fun rejectsMalformedMetadataAndDependencyCycles() {
        val malformed = mapOf("bad" to "{not-json")
        assertTrue(SkillDependencyResolver.inspectInstalledSkill("bad", mapOf("bad" to "prompt"), malformed).isNotEmpty())
        val skills = mapOf("a" to "a", "b" to "b")
        val metadata = mapOf(
            "a" to """{"dependencies":[{"name":"b","version":"*"}]}""",
            "b" to """{"dependencies":[{"name":"a","version":"*"}]}"""
        )
        assertTrue(SkillDependencyResolver.inspectInstalledSkill("a", skills, metadata).any { it.contains("循环") })
    }
}
