package app.nestro.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectAccessControlStoreTest {
    @Test fun missingGrantDeniesAndAuditsAttempt() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("project_access_control_test", Context.MODE_PRIVATE)
        try {
            prefs.edit().clear().commit()
            assertFalse(ProjectAccessControlStore.authorize(prefs, "source", "target", ProjectAccessControlStore.Access.READ, 10L))
            val audit = ProjectAccessControlStore.loadAudit(prefs).single()
            assertFalse(audit.allowed)
            assertEquals("no_matching_grant", audit.reason)
        } finally { prefs.edit().clear().commit() }
    }

    @Test fun grantsAreDirectionalAndReadDoesNotImplyWrite() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("project_access_direction_test", Context.MODE_PRIVATE)
        try {
            prefs.edit().clear().commit()
            ProjectAccessControlStore.grant(prefs, "source", "target", setOf(ProjectAccessControlStore.Access.READ), 20L)
            assertTrue(ProjectAccessControlStore.authorize(prefs, "source", "target", ProjectAccessControlStore.Access.READ, 21L))
            assertFalse(ProjectAccessControlStore.authorize(prefs, "source", "target", ProjectAccessControlStore.Access.WRITE, 22L))
            assertFalse(ProjectAccessControlStore.authorize(prefs, "target", "source", ProjectAccessControlStore.Access.READ, 23L))
            assertEquals(4, ProjectAccessControlStore.loadAudit(prefs).size)
        } finally { prefs.edit().clear().commit() }
    }

    @Test fun revocationImmediatelyRemovesGrant() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("project_access_revoke_test", Context.MODE_PRIVATE)
        try {
            prefs.edit().clear().commit()
            ProjectAccessControlStore.grant(prefs, "source", "target", setOf(ProjectAccessControlStore.Access.READ, ProjectAccessControlStore.Access.WRITE), 30L)
            assertTrue(ProjectAccessControlStore.authorize(prefs, "source", "target", ProjectAccessControlStore.Access.WRITE, 31L))
            ProjectAccessControlStore.revoke(prefs, "source", "target", 32L)
            assertFalse(ProjectAccessControlStore.authorize(prefs, "source", "target", ProjectAccessControlStore.Access.READ, 33L))
        } finally { prefs.edit().clear().commit() }
    }

    @Test fun readableSourceProjectsOnlyIncludesExplicitReadGrantsAndAuditsThem() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("project_access_readable_projects_test", Context.MODE_PRIVATE)
        try {
            prefs.edit().clear().commit()
            ProjectAccessControlStore.grant(prefs, "source-a", "target", setOf(ProjectAccessControlStore.Access.READ), 35L)
            // WRITE-only 授权在生产侧已不存在（7449ef32 起 WRITE 必须同时包含 READ，
            // 再 grant WRITE-only 会在 encodeGrants 处抛错）；source-b 改为一次被拒的 READ
            // 授权尝试：无授权故被拒、审计留痕，readableSourceProjects 仍应排除它。
            assertFalse(ProjectAccessControlStore.authorize(prefs, "source-b", "target", ProjectAccessControlStore.Access.READ, 36L))
            assertEquals(setOf("source-a"), ProjectAccessControlStore.readableSourceProjects(
                prefs, "target", setOf("source-a", "source-b", "target"), 37L
            ))
            val attempts = ProjectAccessControlStore.loadAudit(prefs).takeLast(2)
            assertEquals(setOf("source-a", "source-b"), attempts.map { it.sourceProjectId }.toSet())
        } finally { prefs.edit().clear().commit() }
    }

    @Test fun resourceVisibilityIncludesOnlyExplicitlyReadableSourceProjects() {
        val scopes = ProjectResourceScopeStore.setProjectScope(
            emptyMap(), ProjectResourceKind.EXPERT, "expert-a", setOf("source")
        )
        assertFalse(ProjectResourceScopeStore.isVisibleFromProjects(
            scopes, ProjectResourceKind.EXPERT, "expert-a", "target", emptySet()
        ))
        assertTrue(ProjectResourceScopeStore.isVisibleFromProjects(
            scopes, ProjectResourceKind.EXPERT, "expert-a", "target", setOf("source")
        ))
        assertTrue(ProjectResourceScopeStore.isVisibleFromProjects(
            scopes, ProjectResourceKind.EXPERT, "expert-a", "source", emptySet()
        ))
    }

    @Test fun grantedWritableProjectsAreSeparateFromReadOnlyGrants() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("project_access_writable_projects_test", Context.MODE_PRIVATE)
        try {
            prefs.edit().clear().commit()
            ProjectAccessControlStore.grant(prefs, "read-only", "target", setOf(ProjectAccessControlStore.Access.READ), 38L)
            ProjectAccessControlStore.grant(prefs, "read-write", "target", setOf(ProjectAccessControlStore.Access.READ, ProjectAccessControlStore.Access.WRITE), 39L)
            assertEquals(setOf("read-write"), ProjectAccessControlStore.grantedWritableSourceProjects(prefs, "target"))
        } finally { prefs.edit().clear().commit() }
    }

    @Test fun sharedResourceRequiresWriteFromEveryForeignOwner() {
        val scopes = ProjectResourceScopeStore.setProjectScope(emptyMap(), ProjectResourceKind.EXPERT, "shared", setOf("source-a", "source-b"))
        assertFalse(ProjectResourceScopeStore.canWriteFromProject(scopes, ProjectResourceKind.EXPERT, "shared", "target", setOf("source-a")))
        assertTrue(ProjectResourceScopeStore.canWriteFromProject(scopes, ProjectResourceKind.EXPERT, "shared", "target", setOf("source-a", "source-b")))
        assertTrue(ProjectResourceScopeStore.canWriteFromProject(scopes, ProjectResourceKind.EXPERT, "shared", "source-a", setOf("source-b")))
        assertFalse(ProjectResourceScopeStore.canWriteFromProject(scopes, ProjectResourceKind.EXPERT, "shared", "unrelated", emptySet()))
    }

    @Test fun legacyAndGlobalResourcesAreWritableButOrphanedScopesFailClosed() {
        assertTrue(ProjectResourceScopeStore.canWriteFromProject(emptyMap(), ProjectResourceKind.SKILL, "legacy", "target", emptySet()))
        val global = ProjectResourceScopeStore.setGlobal(emptyMap(), ProjectResourceKind.SKILL, "global")
        assertTrue(ProjectResourceScopeStore.canWriteFromProject(global, ProjectResourceKind.SKILL, "global", "target", emptySet()))
        val orphan = mapOf(ProjectResourceScopeStore.resourceKey(ProjectResourceKind.SKILL, "orphan") to emptySet<String>())
        assertFalse(ProjectResourceScopeStore.canWriteFromProject(orphan, ProjectResourceKind.SKILL, "orphan", "target", emptySet()))
    }

    @Test fun malformedGrantDataIsRejectedInsteadOfPartiallyAccepted() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            ProjectAccessControlStore.decodeGrants("""[{"source":"a","target":"b","access":["read","read"],"granted_at":1}]""")
        }
    }

    @Test fun grantAndRevokeAuditEachPermissionInsteadOfMislabelingCombinedAccess() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("project_access_audit_scope_test", Context.MODE_PRIVATE)
        try {
            prefs.edit().clear().commit()
            ProjectAccessControlStore.grant(
                prefs, "source", "target",
                setOf(ProjectAccessControlStore.Access.READ, ProjectAccessControlStore.Access.WRITE), 40L
            )
            val granted = ProjectAccessControlStore.loadAudit(prefs)
            assertEquals(setOf(ProjectAccessControlStore.Access.READ, ProjectAccessControlStore.Access.WRITE), granted.map { it.access }.toSet())
            assertTrue(granted.all { it.allowed && it.reason == "grant" })

            ProjectAccessControlStore.revoke(prefs, "source", "target", 41L)
            val revoked = ProjectAccessControlStore.loadAudit(prefs).takeLast(2)
            assertEquals(setOf(ProjectAccessControlStore.Access.READ, ProjectAccessControlStore.Access.WRITE), revoked.map { it.access }.toSet())
            assertTrue(revoked.all { it.allowed && it.reason == "revoke" })
        } finally { prefs.edit().clear().commit() }
    }

    @Test fun corruptAuditLogPreventsGrantAndFailsClosedDuringAuthorization() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("project_access_corrupt_audit_test", Context.MODE_PRIVATE)
        try {
            prefs.edit().clear().putString("project_access_audit_v1", "not-json").commit()
            org.junit.Assert.assertThrows(RuntimeException::class.java) {
                ProjectAccessControlStore.grant(
                    prefs, "source", "target", setOf(ProjectAccessControlStore.Access.READ), 50L
                )
            }
            assertTrue(ProjectAccessControlStore.loadGrants(prefs).isEmpty())
            assertFalse(ProjectAccessControlStore.authorize(
                prefs, "source", "target", ProjectAccessControlStore.Access.READ, 51L
            ))
        } finally { prefs.edit().clear().commit() }
    }
    @Test fun deletingProjectRemovesIncomingAndOutgoingGrantsAndAuditsThem() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("project_access_delete_test", Context.MODE_PRIVATE)
        try {
            prefs.edit().clear().commit()
            ProjectAccessControlStore.grant(
                prefs, "deleted", "reader",
                setOf(ProjectAccessControlStore.Access.READ), 40L
            )
            ProjectAccessControlStore.grant(
                prefs, "writer", "deleted",
                setOf(ProjectAccessControlStore.Access.READ, ProjectAccessControlStore.Access.WRITE), 41L
            )
            ProjectAccessControlStore.grant(
                prefs, "kept", "other",
                setOf(ProjectAccessControlStore.Access.READ), 42L
            )

            ProjectAccessControlStore.removeProject(prefs, "deleted", 50L)

            val grants = ProjectAccessControlStore.loadGrants(prefs)
            assertEquals(1, grants.size)
            assertEquals("kept", grants.single().sourceProjectId)
            assertEquals("other", grants.single().targetProjectId)
            val deletedAudit = ProjectAccessControlStore.loadAudit(prefs).filter { it.reason == "project_deleted" }
            assertEquals(3, deletedAudit.size)
            assertTrue(deletedAudit.all { it.at == 50L && (it.sourceProjectId == "deleted" || it.targetProjectId == "deleted") })
            assertTrue(ProjectAccessControlStore.grantedReadableSourceProjects(prefs, "reader").isEmpty())
            assertTrue(ProjectAccessControlStore.grantedWritableSourceProjects(prefs, "deleted").isEmpty())
        } finally {
            prefs.edit().clear().commit()
        }
    }

}
