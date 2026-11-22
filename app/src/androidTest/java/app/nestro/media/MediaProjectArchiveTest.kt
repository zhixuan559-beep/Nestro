package app.nestro.media

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class MediaProjectArchiveTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun archiveRoundTripPreservesEditableTimelineAndOriginalAssetBytes() {
        val suffix = UUID.randomUUID().toString()
        val sourceProject = "archive-source-$suffix"
        val restoredProject = "archive-restored-$suffix"
        val sourceImage = File(context.cacheDir, "archive-image-$suffix.png")
        val archive = File(context.cacheDir, "archive-$suffix.zip")
        try {
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            try {
                sourceImage.outputStream().use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                }
            } finally {
                bitmap.recycle()
            }
            val asset = MediaProjectStore.importGeneratedFile(
                context, sourceProject, sourceImage, MediaAssetKind.IMAGE
            )
            val emptyTimeline = MediaProjectStore.readTimeline(context, sourceProject)
            val editedTimeline = MediaTimelinePolicy.addClip(emptyTimeline, "video-main", asset)
            val originalTimeline = MediaProjectStore.saveTimeline(
                context, editedTimeline, emptyTimeline.revision
            )
            val exported = MediaProjectStore.exportProjectArchive(context, sourceProject, archive)
            assertEquals(true, exported.isFile)

            val restoredTimeline = MediaProjectStore.importProjectArchive(
                context, archive, restoredProject
            )
            assertEquals(restoredProject, restoredTimeline.projectId)
            assertEquals(originalTimeline.tracks.map { it.kind }, restoredTimeline.tracks.map { it.kind })
            assertEquals(
                originalTimeline.tracks.map { track -> track.clips.map { it.assetId } },
                restoredTimeline.tracks.map { track -> track.clips.map { it.assetId } }
            )

            val restoredAsset = MediaProjectStore.listAssets(context, restoredProject).single()
            assertEquals(asset.byteSize, restoredAsset.byteSize)
            assertEquals(asset.sha256, restoredAsset.sha256)
            assertArrayEquals(
                MediaProjectStore.assetFile(context, asset).readBytes(),
                MediaProjectStore.assetFile(context, restoredAsset).readBytes()
            )
            assertThrows(IllegalArgumentException::class.java) {
                MediaProjectStore.importProjectArchive(context, archive, restoredProject)
            }
            val replacedTimeline = MediaProjectStore.importProjectArchive(
                context, archive, restoredProject, replaceExisting = true
            )
            assertEquals(restoredProject, replacedTimeline.projectId)
            assertEquals(1, MediaProjectStore.listAssets(context, restoredProject).size)
        } finally {
            MediaProjectStore.deleteProject(context, sourceProject)
            MediaProjectStore.deleteProject(context, restoredProject)
            sourceImage.delete()
            archive.delete()
        }
    }
    @Test fun interruptedProjectReplacementRestoresThePreviousProjectDirectory() {
        val projectId = "archive-journal-${UUID.randomUUID()}"
        val mediaRoot = File(context.filesDir, "media-editor")
        check(mediaRoot.mkdirs() || mediaRoot.isDirectory)
        val backupName = ".replaced-${UUID.randomUUID()}"
        val stagingName = ".import-${UUID.randomUUID()}"
        val backup = File(mediaRoot, backupName)
        val staging = File(mediaRoot, stagingName)
        val target = File(mediaRoot, projectId)
        val journal = File(mediaRoot, ".restore-$projectId.json")
        try {
            check(backup.mkdirs())
            check(staging.mkdirs())
            val manifest = JSONObject()
                .put("schemaVersion", 1)
                .put("projectId", projectId)
                .put("assets", JSONArray())
                .put("timeline", MediaProjectCodec.encodeTimeline(MediaTimeline.empty(projectId)))
                .put("undo", JSONArray())
                .put("redo", JSONArray())
            File(backup, "project.json").writeText(manifest.toString(), Charsets.UTF_8)
            journal.writeText(
                JSONObject()
                    .put("projectId", projectId)
                    .put("backupName", backupName)
                    .put("stagingName", stagingName)
                    .toString(),
                Charsets.UTF_8
            )

            val restored = MediaProjectStore.readTimeline(context, projectId)
            assertEquals(projectId, restored.projectId)
            assertTrue(target.isDirectory)
            assertEquals(false, journal.exists())
            assertEquals(false, backup.exists())
            assertEquals(false, staging.exists())
        } finally {
            MediaProjectStore.deleteProject(context, projectId)
            backup.deleteRecursively()
            staging.deleteRecursively()
            journal.delete()
        }
    }

}
