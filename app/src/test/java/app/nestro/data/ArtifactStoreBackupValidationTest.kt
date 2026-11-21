package app.nestro.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ArtifactStoreBackupValidationTest {
    private fun row(
        id: String = "123e4567-e89b-12d3-a456-426614174000",
        projectId: String = "project-a",
        title: String = "Draft",
        version: Int = 1,
        content: String = "# Draft"
    ) = JSONObject()
        .put("id", id)
        .put("projectId", projectId)
        .put("title", title)
        .put("version", version)
        .put("createdAt", 1L)
        .put("content", content)

    @Test
    fun acceptsValidProjectScopedArtifactBackup() {
        val result = ArtifactStore.validateBackup(
            JSONArray().put(row()),
            setOf("project-a", "default")
        )

        assertEquals(1, result.length())
        assertEquals("project-a", result.getJSONObject(0).getString("projectId"))
        assertEquals("# Draft", result.getJSONObject(0).getString("content"))
    }

    @Test
    fun rejectsArtifactsOutsideRestoredProjects() {
        assertThrows(IllegalArgumentException::class.java) {
            ArtifactStore.validateBackup(JSONArray().put(row(projectId = "project-b")), setOf("project-a"))
        }
    }

    @Test
    fun rejectsDuplicateArtifactIds() {
        val id = "123e4567-e89b-12d3-a456-426614174000"
        assertThrows(IllegalArgumentException::class.java) {
            ArtifactStore.validateBackup(
                JSONArray().put(row(id = id)).put(row(id = id, title = "Another")),
                setOf("project-a")
            )
        }
    }

    @Test
    fun rejectsEmptyOrOversizedArtifactContent() {
        assertThrows(IllegalArgumentException::class.java) {
            ArtifactStore.validateBackup(JSONArray().put(row(content = "")), setOf("project-a"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArtifactStore.validateBackup(JSONArray().put(row(content = "x".repeat(1_500_001))), setOf("project-a"))
        }
    }

    @Test
    fun rejectsInvalidVersionNumbers() {
        assertThrows(IllegalArgumentException::class.java) {
            ArtifactStore.validateBackup(JSONArray().put(row(version = 0)), setOf("project-a"))
        }
    }
}
