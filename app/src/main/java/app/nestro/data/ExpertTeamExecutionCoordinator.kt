package app.nestro.data

import app.nestro.agent.AgentTaskCoordinator
import app.nestro.agent.AgentTaskMetadata
import app.nestro.agent.AgentCapabilityRegistry
import app.nestro.agent.AgentToolDiscovery
import app.nestro.agent.AgentToolCatalog
import app.nestro.agent.AgentToolLeaseRegistry
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

enum class ExpertTeamSchedulingStrategy {
    /** User assigns each member a work package; independent work runs concurrently. */
    PARALLEL_WORK_PACKAGES,
    /** The lead expert may hand off bounded subtasks to other members. */
    LEAD_HANDOFF
}

/**
 * Owns expert-team execution and its shared AgentTaskCoordinator lifecycle.
 * Compose callers provide model invocation and UI progress callbacks; the
 * coordinator owns task registration, checkpoints, retries, and cancellation.
 */
class ExpertTeamExecutionCoordinator(
    private val taskCoordinator: AgentTaskCoordinator
) {
    suspend fun execute(
        teamId: String,
        teamName: String,
        teamDescription: String,
        task: String,
        members: List<ExpertTeamMemberTask>,
        strategy: ExpertTeamSchedulingStrategy = ExpertTeamSchedulingStrategy.LEAD_HANDOFF,
        taskId: String = UUID.randomUUID().toString(),
        projectId: String = "default",
        shouldStop: () -> Boolean = { false },
        onMember: suspend (String, String) -> Unit = { _, _ -> },
        eventSink: ExpertTeamEventSink? = null,
        invokeMember: suspend (ExpertTeamMemberTask, String) -> String,
        toolCatalog: AgentToolCatalog? = null,
        invokeMemberWithTools: (suspend (ExpertTeamMemberTask, String, AgentToolDiscovery?) -> String)? = null
    ): ExpertTeamRunResult {
        val teamIdentity = JSONObject()
            .put("teamId", teamId)
            .put("projectId", projectId)
            .put("teamName", teamName)
            .put("teamDescription", teamDescription)
            .put("task", task)
            .put("strategy", strategy.name)
            .put("members", JSONArray().apply {
                members.forEach { member ->
                    put(JSONObject().put("name", member.name)
                        .put("rolePrompt", member.rolePrompt)
                        .put("assignedTask", member.assignedTask))
                }
            }).toString()
        val teamFingerprint = MessageDigest.getInstance("SHA-256")
            .digest(teamIdentity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val savedPayload = taskCoordinator.state(taskId)?.checkpoint
            ?.takeIf { it.startsWith("team_run:") }
            ?.removePrefix("team_run:")
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (savedPayload != null) {
            require(savedPayload.optString("teamFingerprint") == teamFingerprint) {
                "专家团队检查点与当前团队、任务或成员配置不匹配，已停止恢复"
            }
        }
        val resumeMembers = if (strategy == ExpertTeamSchedulingStrategy.PARALLEL_WORK_PACKAGES) savedPayload?.optJSONArray("members")?.let { rows ->
            (0 until rows.length()).mapNotNull { index ->
                val row = rows.optJSONObject(index) ?: return@mapNotNull null
                ExpertTeamMemberRun(
                    name = row.optString("name"),
                    status = row.optString("status"),
                    attempts = row.optInt("attempts").coerceIn(0, 3),
                    elapsedMillis = row.optLong("elapsedMillis").coerceAtLeast(0),
                    output = row.optString("output").take(2_200),
                    error = row.optString("error").take(500)
                )
            }
        }.orEmpty() else emptyList()
        val resumeCollaborative = savedPayload?.optJSONObject("collaborative")?.let { item ->
            val completed = item.optJSONArray("completedMembers") ?: JSONArray()
            val visited = item.optJSONArray("visitedMembers") ?: JSONArray()
            ExpertTeamCollaborativeCheckpoint(
                completedMembers = (0 until completed.length()).mapNotNull { index ->
                    val row = completed.optJSONObject(index) ?: return@mapNotNull null
                    ExpertTeamMemberRun(
                        name = row.optString("name"),
                        status = row.optString("status"),
                        attempts = row.optInt("attempts").coerceIn(0, 2),
                        elapsedMillis = row.optLong("elapsedMillis").coerceAtLeast(0),
                        output = row.optString("output").take(2_200),
                        error = row.optString("error").take(500)
                    )
                },
                visitedMembers = (0 until visited.length()).mapNotNull { index ->
                    visited.optString(index).takeIf { it.isNotBlank() }
                },
                nextMemberName = item.optString("nextMemberName"),
                nextTask = item.optString("nextTask").take(2_000),
                handoff = item.optString("handoff").take(2_000),
                delegationCount = item.optInt("delegationCount").coerceIn(0, 3)
            )
        }
        fun encodeCollaborativeCheckpoint(checkpoint: ExpertTeamCollaborativeCheckpoint): JSONObject =
            JSONObject()
                .put("completedMembers", JSONArray().apply {
                    checkpoint.completedMembers.forEach { row ->
                        put(JSONObject()
                            .put("name", row.name).put("status", row.status)
                            .put("attempts", row.attempts).put("elapsedMillis", row.elapsedMillis)
                            .put("output", row.output.take(2_200)).put("error", row.error.take(500)))
                    }
                })
                .put("visitedMembers", JSONArray(checkpoint.visitedMembers))
                .put("nextMemberName", checkpoint.nextMemberName)
                .put("nextTask", checkpoint.nextTask.take(2_000))
                .put("handoff", checkpoint.handoff.take(2_000))
                .put("delegationCount", checkpoint.delegationCount)
        val emptyCollaborativeCheckpoint = ExpertTeamCollaborativeCheckpoint()
        val initialCheckpoint = "team_run:" + JSONObject()
            .put("teamFingerprint", teamFingerprint)
            .put("members", JSONArray().apply {
                resumeMembers.forEach { row ->
                    put(JSONObject()
                        .put("name", row.name).put("status", row.status)
                        .put("attempts", row.attempts).put("elapsedMillis", row.elapsedMillis)
                        .put("output", row.output.take(2_200)).put("error", row.error.take(500)))
                }
            })
            .put("collaborative", encodeCollaborativeCheckpoint(resumeCollaborative ?: emptyCollaborativeCheckpoint))
            .toString()
        return taskCoordinator.runInline(
            taskId = taskId,
            owner = "expert_team",
            initialCheckpoint = initialCheckpoint,
            metadata = AgentTaskMetadata(
                projectId = projectId,
                kind = "expert_team",
                capabilityId = teamId,
                title = teamName.take(160),
                input = task.take(12_000)
            )
        ) { taskContext ->
        taskContext.checkpoint(initialCheckpoint, resumeMembers.size)
        var collaborativeCheckpoint = resumeCollaborative ?: emptyCollaborativeCheckpoint
        var eventSequence = 0L
        suspend fun emit(
            type: String,
            memberName: String = "",
            relatedMemberName: String = "",
            status: String = "",
            message: String = ""
        ) {
            eventSink?.append(
                ExpertTeamCollaborationEvent(
                    taskId = taskId, projectId = projectId, teamId = teamId,
                    sequence = eventSequence++, type = type, memberName = memberName,
                    relatedMemberName = relatedMemberName, status = status, message = message.take(3_000)
                )
            )
        }
        emit("team_started", status = "running", message = task.take(2_000))

        suspend fun invokeWithLease(member: ExpertTeamMemberTask, prompt: String): String {
            val lease = AgentCapabilityRegistry.openLease(
                taskId = taskId,
                owner = "expert_team",
                operation = member.name,
                catalog = toolCatalog
            )
            return try {
                if (lease != null && invokeMemberWithTools == null) {
                    error("专家成员 ${member.name} 声明了工具能力，但未提供工具感知的模型调用适配器")
                }
                invokeMemberWithTools?.invoke(member, prompt, lease?.access)
                    ?: invokeMember(member, prompt)
            } finally {
                lease?.let { AgentToolLeaseRegistry.release(it.id) }
            }
        }

        val result = when (strategy) {
            ExpertTeamSchedulingStrategy.PARALLEL_WORK_PACKAGES -> ExpertTeamEngine.run(
                teamName = teamName,
                teamDescription = teamDescription,
                task = task,
                members = members,
                maxParallel = 4,
                shouldStop = shouldStop,
                resumeMembers = resumeMembers,
                onMemberResult = { rows ->
                    taskContext.checkpoint(
                        "team_run:" + JSONObject()
                            .put("teamFingerprint", teamFingerprint)
                            .put("members", JSONArray().apply {
                                rows.forEach { row ->
                                    put(JSONObject()
                                        .put("name", row.name)
                                        .put("status", row.status)
                                        .put("attempts", row.attempts)
                                        .put("elapsedMillis", row.elapsedMillis)
                                        .put("output", row.output.take(2_200))
                                        .put("error", row.error.take(500)))
                                }
                            })
                            .put("collaborative", encodeCollaborativeCheckpoint(collaborativeCheckpoint))
                            .toString()
                    )
                },
                onMember = { name, status ->
                    onMember(name, status)
                    emit(when (status) { "running" -> "member_started"; "retrying" -> "member_retrying"; "success" -> "member_completed"; else -> "member_failed" },
                        memberName = name, status = status)
                },
                invoke = ::invokeWithLease
            )
            ExpertTeamSchedulingStrategy.LEAD_HANDOFF -> ExpertTeamEngine.runCollaborative(
                teamName = teamName,
                teamDescription = teamDescription,
                task = task,
                members = members,
                timeoutMillis = 45_000,
                shouldStop = shouldStop,
                onMember = { name, status ->
                    onMember(name, status)
                    emit(when (status) { "running" -> "member_started"; "retrying" -> "member_retrying"; "success" -> "member_completed"; else -> "member_failed" },
                        memberName = name, status = status)
                },
                onHandoff = { from, to, subtask, handoff ->
                    emit("member_handoff", memberName = from, relatedMemberName = to,
                        status = "requested", message = "子任务：" + subtask + "\n交接内容：" + handoff)
                },
                resumeCheckpoint = resumeCollaborative,
                onCheckpoint = { checkpoint ->
                    collaborativeCheckpoint = checkpoint
                    taskContext.checkpoint(
                        "team_run:" + JSONObject()
                            .put("teamFingerprint", teamFingerprint)
                            .put("members", JSONArray())
                            .put("collaborative", encodeCollaborativeCheckpoint(checkpoint))
                            .toString(),
                        checkpoint.completedMembers.size
                    )
                },
                invoke = ::invokeWithLease
            )
        }
        result.members.filter { it.status == "success" }.forEach { member ->
            emit("artifact_submitted", memberName = member.name, status = "submitted", message = member.output)
        }
        emit("team_completed", status = if (result.failedMembers == 0) "success" else "partial",
            message = "成功 " + result.successfulMembers + " 人，失败 " + result.failedMembers + " 人")
        taskContext.checkpoint(
            "team_run:" + JSONObject()
                .put("teamFingerprint", teamFingerprint)
                .put("members", JSONArray().apply {
                    result.members.forEach { row ->
                        put(JSONObject()
                            .put("name", row.name).put("status", row.status)
                            .put("attempts", row.attempts).put("elapsedMillis", row.elapsedMillis)
                            .put("output", row.output.take(2_200)).put("error", row.error.take(500)))
                    }
                })
                .put("collaborative", encodeCollaborativeCheckpoint(collaborativeCheckpoint))
                .toString(),
            result.members.size
        )
        result.copy(projectId = projectId)
        }
    }
    suspend fun executeProjectPlan(
        teamId: String,
        teamName: String,
        teamDescription: String,
        plan: ExpertProjectPlan,
        members: Map<String, ExpertTeamMemberTask>,
        projectId: String,
        taskId: String = UUID.randomUUID().toString(),
        shouldStop: () -> Boolean = { false },
        eventSink: ExpertTeamEventSink? = null,
        onPackage: suspend (String, String, String) -> Unit = { _, _, _ -> },
        invokeMember: suspend (ExpertTeamMemberTask, String) -> String,
        toolCatalog: AgentToolCatalog? = null,
        invokeMemberWithTools: (suspend (ExpertTeamMemberTask, String, AgentToolDiscovery?) -> String)? = null
    ): ExpertProjectExecutionResult {
        val planIdentityJson = JSONObject()
            .put("teamName", teamName)
            .put("teamDescription", teamDescription)
            .put("goal", plan.projectGoal)
            .put("acceptance", plan.finalAcceptanceCriteria)
            .put("members", JSONArray().apply {
                members.toSortedMap().forEach { (name, member) ->
                    put(JSONObject().put("name", name)
                        .put("rolePrompt", member.rolePrompt)
                        .put("assignedTask", member.assignedTask))
                }
            })
            .put("packages", JSONArray().apply {
                plan.packages.forEach { item ->
                    put(JSONObject()
                        .put("id", item.id)
                        .put("member", item.memberName)
                        .put("instruction", item.instruction)
                        .put("acceptance", item.acceptanceCriteria)
                        .put("dependsOn", JSONArray(item.dependsOn)))
                }
            }).toString()
        val planFingerprint = MessageDigest.getInstance("SHA-256")
            .digest(planIdentityJson.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val savedState = taskCoordinator.state(taskId)
        val savedPayload = savedState?.checkpoint
            ?.takeIf { it.startsWith("expert_project:") }
            ?.removePrefix("expert_project:")
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (savedPayload != null) {
            require(savedPayload.optString("teamId") == teamId &&
                savedPayload.optString("projectId") == projectId &&
                savedPayload.optString("planFingerprint") == planFingerprint) {
                "专家项目检查点与当前团队、项目或任务图不匹配，已停止恢复"
            }
        }
        val resumeResults = savedPayload?.optJSONArray("results")?.let { rows ->
            (0 until rows.length()).mapNotNull { index ->
                val row = rows.optJSONObject(index) ?: return@mapNotNull null
                ExpertWorkPackageResult(
                    packageId = row.optString("packageId"),
                    memberName = row.optString("memberName"),
                    status = row.optString("status"),
                    output = row.optString("output").take(4_000),
                    error = row.optString("error").take(500),
                    completedAt = row.optLong("completedAt")
                )
            }
        }.orEmpty()
        val initialCheckpoint = "expert_project:" + JSONObject()
            .put("teamId", teamId)
            .put("projectId", projectId)
            .put("planFingerprint", planFingerprint)
            .put("results", JSONArray())
            .toString()

        return taskCoordinator.runInline(
            taskId = taskId,
            owner = "expert_team_project",
            initialCheckpoint = initialCheckpoint
        ) { taskContext ->
            var sequence = 0L
            suspend fun emit(type: String, member: String = "", status: String = "", message: String = "") {
                eventSink?.append(
                    ExpertTeamCollaborationEvent(
                        taskId = taskId, projectId = projectId, teamId = teamId,
                        sequence = sequence++, type = type, memberName = member,
                        status = status, message = message.take(3_000)
                    )
                )
            }
            emit("team_started", status = "running", message = plan.projectGoal.take(2_000))
            val result = ExpertProjectPlanExecutor.execute(
                plan = plan,
                availableMembers = members.keys,
                shouldStop = shouldStop,
                resumeResults = resumeResults,
                invoke = { memberName, instruction ->
                    val member = members[memberName] ?: error("专家不在当前团队中：$memberName")
                    val lease = AgentCapabilityRegistry.openLease(
                        taskId = taskId, owner = "expert_team_project",
                        operation = member.name, catalog = toolCatalog
                    )
                    try {
                        if (lease != null && invokeMemberWithTools == null) {
                            error("专家成员 ${member.name} 声明了工具能力，但未提供工具感知的模型调用适配器")
                        }
                        invokeMemberWithTools?.invoke(member, instruction, lease?.access)
                            ?: invokeMember(member, instruction)
                    } finally {
                        lease?.let { AgentToolLeaseRegistry.release(it.id) }
                    }
                },
                onPackage = { packageId, status, detail ->
                    onPackage(packageId, status, detail)
                    val packageInfo = plan.packages.firstOrNull { it.id == packageId }
                    val eventType = when (status) {
                        "running" -> "member_started"
                        "success" -> "artifact_submitted"
                        "failed" -> "member_failed"
                        else -> "member_message"
                    }
                    emit(eventType, member = packageInfo?.memberName.orEmpty(), status = status,
                        message = "工作包 $packageId：$detail")
                },
                onPackageResult = { rows ->
                    taskContext.checkpoint(
                        "expert_project:" + JSONObject()
                            .put("teamId", teamId)
                            .put("projectId", projectId)
                            .put("planFingerprint", planFingerprint)
                            .put("results", JSONArray().apply {
                                rows.forEach { row ->
                                    put(JSONObject()
                                        .put("packageId", row.packageId)
                                        .put("memberName", row.memberName)
                                        .put("status", row.status)
                                        .put("output", row.output.take(4_000))
                                        .put("error", row.error.take(500))
                                        .put("completedAt", row.completedAt))
                                }
                            }).toString(),
                        rows.size
                    )
                },
                onHandoff = { fromPackage, toPackage, fromMember, toMember, artifact ->
                    emit(
                        "member_handoff",
                        member = fromMember,
                        status = "delivered",
                        message = "交接至 $toMember（$toPackage），来源工作包 $fromPackage：\n$artifact"
                    )
                }
            )
            emit("team_completed", status = if (result.isSuccessful) "success" else "partial",
                message = "工作包完成：${result.packageResults.count { it.status == "success" }}/${plan.packages.size}")
            taskContext.checkpoint(
                "expert_project:" + JSONObject()
                    .put("teamId", teamId)
                    .put("projectId", projectId)
                    .put("planFingerprint", planFingerprint)
                    .put("results", JSONArray().apply {
                        result.packageResults.forEach { row ->
                            put(JSONObject()
                                .put("packageId", row.packageId)
                                .put("memberName", row.memberName)
                                .put("status", row.status)
                                .put("output", row.output.take(4_000))
                                .put("error", row.error.take(500))
                                .put("completedAt", row.completedAt))
                        }
                    }).toString(),
                result.packageResults.size
            )
            result
        }
    }

}
