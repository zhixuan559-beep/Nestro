package app.nestro.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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
    fun readAndDeleteRejectForgedCrossProjectReferences() {
        val record = ArtifactStore.save(context, "project-a", "Private", "project a content")
        val forged = record.copy(projectId = "project-b")

        assertThrows(IllegalArgumentException::class.java) {
            ArtifactStore.read(context, forged)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArtifactStore.delete(context, forged)
        }

        assertEquals("project a content", ArtifactStore.read(context, record))
        assertEquals(1, ArtifactStore.list(context, "project-a").size)
        assertTrue(ArtifactStore.list(context, "project-b").isEmpty())
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
    fun projectDeletionGuardDetectsRegisteredArtifactsAndOrphanedFiles() {
        val record = ArtifactStore.save(context, "project-a", "Keep", "content")
        assertTrue(ArtifactStore.hasProjectArtifactsOrOrphans(context, "project-a"))
        assertFalse(ArtifactStore.hasProjectArtifactsOrOrphans(context, "project-b"))

        ArtifactStore.delete(context, record)
        assertFalse(ArtifactStore.hasProjectArtifactsOrOrphans(context, "project-a"))

        val orphan = File(context.filesDir, "artifacts/${UUID.randomUUID()}.md")
        orphan.writeText("orphaned content")
        assertTrue(ArtifactStore.hasProjectArtifactsOrOrphans(context, "project-b"))
    }

    @Test
    fun stagedProjectDeletionRecoversFilesBeforeIndexCommitAndCleansAfterCommit() {
        val record = ArtifactStore.save(context, "project-a", "Recover", "recoverable content")
        val directory = File(context.filesDir, "artifacts")
        val original = File(directory, record.fileName)
        val staged = File(directory, ".${record.fileName}.${UUID.randomUUID()}.deleting")
        assertTrue(original.renameTo(staged))

        assertEquals("recoverable content", ArtifactStore.read(context, record))
        assertTrue(original.isFile)
        assertFalse(staged.exists())

        assertTrue(original.renameTo(staged))
        File(directory, "index.json").writeText(JSONArray().toString(), Charsets.UTF_8)
        assertTrue(ArtifactStore.list(context, "project-a").isEmpty())
        assertFalse(staged.exists())
    }

    @Test
    fun listFailsClosedWhenIndexIsMissingButArtifactFilesRemain() {
        val record = ArtifactStore.save(context, "project-a", "Orphan", "content")
        File(context.filesDir, "artifacts/index.json").delete()

        assertThrows(IllegalArgumentException::class.java) {
            ArtifactStore.list(context, "project-a")
        }
        assertTrue(File(context.filesDir, "artifacts/${record.fileName}").isFile)
    }

    @Test
    fun projectDeletionGuardFailsClosedWhenArtifactIndexIsCorrupt() {
        val directory = File(context.filesDir, "artifacts").apply { mkdirs() }
        val index = File(directory, "index.json")
        index.writeText("{not-json")
        assertThrows(Exception::class.java) {
            ArtifactStore.hasProjectArtifactsOrOrphans(context, "project-a")
        }
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
    @Test
    fun backupRejectsMalformedArtifactIndexInsteadOfSkippingOrClampingIt() {
        val record = ArtifactStore.save(context, "project-a", "Valid", "content")
        val indexFile = File(context.filesDir, "artifacts/index.json")
        val rows = JSONArray(indexFile.readText(Charsets.UTF_8))
        rows.getJSONObject(0).put("title", "x".repeat(101))
        indexFile.writeText(rows.toString(), Charsets.UTF_8)

        var rejectedLongTitle = false
        try {
            ArtifactStore.backupAll(context, setOf("project-a"))
        } catch (_: IllegalArgumentException) {
            rejectedLongTitle = true
        }
        assertTrue("backup must reject an overlong indexed title", rejectedLongTitle)

        rows.getJSONObject(0).put("title", record.title).put("version", 0)
        indexFile.writeText(rows.toString(), Charsets.UTF_8)
        var rejectedVersion = false
        try {
            ArtifactStore.backupAll(context, setOf("project-a"))
        } catch (_: IllegalArgumentException) {
            rejectedVersion = true
        }
        assertTrue("backup must reject an invalid version instead of clamping it", rejectedVersion)

        indexFile.writeText(JSONArray().put(JSONObject.NULL).toString(), Charsets.UTF_8)
        var rejectedMalformedRow = false
        try {
            ArtifactStore.backupAll(context, setOf("project-a"))
        } catch (_: IllegalArgumentException) {
            rejectedMalformedRow = true
        }
        assertTrue("backup must reject malformed index rows instead of skipping them", rejectedMalformedRow)
    }

    @Test
    fun saveRejectsOverlongArtifactTitleInsteadOfTruncatingIt() {
        var rejected = false
        try {
            ArtifactStore.save(context, "project-a", "x".repeat(101), "content")
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("artifact title must be rejected rather than truncated", rejected)
    }


    @Test
    fun projectDuplicationCopiesAllArtifactVersionsAndKeepsTheSourceIndependent() {
        ArtifactStore.save(context, "source", "Plan", "# first version")
        ArtifactStore.save(context, "source", "Plan", "# second version")
        ArtifactStore.save(context, "source", "Notes", "# notes")

        val copied = ArtifactStore.duplicateProject(context, "source", "copy")

        assertEquals(3, copied.size)
        assertEquals(2, ArtifactStore.list(context, "copy").count { it.title == "Plan" })
        assertEquals(
            listOf("# first version", "# second version"),
            ArtifactStore.list(context, "copy").filter { it.title == "Plan" }.sortedBy { it.version }
                .map { ArtifactStore.read(context, it) }
        )
        assertEquals(3, ArtifactStore.list(context, "source").size)
        assertTrue(ArtifactStore.list(context, "other").isEmpty())
    }

    @Test
    fun projectDuplicationPreservesVersionNumbersAndCreationTimes() {
        val first = ArtifactStore.save(context, "source", "Plan", "# first")
        val second = ArtifactStore.save(context, "source", "Plan", "# second")
        val third = ArtifactStore.save(context, "source", "Plan", "# third")
        ArtifactStore.delete(context, second)

        val copied = ArtifactStore.duplicateProject(context, "source", "copy")
            .sortedBy { it.version }

        assertEquals(listOf(1, 3), copied.map { it.version })
        assertEquals(listOf(first.createdAt, third.createdAt), copied.map { it.createdAt })
        assertEquals(listOf("# first", "# third"), copied.map { ArtifactStore.read(context, it) })
    }

    @Test
    fun deletingProjectArtifactsDoesNotTouchOtherProjects() {
        ArtifactStore.save(context, "project-a", "A", "A content")
        ArtifactStore.save(context, "project-b", "B", "B content")

        ArtifactStore.deleteProject(context, "project-a")

        assertTrue(ArtifactStore.list(context, "project-a").isEmpty())
        assertEquals("B content", ArtifactStore.read(context, ArtifactStore.list(context, "project-b").single()))
    }

}
