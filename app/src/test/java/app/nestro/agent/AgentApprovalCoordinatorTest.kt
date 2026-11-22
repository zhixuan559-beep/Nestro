package app.nestro.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentApprovalCoordinatorTest {
    @Test
    fun authorizeOnceDoesNotGrantTheNextToolCall() = runBlocking {
        val approvals = AgentApprovalCoordinator()
        var requests = 0
        approvals.setHandler {
            requests++
            AgentApprovalDecision.AUTHORIZE_ONCE
        }

        assertTrue(approvals.authorize("request-1", "mcp_tool_a", "需要确认"))
        assertTrue(approvals.authorize("request-1", "mcp_tool_a", "需要确认"))
        assertEquals(2, requests)
        approvals.endScope("request-1")
    }

    @Test
    fun authorizeTaskCoversTheWholeRequestButIsClearedAtEnd() = runBlocking {
        val approvals = AgentApprovalCoordinator()
        var requests = 0
        approvals.setHandler {
            requests++
            AgentApprovalDecision.AUTHORIZE_TASK
        }

        assertTrue(approvals.authorize("request-2", "plugin.a", "插件需要确认"))
        assertTrue(approvals.authorize("request-2", "mcp_tool_b", "MCP 需要确认"))
        assertEquals(1, requests)

        approvals.endScope("request-2")
        approvals.setHandler(null)
        assertFalse(approvals.authorize("request-2", "mcp_tool_b", "再次确认"))
    }

    @Test
    fun rejectionAndMissingUiHandlerFailClosed() = runBlocking {
        val approvals = AgentApprovalCoordinator()
        assertFalse(approvals.authorize("request-3", "external.write", "需要确认"))
        approvals.setHandler { AgentApprovalDecision.REJECT }
        assertFalse(approvals.authorize("request-3", "external.write", "需要确认"))
        approvals.endScope("request-3")
    }
}
