package app.nestro.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WorkflowEngineTest {
    @Test fun codecRoundTripsWorkflowAndEnabledFlags() {
        val workflow=WorkflowDefinition(name="内容生产",description="研究后写作",steps=listOf(
            WorkflowStep(kind=WorkflowStepKind.EXPERT,title="研究",instruction="收集事实"),
            WorkflowStep(kind=WorkflowStepKind.SKILL,title="写作",instruction="形成文章",enabled=false)
        ))
        val restored=WorkflowDefinitionCodec.decode(WorkflowDefinitionCodec.encode(listOf(workflow))).single()
        assertEquals("内容生产",restored.name)
        assertEquals(2,restored.steps.size)
        assertEquals(false,restored.steps[1].enabled)
        assertEquals(WorkflowStepKind.SKILL,restored.steps[1].kind)
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

    @Test fun runnerRejectsWorkflowWithoutEnabledSteps()=runBlocking {
        val workflow=WorkflowDefinition(name="空工作流",description="",steps=listOf(
            WorkflowStep(title="跳过",instruction="不执行",enabled=false)
        ))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { WorkflowEngine.run(workflow,"输入",{ _, _ -> "结果" }) }
        }
    }
}
