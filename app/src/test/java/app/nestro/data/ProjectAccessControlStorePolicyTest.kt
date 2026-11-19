package app.nestro.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectAccessControlStorePolicyTest {
    @Test
    fun writeGrantMustAlsoIncludeReadWhenDecoding() {
        val raw = """[{"source":"project-a","target":"project-b","access":["write"],"granted_at":1}]"""

        val error = runCatching { ProjectAccessControlStore.decodeGrants(raw) }.exceptionOrNull()

        assertEquals("WRITE 授权必须同时包含 READ", error?.message)
    }

    @Test
    fun writeGrantMustAlsoIncludeReadWhenEncoding() {
        val grant = ProjectAccessControlStore.Grant(
            sourceProjectId = "project-a",
            targetProjectId = "project-b",
            access = setOf(ProjectAccessControlStore.Access.WRITE),
            grantedAt = 1L
        )

        val error = runCatching { ProjectAccessControlStore.encodeGrants(listOf(grant)) }.exceptionOrNull()

        assertEquals("授权参数无效或 WRITE 未包含 READ", error?.message)
    }

    @Test
    fun readOnlyGrantRemainsValid() {
        val grant = ProjectAccessControlStore.Grant(
            sourceProjectId = "project-a",
            targetProjectId = "project-b",
            access = setOf(ProjectAccessControlStore.Access.READ),
            grantedAt = 1L
        )

        val decoded = ProjectAccessControlStore.decodeGrants(ProjectAccessControlStore.encodeGrants(listOf(grant)))

        assertEquals(listOf(grant), decoded)
    }

    @Test
    fun readWriteGrantRemainsValid() {
        val grant = ProjectAccessControlStore.Grant(
            sourceProjectId = "project-a",
            targetProjectId = "project-b",
            access = setOf(ProjectAccessControlStore.Access.READ, ProjectAccessControlStore.Access.WRITE),
            grantedAt = 1L
        )

        val decoded = ProjectAccessControlStore.decodeGrants(ProjectAccessControlStore.encodeGrants(listOf(grant)))

        assertEquals(listOf(grant), decoded)
    }
}
