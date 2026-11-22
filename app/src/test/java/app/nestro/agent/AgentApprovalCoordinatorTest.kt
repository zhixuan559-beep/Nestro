package app.nestro.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentApprovalCoordinatorTest {
    private class MemoryApprovalStore {
        val records = mutableListOf<AgentApprovalAuditRecord>()

        fun coordinator(
            resumeTask: suspend (scopeId: String) -> Unit = {}
        ) = AgentApprovalCoordinator(
            persistAudit = { record ->
                records.removeAll { it.id == record.id }
                records += record
            },
            closeActiveAudits = { scopeId, status, updatedAt ->
                val updated = records.map { record ->
                    if (record.scopeId == scopeId &&
                        record.status in setOf("PENDING", "AUTHORIZED_ONCE", "AUTHORIZED_TASK", "REJECTED")
                    ) record.copy(status = status, updatedAt = updatedAt) else record
                }
                records.clear()
                records.addAll(updated)
            },
            loadPendingAudits = { records.filter { it.status == "PENDING" }.sortedBy { it.createdAt } },
            loadAudit = { id -> records.firstOrNull { it.id == id } },
            loadAuditsForScope = { scopeId ->
                records.filter { it.scopeId == scopeId }.sortedBy { it.createdAt }
            },
            loadResumableAudits = {
                records.filter { it.status in setOf("AUTHORIZED_ONCE", "AUTHORIZED_TASK", "REJECTED") }
            },
            resumeTask = resumeTask
        )
    }

    @Test
    fun authorizeOnceDoesNotGrantTheNextToolCall() = runBlocking {
        val store = MemoryApprovalStore()
        val approvals = store.coordinator()
        var requests = 0
        approvals.setHandler { request ->
            requests += 1
            approvals.respond(request.id, AgentApprovalDecision.AUTHORIZE_ONCE)
        }

        assertEquals(AgentApprovalDecision.AUTHORIZE_ONCE, approvals.authorize("request-1", "mcp_tool_a", "需要确认"))
        assertEquals(AgentApprovalDecision.AUTHORIZE_ONCE, approvals.authorize("request-1", "mcp_tool_a", "需要确认"))
        assertEquals(2, requests)
        assertTrue(store.records.all { it.status == "CONSUMED_ONCE" })
        approvals.endScope("request-1")
    }

    @Test
    fun authorizeTaskSurvivesCoordinatorRecreationButClosesAtEnd() = runBlocking {
        val store = MemoryApprovalStore()
        val first = store.coordinator()
        var requests = 0
        first.setHandler { request ->
            requests += 1
            first.respond(request.id, AgentApprovalDecision.AUTHORIZE_TASK)
        }

        assertEquals(AgentApprovalDecision.AUTHORIZE_TASK, first.authorize("request-2", "plugin.a", "插件需要确认"))
        val afterRestart = store.coordinator()
        assertEquals(AgentApprovalDecision.AUTHORIZE_TASK, afterRestart.authorize("request-2", "mcp_tool_b", "MCP 需要确认"))
        assertEquals(1, requests)

        afterRestart.endScopeAndAudit("request-2")
        val afterClose = store.coordinator()
        assertNull(afterClose.authorize("request-2", "mcp_tool_b", "再次确认"))
    }

    @Test
    fun pendingApprovalCanBeResolvedAfterProcessRestartAndResumesItsScope() = runBlocking {
        val store = MemoryApprovalStore()
        val beforeRestart = store.coordinator()
        assertNull(beforeRestart.authorize("request-3", "external.write", "需要确认"))

        val pending = beforeRestart.pendingRequests().single()
        var resumedScope = ""
        val afterRestart = store.coordinator { scopeId ->
            resumedScope = scopeId
        }

        assertTrue(afterRestart.respond(pending.id, AgentApprovalDecision.AUTHORIZE_ONCE))
        assertEquals("request-3", resumedScope)
        assertEquals(AgentApprovalDecision.AUTHORIZE_ONCE, afterRestart.authorize("request-3", "external.write", "需要确认"))
        assertEquals("CONSUMED_ONCE", store.records.single().status)
    }

    @Test
    fun independentApprovalRequestsCanQueueAndResolveConcurrently() = runBlocking {
        val store = MemoryApprovalStore()
        val approvals = store.coordinator()
        val twoRequestsEnqueued = CompletableDeferred<Unit>()
        val enqueued = mutableListOf<String>()
        approvals.setHandler { request ->
            synchronized(enqueued) {
                enqueued += request.toolId
                if (enqueued.size == 2) twoRequestsEnqueued.complete(Unit)
            }
        }

        val first = async { approvals.authorize("scope-a", "tool-a", "需要确认") }
        val second = async { approvals.authorize("scope-b", "tool-b", "需要确认") }
        withTimeout(2_000) { twoRequestsEnqueued.await() }

        val pending = approvals.pendingRequests()
        assertEquals(2, pending.size)
        assertTrue(approvals.respond(pending.single { it.toolId == "tool-b" }.id, AgentApprovalDecision.AUTHORIZE_ONCE))
        assertEquals(AgentApprovalDecision.AUTHORIZE_ONCE, withTimeout(2_000) { second.await() })
        assertTrue(approvals.respond(pending.single { it.toolId == "tool-a" }.id, AgentApprovalDecision.REJECT))
        assertEquals(AgentApprovalDecision.REJECT, withTimeout(2_000) { first.await() })
    }

    @Test
    fun approvalDecisionCanOnlyBeCommittedOnce() = runBlocking {
        val store = MemoryApprovalStore()
        val approvals = store.coordinator()
        val pendingId = CompletableDeferred<String>()
        approvals.setHandler { request -> pendingId.complete(request.id) }
        val execution = async { approvals.authorize("scope-once", "tool-once", "需要确认") }
        val id = withTimeout(2_000) { pendingId.await() }

        val responses = listOf(
            async { approvals.respond(id, AgentApprovalDecision.AUTHORIZE_ONCE) },
            async { approvals.respond(id, AgentApprovalDecision.REJECT) }
        ).awaitAll()

        assertEquals(1, responses.count { it })
        val decision = withTimeout(2_000) { execution.await() }
        val expectedStatus = if (decision == AgentApprovalDecision.AUTHORIZE_ONCE) {
            "CONSUMED_ONCE"
        } else {
            "CONSUMED_REJECTED"
        }
        assertEquals(expectedStatus, store.records.single().status)
    }

    @Test
    fun rejectionAndMissingUiHandlerFailClosed() = runBlocking {
        val store = MemoryApprovalStore()
        val approvals = store.coordinator()
        assertNull(approvals.authorize("request-4", "external.write", "需要确认"))

        val pending = approvals.pendingRequests().single()
        assertTrue(approvals.respond(pending.id, AgentApprovalDecision.REJECT))
        assertEquals(AgentApprovalDecision.REJECT, approvals.authorize("request-4", "external.write", "需要确认"))
        assertFalse(store.records.any { it.status == "PENDING" })
    }
}
