package app.nestro.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import app.nestro.agent.AgentToolPermission
import app.nestro.agent.AgentToolSpec
import app.nestro.agent.InMemoryAgentToolCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GeneratedPluginStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val catalog = InMemoryAgentToolCatalog().apply {
        register(AgentToolSpec("test.read", "Read test data", AgentToolPermission.READ_ONLY)) {
            app.nestro.agent.AgentToolResult(true, output = "ok")
        }
        register(AgentToolSpec("test.write", "Write test data", AgentToolPermission.EXTERNAL_WRITE)) {
            app.nestro.agent.AgentToolResult(true, output = "written")
        }
    }

    private fun plugin(version: String = "0.1.0") = GeneratedPluginPackage(
        id = "test.pipeline",
        name = "Test pipeline",
        version = version,
        description = "A local test workflow",
        inputs = listOf(GeneratedPluginInput("query", true, "Search query")),
        steps = listOf(GeneratedPluginStep("read", "test.read", mapOf("query" to "${input.query}"), "result")),
        outputDescription = "Workflow result"
    )

    @Test
    fun installEnableUpdateRollbackAndUninstall() {
        context.getSharedPreferences("nestro_generated_plugins_v1", Context.MODE_PRIVATE).edit().clear().commit()
        val store = GeneratedPluginStore(context)

        val installed = store.install(plugin(), catalog)
        assertFalse(installed.enabled)
        assertFalse(store.find("test.pipeline")!!.enabled)

        store.setEnabled("test.pipeline", true, catalog)
        assertTrue(store.find("test.pipeline")!!.enabled)

        store.replaceVersion(plugin("0.2.0"), catalog)
        assertFalse(store.find("test.pipeline")!!.enabled)
        assertEquals("0.1.0", store.historyVersions("test.pipeline").last().version)

        val rolledBack = store.rollbackPrevious("test.pipeline", catalog)
        assertEquals("0.1.0", rolledBack.version)
        assertFalse(rolledBack.enabled)
        assertEquals("0.2.0", store.historyVersions("test.pipeline").last().version)

        assertTrue(store.remove("test.pipeline"))
        assertTrue(store.list().isEmpty())
        assertTrue(store.historyVersions("test.pipeline").isEmpty())
    }

    @Test
    fun installRejectsHighRiskTools() {
        val riskyCatalog = InMemoryAgentToolCatalog().apply {
            register(AgentToolSpec("device.tap", "Tap device UI", AgentToolPermission.DEVICE_ACTION)) {
                app.nestro.agent.AgentToolResult(true)
            }
        }
        val unsafe = plugin().copy(
            steps = listOf(GeneratedPluginStep("tap", "device.tap", emptyMap(), "done"))
        )
        assertFalse(runCatching { GeneratedPluginStore(context).install(unsafe, riskyCatalog) }.isSuccess)
    }
}
