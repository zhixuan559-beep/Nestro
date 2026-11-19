package app.nestro.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

data class ExpertTeamMemberTask(
    val name:String,
    val rolePrompt:String
)

object ExpertTeamDefaults {
    fun rolePrompt(name:String):String = when(name) {
        "文案官" -> "负责内容表达与文案质量：梳理受众、核心信息、结构、语气和传播表达，指出空话与表达风险。"
        "选题狐" -> "负责选题与用户洞察：识别真实需求、内容切口、差异化角度、受众痛点和选题验证方式。"
        "代码佬" -> "负责技术分析与实现判断：评估技术可行性、系统结构、实现步骤、边界条件和工程风险。"
        else -> ""
    }
}

data class ExpertTeamMemberRun(
    val name:String,
    val status:String,
    val attempts:Int,
    val elapsedMillis:Long,
    val output:String="",
    val error:String=""
)

data class ExpertTeamRunResult(
    val teamName:String,
    val task:String,
    val startedAt:Long,
    val finishedAt:Long,
    val members:List<ExpertTeamMemberRun>,
    val projectId:String = "default"
) {
    val successfulMembers:Int get()=members.count { it.status=="success" }
    val failedMembers:Int get()=members.count { it.status=="failed" }
    val isUsable:Boolean get()=successfulMembers>0
    fun toContext(maxChars:Int=10_000):String {
        require(maxChars in 1000..20_000)
        val header="专家团队“$teamName”的成员独立分析如下。请比较各成员依据，识别冲突和不确定点，再给出综合结论。\n\n"
        val body=members.joinToString("\n\n") { member ->
            if(member.status=="success" && member.output.isNotBlank()) {
                "【${member.name}】\n${member.output.take(2200)}"
            } else {
                "【${member.name}】本轮分析未完成：${member.error.ifBlank { "调用失败" }}。请勿将其视为已提供意见。"
            }
        }
        return (header+body).take(maxChars)
    }
}

object ExpertTeamEngine {
    suspend fun run(
        teamName:String,
        teamDescription:String,
        task:String,
        members:List<ExpertTeamMemberTask>,
        maxRetries:Int=1,
        timeoutMillis:Long=45_000,
        maxParallel:Int=4,
        shouldStop:()->Boolean={false},
        onMember:(suspend (String,String)->Unit)?=null,
        invoke:suspend (ExpertTeamMemberTask,String)->String
    ):ExpertTeamRunResult {
        require(teamName.isNotBlank() && teamName.length<=40) { "团队名称无效" }
        require(teamDescription.isNotBlank() && teamDescription.length<=300) { "团队分工不能为空" }
        require(task.isNotBlank() && task.length<=12_000) { "团队任务为空或过长" }
        require(members.size in 2..4) { "专家团队必须有 2 到 4 名有效成员" }
        require(members.map { it.name }.distinct().size==members.size) { "专家团队成员不能重名" }
        require(members.all { it.name.isNotBlank() && it.rolePrompt.isNotBlank() }) { "团队成员缺少角色指令" }
        require(maxRetries in 0..2 && timeoutMillis in 5_000..120_000 && maxParallel in 1..4) { "团队执行参数超出安全范围" }
        val started=System.currentTimeMillis()
        val semaphore=Semaphore(maxParallel)
        val results=coroutineScope {
            members.map { member ->
                async {
                    semaphore.withPermit {
                        val memberStart=System.currentTimeMillis()
                        var attempts=0
                        var output=""
                        var errorText=""
                        while(attempts<=maxRetries && output.isBlank()) {
                            if(shouldStop()) throw CancellationException("用户停止专家团")
                            attempts++
                            onMember?.invoke(member.name,if(attempts==1) "running" else "retrying")
                            val memberPrompt=buildString {
                                append("你是专家团队“").append(teamName).append("”中的成员“").append(member.name).append("”。\n")
                                append("你的专业角色：").append(member.rolePrompt.take(4000)).append("\n")
                                append("团队共同目标：").append(teamDescription).append("\n\n")
                                append("本次任务：\n").append(task.take(8000)).append("\n\n")
                                append("请独立完成分析，给出结论、关键依据、风险、未知项和可执行建议。")
                                append("不要假装拥有其他成员的意见，不要编造事实；证据不足时明确说明。")
                                append("回答控制在 2200 字符以内。")
                            }
                            try {
                                output=withTimeout(timeoutMillis) { invoke(member,memberPrompt).trim().take(2200) }
                                if(shouldStop()) throw CancellationException("用户停止专家团")
                                require(output.isNotBlank()) { "模型返回空结果" }
                            } catch(timeout: kotlinx.coroutines.TimeoutCancellationException) {
                                errorText="成员执行超时（${timeoutMillis/1000} 秒）"
                            } catch(cancelled:CancellationException) {
                                throw cancelled
                            } catch(error:Exception) {
                                errorText=error.message?.take(500) ?: "成员调用失败"
                            }
                        }
                        ExpertTeamMemberRun(
                            name=member.name,
                            status=if(output.isNotBlank()) "success" else "failed",
                            attempts=attempts,
                            elapsedMillis=System.currentTimeMillis()-memberStart,
                            output=output,
                            error=if(output.isBlank()) errorText else ""
                        ).also { onMember?.invoke(member.name,it.status) }
                    }
                }
            }.awaitAll()
        }
        return ExpertTeamRunResult(teamName,task.take(12000),started,System.currentTimeMillis(),results)
    }

    /**
     * Runs a task through the smallest useful expert chain. One expert starts;
     * additional experts are invoked only when the current expert explicitly
     * requests a bounded handoff using [[DELEGATE: name | subtask]].
     */
    suspend fun runCollaborative(
        teamName: String,
        teamDescription: String,
        task: String,
        members: List<ExpertTeamMemberTask>,
        maxDelegations: Int = 3,
        timeoutMillis: Long = 45_000,
        shouldStop: () -> Boolean = { false },
        onMember: (suspend (String, String) -> Unit)? = null,
        invoke: suspend (ExpertTeamMemberTask, String) -> String
    ): ExpertTeamRunResult {
        require(teamName.isNotBlank() && teamName.length <= 40)
        require(teamDescription.isNotBlank() && teamDescription.length <= 300)
        require(task.isNotBlank() && task.length <= 12_000)
        require(members.size in 2..4 && members.map { it.name }.distinct().size == members.size)
        require(members.all { it.name.isNotBlank() && it.rolePrompt.isNotBlank() })
        require(maxDelegations in 0..3 && timeoutMillis in 5_000..120_000)
        val started = System.currentTimeMillis()
        val byName = members.associateBy { it.name }
        val visited = linkedSetOf<String>()
        val results = mutableListOf<ExpertTeamMemberRun>()
        var current: ExpertTeamMemberTask? = selectPrimaryExpert(task, members)
        var currentTask = task
        var handoff = ""
        var delegationCount = 0

        while (current != null && results.size < maxDelegations + 1) {
            if (shouldStop()) throw CancellationException("用户停止专家协作")
            val activeMember = current ?: break
            if (!visited.add(activeMember.name)) break
            val memberStarted = System.currentTimeMillis()
            var attempts = 0
            var output = ""
            var errorText = ""
            while (attempts < 2 && output.isBlank()) {
                if (shouldStop()) throw CancellationException("用户停止专家协作")
                attempts++
                onMember?.invoke(activeMember.name, if (attempts == 1) "running" else "retrying")
                val candidates = members.map { it.name }.filter { it !in visited }
                val prompt = buildString {
                    append("你是专家团队“").append(teamName).append("”中的成员“").append(activeMember.name).append("”。\n")
                    append("专业角色：").append(activeMember.rolePrompt.take(3000)).append("\n")
                    append("团队目标：").append(teamDescription).append("\n\n")
                    append("本轮子任务：\n").append(currentTask.take(5000)).append("\n\n")
                    if (handoff.isNotBlank()) append("前序专家交接（仅供参考）：\n").append(handoff.take(1800)).append("\n\n")
                    append("请完成自己的专业工作，给出结论、依据、风险和未知项。")
                    append("不要重复前序专家已经完成的工作。")
                    if (candidates.isNotEmpty() && delegationCount < maxDelegations) {
                        append("\n如果确实需要其他专业能力，可以请求一名成员继续处理。可选成员：")
                        append(candidates.joinToString("、"))
                        append("。请求时必须在最后单独输出：[[DELEGATE:成员名称 | 明确的子任务]]。")
                    }
                    append("如果不需要协作，不要输出委派标记。不要请求自己或重复请求已参与成员。")
                    append("回答控制在 1800 字符以内。")
                }
                try {
                    output = withTimeout(timeoutMillis) { invoke(activeMember, prompt).trim().take(2200) }
                    require(output.isNotBlank()) { "模型返回空结果" }
                } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                    errorText = "成员执行超时（" + (timeoutMillis / 1000) + " 秒）"
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    errorText = error.message?.take(500) ?: "成员调用失败"
                }
            }
            val delegation = DELEGATION_PATTERN.find(output)
            val cleanOutput = output.replace(DELEGATION_PATTERN, "").trim().take(2200)
            val memberResult = ExpertTeamMemberRun(
                name = activeMember.name,
                status = if (cleanOutput.isNotBlank()) "success" else "failed",
                attempts = attempts,
                elapsedMillis = System.currentTimeMillis() - memberStarted,
                output = cleanOutput,
                error = if (cleanOutput.isBlank()) errorText.ifBlank { "成员未返回有效结果" } else ""
            )
            results += memberResult
            onMember?.invoke(activeMember.name, memberResult.status)
            if (cleanOutput.isBlank()) break

            val request = delegation?.groupValues?.getOrNull(1)?.trim()
            val subtask = delegation?.groupValues?.getOrNull(2)?.trim()
            val target = request?.let { byName[it] }
            if (target == null || target.name in visited || subtask.isNullOrBlank() || delegationCount >= maxDelegations) break

            handoff = "【" + activeMember.name + "交接】" + cleanOutput.take(1200)
            currentTask = subtask.take(2000)
            current = target
            delegationCount++
        }
        return ExpertTeamRunResult(teamName, task.take(12_000), started, System.currentTimeMillis(), results)
    }

    fun eligibleMembers(members: List<ExpertTeamMemberTask>, maxMembers: Int = 4): List<ExpertTeamMemberTask> =
        members.filter { it.name.isNotBlank() && it.rolePrompt.isNotBlank() }
            .distinctBy { it.name }.take(maxMembers.coerceIn(1, 4))

    fun planInitialTeam(task: String, members: List<ExpertTeamMemberTask>): List<ExpertTeamMemberTask> {
        val eligible = eligibleMembers(members)
        if (eligible.isEmpty()) return emptyList()
        val primary = selectPrimaryExpert(task, eligible)
        val secondary = eligible.filter { it.name != primary.name }
            .sortedByDescending { relevance(task, it) }
            .take(2)
        return listOf(primary) + secondary
    }

    private fun relevance(task: String, member: ExpertTeamMemberTask): Int {
        val text = task.lowercase()
        val role = (member.name + " " + member.rolePrompt).lowercase()
        val terms = listOf("代码","开发","技术","安卓","接口","软件","运营","商业","销售","增长","内容","文案","写作","研究","数据","分析","调研")
        return terms.count { text.contains(it) && role.contains(it) }
    }

    private fun selectPrimaryExpert(task: String, members: List<ExpertTeamMemberTask>): ExpertTeamMemberTask {
        val text = task.lowercase()
        val domains = listOf(
            listOf("文章", "文案", "写作", "公众号", "知乎", "内容") to listOf("文案", "内容", "写作", "表达", "选题"),
            listOf("代码", "开发", "bug", "接口", "安卓", "软件", "技术") to listOf("技术", "代码", "开发", "程序", "软件", "接口"),
            listOf("运营", "商业", "销售", "增长", "转化", "市场") to listOf("运营", "商业", "销售", "增长", "转化", "市场"),
            listOf("调研", "研究", "数据", "证据", "比较", "分析") to listOf("研究", "调研", "数据", "证据", "分析")
        )
        return members.maxByOrNull { member ->
            val role = (member.name + " " + member.rolePrompt).lowercase()
            domains.count { domain: Pair<List<String>, List<String>> ->
                val taskTerms = domain.first
                val roleTerms = domain.second
                taskTerms.any { term -> text.contains(term) } && roleTerms.any { term -> role.contains(term) }
            }
        } ?: members.first()
    }

    private val DELEGATION_PATTERN = Regex("""\[\[DELEGATE:\s*([^|]+)\|\s*(.+?)]]""", RegexOption.DOT_MATCHES_ALL)

}

object ExpertTeamRunHistoryCodec {
    fun encode(records:List<ExpertTeamRunResult>):String =
        JSONArray().apply {
            records.take(300).forEach { record ->
                put(JSONObject()
                    .put("teamName",record.teamName)
                    .put("task",record.task.take(1200))
                    .put("startedAt",record.startedAt)
                    .put("finishedAt",record.finishedAt)
                    .put("projectId",record.projectId)
                    .put("members",JSONArray().apply {
                        record.members.forEach { member ->
                            put(JSONObject()
                                .put("name",member.name)
                                .put("status",member.status)
                                .put("attempts",member.attempts)
                                .put("elapsedMillis",member.elapsedMillis)
                                .put("output",member.output.take(2200))
                                .put("error",member.error.take(500)))
                        }
                    }))
            }
        }.toString()

    fun decode(raw:String):List<ExpertTeamRunResult> = runCatching {
        require(raw.length<=3_000_000) { "团队运行记录过大" }
        val array=JSONArray(raw)
        (0 until minOf(array.length(),300)).mapNotNull { index ->
            val item=array.optJSONObject(index) ?: return@mapNotNull null
            val members=item.optJSONArray("members") ?: JSONArray()
            val decoded=(0 until minOf(members.length(),4)).mapNotNull { memberIndex ->
                val member=members.optJSONObject(memberIndex) ?: return@mapNotNull null
                val status=member.optString("status")
                if(status !in setOf("success","failed")) return@mapNotNull null
                ExpertTeamMemberRun(
                    name=member.optString("name").take(40),
                    status=status,
                    attempts=member.optInt("attempts").coerceIn(0,3),
                    elapsedMillis=member.optLong("elapsedMillis").coerceAtLeast(0),
                    output=member.optString("output").take(2200),
                    error=member.optString("error").take(500)
                )
            }
            if(item.optString("teamName").isBlank() || decoded.isEmpty()) null else
                ExpertTeamRunResult(
                    teamName=item.optString("teamName").take(40),
                    task=item.optString("task").take(1200),
                    startedAt=item.optLong("startedAt").coerceAtLeast(0),
                    finishedAt=item.optLong("finishedAt").coerceAtLeast(0),
                    members=decoded,
                    projectId=item.optString("projectId", "default").ifBlank { "default" }
                )
        }
    }.getOrDefault(emptyList())
}
