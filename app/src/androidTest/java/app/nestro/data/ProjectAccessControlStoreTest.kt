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
            assertEquals("no_matching_grant_or_invalid_store", audit.reason)
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

    @Test fun malformedGrantDataIsRejectedInsteadOfPartiallyAccepted() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            ProjectAccessControlStore.decodeGrants("""[{"source":"a","target":"b","access":["read","read"],"granted_at":1}]""")
        }
    }
}
