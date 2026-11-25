package app.nestro.data

import app.nestro.agent.AgentToolDiscovery
import app.nestro.agent.AgentToolPermission
import app.nestro.agent.AgentToolSpec
import app.nestro.agent.AgentToolResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneratedPluginFactoryTest {
    private val discovery = object : AgentToolDiscovery {
        private val specs = listOf(
            AgentToolSpec("media.inspect", "Inspect media metadata", AgentToolPermission.READ_ONLY),
            AgentToolSpec("media.render", "Render media output", AgentToolPermission.EXTERNAL_WRITE)
        )
        override fun find(toolId: String) = specs.firstOrNull { it.id == toolId }
        override fun list() = specs
    }

    private fun plugin() = GeneratedPluginPackage(
        id = "media.video-cut",
        name = "短视频剪辑流水线",
        version = "0.1.0",
        description = "先分析素材，再生成剪辑成品。",
        inputs = listOf(GeneratedPluginInput("source", true, "原始视频素材引用")),
        steps = listOf(
            GeneratedPluginStep("inspect", "media.inspect", mapOf("source" to "\${input.source}"), "metadata"),
            GeneratedPluginStep("render", "media.render", mapOf("source" to "\${input.source}", "metadata" to "\${steps.metadata.output}"), "video")
        ),
        outputDescription = "剪辑后的视频文件引用"
    )

    @Test
    fun pluginPermissionPolicyRequiresConfirmationForExternalWrites() {
        val readOnly = AgentToolSpec("media.inspect", "Inspect media metadata", AgentToolPermission.READ_ONLY)
        val externalWrite = AgentToolSpec("media.render", "Render media output", AgentToolPermission.EXTERNAL_WRITE)
        val confirmedRead = AgentToolSpec("media.export", "Export metadata", AgentToolPermission.READ_ONLY, requiresConfirmation = true)
        val deviceAction = AgentToolSpec("phone.click", "Click a device control", AgentToolPermission.DEVICE_ACTION)

        assertEquals(app.nestro.agent.AgentAuthorization.Allow, GeneratedPluginAuthorizationPolicy.authorize(readOnly))
        assertTrue(GeneratedPluginAuthorizationPolicy.authorize(externalWrite) is app.nestro.agent.AgentAuthorization.RequireConfirmation)
        assertTrue(GeneratedPluginAuthorizationPolicy.authorize(confirmedRead) is app.nestro.agent.AgentAuthorization.RequireConfirmation)
        assertTrue(GeneratedPluginAuthorizationPolicy.authorize(deviceAction) is app.nestro.agent.AgentAuthorization.Deny)
    }

    @Test
    fun validatorAllowsOnlyRegisteredToolsAndPriorStepReferences() {
        GeneratedPluginPackageValidator.validate(plugin(), discovery)
        val invalid = plugin().copy(steps = listOf(
            GeneratedPluginStep("first", "media.inspect", emptyMap(), "first"),
            GeneratedPluginStep("second", "media.render", mapOf("x" to "\${steps.future.output}"), "second")
        ))
        assertFalse(runCatching { GeneratedPluginPackageValidator.validate(invalid, discovery) }.isSuccess)
    }

    @Test
    fun executorRoutesEveryStepThroughInjectedRuntime() = runBlocking {
        val calls = mutableListOf<Pair<String, Map<String, String>>>()
        val result = GeneratedPluginWorkflowExecutor.execute(
            plugin = plugin().copy(enabled = true),
            inputs = mapOf("source" to "content://video/1"),
            discovery = discovery,
            invokeThroughRuntime = { toolId, args ->
                calls += toolId to args
                AgentToolResult(true, output = if (toolId == "media.inspect") "duration=90" else "content://video/output")
            }
        )
        assertTrue(result.success)
        assertEquals(listOf("media.inspect", "media.render"), calls.map { it.first })
        assertEquals("duration=90", calls.last().second["metadata"])
    }

    @Test
    fun executorCheckpointsEachSuccessfulStepAndResumesWithoutReplayingIt() = runBlocking {
        val resumePlugin = plugin().copy(
            enabled = true,
            steps = plugin().steps + GeneratedPluginStep(
                "verify", "media.inspect",
                mapOf("source" to "\${steps.video.output}"), "verified"
            )
        )
        val checkpoints = mutableListOf<Pair<Int, String>>()
        val firstCalls = mutableListOf<String>()
        val interrupted = GeneratedPluginWorkflowExecutor.execute(
            plugin = resumePlugin,
            inputs = mapOf("source" to "content://video/1"),
            discovery = discovery,
            invokeThroughRuntime = { toolId, _ ->
                firstCalls += toolId
                when (firstCalls.size) {
                    1 -> AgentToolResult(true, output = "duration=90")
                    2 -> AgentToolResult(true, output = "content://video/output")
                    else -> AgentToolResult(false, error = "temporary read-only failure")
                }
            },
            onStepCheckpoint = { step, checkpoint -> checkpoints += step to checkpoint }
        )
        assertFalse(interrupted.success)
        assertEquals(listOf("media.inspect", "media.render", "media.inspect"), firstCalls)
        val saved = checkpoints.last()
        assertEquals(2, saved.first)
        assertEquals(2, GeneratedPluginRunCheckpointCodec.decode(
            resumePlugin, mapOf("source" to "content://video/1"), saved.second, discovery
        ).size)

        val resumedCalls = mutableListOf<String>()
        val resumed = GeneratedPluginWorkflowExecutor.execute(
            plugin = resumePlugin,
            inputs = mapOf("source" to "content://video/1"),
            discovery = discovery,
            invokeThroughRuntime = { toolId, args ->
                resumedCalls += toolId
                assertEquals("content://video/output", args["source"])
                AgentToolResult(true, output = "verified")
            },
            resumeCheckpoint = saved.second
        )
        assertTrue(resumed.success)
        assertEquals(listOf("media.inspect"), resumedCalls)
        assertTrue(resumed.output.contains("duration=90"))
    }

    @Test
    fun executorRefusesToReplayAnInterruptedExternalWrite() = runBlocking {
        // 同一个插件实例：createdAt 默认取当前毫秒并计入检查点指纹，
        // 执行与恢复若各调一次 plugin()，指纹失配会先抛“检查点与当前插件版本或输入不匹配”，
        // 到不了“外部写入”断言。
        val pluginUnderTest = plugin().copy(enabled = true)
        val checkpoints = mutableListOf<String>()
        val result = runCatching {
            GeneratedPluginWorkflowExecutor.execute(
                plugin = pluginUnderTest,
                inputs = mapOf("source" to "content://video/1"),
                discovery = discovery,
                invokeThroughRuntime = { toolId, _ ->
                    if (toolId == "media.inspect") AgentToolResult(true, output = "duration=90")
                    else throw kotlinx.coroutines.CancellationException("process interrupted")
                },
                onStepCheckpoint = { _, checkpoint -> checkpoints += checkpoint }
            )
        }
        assertTrue(result.isFailure)
        val interruptedCheckpoint = checkpoints.last()
        val resumeError = runCatching {
            GeneratedPluginRunCheckpointCodec.decode(
                pluginUnderTest,
                mapOf("source" to "content://video/1"),
                interruptedCheckpoint,
                discovery
            )
        }.exceptionOrNull()
        assertTrue(resumeError?.message?.contains("外部写入") == true)
    }

    @Test
    fun pluginCheckpointRejectsChangedInputOrPluginDefinition() {
        val original = plugin().copy(enabled = true)
        val checkpoint = GeneratedPluginRunCheckpointCodec.encode(
            original,
            mapOf("source" to "content://video/1"),
            listOf(GeneratedPluginStepResult("inspect", "media.inspect", true, "duration=90"))
        )
        assertFalse(runCatching {
            GeneratedPluginRunCheckpointCodec.decode(original, mapOf("source" to "content://video/2"), checkpoint, discovery)
        }.isSuccess)
        assertFalse(runCatching {
            GeneratedPluginRunCheckpointCodec.decode(original.copy(version = "0.2.0"), mapOf("source" to "content://video/1"), checkpoint, discovery)
        }.isSuccess)
    }

    @Test
    fun installedPluginPinsToolContractsAndRejectsChangedCapabilities() {
        val pinned = GeneratedPluginPackageValidator.pinToolContracts(plugin(), discovery)
        assertTrue(pinned.steps.all { it.toolFingerprint.length == 64 })
        GeneratedPluginPackageValidator.validate(pinned, discovery)

        val changedDiscovery = object : AgentToolDiscovery {
            override fun find(toolId: String): AgentToolSpec? = when (toolId) {
                "media.inspect" -> AgentToolSpec("media.inspect", "Changed inspection semantics", AgentToolPermission.READ_ONLY)
                "media.render" -> AgentToolSpec("media.render", "Render media output", AgentToolPermission.EXTERNAL_WRITE)
                else -> null
            }
            override fun list() = listOfNotNull(find("media.inspect"), find("media.render"))
        }
        assertFalse(runCatching {
            GeneratedPluginPackageValidator.validate(pinned, changedDiscovery)
        }.isSuccess)

        val restored = GeneratedPluginPackageCodec.decode(
            GeneratedPluginPackageCodec.encode(listOf(pinned))
        ).single()
        assertEquals(pinned, restored)
    }

    @Test
    fun packageCodecPreservesDraftAndDisabledDefault() {
        // 同一个插件实例：createdAt 默认取当前毫秒，两次 plugin() 相差几毫秒即判不等；
        // arguments 顺序差异只是打印显示，Map 相等与顺序无关。
        val original = plugin()
        val decoded = GeneratedPluginPackageCodec.decode(GeneratedPluginPackageCodec.encode(listOf(original)))
        assertEquals(listOf(original), decoded)
    }
}
