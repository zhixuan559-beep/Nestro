package app.nestro.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

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
    val continueOnError:Boolean=false
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
    val error:String=""
)

data class WorkflowRunResult(
    val output:String,
    val completedSteps:Int,
    val stepOutputs:List<String>,
    val stepRuns:List<WorkflowStepRun> = emptyList()
)

object WorkflowDefinitionCodec {
    fun encode(items:List<WorkflowDefinition>):String = JSONArray().apply {
        items.take(100).forEach { workflow ->
            put(JSONObject().put("id",workflow.id).put("name",workflow.name).put("description",workflow.description)
                .put("steps",JSONArray().apply { workflow.steps.take(20).forEach { step ->
                    put(JSONObject().put("id",step.id).put("kind",step.kind.name).put("title",step.title)
                        .put("targetId",step.targetId).put("instruction",step.instruction).put("enabled",step.enabled)
                        .put("retries",step.retries).put("timeoutSeconds",step.timeoutSeconds)
                        .put("continueOnError",step.continueOnError))
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
                    continueOnError=step.optBoolean("continueOnError",false)
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
        }
    }
}

object WorkflowEngine {
    suspend fun run(
        workflow:WorkflowDefinition,
        userInput:String,
        invokeModel:suspend (WorkflowStep,String)->String,
        onStep:suspend (Int,WorkflowStep)->Unit={ _, _ -> }
    ):WorkflowRunResult {
        WorkflowDefinitionCodec.validate(workflow)
        require(userInput.isNotBlank() && userInput.length<=12_000) { "工作流输入为空或过长" }
        var previousOutput=""
        val outputs=mutableListOf<String>()
        val runs=mutableListOf<WorkflowStepRun>()
        val active=workflow.steps.filter { it.enabled }
        require(active.isNotEmpty()) { "工作流没有启用的步骤" }
        active.forEachIndexed { index,step ->
            onStep(index+1,step)
            val prompt=buildString {
                append("工作流：").append(workflow.name).append("\n")
                append("当前步骤：").append(step.title).append("\n")
                append("步骤要求：").append(step.instruction).append("\n\n")
                append("用户原始目标：\n").append(userInput).append("\n\n")
                if(previousOutput.isNotBlank()) append("前序步骤输出：\n").append(previousOutput.take(12000)).append("\n\n")
                append("请只完成当前步骤，输出可供下一步骤继续处理的结果。不要重复用户目标。")
            }
            var attempts=0
            var result=""
            var failure:Throwable?=null
            while(attempts<=step.retries && result.isBlank()) {
                attempts++
                try {
                    result=kotlinx.coroutines.withTimeout(step.timeoutSeconds*1000L) {
                        invokeModel(step,prompt).trim()
                    }
                    require(result.isNotBlank()) { "模型返回了空结果" }
                } catch (cancelled:kotlinx.coroutines.CancellationException) {
                    if(cancelled is kotlinx.coroutines.TimeoutCancellationException) {
                        failure=IllegalStateException("步骤“${step.title}”执行超时（${step.timeoutSeconds} 秒）")
                    } else throw cancelled
                } catch (error:Exception) {
                    failure=error
                }
                if(result.isBlank() && attempts<=step.retries) {
                    onStep(index+1,step.copy(title="${step.title}（重试 ${attempts}/${step.retries}）"))
                }
            }
            if(result.isBlank()) {
                val message=failure?.message ?: "未知错误"
                runs += WorkflowStepRun(step.id,step.title,"failed",attempts,error=message)
                if(!step.continueOnError) throw IllegalStateException("步骤“${step.title}”失败：$message",failure)
                result="步骤失败，已按设置继续。错误：$message"
            } else {
                runs += WorkflowStepRun(step.id,step.title,"success",attempts,output=result)
            }
            previousOutput=result
            outputs += result
        }
        return WorkflowRunResult(previousOutput,runs.count { it.status == "success" },outputs,runs)
    }
}
