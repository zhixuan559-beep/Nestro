package app.nestro.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ProjectResourceScopeStoreTest {
    @Test fun legacyResourcesRemainGlobalButExplicitScopesStayIsolated() {
        val scopes = ProjectResourceScopeStore.decode("""{"skill:private":["project-a"]}""")
        assertTrue(ProjectResourceScopeStore.isVisible(scopes, ProjectResourceKind.EXPERT, "legacy", "project-b"))
        assertTrue(ProjectResourceScopeStore.isVisible(scopes, ProjectResourceKind.SKILL, "private", "project-a"))
        assertFalse(ProjectResourceScopeStore.isVisible(scopes, ProjectResourceKind.SKILL, "private", "project-b"))
        assertTrue(
            ProjectResourceScopeStore.isVisibleFromProjects(
                scopes, ProjectResourceKind.SKILL, "private", "project-b", setOf("project-a")
            )
        )
    }

    @Test fun writeRequiresPermissionFromEveryForeignOwner() {
        val scopes = mapOf("expert:shared" to setOf("project-a", "project-b"))
        assertFalse(ProjectResourceScopeStore.canWriteFromProject(
            scopes, ProjectResourceKind.EXPERT, "shared", "project-c", setOf("project-a")
        ))
        assertTrue(ProjectResourceScopeStore.canWriteFromProject(
            scopes, ProjectResourceKind.EXPERT, "shared", "project-c", setOf("project-a", "project-b")
        ))
        assertFalse(ProjectResourceScopeStore.canWriteFromProject(
            mapOf("expert:orphan" to emptySet()), ProjectResourceKind.EXPERT, "orphan", "project-c", emptySet()
        ))
        assertTrue(ProjectResourceScopeStore.canWriteFromProject(
            mapOf("expert:global" to setOf("*")), ProjectResourceKind.EXPERT, "global", "project-c", emptySet()
        ))
    }

    @Test fun duplicationAddsTargetWithoutChangingSourceAndRemovalFailsClosed() {
        val original = mapOf("workflow:w1" to setOf("source"))
        val duplicated = ProjectResourceScopeStore.duplicateProjectScope(original, "source", "copy")
        assertEquals(setOf("source", "copy"), duplicated.getValue("workflow:w1"))
        val removed = ProjectResourceScopeStore.removeProjectScope(duplicated, "source")
        assertEquals(setOf("copy"), removed.getValue("workflow:w1"))
        val orphaned = ProjectResourceScopeStore.removeProjectScope(removed, "copy")
        assertEquals(emptySet<String>(), orphaned.getValue("workflow:w1"))
        assertFalse(ProjectResourceScopeStore.isVisible(orphaned, ProjectResourceKind.WORKFLOW, "w1", "any"))
    }

    @Test fun malformedResourceKeysAndScopesAreRejectedOnBothPaths() {
        assertThrows(IllegalArgumentException::class.java) {
            ProjectResourceScopeStore.encode(mapOf("unknown:item" to setOf("project-a")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProjectResourceScopeStore.encode(mapOf("skill:" to setOf("project-a")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProjectResourceScopeStore.decode("""{"skill:item":["*","project-a"]}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProjectResourceScopeStore.decode("""{"skill:item":["project-a","project-a"]}""")
        }
    }
    @Test fun teamVisibilityRequiresEveryMemberToBeVisibleInTheTargetProject() {
        val scopes = mapOf(
            "team:team-a" to setOf("project-b"),
            "expert:member-a" to setOf("project-b"),
            "expert:member-b" to setOf("project-a")
        )
        assertFalse(ProjectResourceScopeStore.isTeamVisibleFromProjects(
            scopes, "team-a", listOf("member-a", "member-b"), "project-b", emptySet()
        ))
        assertTrue(ProjectResourceScopeStore.isTeamVisibleFromProjects(
            scopes, "team-a", listOf("member-a", "member-b"), "project-b", setOf("project-a")
        ))
        assertFalse(ProjectResourceScopeStore.isTeamVisibleFromProjects(
            scopes, "team-a", listOf("member-a"), "project-b", emptySet()
        ))
    }

    @Test fun renamingAndRemovingResourcesPreservesScopeWithoutMakingThemGlobal() {
        val original = mapOf("expert:writer" to setOf("project-a"))
        val renamed = ProjectResourceScopeStore.renameResourceScope(
            original, ProjectResourceKind.EXPERT, "writer", "writer-v2"
        )
        assertFalse("expert:writer" in renamed)
        assertEquals(setOf("project-a"), renamed.getValue("expert:writer-v2"))
        val removed = ProjectResourceScopeStore.removeResourceScope(
            renamed, ProjectResourceKind.EXPERT, "writer-v2"
        )
        assertFalse("expert:writer-v2" in removed)
        assertTrue(ProjectResourceScopeStore.isVisible(removed, ProjectResourceKind.EXPERT, "writer-v2", "project-b"))
    }

    @Test fun renamingCannotOverwriteAnExistingResourceScope() {
        val scopes = mapOf(
            "skill:old" to setOf("project-a"),
            "skill:new" to setOf("project-b")
        )
        assertThrows(IllegalArgumentException::class.java) {
            ProjectResourceScopeStore.renameResourceScope(scopes, ProjectResourceKind.SKILL, "old", "new")
        }
    }

    @Test fun projectDeletionCanDetectResourcesThatWouldBecomeOrphaned() {
        val scopes = mapOf(
            "expert:private" to setOf("project-a"),
            "skill:shared" to setOf("project-a", "project-b"),
            "workflow:global" to setOf("*")
        )
        assertEquals(
            setOf("expert:private"),
            ProjectResourceScopeStore.resourcesOwnedOnlyByProject(scopes, "project-a")
        )
        val afterRemovingA = ProjectResourceScopeStore.removeProjectScope(scopes, "project-a")
        assertEquals(setOf("project-b"), afterRemovingA.getValue("skill:shared"))
    }

}
