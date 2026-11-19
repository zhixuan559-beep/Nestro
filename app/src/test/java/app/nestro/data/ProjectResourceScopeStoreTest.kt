package app.nestro.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectResourceScopeStoreTest {
    @Test
    fun newlyCreatedResourceDefaultsToCurrentProjectOnly() {
        val scopes = ProjectResourceScopeStore.assignNewResourceToProject(
            emptyMap(), ProjectResourceKind.EXPERT, "审查员", "project-a"
        )

        assertTrue(ProjectResourceScopeStore.isVisible(scopes, ProjectResourceKind.EXPERT, "审查员", "project-a"))
        assertFalse(ProjectResourceScopeStore.isVisible(scopes, ProjectResourceKind.EXPERT, "审查员", "project-b"))
    }

    @Test
    fun legacyResourceWithoutScopeRemainsGlobal() {
        assertTrue(ProjectResourceScopeStore.isVisible(emptyMap(), ProjectResourceKind.SKILL, "legacy-skill", "project-a"))
        assertTrue(ProjectResourceScopeStore.isVisible(emptyMap(), ProjectResourceKind.SKILL, "legacy-skill", "project-b"))
    }

    @Test
    fun renamingResourcePreservesItsProjectBoundary() {
        val initial = ProjectResourceScopeStore.assignNewResourceToProject(
            emptyMap(), ProjectResourceKind.SKILL, "old-name", "project-a"
        )

        val renamed = ProjectResourceScopeStore.renameResource(
            initial, ProjectResourceKind.SKILL, "old-name", "new-name"
        )

        assertFalse(ProjectResourceScopeStore.isVisible(renamed, ProjectResourceKind.SKILL, "old-name", "project-a"))
        assertTrue(ProjectResourceScopeStore.isVisible(renamed, ProjectResourceKind.SKILL, "new-name", "project-a"))
        assertFalse(ProjectResourceScopeStore.isVisible(renamed, ProjectResourceKind.SKILL, "new-name", "project-b"))
    }

    @Test
    fun legacyGlobalResourceRemainsGlobalAfterRename() {
        val renamed = ProjectResourceScopeStore.renameResource(
            emptyMap(), ProjectResourceKind.EXPERT, "legacy", "legacy-renamed"
        )

        assertTrue(renamed.isEmpty())
        assertTrue(ProjectResourceScopeStore.isVisible(renamed, ProjectResourceKind.EXPERT, "legacy-renamed", "project-a"))
        assertTrue(ProjectResourceScopeStore.isVisible(renamed, ProjectResourceKind.EXPERT, "legacy-renamed", "project-b"))
    }

    @Test
    fun exclusiveResourcesAreIdentifiedBeforeProjectDeletion() {
        val scopes = mapOf(
            "expert:private" to setOf("project-a"),
            "skill:shared" to setOf("project-a", "project-b"),
            "team:global" to setOf("*")
        )

        assertEquals(setOf("expert:private"), ProjectResourceScopeStore.resourcesExclusiveToProject(scopes, "project-a"))
        assertTrue(ProjectResourceScopeStore.resourcesExclusiveToProject(scopes, "project-b").isEmpty())
    }
}
