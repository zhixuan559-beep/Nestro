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

    @Test fun findsDirectDependentsAndFailsClosedOnCorruptStoredMetadata() {
        val skills = mapOf(
            "base" to "base prompt",
            "consumer" to "consumer prompt",
            "unrelated" to "unrelated prompt"
        )
        val metadata = mapOf(
            "base" to """{"version":"1.0.0"}""",
            "consumer" to """{"version":"1.0.0","dependencies":[{"name":"base","version":"^1.0.0"}]}"""
        )
        assertEquals(listOf("consumer"), SkillDependencyResolver.findDependents("base", skills, metadata))
        assertEquals(emptyList<String>(), SkillDependencyResolver.findDependents("unrelated", skills, metadata))
        assertEquals(
            null,
            SkillDependencyResolver.findDependents("base", skills, metadata + ("broken" to "{not-json"))
        )
    }

    @Test fun candidateDependenciesRequireVisibleEnabledCompatibleInstalledSkills() {
        val installed = mapOf("base" to "base prompt")
        val metadata = mapOf("base" to """{"version":"1.5.0"}""")
        val candidate = SkillBundle(
            name = "consumer",
            version = "1.0.0",
            description = "",
            prompt = "consumer prompt",
            source = "local",
            permissions = emptyList(),
            dependencies = listOf(SkillDependency("base", "^1.0.0"))
        )
        assertTrue(SkillDependencyResolver.inspectCandidate(candidate, installed, metadata).isEmpty())
        assertTrue(SkillDependencyResolver.inspectCandidate(candidate, installed, metadata, setOf("base")).isNotEmpty())
        assertTrue(
            SkillDependencyResolver.inspectCandidate(
                candidate,
                installed,
                mapOf("base" to """{"version":"0.9.0"}""")
            ).isNotEmpty()
        )
        assertTrue(SkillDependencyResolver.inspectCandidate(candidate, emptyMap(), emptyMap()).isNotEmpty())
    }

    @Test fun replacementCannotBreakInstalledDependentsAndCorruptMetadataFailsClosed() {
        val skills = mapOf("base" to "base prompt", "consumer" to "consumer prompt")
        val metadata = mapOf(
            "base" to """{"version":"1.0.0"}""",
            "consumer" to """{"dependencies":[{"name":"base","version":"^1.0.0"}]}"""
        )
        assertEquals(emptyList<String>(), SkillDependencyResolver.findIncompatibleDependents("base", "1.5.0", skills, metadata))
        assertEquals(listOf("consumer"), SkillDependencyResolver.findIncompatibleDependents("base", "2.0.0", skills, metadata))
        assertEquals(
            null,
            SkillDependencyResolver.findIncompatibleDependents("base", "2.0.0", skills, metadata + ("consumer" to "{bad"))
        )
    }
}
