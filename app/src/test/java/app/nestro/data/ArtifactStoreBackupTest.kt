package app.nestro.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ArtifactStoreBackupTest {
    private fun row(id: String, projectId: String = "project-a") = JSONObject()
        .put("id", id)
        .put("projectId", projectId)
        .put("title", "设计稿")
        .put("version", 1)
        .put("createdAt", 1L)
        .put("content", "# 产物")

    @Test
    fun validatesAndNormalizesProjectScopedArtifact() {
        val rows = JSONArray().put(row("123e4567-e89b-12d3-a456-426614174000"))
        val validated = ArtifactStore.validateBackup(rows, setOf("project-a", "default"))
        assertEquals(1, validated.length())
        assertEquals("project-a", validated.getJSONObject(0).getString("projectId"))
        assertEquals("# 产物", validated.getJSONObject(0).getString("content"))
    }

    @Test
    fun rejectsArtifactFromUnknownProject() {
        val rows = JSONArray().put(row("123e4567-e89b-12d3-a456-426614174000", "project-foreign"))
        assertThrows(IllegalArgumentException::class.java) {
            ArtifactStore.validateBackup(rows, setOf("project-a", "default"))
        }
    }

    @Test
    fun rejectsDuplicateArtifactIds() {
        val id = "123e4567-e89b-12d3-a456-426614174000"
        val rows = JSONArray().put(row(id)).put(row(id))
        assertThrows(IllegalArgumentException::class.java) {
            ArtifactStore.validateBackup(rows, setOf("project-a"))
        }
    }

    @Test
    fun acceptsMoreThanTwoHundredArtifactsAcrossMultipleProjects() {
        val rows = JSONArray()
        repeat(201) { index ->
            val id = "00000000-0000-0000-0000-" + index.toString().padStart(12, '0')
            val projectId = if (index % 2 == 0) "project-a" else "project-b"
            rows.put(row(id, projectId))
        }
        assertEquals(201, ArtifactStore.validateBackup(rows, setOf("project-a", "project-b")).length())
    }

    @Test
    fun rejectsArtifactOverSizeLimit() {
        val content = "x".repeat(1_500_001)
        val rows = JSONArray().put(row("123e4567-e89b-12d3-a456-426614174000").put("content", content))
        assertThrows(IllegalArgumentException::class.java) {
            ArtifactStore.validateBackup(rows, setOf("project-a"))
        }
    }
}
