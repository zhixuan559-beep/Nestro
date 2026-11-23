package app.nestro.data

import app.nestro.agent.AgentToolPermission
import app.nestro.agent.AgentToolSpec
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelLedToolDispatcherAllowListTest {
    private val tools = listOf(
        AgentToolSpec("knowledge.search", "Search project knowledge", AgentToolPermission.READ_ONLY),
        AgentToolSpec("media.project.assets", "List project media", AgentToolPermission.READ_ONLY),
        AgentToolSpec("phone_agent.execute", "Execute phone task", AgentToolPermission.DEVICE_ACTION)
    )

    @Test
    fun nullAllowListPreservesNormalChatToolDiscovery() {
        assertEquals(tools, filterTaskAllowedTools(tools, null))
    }

    @Test
    fun scopedAllowListExposesOnlyToolsDeclaredForTheOperation() {
        assertEquals(
            listOf(tools[0], tools[2]),
            filterTaskAllowedTools(tools, setOf("knowledge.search", "phone_agent.execute"))
        )
    }

    @Test
    fun emptyAllowListExposesNoTools() {
        assertEquals(emptyList<AgentToolSpec>(), filterTaskAllowedTools(tools, emptySet()))
    }
}
