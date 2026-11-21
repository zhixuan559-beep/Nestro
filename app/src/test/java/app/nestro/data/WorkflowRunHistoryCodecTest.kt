package app.nestro.data

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkflowRunHistoryCodecTest {
    @Test fun roundTripPreservesProjectOwnership() {
        val record = WorkflowRunRecord(
            workflowId = "workflow-a",
            workflowName = "项目工作流",
            input = "输入",
            startedAt = 10L,
            status = "success",
            output = "结果",
            projectId = "project-a"
        )
        assertEquals("project-a", WorkflowRunHistoryCodec.decode(WorkflowRunHistoryCodec.encode(listOf(record))).single().projectId)
    }

    @Test fun legacyHistoryWithoutProjectIdBelongsToDefaultProject() {
        val legacy = """[{"workflowId":"workflow-a","workflowName":"旧记录","input":"输入","startedAt":10,"status":"success","output":"结果"}]"""
        assertEquals("default", WorkflowRunHistoryCodec.decode(legacy).single().projectId)
    }
}
