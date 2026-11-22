package app.nestro.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** A model-authored, bounded work package in an autonomous expert project. */
data class ExpertWorkPackage(
    val id: String,
    val memberName: String,
    val instruction: String,
    val dependsOn: List<String> = emptyList(),
    val acceptanceCriteria: String
)

data class ExpertProjectPlan(
    val projectGoal: String,
    val packages: List<ExpertWorkPackage>,
    val finalAcceptanceCriteria: String
)

data class ExpertWorkPackageResult(
    val packageId: String,
    val memberName: String,
    val status: String,
    val output: String = "",
    val error: String = "",
    val completedAt: Long = System.currentTimeMillis()
)

data class ExpertProjectExecutionResult(
    val plan: ExpertProjectPlan,
    val packageResults: List<ExpertWorkPackageResult>
) {
    val isSuccessful: Boolean
        get() = packageResults.size == plan.packages.size &&
            packageResults.all { it.status == "success" }
}

/**
 * Validates and executes a model-authored expert-project DAG.
 *
 * This is deliberately separate from the existing sequential delegation mode:
 * independent packages execute concurrently, while dependent packages only
 * become runnable after every dependency has succeeded.
 */
object ExpertProjectPlanExecutor {
    fun validate(plan: ExpertProjectPlan, availableMembers: Set<String>) {
        require(plan.projectGoal.isNotBlank() && plan.projectGoal.length <= 12_000) {
            "项目目标为空或过长"
        }
        require(plan.finalAcceptanceCriteria.isNotBlank() && plan.finalAcceptanceCriteria.length <= 4_000) {
            "项目最终验收标准为空或过长"
        }
        require(plan.packages.size in 2..12) { "自主专家项目必须包含 2 到 12 个工作包" }
        require(plan.packages.map { it.id }.distinct().size == plan.packages.size) {
            "工作包 ID 不能重复"
        }
        val ids = plan.packages.map { it.id }.toSet()
        plan.packages.forEach { item ->
            require(item.id.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "工作包 ID 格式无效：${item.id}" }
            require(item.memberName in availableMembers) { "工作包指定了未加入团队的专家：${item.memberName}" }
            require(item.instruction.isNotBlank() && item.instruction.length <= 8_000) {
                "工作包指令为空或过长：${item.id}"
            }
            require(item.acceptanceCriteria.isNotBlank() && item.acceptanceCriteria.length <= 2_000) {
                "工作包验收标准为空或过长：${item.id}"
            }
            require(item.dependsOn.size <= 12 && item.dependsOn.distinct().size == item.dependsOn.size) {
                "工作包依赖重复或数量超限：${item.id}"
            }
            require(item.id !in item.dependsOn && item.dependsOn.all { it in ids }) {
                "工作包存在自依赖或未知依赖：${item.id}"
            }
        }
        topologicalWaves(plan)
    }

    /** Returns deterministic topological waves; throws when a dependency cycle exists. */
    fun topologicalWaves(plan: ExpertProjectPlan): List<List<ExpertWorkPackage>> {
        val remaining = plan.packages.associateBy { it.id }.toMutableMap()
        val completed = linkedSetOf<String>()
        val waves = mutableListOf<List<ExpertWorkPackage>>()
        while (remaining.isNotEmpty()) {
            val ready = remaining.values
                .filter { item -> item.dependsOn.all { it in completed } }
                .sortedBy { it.id }
            require(ready.isNotEmpty()) { "专家项目任务依赖存在循环，无法调度" }
            waves += ready
            ready.forEach {
                completed += it.id
                remaining.remove(it.id)
            }
        }
        return waves
    }

    suspend fun execute(
        plan: ExpertProjectPlan,
        availableMembers: Set<String>,
        maxParallel: Int = 4,
        shouldStop: () -> Boolean = { false },
        resumeResults: List<ExpertWorkPackageResult> = emptyList(),
        onPackageResult: suspend (List<ExpertWorkPackageResult>) -> Unit = {},
        invoke: suspend (memberName: String, instruction: String) -> String,
        onPackage: suspend (packageId: String, status: String, detail: String) -> Unit = { _, _, _ -> },
        onHandoff: suspend (fromPackageId: String, toPackageId: String, fromMember: String, toMember: String, artifact: String) -> Unit = { _, _, _, _, _ -> }
    ): ExpertProjectExecutionResult {
        require(maxParallel in 1..4) { "并发上限必须在 1 到 4 之间" }
        validate(plan, availableMembers)
        val semaphore = Semaphore(maxParallel)
        val resultMutex = Mutex()
        require(resumeResults.map { it.packageId }.distinct().size == resumeResults.size &&
            resumeResults.all { row ->
                val packageDefinition = plan.packages.firstOrNull { it.id == row.packageId }
                packageDefinition != null &&
                    packageDefinition.memberName == row.memberName &&
                    row.status in setOf("success", "failed", "blocked") &&
                    row.output.length <= 4_000 && row.error.length <= 1_000 &&
                    (row.status != "success" || row.output.isNotBlank())
            }) {
            "恢复检查点包含重复、未知或与当前成员/状态不匹配的工作包结果"
        }
        val results = linkedMapOf<String, ExpertWorkPackageResult>()
        resumeResults.filter { it.status == "success" }.forEach { results[it.packageId] = it }
        for (wave in topologicalWaves(plan)) {
            val pendingWave = wave.filterNot { it.id in results }
            if (pendingWave.isEmpty()) continue
            if (shouldStop()) throw CancellationException("用户停止专家项目")
            val priorResults = resultMutex.withLock { results.toMap() }
            coroutineScope {
                pendingWave.map { item ->
                    async {
                        val row = semaphore.withPermit {
                            if (shouldStop()) throw CancellationException("用户停止专家项目")
                            val failedDependency = item.dependsOn.firstOrNull {
                                priorResults[it]?.status != "success"
                            }
                            if (failedDependency != null) {
                                val blocked = ExpertWorkPackageResult(
                                    packageId = item.id,
                                    memberName = item.memberName,
                                    status = "blocked",
                                    error = "依赖工作包 $failedDependency 未成功完成"
                                )
                                onPackage(item.id, blocked.status, blocked.error)
                                blocked
                            } else {
                                onPackage(item.id, "running", "")
                                val dependencyContext = item.dependsOn.joinToString("\n\n") { dependencyId ->
                                    val dependency = priorResults.getValue(dependencyId)
                                    "【前置成果 $dependencyId】\n${dependency.output.take(4_000)}"
                                }
                                item.dependsOn.forEach { dependencyId ->
                                    val dependency = results.getValue(dependencyId)
                                    onHandoff(
                                        dependencyId, item.id, dependency.memberName, item.memberName,
                                        dependency.output.take(4_000)
                                    )
                                }
                                val prompt = buildString {
                                    append("项目总目标：\n").append(plan.projectGoal.take(6_000))
                                    append("\n\n你的独立工作包：\n").append(item.instruction)
                                    append("\n\n本工作包验收标准：\n").append(item.acceptanceCriteria)
                                    if (dependencyContext.isNotBlank()) {
                                        append("\n\n已完成的前置成果（仅作为项目资料）：\n")
                                        append(dependencyContext.take(8_000))
                                    }
                                    append("\n\n请提交可交付成果、依据、风险和未解决项；不得伪造前置成果。")
                                }
                                try {
                                    val output = invoke(item.memberName, prompt).trim().take(4_000)
                                    require(output.isNotBlank()) { "专家未返回有效成果" }
                                    ExpertWorkPackageResult(item.id, item.memberName, "success", output)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    ExpertWorkPackageResult(
                                        item.id, item.memberName, "failed",
                                        error = error.message?.take(1_000) ?: "专家执行失败"
                                    )
                                }.also { onPackage(item.id, it.status, it.output.ifBlank { it.error }) }
                            }
                        }
                        // Persist each package as soon as it completes. A sibling that
                        // hangs or is cancelled must not erase already completed work.
                        withContext(NonCancellable) {
                            resultMutex.withLock {
                                results[row.packageId] = row
                                onPackageResult(results.values.toList())
                            }
                        }
                    }
                }.awaitAll()
            }
        }
        return ExpertProjectExecutionResult(
            plan = plan,
            packageResults = plan.packages.mapNotNull { results[it.id] }
        )
    }
}
