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

    @Test fun completedTaskStoresResultAndIsNotRecovered() {
        val task=MediaAiTaskStore.create(context,"ai-task-test","00000000-0000-0000-0000-000000000002","provider.test","image")
        MediaAiTaskStore.update(context,task.projectId,task.id,status=MediaAiTaskStatus.COMPLETED,progress=100,resultJson="{\"type\":\"image\",\"text\":\"ok\"}")
        val restored=MediaAiTaskStore.read(context,task.projectId,task.id)
        assertEquals(MediaAiTaskStatus.COMPLETED,restored?.status)
        assertEquals(100,restored?.progress)
        assertTrue(MediaAiTaskStore.recoverable(context,task.projectId).none { it.id==task.id })
    }
}
