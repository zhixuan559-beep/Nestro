package app.nestro.data

import org.json.JSONObject
import java.security.MessageDigest

/** Validates model-authored, package-scoped acceptance feedback before a rework run. */
internal object ExpertProjectReworkPolicy {
    fun apply(
        plan: ExpertProjectPlan,
        reworkRound: Int,
        reviewFeedbackJson: String
    ): ExpertProjectPlan {
        require(reworkRound in 0..2) { "专家项目最多允许两轮返工" }
        val rawFeedback = reviewFeedbackJson.trim()
        if (reworkRound == 0) {
            require(rawFeedback.isEmpty()) { "首次执行不能携带验收返工反馈" }
            return plan.copy(reworkRound = 0)
        }
        require(rawFeedback.isNotEmpty()) { "返工轮次必须提供逐工作包验收反馈" }
        val feedback = runCatching { JSONObject(rawFeedback) }
            .getOrElse { throw IllegalArgumentException("返工反馈必须是以工作包 ID 为键的 JSON 对象", it) }
        val packageIds = plan.packages.map { it.id }.toSet()
        val feedbackIds = feedback.keys().asSequence().toSet()
        require(packageIds.isNotEmpty() && feedbackIds == packageIds) {
            "返工计划必须只包含需要返工的工作包，并为每个工作包提供反馈"
        }
        val packages = plan.packages.map { item ->
            val rawNote = feedback.opt(item.id)
            require(rawNote is String) { "工作包 ${item.id} 的返工反馈必须是字符串" }
            val note = rawNote.trim()
            require(note.isNotEmpty()) { "工作包 ${item.id} 的返工反馈为空" }
            val instruction = item.instruction + "\n\n【第${reworkRound}轮验收返工要求】\n" + note
            require(instruction.length <= 8_000) { "工作包 ${item.id} 的返工指令超过 8000 字符" }
            item.copy(instruction = instruction)
        }
        return plan.copy(packages = packages, reworkRound = reworkRound)
    }
}

/** Produces an idempotent task identity that changes when a plan or rework round changes. */
internal object ExpertProjectTaskIdentity {
    fun create(
        baseTaskId: String,
        teamId: String,
        projectId: String,
        teamName: String,
        teamDescription: String,
        plan: ExpertProjectPlan,
        members: Map<String, ExpertTeamMemberTask>
    ): String {
        val identity = buildString {
            append(teamId).append('\n')
            append(projectId).append('\n')
            append(teamName).append('\n')
            append(teamDescription).append('\n')
            append(plan.reworkRound).append('\n')
            append(plan.projectGoal).append('\n')
            append(plan.finalAcceptanceCriteria).append('\n')
            plan.packages.sortedBy { it.id }.forEach { item ->
                append(item.id).append('|').append(item.memberName).append('|')
                    .append(item.instruction).append('|').append(item.acceptanceCriteria).append('|')
                    .append(item.dependsOn.sorted().joinToString(",")).append('\n')
            }
            members.toSortedMap().forEach { (name, member) ->
                append(name).append('|').append(member.rolePrompt).append('|')
                    .append(member.assignedTask).append('\n')
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        val suffix = digest.take(10).joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        return "${baseTaskId}-team-$suffix".take(120)
    }
}
