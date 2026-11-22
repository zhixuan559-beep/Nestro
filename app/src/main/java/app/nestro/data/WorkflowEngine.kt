package app.nestro.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

enum class WorkflowStepKind { PROMPT, EXPERT, SKILL }

data class WorkflowStep(
    val id:String=UUID.randomUUID().toString(),
    val kind:WorkflowStepKind=WorkflowStepKind.PROMPT,
    val title:String,
    val targetId:String="",
    val instruction:String,
    val enabled:Boolean=true,
    val retries:Int=1,
    val timeoutSeconds:Int=120,
    val continueOnError:Boolean=false,
    val skipIfPreviousContains:String="",
    val repeatCount:Int=1,
    val condition:String="",
    val approvalRequired:Boolean=false,
    val parallelGroup:String=""
)

data class WorkflowDefinition(
    val id:String=UUID.randomUUID().toString(),
    val name:String,
    val description:String,
    val steps:List<WorkflowStep>
)

data class WorkflowStepRun(
    val stepId:String,
    val title:String,
    val status:String,
    val attempts:Int,
    val output:String="",
    val error:String="",
    val iterations:Int=1
)

data class WorkflowRunResult(
    val output:String,
    val completedSteps:Int,
    val stepOutputs:List<String>,
    val stepRuns:List<WorkflowStepRun> = emptyList(),
    val checkpointIndex:Int = 0
)

data class WorkflowRunRecord(
    val id:String=UUID.randomUUID().toString(),
    val workflowId:String,
    val workflowName:String,
    val input:String,
    val startedAt:Long,
    val status:String,
    val output:String,
    val stepRuns:List<WorkflowStepRun> = emptyList(),
    val projectId:String = "default"
)

object WorkflowRunHistoryCodec {
    fun encode(records:List<WorkflowRunRecord>):String = JSONArray().apply {
        records.take(200).forEach { record ->
            put(JSONObject().put("id",record.id).put("workflowId",record.workflowId)
                .put("workflowName",record.workflowName).put("input",record.input.take(12000))
                .put("startedAt",record.startedAt).put("status",record.status).put("projectId",record.projectId)
                .put("checkpointIndex",record.stepRuns.indexOfLast { it.status == "success" }.coerceAtLeast(-1) + 1)
                .put("output",record.output.take(20000))
                .put("stepRuns",JSONArray().apply {
                    record.stepRuns.take(20).forEach { step ->
                        put(JSONObject().put("stepId",step.stepId).put("title",step.title)
                            .put("status",step.status).put("attempts",step.attempts)
                            .put("output",step.output.take(12000)).put("error",step.error.take(2000))
                            .put("iterations",step.iterations))
                    }
                }))
        }
    }.toString()

    fun decode(raw:String):List<WorkflowRunRecord> = runCatching {
        val array=JSONArray(raw)
        (0 until array.length()).mapNotNull { index ->
            val item=array.optJSONObject(index) ?: return@mapNotNull null
            val name=item.optString("workflowName").take(40)
            if(name.isBlank()) return@mapNotNull null
            val steps=item.optJSONArray("stepRuns") ?: JSONArray()
            val parsed=(0 until steps.length()).mapNotNull { stepIndex ->
                val step=steps.optJSONObject(stepIndex) ?: return@mapNotNull null
                WorkflowStepRun(
                    stepId=step.optString("stepId"),
                    title=step.optString("title").take(40),
                    status=step.optString("status"),
                    attempts=step.optInt("attempts").coerceIn(0,4),
                    output=step.optString("output").take(12000),
                    error=step.optString("error").take(2000),
                    iterations=step.optInt("iterations",1).coerceIn(0,3)
                )
            }.take(20)
            WorkflowRunRecord(
                id=item.optString("id").ifBlank { UUID.randomUUID().toString() },
                workflowId=item.optString("workflowId"),
                workflowName=name,
                input=item.optString("input").take(12000),
                startedAt=item.optLong("startedAt"),
                status=item.optString("status").take(20),
                output=item.optString("output").take(20000),
                stepRuns=parsed,
                projectId=item.optString("projectId", "default").ifBlank { "default" }
            )
        }.take(200)
    }.getOrDefault(emptyList())
}

object WorkflowDefinitionEditor {
    fun insertStep(workflow: WorkflowDefinition, index: Int, step: WorkflowStep): WorkflowDefinition {
        require(workflow.steps.size < 20) { "工作流最多支持 20 个步骤" }
        val target = index.coerceIn(0, workflow.steps.size)
        return workflow.copy(steps = workflow.steps.toMutableList().apply { add(target, step) })
    }
    fun removeStep(workflow: WorkflowDefinition, stepId: String): WorkflowDefinition {
        require(workflow.steps.size > 1) { "工作流至少保留 1 个步骤" }
        val remaining = workflow.steps.filterNot { it.id == stepId }
        require(remaining.size < workflow.steps.size) { "未找到要删除的步骤" }
        return workflow.copy(steps = remaining)
    }
    fun moveStep(workflow: WorkflowDefinition, stepId: String, targetIndex: Int): WorkflowDefinition {
        val currentIndex = workflow.steps.indexOfFirst { it.id == stepId }
        require(currentIndex >= 0) { "未找到要移动的步骤" }
        val items = workflow.steps.toMutableList()
        val item = items.removeAt(currentIndex)
        items.add(targetIndex.coerceIn(0, items.size), item)
        return workflow.copy(steps = items)
    }
    fun updateStep(workflow: WorkflowDefinition, stepId: String, transform: (WorkflowStep) -> WorkflowStep): WorkflowDefinition {
        require(workflow.steps.any { it.id == stepId }) { "未找到要编辑的步骤" }
        return workflow.copy(steps = workflow.steps.map { if (it.id == stepId) transform(it) else it })
    }
}

object WorkflowDefinitionCodec {
    fun encode(items:List<WorkflowDefinition>):String = JSONArray().apply {
        items.take(100).forEach { workflow ->
            put(JSONObject().put("id",workflow.id).put("name",workflow.name).put("description",workflow.description)
                .put("steps",JSONArray().apply { workflow.steps.take(20).forEach { step ->
                    put(JSONObject().put("id",step.id).put("kind",step.kind.name).put("title",step.title)
                        .put("targetId",step.targetId).put("instruction",step.instruction).put("enabled",step.enabled)
                        .put("retries",step.retries).put("timeoutSeconds",step.timeoutSeconds)
                        .put("continueOnError",step.continueOnError)
                        .put("skipIfPreviousContains",step.skipIfPreviousContains)
                        .put("repeatCount",step.repeatCount)
                        .put("condition",step.condition).put("approvalRequired",step.approvalRequired)
                        .put("parallelGroup",step.parallelGroup))
                } }))
        }
    }.toString()

    fun decode(raw:String):List<WorkflowDefinition> = runCatching {
        val array=JSONArray(raw)
        (0 until array.length()).mapNotNull { index ->
            val item=array.optJSONObject(index) ?: return@mapNotNull null
            val steps=item.optJSONArray("steps") ?: JSONArray()
            val parsed=(0 until steps.length()).mapNotNull { stepIndex ->
                val step=steps.optJSONObject(stepIndex) ?: return@mapNotNull null
                val kind=runCatching { WorkflowStepKind.valueOf(step.optString("kind")) }.getOrDefault(WorkflowStepKind.PROMPT)
                val title=step.optString("title").trim()
                val instruction=step.optString("instruction").trim()
                if(title.isBlank() || instruction.isBlank()) null
                else WorkflowStep(
                    id=step.optString("id").ifBlank { UUID.randomUUID().toString() },
                    kind=kind,
                    title=title,
                    targetId=step.optString("targetId").ifBlank { if(kind==WorkflowStepKind.PROMPT) "" else title },
                    instruction=instruction,
                    enabled=step.optBoolean("enabled",true),
                    retries=step.optInt("retries",1).coerceIn(0,3),
                    timeoutSeconds=step.optInt("timeoutSeconds",120).coerceIn(15,300),
                    continueOnError=step.optBoolean("continueOnError",false),
                    skipIfPreviousContains=step.optString("skipIfPreviousContains").take(120),
                    repeatCount=step.optInt("repeatCount",1).coerceIn(1,3),
                    condition=step.optString("condition").take(200),
                    approvalRequired=step.optBoolean("approvalRequired",false),
                    parallelGroup=step.optString("parallelGroup").take(40)
                )
            }.take(20)
            val name=item.optString("name").trim()
            if(name.isBlank() || parsed.isEmpty()) null
            else WorkflowDefinition(item.optString("id").ifBlank { UUID.randomUUID().toString() },name.take(40),item.optString("description").take(300),parsed)
        }.take(100)
    }.getOrDefault(emptyList())

    fun validate(workflow:WorkflowDefinition) {
        require(workflow.name.isNotBlank() && workflow.name.length<=40) { "工作流名称无效" }
        require(workflow.description.length<=300) { "工作流描述不能超过 300 个字符" }
        require(workflow.steps.size in 1..20) { "工作流必须包含 1 到 20 个步骤" }
        workflow.steps.forEach {
            require(it.title.isNotBlank() && it.title.length<=40) { "步骤名称无效" }
            require(it.instruction.isNotBlank() && it.instruction.length<=4_000) { "步骤指令为空或过长" }
            require(it.retries in 0..3) { "单步重试次数必须在 0 到 3 次之间" }
            require(it.timeoutSeconds in 15..300) { "单步超时必须在 15 到 300 秒之间" }
            require(it.skipIfPreviousContains.length<=120) { "条件文本不能超过 120 个字符" }
            require(it.repeatCount in 1..3) { "单步循环次数必须在 1 到 3 次之间" }
            require(it.condition.length<=200) { "条件不能超过 200 个字符" }
            require(it.parallelGroup.length<=40) { "并行组名称不能超过 40 个字符" }
            if(it.kind != WorkflowStepKind.PROMPT) {
                require(it.targetId.isNotBlank()) { "步骤“${it.title}”尚未选择对应的专家或技能" }
            }
        }
    }
}

class WorkflowExecutionException(
    message:String,
    val stepRuns:List<WorkflowStepRun>,
    cause:Throwable?=null
):IllegalStateException(message,cause)

object WorkflowEngine {
    suspend fun run(
        workflow:WorkflowDefinition,
        userInput:String,
        invokeModel:suspend (WorkflowStep,String)->String,
        onStep:suspend (Int,WorkflowStep)->Unit={ _, _ -> },
        onStepCompleted:suspend (Int,WorkflowStepRun,String)->Unit={ _, _, _ -> },
        requestApproval:suspend (WorkflowStep,String)->Boolean={ _, _ -> false },
        resumeCompletedRuns:List<WorkflowStepRun> = emptyList(),
        resumeOutput:String = ""
    ):WorkflowRunResult {
        WorkflowDefinitionCodec.validate(workflow)
        require(userInput.isNotBlank() && userInput.length<=12_000) { "工作流输入为空或过长" }
        var previousOutput = resumeOutput
        val outputs = resumeCompletedRuns.map { it.output }.toMutableList()
        val runs = resumeCompletedRuns.toMutableList()
        val completedIds = resumeCompletedRuns.map { it.stepId }.toSet()
        val active = workflow.steps.filter { it.enabled && it.id !in completedIds }
        require(active.isNotEmpty() || resumeCompletedRuns.isNotEmpty()) { "工作流没有启用的步骤" }

        suspend fun executeStep(index: Int, step: WorkflowStep, input: String): Pair<WorkflowStepRun, String> {
            var stepOutput = input
            var totalAttempts = 0
            var failedMessage = ""
            var completedIterations = 0
            for (iteration in 1..step.repeatCount) {
                val iterationInput = stepOutput
                val prompt = buildString {
                    append("工作流：").append(workflow.name).append("\n")
                    append("当前步骤：").append(step.title).append("\n")
                    append("当前迭代：").append(iteration).append(" / ").append(step.repeatCount).append("\n")
                    append("步骤要求：").append(step.instruction).append("\n\n")
                    append("用户原始目标：\n").append(userInput).append("\n\n")
                    if (iterationInput.isNotBlank()) {
                        append(if (iteration == 1) "前序步骤输出：" else "本步骤上一次迭代输出：")
                            .append("\n").append(iterationInput.take(12_000)).append("\n\n")
                    }
                    append("请只完成当前步骤的本次迭代，输出可供后续迭代或下一步骤继续处理的结果。")
                }
                var attempts = 0
                var result = ""
                var failure: Throwable? = null
                while (attempts <= step.retries && result.isBlank()) {
                    attempts++
                    onStep(index + 1, step.copy(title = if (iteration == 1) step.title else "${step.title}（迭代 $iteration/${step.repeatCount}）"))
                    try {
                        result = kotlinx.coroutines.withTimeout(step.timeoutSeconds * 1000L) {
                            invokeModel(step, prompt).trim()
                        }
                        require(result.isNotBlank()) { "模型返回了空结果" }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        if (cancelled is kotlinx.coroutines.TimeoutCancellationException) {
                            failure = IllegalStateException("步骤“${step.title}”执行超时（${step.timeoutSeconds} 秒）")
                        } else throw cancelled
                    } catch (error: Exception) {
                        failure = error
                    }
                    if (result.isBlank() && attempts <= step.retries) {
                        onStep(index + 1, step.copy(title = "${step.title}（重试 $attempts/${step.retries}）"))
                    }
                }
                totalAttempts += attempts
                if (result.isBlank()) {
                    failedMessage = failure?.message ?: "未知错误"
                    if (!step.continueOnError) {
                        val failed = WorkflowStepRun(step.id, step.title, "failed", totalAttempts, error = failedMessage, iterations = completedIterations)
                        throw WorkflowExecutionException("步骤“${step.title}”失败：$failedMessage", runs + failed, failure)
                    }
                    stepOutput = "步骤失败，已按设置继续。错误：$failedMessage"
                    break
                }
                stepOutput = result
                completedIterations++
            }
            val status = if (failedMessage.isBlank()) "success" else "failed"
            return WorkflowStepRun(
                stepId = step.id, title = step.title, status = status,
                attempts = totalAttempts, output = stepOutput, error = failedMessage,
                iterations = completedIterations
            ) to stepOutput
        }

        var cursor = 0
        while (cursor < active.size) {
            val groupName = active[cursor].parallelGroup
            val groupStart = cursor
            cursor++
            if (groupName.isNotBlank()) {
                while (cursor < active.size && active[cursor].parallelGroup == groupName) cursor++
            }
            val group = active.subList(groupStart, cursor)
            val ready = mutableListOf<Pair<Int, WorkflowStep>>()
            group.forEachIndexed { offset, step ->
                val index = groupStart + offset
                when {
                    step.condition.isNotBlank() && !evaluateCondition(step.condition, previousOutput) -> {
                        val skipped = WorkflowStepRun(step.id, step.title, "skipped", 0, error = "条件未满足")
                        runs += skipped
                        outputs += previousOutput
                        onStepCompleted(index + 1, skipped, previousOutput)
                    }
                    step.approvalRequired && !requestApproval(step, previousOutput) -> {
                        val rejected = WorkflowStepRun(step.id, step.title, "approval_rejected", 0, error = "审批未通过")
                        runs += rejected
                        throw WorkflowExecutionException("步骤“${step.title}”未获批准", runs.toList())
                    }
                    step.skipIfPreviousContains.isNotBlank() &&
                        previousOutput.contains(step.skipIfPreviousContains, ignoreCase = true) -> {
                        val skipped = WorkflowStepRun(step.id, step.title, "skipped", 0, output = previousOutput)
                        runs += skipped
                        outputs += previousOutput
                        onStepCompleted(index + 1, skipped, previousOutput)
                    }
                    else -> ready += index to step
                }
            }
            if (ready.isEmpty()) continue
            val completed = if (ready.size > 1 && groupName.isNotBlank()) {
                coroutineScope {
                    ready.map { (index, step) ->
                        async { executeStep(index, step, previousOutput) }
                    }.map { it.await() }
                }
            } else {
                ready.map { (index, step) -> executeStep(index, step, previousOutput) }
            }
            completed.forEach { (stepRun, stepOutput) ->
                runs += stepRun
                outputs += stepOutput
            }
            previousOutput = if (groupName.isNotBlank()) {
                val groupIds = group.map { it.id }.toSet()
                runs.filter { it.stepId in groupIds }
                    .sortedBy { row -> group.indexOfFirst { it.id == row.stepId } }
                    .joinToString("\n\n") { row ->
                        "【并行步骤：${row.title}】\n${row.output.ifBlank { row.error }}"
                    }.take(24_000)
            } else {
                completed.last().second
            }
            completed.forEachIndexed { resultIndex, pair ->
                val stepRun = pair.first
                val stepIndex = ready[resultIndex].first
                // For a parallel wave, persist the complete merged wave output so a
                // resumed sibling receives the same deterministic context.
                onStepCompleted(stepIndex + 1, stepRun, previousOutput)
            }
        }
        return WorkflowRunResult(
            previousOutput,
            runs.count { it.status == "success" },
            outputs,
            runs,
            runs.indexOfLast { it.status == "success" }.coerceAtLeast(-1) + 1
        )
    }
    private fun evaluateCondition(condition:String, previousOutput:String):Boolean {
        val c=condition.trim()
        if(c.equals("always",true)) return true
        if(c.equals("nonblank",true)) return previousOutput.isNotBlank()
        if(c.startsWith("contains:",true)) return previousOutput.contains(c.substringAfter(":").trim(),true)
        if(c.startsWith("not_contains:",true)) return !previousOutput.contains(c.substringAfter(":").trim(),true)
        return false
    }
}
