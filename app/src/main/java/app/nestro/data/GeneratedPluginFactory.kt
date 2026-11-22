package app.nestro.data

import app.nestro.agent.AgentToolDiscovery
import app.nestro.agent.AgentToolResult
import android.content.Context
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.security.MessageDigest

/** Enforces confirmation for plugin capabilities that can change external state. */
object GeneratedPluginAuthorizationPolicy {
    fun authorize(tool: app.nestro.agent.AgentToolSpec): app.nestro.agent.AgentAuthorization = when {
        tool.permission == app.nestro.agent.AgentToolPermission.READ_ONLY && !tool.requiresConfirmation ->
            app.nestro.agent.AgentAuthorization.Allow
        tool.permission == app.nestro.agent.AgentToolPermission.EXTERNAL_WRITE || tool.requiresConfirmation ->
            app.nestro.agent.AgentAuthorization.RequireConfirmation("插件步骤需要用户确认：${tool.id}")
        else ->
            app.nestro.agent.AgentAuthorization.Deny("生成插件不能执行该高风险能力：${tool.id}")
    }
}

data class GeneratedPluginInput(
    val name: String,
    val required: Boolean,
    val description: String
)

data class GeneratedPluginStep(
    val id: String,
    val toolId: String,
    val arguments: Map<String, String>,
    val resultKey: String,
    val toolFingerprint: String = ""
)

data class GeneratedPluginPackage(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val inputs: List<GeneratedPluginInput>,
    val steps: List<GeneratedPluginStep>,
    val outputDescription: String,
    val createdAt: Long = System.currentTimeMillis(),
    val enabled: Boolean = false
)

data class GeneratedPluginStepResult(
    val stepId: String,
    val toolId: String,
    val success: Boolean,
    val output: String = "",
    val error: String = "",
    val requiresUser: Boolean = false
)

data class GeneratedPluginExecutionResult(
    val pluginId: String,
    val steps: List<GeneratedPluginStepResult>
) {
    val success: Boolean get() = steps.isNotEmpty() && steps.all { it.success }
    val requiresUser: Boolean get() = steps.any { it.requiresUser }
    val output: String get() = steps.joinToString("\n\n") {
        "【${it.stepId}】\n${it.output.ifBlank { it.error }}"
    }.take(16_000)
}

object GeneratedPluginPackageValidator {
    private val idPattern = Regex("[a-z][a-z0-9]*(?:[.-][a-z0-9]+){1,5}")
    private val versionPattern = Regex("(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)(?:-[0-9A-Za-z.-]+)?")

    fun validate(plugin: GeneratedPluginPackage, discovery: AgentToolDiscovery) {
        require(idPattern.matches(plugin.id)) { "插件 ID 必须使用稳定的小写命名空间格式" }
        require(versionPattern.matches(plugin.version)) { "插件版本必须符合 SemVer" }
        require(plugin.name.isNotBlank() && plugin.name.length <= 60)
        require(plugin.description.isNotBlank() && plugin.description.length <= 1_000)
        require(plugin.outputDescription.isNotBlank() && plugin.outputDescription.length <= 1_000)
        require(plugin.inputs.size <= 24 && plugin.inputs.map { it.name }.distinct().size == plugin.inputs.size)
        plugin.inputs.forEach {
            require(it.name.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,63}")))
            require(it.description.length <= 500)
        }
        require(plugin.steps.size in 1..12) { "插件工作流必须包含 1 到 12 个步骤" }
        require(plugin.steps.map { it.id }.distinct().size == plugin.steps.size)
        val seenResults = mutableSetOf<String>()
        plugin.steps.forEach { step ->
            require(step.id.matches(Regex("[A-Za-z0-9_-]{1,64}")))
            require(step.resultKey.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,63}")))
            require(!step.toolId.startsWith("plugin.")) { "插件不能递归调用其他生成插件" }
            val toolSpec = discovery.find(step.toolId)
                ?: error("插件引用了未注册的工具：${step.toolId}")
            require(step.toolFingerprint.isBlank() ||
                step.toolFingerprint == toolFingerprint(toolSpec)) {
                "插件依赖工具契约已变化，请重新审查并更新插件：${step.toolId}"
            }
            require(step.toolId.length in 1..100)
            require(toolSpec.permission in setOf(
                app.nestro.agent.AgentToolPermission.READ_ONLY,
                app.nestro.agent.AgentToolPermission.EXTERNAL_WRITE
            )) { "插件不能组合设备控制、身份验证或金融类高风险工具：${step.toolId}" }
            require(step.arguments.size <= 24 && step.arguments.all { (key, value) ->
                key.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,63}")) && value.length <= 2_000
            })
            step.arguments.values.forEach { template ->
                val matches = TEMPLATE.findAll(template).toList()
                val residue = TEMPLATE.replace(template, "")
                require(!residue.contains("\\${") && !residue.contains("}")) {
                    "插件参数模板包含不支持的表达式"
                }
                matches.forEach { match ->
                    val reference = match.groupValues[1]
                    if (reference.startsWith("input.")) {
                        val inputName = reference.removePrefix("input.")
                        require(plugin.inputs.any { it.name == inputName }) { "插件引用了未声明输入：$inputName" }
                    } else if (reference.startsWith("steps.")) {
                        val resultName = reference.removePrefix("steps.").removeSuffix(".output")
                        require(reference.endsWith(".output") && resultName in seenResults) {
                            "插件只能引用前序步骤已产生的结果：$reference"
                        }
                    } else {
                        error("插件模板仅允许引用 input.* 或前序 steps.*.output")
                    }
                }
            }
            require(step.resultKey !in seenResults) { "插件步骤结果键不能重复：${step.resultKey}" }
            seenResults += step.resultKey
        }
    }

    fun toolFingerprint(spec: app.nestro.agent.AgentToolSpec): String =
        MessageDigest.getInstance("SHA-256")
            .digest(
                listOf(spec.id, spec.permission.name, spec.requiresConfirmation.toString(), spec.description)
                    .joinToString("\n").toByteArray(Charsets.UTF_8)
            ).joinToString("") { "%02x".format(it) }

    fun pinToolContracts(plugin: GeneratedPluginPackage, discovery: AgentToolDiscovery): GeneratedPluginPackage {
        validate(plugin, discovery)
        return plugin.copy(steps = plugin.steps.map { step ->
            val spec = discovery.find(step.toolId) ?: error("插件引用了未注册的工具：${step.toolId}")
            step.copy(toolFingerprint = toolFingerprint(spec))
        })
    }

    internal val TEMPLATE = Regex("""\$\{([^}]{1,100})\}""")
}


object GeneratedPluginPackageCodec {
    fun encode(packages: List<GeneratedPluginPackage>): String =
        JSONArray().apply {
            packages.takeLast(100).forEach { plugin ->
                put(JSONObject()
                    .put("id", plugin.id)
                    .put("name", plugin.name)
                    .put("version", plugin.version)
                    .put("description", plugin.description)
                    .put("outputDescription", plugin.outputDescription)
                    .put("createdAt", plugin.createdAt)
                    .put("enabled", plugin.enabled)
                    .put("inputs", JSONArray().apply {
                        plugin.inputs.forEach { input ->
                            put(JSONObject().put("name", input.name).put("required", input.required).put("description", input.description))
                        }
                    })
                    .put("steps", JSONArray().apply {
                        plugin.steps.forEach { step ->
                            put(JSONObject()
                                .put("id", step.id)
                                .put("toolId", step.toolId)
                                .put("resultKey", step.resultKey)
                                .put("toolFingerprint", step.toolFingerprint)
                                .put("arguments", JSONObject(step.arguments)))
                        }
                    }))
            }
        }.toString()

    fun inputSchema(plugin: GeneratedPluginPackage): JSONObject {
        val properties = JSONObject()
        val required = JSONArray()
        plugin.inputs.forEach { input ->
            properties.put(input.name, JSONObject().put("type", "string").put("description", input.description.take(300)))
            if (input.required) required.put(input.name)
        }
        return JSONObject().put("type", "object").put("properties", properties)
            .put("additionalProperties", false).apply {
                if (required.length() > 0) put("required", required)
            }
    }

    fun decode(raw: String): List<GeneratedPluginPackage> = runCatching {
        require(raw.length <= 2_000_000) { "插件包数据过大" }
        val array = JSONArray(raw)
        (0 until minOf(array.length(), 100)).mapNotNull { index ->
            val root = array.optJSONObject(index) ?: return@mapNotNull null
            val inputsJson = root.optJSONArray("inputs") ?: JSONArray()
            val stepsJson = root.optJSONArray("steps") ?: JSONArray()
            val inputs = (0 until minOf(inputsJson.length(), 24)).mapNotNull { itemIndex ->
                val item = inputsJson.optJSONObject(itemIndex) ?: return@mapNotNull null
                GeneratedPluginInput(item.optString("name"), item.optBoolean("required"), item.optString("description").take(500))
            }
            val steps = (0 until minOf(stepsJson.length(), 12)).mapNotNull { itemIndex ->
                val item = stepsJson.optJSONObject(itemIndex) ?: return@mapNotNull null
                val args = item.optJSONObject("arguments") ?: JSONObject()
                val mapped = buildMap {
                    val keys = args.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        put(key, args.optString(key).take(2_000))
                    }
                }
                GeneratedPluginStep(
                    item.optString("id"), item.optString("toolId"), mapped,
                    item.optString("resultKey"), item.optString("toolFingerprint")
                )
            }
            GeneratedPluginPackage(
                id = root.optString("id"), name = root.optString("name"),
                version = root.optString("version"), description = root.optString("description").take(1_000),
                inputs = inputs, steps = steps, outputDescription = root.optString("outputDescription").take(1_000),
                createdAt = root.optLong("createdAt").coerceAtLeast(0), enabled = root.optBoolean("enabled", false)
            ).takeIf { it.id.isNotBlank() && it.steps.isNotEmpty() }
        }
    }.getOrDefault(emptyList())
}

/**
 * Local install/enable lifecycle for declarative plugins. API keys are never stored
 * in plugin packages; all step execution still passes through the AgentRuntime callback.
 */
class GeneratedPluginStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("nestro_generated_plugins_v1", Context.MODE_PRIVATE)

    @Synchronized
    fun list(discovery: AgentToolDiscovery? = null): List<GeneratedPluginPackage> {
        val decoded = GeneratedPluginPackageCodec.decode(prefs.getString(KEY_PACKAGES, "[]").orEmpty())
        val normalized = if (discovery == null) decoded else decoded.map { plugin ->
            if (plugin.steps.any { it.toolFingerprint.isBlank() }) {
                runCatching { GeneratedPluginPackageValidator.pinToolContracts(plugin, discovery) }.getOrDefault(plugin)
            } else plugin
        }
        if (normalized != decoded) persist(normalized)
        return normalized.mapNotNull { plugin ->
            if (discovery == null) plugin else runCatching {
                GeneratedPluginPackageValidator.validate(plugin, discovery)
                plugin
            }.getOrNull()
        }.sortedWith(compareBy<GeneratedPluginPackage> { it.name.lowercase() }.thenBy { it.id })
    }

    fun find(pluginId: String, discovery: AgentToolDiscovery? = null): GeneratedPluginPackage? =
        list(discovery).firstOrNull { it.id == pluginId }

    @Synchronized
    fun install(plugin: GeneratedPluginPackage, discovery: AgentToolDiscovery): GeneratedPluginPackage {
        val pinned = GeneratedPluginPackageValidator.pinToolContracts(plugin, discovery)
        val current = list()
        require(current.none { it.id == plugin.id }) { "插件 ID 已安装，请使用版本更新流程" }
        require(current.size < MAX_PLUGINS) { "本地插件数量已达到上限" }
        val installed = pinned.copy(enabled = false)
        persist(current + installed)
        return installed
    }

    @Synchronized
    fun setEnabled(pluginId: String, enabled: Boolean, discovery: AgentToolDiscovery): GeneratedPluginPackage {
        val current = list()
        val existing = current.firstOrNull { it.id == pluginId } ?: error("插件不存在")
        val pinned = GeneratedPluginPackageValidator.pinToolContracts(existing, discovery)
        val updated = pinned.copy(enabled = enabled)
        persist(current.map { if (it.id == pluginId) updated else it })
        return updated
    }

    @Synchronized
    fun remove(pluginId: String): Boolean {
        val current = list()
        if (current.none { it.id == pluginId }) return false
        check(prefs.edit()
            .putString(KEY_PACKAGES, GeneratedPluginPackageCodec.encode(current.filterNot { it.id == pluginId }))
            .remove(historyKey(pluginId))
            .commit()) { "插件卸载失败" }
        return true
    }

    @Synchronized
    fun replaceVersion(plugin: GeneratedPluginPackage, discovery: AgentToolDiscovery): GeneratedPluginPackage {
        val pinned = GeneratedPluginPackageValidator.pinToolContracts(plugin, discovery)
        val current = list()
        val existing = current.firstOrNull { it.id == plugin.id } ?: error("待更新插件不存在")
        require(compareSemVer(pinned.version, existing.version) > 0) { "插件更新版本必须高于当前版本" }
        val updated = pinned.copy(enabled = false)
        val history = historyVersions(plugin.id)
        persistWithHistory(
            current.map { if (it.id == plugin.id) updated else it },
            plugin.id,
            (history + existing).takeLast(MAX_HISTORY_VERSIONS)
        )
        return updated
    }

    @Synchronized
    fun historyVersions(pluginId: String): List<GeneratedPluginPackage> =
        GeneratedPluginPackageCodec.decode(prefs.getString(historyKey(pluginId), "[]").orEmpty())

    @Synchronized
    fun rollbackPrevious(pluginId: String, discovery: AgentToolDiscovery): GeneratedPluginPackage {
        val current = list()
        val active = current.firstOrNull { it.id == pluginId } ?: error("插件不存在")
        val history = historyVersions(pluginId)
        val previous = history.lastOrNull() ?: error("没有可回滚的插件版本")
        val pinned = GeneratedPluginPackageValidator.pinToolContracts(previous, discovery)
        val restored = pinned.copy(enabled = false)
        persistWithHistory(
            current.map { if (it.id == pluginId) restored else it },
            pluginId,
            history.dropLast(1) + active
        )
        return restored
    }

    private fun persistWithHistory(
        packages: List<GeneratedPluginPackage>,
        pluginId: String,
        versions: List<GeneratedPluginPackage>
    ) {
        check(prefs.edit()
            .putString(KEY_PACKAGES, GeneratedPluginPackageCodec.encode(packages))
            .putString(historyKey(pluginId), GeneratedPluginPackageCodec.encode(versions))
            .commit()) { "插件版本变更写入失败" }
    }

    private fun historyKey(pluginId: String) = "history_${pluginId}"

    private fun persist(packages: List<GeneratedPluginPackage>) {
        check(prefs.edit().putString(KEY_PACKAGES, GeneratedPluginPackageCodec.encode(packages)).commit()) {
            "插件安装库写入失败"
        }
    }

    private fun compareSemVer(left: String, right: String): Int {
        fun parts(value: String): Pair<List<Int>, String?> {
            val core = value.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            return core to value.substringAfter('-', "").takeIf { it.isNotBlank() }
        }
        val (leftCore, leftPre) = parts(left)
        val (rightCore, rightPre) = parts(right)
        for (index in 0..2) {
            val diff = leftCore.getOrElse(index) { 0 }.compareTo(rightCore.getOrElse(index) { 0 })
            if (diff != 0) return diff
        }
        return when {
            leftPre == null && rightPre != null -> 1
            leftPre != null && rightPre == null -> -1
            else -> (leftPre ?: "").compareTo(rightPre ?: "")
        }
    }

    private companion object {
        const val KEY_PACKAGES = "installed_packages"
        const val MAX_PLUGINS = 100
        const val MAX_HISTORY_VERSIONS = 5
    }
}

internal fun parseGeneratedPluginPackage(raw: String): GeneratedPluginPackage {
    val start = raw.indexOf('{')
    val end = raw.lastIndexOf('}')
    require(start >= 0 && end > start) { "模型没有返回插件 JSON" }
    val root = JSONObject(raw.substring(start, end + 1))
    val inputsArray = root.optJSONArray("inputs") ?: JSONArray()
    val stepsArray = root.getJSONArray("steps")
    val inputs = (0 until inputsArray.length()).map { index ->
        val item = inputsArray.getJSONObject(index)
        GeneratedPluginInput(
            name = item.optString("name"),
            required = item.optBoolean("required", false),
            description = item.optString("description").take(500)
        )
    }
    val steps = (0 until stepsArray.length()).map { index ->
        val item = stepsArray.getJSONObject(index)
        val args = item.optJSONObject("arguments") ?: JSONObject()
        val mapped = buildMap {
            val keys = args.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                put(key, args.optString(key).take(2_000))
            }
        }
        GeneratedPluginStep(
            id = item.optString("id"),
            toolId = item.optString("tool_id"),
            arguments = mapped,
            resultKey = item.optString("result_key")
        )
    }
    return GeneratedPluginPackage(
        id = root.optString("id"),
        name = root.optString("name"),
        version = root.optString("version"),
        description = root.optString("description").take(1_000),
        inputs = inputs,
        steps = steps,
        outputDescription = root.optString("output_description").take(1_000)
    )
}

internal fun interface GeneratedPluginDraftTransport {
    fun complete(
        configs: List<ModelConfig>,
        history: List<ChatMessage>,
        systemPrompt: String,
        onUsage: (ModelConfig, Int, Int) -> Unit
    ): String
}

internal class GeneratedPluginFactory(
    private val transport: GeneratedPluginDraftTransport
) {
    fun generate(
        request: String,
        existingPluginIds: Set<String>,
        discovery: AgentToolDiscovery,
        allowExistingPluginId: String? = null,
        configs: List<ModelConfig>,
        onUsage: (ModelConfig, Int, Int) -> Unit
    ): GeneratedPluginPackage {
        require(request.isNotBlank() && request.length <= 8_000)
        val toolDirectory = discovery.list().filterNot { it.id.startsWith("plugin.") }
            .filter { it.permission in setOf(app.nestro.agent.AgentToolPermission.READ_ONLY, app.nestro.agent.AgentToolPermission.EXTERNAL_WRITE) }
            .take(100).joinToString("\n") { spec ->
            "- ${spec.id}: ${spec.description} (permission=${spec.permission.name}, confirmation=${spec.requiresConfirmation})"
        }
        require(toolDirectory.isNotBlank()) { "当前没有可组合的已注册工具，无法创建可执行插件" }
        val system = """
你是 Nestro 的受限插件工厂。把用户需求设计成一个可安装的声明式工具工作流插件。
只返回 JSON，不要 Markdown。字段：id、name、version、description、inputs、steps、output_description。
id 使用小写命名空间，例如 media.video-brief；version 使用 SemVer。
inputs 是 [{name,required,description}]。
steps 是按顺序执行的数组，每项为 {id,tool_id,arguments,result_key}。
tool_id 必须来自下方当前工具目录；arguments 的值只能是普通文本，或 ${'$'}{input.field} / ${'$'}{steps.previous_result.output} 模板。
当前工具目录：
$toolDirectory
禁止生成 Kotlin/Java/Python/JavaScript/shell 代码，禁止任意网络、文件、设备权限声明，禁止引用不存在的工具。
插件只组合现有已注册的只读或外部写入工具，不能组合设备控制、身份验证、金融工具或其他生成插件；所有执行仍由 Nestro 统一 AgentRuntime 和权限路由处理。
已存在的插件 ID：${existingPluginIds.take(100).joinToString(",").ifBlank { "无" }}。
用户需求：${request}
""".trimIndent()
        val raw = transport.complete(
            configs,
            listOf(ChatMessage(UUID.randomUUID().toString(), "user", request)),
            system,
            onUsage
        )
        val plugin = parseGeneratedPluginPackage(raw)
        require(plugin.id !in existingPluginIds || plugin.id == allowExistingPluginId) { "插件 ID 已存在，请生成新的唯一 ID" }
        GeneratedPluginPackageValidator.validate(plugin, discovery)
        return plugin
    }
}

object GeneratedPluginRunCheckpointCodec {
    private const val PREFIX = "generated_plugin_run:"
    private const val SCHEMA_VERSION = 1
    private const val MAX_STEP_OUTPUT = 2_000

    fun encode(
        plugin: GeneratedPluginPackage,
        inputs: Map<String, String>,
        completedSteps: List<GeneratedPluginStepResult>,
        inProgressStepId: String = "",
        inProgressPermission: app.nestro.agent.AgentToolPermission? = null
    ): String {
        val valid = completedSteps.take(plugin.steps.size)
        require(valid.all { it.success && !it.requiresUser })
        require(inProgressStepId.isBlank() == (inProgressPermission == null))
        if (inProgressStepId.isNotBlank()) {
            require(plugin.steps.getOrNull(valid.size)?.id == inProgressStepId) {
                "插件正在执行的步骤必须紧跟已完成步骤"
            }
        }
        val payload = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("pluginId", plugin.id)
            .put("pluginVersion", plugin.version)
            .put("pluginFingerprint", pluginFingerprint(plugin))
            .put("inputFingerprint", fingerprintInputs(inputs))
            .put("inProgressStepId", inProgressStepId)
            .put("inProgressPermission", inProgressPermission?.name.orEmpty())
            .put("completedSteps", JSONArray().apply {
                valid.forEachIndexed { index, result ->
                    val expected = plugin.steps[index]
                    require(result.stepId == expected.id && result.toolId == expected.toolId) {
                        "插件检查点步骤顺序与当前定义不匹配"
                    }
                    put(JSONObject()
                        .put("stepId", result.stepId)
                        .put("toolId", result.toolId)
                        .put("output", result.output.take(MAX_STEP_OUTPUT)))
                }
            })
        return PREFIX + payload.toString().also {
            require(it.length <= 64_000) { "插件检查点超过 64 KB" }
        }
    }

    fun decode(
        plugin: GeneratedPluginPackage,
        inputs: Map<String, String>,
        checkpoint: String,
        discovery: AgentToolDiscovery
    ): List<GeneratedPluginStepResult> {
        if (checkpoint.isBlank()) return emptyList()
        require(checkpoint.startsWith(PREFIX) && checkpoint.length <= 64_000) {
            "插件检查点格式无效或超过 64 KB"
        }
        val payload = JSONObject(checkpoint.removePrefix(PREFIX))
        require(payload.optInt("schemaVersion") == SCHEMA_VERSION &&
            payload.optString("pluginId") == plugin.id &&
            payload.optString("pluginVersion") == plugin.version &&
            payload.optString("pluginFingerprint") == pluginFingerprint(plugin) &&
            payload.optString("inputFingerprint") == fingerprintInputs(inputs)) {
            "插件检查点与当前插件版本或输入不匹配，已停止恢复"
        }
        val rows = payload.optJSONArray("completedSteps") ?: JSONArray()
        require(rows.length() <= plugin.steps.size) { "插件检查点包含多余步骤" }
        val inProgressStepId = payload.optString("inProgressStepId")
        val inProgressPermission = payload.optString("inProgressPermission")
        if (inProgressStepId.isNotBlank()) {
            val pending = plugin.steps.getOrNull(rows.length())
                ?: error("插件检查点声明了不存在的执行中步骤")
            require(pending.id == inProgressStepId) { "插件检查点执行中步骤顺序无效" }
            val currentSpec = discovery.find(pending.toolId)
                ?: error("插件执行中步骤依赖的工具已不存在")
            require(currentSpec.permission.name == inProgressPermission) {
                "插件执行中步骤的权限定义已变化，已停止恢复"
            }
            require(currentSpec.permission == app.nestro.agent.AgentToolPermission.READ_ONLY) {
                "上一步外部写入的执行结果不确定。为避免重复提交，系统已停止自动恢复，请先核对外部操作结果。"
            }
        } else {
            require(inProgressPermission.isBlank()) { "插件检查点包含无效的执行中权限标记" }
        }
        return (0 until rows.length()).map { index ->
            val row = rows.optJSONObject(index) ?: error("插件检查点步骤数据损坏")
            val expected = plugin.steps[index]
            require(row.optString("stepId") == expected.id &&
                row.optString("toolId") == expected.toolId) {
                "插件检查点步骤与当前插件定义不匹配，已停止恢复"
            }
            GeneratedPluginStepResult(
                stepId = expected.id,
                toolId = expected.toolId,
                success = true,
                output = row.optString("output").take(MAX_STEP_OUTPUT)
            )
        }
    }

    private fun pluginFingerprint(plugin: GeneratedPluginPackage): String =
        sha256(GeneratedPluginPackageCodec.encode(listOf(plugin.copy(enabled = false))))

    fun fingerprintInputs(inputs: Map<String, String>): String =
        sha256(JSONObject().apply {
            inputs.toSortedMap().forEach { (key, value) -> put(key, value) }
        }.toString())

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

object GeneratedPluginWorkflowExecutor {
    suspend fun execute(
        plugin: GeneratedPluginPackage,
        inputs: Map<String, String>,
        discovery: AgentToolDiscovery,
        invokeThroughRuntime: suspend (String, Map<String, String>) -> AgentToolResult,
        resumeCheckpoint: String = "",
        onStepCheckpoint: suspend (Int, String) -> Unit = { _, _ -> }
    ): GeneratedPluginExecutionResult {
        require(plugin.enabled) { "插件尚未启用，不能执行" }
        GeneratedPluginPackageValidator.validate(plugin, discovery)
        val inputDefinitions = plugin.inputs.associateBy { it.name }
        require(inputs.keys.all { it in inputDefinitions }) { "调用包含插件未声明的输入" }
        plugin.inputs.filter { it.required }.forEach { field ->
            require(!inputs[field.name].isNullOrBlank()) { "缺少必填插件输入：${field.name}" }
        }
        val restoredSteps = GeneratedPluginRunCheckpointCodec.decode(plugin, inputs, resumeCheckpoint, discovery)
        val results = linkedMapOf<String, String>()
        restoredSteps.forEachIndexed { index, result ->
            results[plugin.steps[index].resultKey] = result.output
        }
        val stepResults = restoredSteps.toMutableList()
        for ((stepIndex, step) in plugin.steps.withIndex()) {
            if (stepIndex < restoredSteps.size) continue
            val arguments = step.arguments.mapValues { (_, template) ->
                GeneratedPluginPackageValidator.TEMPLATE.replace(template) { match ->
                    val reference = match.groupValues[1]
                    when {
                        reference.startsWith("input.") -> inputs[reference.removePrefix("input.")] ?: ""
                        reference.startsWith("steps.") -> {
                            val key = reference.removePrefix("steps.").removeSuffix(".output")
                            results[key] ?: error("前序步骤结果不存在：$key")
                        }
                        else -> error("不支持的插件模板引用")
                    }
                }.take(2_000)
            }
            val toolSpec = discovery.find(step.toolId) ?: error("插件引用的工具已不存在：${step.toolId}")
            onStepCheckpoint(
                stepIndex,
                GeneratedPluginRunCheckpointCodec.encode(
                    plugin, inputs, stepResults,
                    inProgressStepId = step.id,
                    inProgressPermission = toolSpec.permission
                )
            )
            val result = try {
                invokeThroughRuntime(step.toolId, arguments)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AgentToolResult(false, error = error.message?.take(1_000) ?: "插件步骤执行失败")
            }
            if (!result.success || result.requiresUser) {
                if (result.requiresUser) {
                    onStepCheckpoint(
                        stepIndex,
                        GeneratedPluginRunCheckpointCodec.encode(plugin, inputs, stepResults)
                    )
                }
                val failure = GeneratedPluginStepResult(
                    step.id, step.toolId, false,
                    error = if (result.requiresUser) "该工具需要用户确认：${result.error}" else result.error.ifBlank { result.output },
                    requiresUser = result.requiresUser
                )
                stepResults += failure
                break
            }
            val output = result.output.take(2_000)
            results[step.resultKey] = output
            stepResults += GeneratedPluginStepResult(step.id, step.toolId, true, output)
            onStepCheckpoint(
                stepIndex + 1,
                GeneratedPluginRunCheckpointCodec.encode(plugin, inputs, stepResults)
            )
        }
        return GeneratedPluginExecutionResult(plugin.id, stepResults)
    }
}
