package app.nestro.media

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.*
import org.junit.Test

class MediaAiTaskStoreTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun persistsProgressAndRecoversQueuedOrRunningTasks() {
        val task=MediaAiTaskStore.create(context,"ai-task-test","00000000-0000-0000-0000-000000000001","provider.test","image","describe")
        MediaAiTaskStore.update(context,task.projectId,task.id,status=MediaAiTaskStatus.RUNNING,progress=42)
        val restored=MediaAiTaskStore.read(context,task.projectId,task.id)
        assertNotNull(restored)
        assertEquals(MediaAiTaskStatus.RUNNING,restored!!.status)
        assertEquals(42,restored.progress)
        assertTrue(MediaAiTaskStore.recoverable(context,task.projectId).any { it.id==task.id })
    }

    @Test fun videoGenerationTaskDoesNotRequireSourceAsset() {
        val task = MediaAiTaskStore.create(
            context, "ai-video-test", "", "provider.video", "video-generate",
            "A cinematic aerial shot of a coastal city at sunrise."
        )
        assertEquals("video-generate", task.action)
        assertTrue(task.assetId.isBlank())
        assertEquals("A cinematic aerial shot of a coastal city at sunrise.", task.prompt)
    }

    @Test fun videoRevisionLinksOnlyToCompletedPriorVideoTask() {
        val parent = MediaAiTaskStore.create(
            context, "ai-video-test", "", "provider.video", "video-generate", "first prompt"
        )
        assertThrows(IllegalArgumentException::class.java) {
            MediaAiTaskStore.create(
                context, "ai-video-test", "", "provider.video", "video-generate",
                "revision prompt", parentTaskId = parent.id
            )
        }
        MediaAiTaskStore.update(
            context, parent.projectId, parent.id,
            status = MediaAiTaskStatus.COMPLETED, progress = 100,
            resultJson = "{\"type\":\"video\",\"assetId\":\"00000000-0000-0000-0000-000000000010\"}"
        )
        val revision = MediaAiTaskStore.create(
            context, parent.projectId, "", "provider.video", "video-generate",
            "change the camera angle", parentTaskId = parent.id
        )
        assertEquals(parent.id, revision.parentTaskId)
    }

    @Test fun completedTaskStoresResultAndIsNotRecovered() {
        val task=MediaAiTaskStore.create(context,"ai-task-test","00000000-0000-0000-0000-000000000002","provider.test","image")
        MediaAiTaskStore.update(context,task.projectId,task.id,status=MediaAiTaskStatus.COMPLETED,progress=100,resultJson="{\"type\":\"image\",\"text\":\"ok\"}")
        val restored=MediaAiTaskStore.read(context,task.projectId,task.id)
        assertEquals(MediaAiTaskStatus.COMPLETED,restored?.status)
        assertEquals(100,restored?.progress)
        assertTrue(MediaAiTaskStore.pendingResults(context,task.projectId).any { it.id==task.id })
        MediaAiTaskStore.update(context,task.projectId,task.id,status=MediaAiTaskStatus.COMPLETED,resultConsumed=true)
        assertTrue(MediaAiTaskStore.pendingResults(context,task.projectId).none { it.id==task.id })
        assertTrue(MediaAiTaskStore.recoverable(context,task.projectId).none { it.id==task.id })
    }
}
