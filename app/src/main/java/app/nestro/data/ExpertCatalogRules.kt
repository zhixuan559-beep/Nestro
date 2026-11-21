package app.nestro.data

object ExpertCatalogRules {
    fun validateDefinition(name: String, prompt: String, oldName: String, existingNames: List<String>): String? {
        val cleanName = name.trim()
        val cleanPrompt = prompt.trim()
        if (cleanName.isBlank() || cleanName.length > 40) return "专家名称必须为 1 到 40 个字符"
        if (cleanPrompt.isBlank() || cleanPrompt.length > 8_000) return "专家角色指令必须为 1 到 8000 个字符"
        if (existingNames.any { it.equals(cleanName, ignoreCase = true) && !it.equals(oldName, ignoreCase = true) }) {
            return "已存在同名专家，请更换名称"
        }
        if (oldName.isBlank() && existingNames.size >= 100) return "专家数量已达到 100 个上限"
        if (oldName in setOf("文案官", "选题狐", "代码佬") && cleanName != oldName) return "内置专家不可改名"
        return null
    }

    fun resolveSelection(name: String, experts: List<String>, profiles: Map<String, ExpertProfile>): String =
        name.takeIf { it == "通用助手" || (it in experts && profiles[it]?.enabled != false) } ?: "通用助手"

    fun renameWorkflowReferences(workflows: List<WorkflowDefinition>, oldName: String, newName: String): List<WorkflowDefinition> =
        workflows.map { workflow ->
            workflow.copy(steps = workflow.steps.map { step ->
                if (step.kind == WorkflowStepKind.EXPERT && step.targetId.ifBlank { step.title } == oldName) step.copy(targetId = newName) else step
            })
        }
}
