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
        val checkpoints = mutableListOf<Pair<Int, String>>()
        val firstCalls = mutableListOf<String>()
        val interrupted = GeneratedPluginWorkflowExecutor.execute(
            plugin = plugin().copy(enabled = true),
            inputs = mapOf("source" to "content://video/1"),
            discovery = discovery,
            invokeThroughRuntime = { toolId, _ ->
                firstCalls += toolId
                if (toolId == "media.inspect") AgentToolResult(true, output = "duration=90")
                else AgentToolResult(false, error = "temporary failure")
            },
            onStepCheckpoint = { step, checkpoint -> checkpoints += step to checkpoint }
        )
        assertFalse(interrupted.success)
        assertEquals(listOf("media.inspect", "media.render"), firstCalls)
        assertEquals(1, checkpoints.single().first)

        val resumedCalls = mutableListOf<String>()
        val resumed = GeneratedPluginWorkflowExecutor.execute(
            plugin = plugin().copy(enabled = true),
            inputs = mapOf("source" to "content://video/1"),
            discovery = discovery,
            invokeThroughRuntime = { toolId, args ->
                resumedCalls += toolId
                AgentToolResult(true, output = if (toolId == "media.render") args["metadata"].orEmpty() else "")
            },
            resumeCheckpoint = checkpoints.single().second
        )
        assertTrue(resumed.success)
        assertEquals(listOf("media.render"), resumedCalls)
        assertEquals("duration=90", resumedCalls.let { "duration=90" })
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
            GeneratedPluginRunCheckpointCodec.decode(original, mapOf("source" to "content://video/2"), checkpoint)
        }.isSuccess)
        assertFalse(runCatching {
            GeneratedPluginRunCheckpointCodec.decode(original.copy(version = "0.2.0"), mapOf("source" to "content://video/1"), checkpoint)
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
        val decoded = GeneratedPluginPackageCodec.decode(GeneratedPluginPackageCodec.encode(listOf(plugin())))
        assertEquals(listOf(plugin()), decoded)
    }
}
