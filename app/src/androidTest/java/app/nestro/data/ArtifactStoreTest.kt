package app.nestro.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ArtifactStoreTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun clearArtifactStorage() {
        File(context.filesDir, "artifacts").deleteRecursively()
    }

    @After
    fun removeArtifactsCreatedByTest() {
        File(context.filesDir, "artifacts").deleteRecursively()
    }

    @Test
    fun savesVersionsAndKeepsProjectsIsolated() {
        val first = ArtifactStore.save(context, "project-a", "Plan", "# version one")
        val second = ArtifactStore.save(context, "project-a", "Plan", "# version two")

        assertEquals(1, first.version)
        assertEquals(2, second.version)
        assertEquals("# version one", ArtifactStore.read(context, first))
        assertEquals(2, ArtifactStore.list(context, "project-a").size)
        assertTrue(ArtifactStore.list(context, "project-b").isEmpty())

        ArtifactStore.delete(context, first)
        assertEquals(1, ArtifactStore.list(context, "project-a").size)
        assertEquals("# version two", ArtifactStore.read(context, second))
    }

    @Test
    fun restoreStagesNewFilesAndReplacesIndexWithoutOverwritingOldRecords() {
        val old = ArtifactStore.save(context, "project-a", "Old", "old content")
        val backup = JSONArray().put(
            JSONObject()
                .put("id", UUID.randomUUID().toString())
                .put("projectId", "project-a")
                .put("title", "Restored")
                .put("version", 3)
                .put("createdAt", 123456789L)
                .put("content", "# restored content")
        )
        val validated = ArtifactStore.validateBackup(backup, setOf("project-a"))

        ArtifactStore.restoreBackup(context, validated, setOf("project-a"))

        val restored = ArtifactStore.list(context, "project-a").single()
        assertEquals("Restored", restored.title)
        assertEquals(3, restored.version)
        assertEquals("# restored content", ArtifactStore.read(context, restored))
        assertNotEquals(old.id, restored.id)
        assertTrue(!File(context.filesDir, "artifacts/${old.fileName}").exists())
    }

    @Test
    fun readRejectsArtifactWhoseStoredSizeNoLongerMatchesIndex() {
        val record = ArtifactStore.save(context, "project-a", "Integrity", "short")
        File(context.filesDir, "artifacts/${record.fileName}").writeText("different length")

        var rejected = false
        try {
            ArtifactStore.read(context, record)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("corrupted artifact must be rejected", rejected)
    }

    @Test
    fun backupRejectsMissingArtifactInsteadOfSilentlyDroppingIt() {
        val record = ArtifactStore.save(context, "project-a", "Missing", "content")
        File(context.filesDir, "artifacts/${record.fileName}").delete()

        var rejected = false
        try {
            ArtifactStore.backupAll(context, setOf("project-a"))
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("backup must fail when indexed artifact is missing", rejected)
    }
}
