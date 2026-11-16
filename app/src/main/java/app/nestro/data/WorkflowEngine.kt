package app.nestro.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class WorkflowStepKind { PROMPT, EXPERT, SKILL }

data class WorkflowStep(
    val id:String=UUID.randomUUID().toString(),
    val kind:WorkflowStepKind=WorkflowStepKind.PROMPT,
    val title:String,
    val instruction:String,
    val enabled:Boolean=true
)

data class WorkflowDefinition(
    val id:String=UUID.randomUUID().toString(),
    val name:String,
    val description:String,
    val steps:List<WorkflowStep>
)

data class WorkflowRunResult(val output:String,val completedSteps:Int,val stepOutputs:List<String>)

object WorkflowDefinitionCodec {
    fun encode(items:List<WorkflowDefinition>):String = JSONArray().apply {
        items.take(100).forEach { workflow ->
            put(JSONObject().put("id",workflow.id).put("name",workflow.name).put("description",workflow.description)
                .put("steps",JSONArray().apply { workflow.steps.take(20).forEach { step ->
                    put(JSONObject().put("id",step.id).put("kind",step.kind.name).put("title",step.title)
                        .put("instruction",step.instruction).put("enabled",step.enabled))
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
                else WorkflowStep(step.optString("id").ifBlank { UUID.randomUUID().toString() },kind,title,instruction,step.optBoolean("enabled",true))
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
        }
    }
}

object WorkflowEngine {
    suspend fun run(
        workflow:WorkflowDefinition,
        userInput:String,
        invokeModel:suspend (WorkflowStep,String)->String,
        onStep:(Int,WorkflowStep)->Unit={}
    ):WorkflowRunResult {
        WorkflowDefinitionCodec.validate(workflow)
        require(userInput.isNotBlank() && userInput.length<=12_000) { "工作流输入为空或过长" }
        var previousOutput=""
        val outputs=mutableListOf<String>()
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
            val result=invokeModel(step,prompt).trim()
            require(result.isNotBlank()) { "步骤“${step.title}”没有产生有效结果" }
            previousOutput=result
            outputs += result
        }
        return WorkflowRunResult(previousOutput,outputs.size,outputs)
    }
}
