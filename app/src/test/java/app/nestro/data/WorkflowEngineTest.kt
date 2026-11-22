package app.nestro.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test fun runnerSkipsConditionalStepWhenPreviousOutputMatches() = runBlocking {
        val workflow=WorkflowDefinition(name="条件分支",description="",steps=listOf(
            WorkflowStep(title="检查",instruction="检查内容",retries=0),
            WorkflowStep(title="跳过节点",instruction="无需执行",skipIfPreviousContains="无需处理"),
            WorkflowStep(title="继续",instruction="继续处理",retries=0)
        ))
        var calls=0
        val result=WorkflowEngine.run(workflow,"输入",{ _, _ ->
            calls++
            if(calls==1) "此项无需处理" else "继续完成"
        })
        assertEquals(2,calls)
        assertEquals("skipped",result.stepRuns[1].status)
        assertEquals(2,result.completedSteps)
    }

    @Test fun runnerRepeatsStepAndFeedsPriorIterationOutput() = runBlocking {
        val workflow=WorkflowDefinition(name="循环",description="",steps=listOf(
            WorkflowStep(title="迭代",instruction="逐步完善",retries=0,repeatCount=2)
        ))
        val prompts=mutableListOf<String>()
        val result=WorkflowEngine.run(workflow,"输入",{ _, prompt ->
            prompts += prompt
            if(prompts.size==1) "第一轮结果" else "第二轮结果"
        })
        assertEquals(2,prompts.size)
        assertEquals(true,prompts[1].contains("第一轮结果"))
        assertEquals(2,result.stepRuns.single().iterations)
        assertEquals("第二轮结果",result.output)
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

    @Test fun failedExecutionRetainsCompletedAndFailedStepLogs() = runBlocking {
        val workflow=WorkflowDefinition(name="失败记录",description="",steps=listOf(
            WorkflowStep(title="成功步骤",instruction="执行",retries=0),
            WorkflowStep(title="失败步骤",instruction="执行",retries=0)
        ))
        val failure=runCatching {
            WorkflowEngine.run(workflow,"输入",{ step, _ ->
                if(step.title=="失败步骤") error("模型故障")
                "已完成"
            })
        }.exceptionOrNull() as WorkflowExecutionException
        assertEquals(2,failure.stepRuns.size)
        assertEquals("success",failure.stepRuns[0].status)
        assertEquals("failed",failure.stepRuns[1].status)
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
    @Test fun workflowEditorCanInsertRemoveMoveAndUpdateSteps() {
        val first = WorkflowStep(title = "A", instruction = "a")
        val second = WorkflowStep(title = "B", instruction = "b")
        var workflow = WorkflowDefinition(name = "编辑", description = "", steps = listOf(first, second))
        val third = WorkflowStep(title = "C", instruction = "c")
        workflow = WorkflowDefinitionEditor.insertStep(workflow, 1, third)
        assertEquals(listOf("A", "C", "B"), workflow.steps.map { it.title })
        workflow = WorkflowDefinitionEditor.moveStep(workflow, third.id, 2)
        assertEquals(listOf("A", "B", "C"), workflow.steps.map { it.title })
        workflow = WorkflowDefinitionEditor.updateStep(workflow, second.id) { it.copy(title = "B2") }
        assertEquals("B2", workflow.steps[1].title)
        workflow = WorkflowDefinitionEditor.removeStep(workflow, first.id)
        assertEquals(listOf("B2", "C"), workflow.steps.map { it.title })
    }

    @Test fun workflowConditionCanSkipAndApprovalCanReject() = kotlinx.coroutines.test.runTest {
        val skipped = WorkflowDefinition("w1","条件","",listOf(
            WorkflowStep(title="条件步",instruction="x",condition="contains:命中")
        ))
        val result = WorkflowEngine.run(skipped,"目标",{ _, _ -> "不会执行" })
        assertEquals("skipped", result.stepRuns.single().status)

        val gated = WorkflowDefinition("w2","审批","",listOf(
            WorkflowStep(title="审批步",instruction="x",approvalRequired=true)
        ))
        try {
            WorkflowEngine.run(gated,"目标",{ _, _ -> "不会执行" },requestApproval={ _, _ -> false })
            throw AssertionError("expected rejection")
        } catch (e: WorkflowExecutionException) {
            assertEquals("approval_rejected", e.stepRuns.single().status)
        }
    }

    @Test fun successfulRunExposesCheckpointForResume() = kotlinx.coroutines.test.runTest {
        val workflow = WorkflowDefinition("w","恢复","",listOf(
            WorkflowStep(title="A",instruction="a"),
            WorkflowStep(title="B",instruction="b")
        ))
        val result = WorkflowEngine.run(workflow,"输入",{ step, _ -> step.title })
        assertEquals(2, result.checkpointIndex)
        assertEquals(2, result.completedSteps)
    }

    @Test fun parallelGroupRunsConcurrentlyAndMergesOutputsForNextStep() = kotlinx.coroutines.test.runTest {
        val workflow = WorkflowDefinition("parallel", "并行", "", listOf(
            WorkflowStep(title = "研究 A", instruction = "A", parallelGroup = "research", retries = 0),
            WorkflowStep(title = "研究 B", instruction = "B", parallelGroup = "research", retries = 0),
            WorkflowStep(title = "汇总", instruction = "汇总", retries = 0)
        ))
        val bothStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val started = java.util.concurrent.atomic.AtomicInteger()
        val prompts = java.util.concurrent.ConcurrentHashMap<String, String>()
        val result = WorkflowEngine.run(workflow, "目标", { step, prompt ->
            prompts[step.title] = prompt
            if (step.title.startsWith("研究")) {
                if (started.incrementAndGet() == 2) bothStarted.complete(Unit)
                kotlinx.coroutines.withTimeout(2_000) { bothStarted.await() }
                step.title + "结果"
            } else {
                assertEquals(true, prompt.contains("研究 A结果"))
                assertEquals(true, prompt.contains("研究 B结果"))
                "最终结果"
            }
        })
        assertEquals(3, result.completedSteps)
        assertEquals("最终结果", result.output)
        assertEquals(true, prompts["研究 A"]?.contains("用户原始目标") == true)
        assertEquals(true, prompts["研究 B"]?.contains("用户原始目标") == true)
    }

    @Test fun approvalRequiredFailsClosedWithoutApprovalHandler() = kotlinx.coroutines.test.runTest {
        val workflow = WorkflowDefinition("approval-default", "审批默认拒绝", "", listOf(
            WorkflowStep(title = "外部操作", instruction = "执行", approvalRequired = true)
        ))
        val failure = runCatching {
            WorkflowEngine.run(workflow, "目标", { _, _ -> "不应执行" })
        }.exceptionOrNull()
        assertTrue(failure is WorkflowExecutionException)
        assertEquals("approval_rejected", (failure as WorkflowExecutionException).stepRuns.single().status)
    }

    @Test fun runnerResumesAfterPersistedCompletedStepIds() = kotlinx.coroutines.test.runTest {
        val workflow = WorkflowDefinition("resume", "恢复", "", listOf(
            WorkflowStep(id = "step-a", title = "A", instruction = "A"),
            WorkflowStep(id = "step-b", title = "B", instruction = "B")
        ))
        val calls = mutableListOf<String>()
        val result = WorkflowEngine.run(
            workflow = workflow,
            userInput = "原始目标",
            invokeModel = { step, prompt ->
                calls += step.id
                assertEquals(true, prompt.contains("A 的持久化成果"))
                "B 完成"
            },
            resumeCompletedRuns = listOf(
                WorkflowStepRun("step-a", "A", "success", 1, output = "A 的持久化成果")
            ),
            resumeOutput = "A 的持久化成果"
        )
        assertEquals(listOf("step-b"), calls)
        assertEquals(2, result.completedSteps)
        assertEquals("B 完成", result.output)
    }

}
