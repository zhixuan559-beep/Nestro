package app.nestro.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkflowEngineTest {
    @Test fun codecRoundTripsWorkflowAndEnabledFlags() {
        val workflow=WorkflowDefinition(name="内容生产",description="研究后写作",steps=listOf(
            WorkflowStep(kind=WorkflowStepKind.EXPERT,title="研究",targetId="研究专家",instruction="收集事实"),
            WorkflowStep(kind=WorkflowStepKind.SKILL,title="写作",instruction="形成文章",enabled=false)
        ))
        val restored=WorkflowDefinitionCodec.decode(WorkflowDefinitionCodec.encode(listOf(workflow))).single()
        assertEquals("内容生产",restored.name)
        assertEquals(2,restored.steps.size)
        assertEquals(false,restored.steps[1].enabled)
        assertEquals(WorkflowStepKind.SKILL,restored.steps[1].kind)
        assertEquals("研究专家",restored.steps[0].targetId)
        assertEquals(1,restored.steps[0].retries)
        assertEquals(120,restored.steps[0].timeoutSeconds)
        assertEquals(false,restored.steps[0].continueOnError)
    }

    @Test fun runHistoryCodecRoundTripsExecutionRecords() {
        val record=WorkflowRunRecord(
            workflowId="workflow-1",
            workflowName="研究到方案",
            input="分析一个市场",
            startedAt=123456L,
            status="success",
            output="最终方案",
            stepRuns=listOf(WorkflowStepRun("step-1","研究","success",2,output="研究结果"))
        )
        val restored=WorkflowRunHistoryCodec.decode(WorkflowRunHistoryCodec.encode(listOf(record))).single()
        assertEquals(record.workflowId,restored.workflowId)
        assertEquals(record.status,restored.status)
        assertEquals(2,restored.stepRuns.single().attempts)
        assertEquals("研究结果",restored.stepRuns.single().output)
    }

    @Test fun runnerPassesPreviousStepOutputIntoNextStep()= runBlocking {
        val workflow=WorkflowDefinition(name="流水线",description="",steps=listOf(
            WorkflowStep(title="分析",instruction="拆解目标"),
            WorkflowStep(title="执行",instruction="产出结果")
        ))
        val prompts=mutableListOf<String>()
        val result=WorkflowEngine.run(workflow,"写一篇文章",{ _, prompt ->
            prompts += prompt
            if(prompts.size==1) "分析结果" else "最终文章"
        })
        assertEquals("最终文章",result.output)
        assertEquals(2,result.completedSteps)
        assertEquals(true,prompts[1].contains("分析结果"))
    }

    @Test fun runnerRetriesFailedStepAndRecordsAttempts() = runBlocking {
        val workflow=WorkflowDefinition(name="重试",description="",steps=listOf(
            WorkflowStep(title="调用",instruction="执行",retries=2)
        ))
        var calls=0
        val result=WorkflowEngine.run(workflow,"输入",{ _, _ ->
            calls++
            if(calls<2) error("临时故障") else "成功结果"
        })
        assertEquals(2,calls)
        assertEquals(2,result.stepRuns.single().attempts)
        assertEquals("success",result.stepRuns.single().status)
    }

    @Test fun runnerCanContinueAfterConfiguredStepFailure() = runBlocking {
        val workflow=WorkflowDefinition(name="容错",description="",steps=listOf(
            WorkflowStep(title="可跳过步骤",instruction="执行",retries=0,continueOnError=true),
            WorkflowStep(title="后续步骤",instruction="继续",retries=0)
        ))
        val result=WorkflowEngine.run(workflow,"输入",{ step, _ ->
            if(step.title=="可跳过步骤") error("故障")
            "后续完成"
        })
        assertEquals(1,result.completedSteps)
        assertEquals("failed",result.stepRuns.first().status)
        assertEquals("后续完成",result.output)
    }

    @Test fun runnerRejectsWorkflowWithoutEnabledSteps() {
        val workflow=WorkflowDefinition(name="空工作流",description="",steps=listOf(
            WorkflowStep(title="跳过",instruction="不执行",enabled=false)
        ))
        val failure=runBlocking {
            runCatching { WorkflowEngine.run(workflow,"输入",{ _, _ -> "结果" }) }.exceptionOrNull()
        }
        assertEquals(true, failure is IllegalArgumentException)
    }
}
