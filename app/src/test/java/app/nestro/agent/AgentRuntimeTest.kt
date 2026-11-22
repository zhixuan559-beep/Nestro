package app.nestro.agent

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimeTest {
    @Test
    fun readOnlyToolRunsAndAdvancesTaskState() = runBlocking {
        val catalog = InMemoryAgentToolCatalog().apply {
            register(AgentToolSpec("screen.read", "读取当前页面", AgentToolPermission.READ_ONLY)) {
                AgentToolResult(success = true, output = "页面内容")
            }
        }
        val states = InMemoryAgentTaskStateStore()
        val traces = InMemoryAgentTaskTraceStore()
        val runtime = DefaultAgentRuntime(catalog, DefaultAgentPermissionRouter(), states, traces)

        val result = runtime.execute(AgentToolRequest("task-1", "screen.read"))

        assertTrue(result.success)
        assertEquals("页面内容", result.output)
        assertEquals(1, states.get("task-1")?.currentStep)
        assertEquals(AgentTaskStatus.RUNNING, states.get("task-1")?.status)
        assertTrue(traces.recent("task-1").any { it.event == "tool_succeeded" })
    }

    @Test
    fun externalWriteRequiresUserConfirmationByDefault() = runBlocking {
        val catalog = InMemoryAgentToolCatalog().apply {
            register(AgentToolSpec("message.send", "发送消息", AgentToolPermission.EXTERNAL_WRITE)) {
                AgentToolResult(success = true, output = "sent")
            }
        }
        val states = InMemoryAgentTaskStateStore()
        val runtime = DefaultAgentRuntime(catalog, DefaultAgentPermissionRouter(), states)

        val result = runtime.execute(AgentToolRequest("task-2", "message.send"))

        assertFalse(result.success)
        assertTrue(result.requiresUser)
        assertEquals(AgentTaskStatus.WAITING_FOR_USER, states.get("task-2")?.status)
    }

    @Test
    fun approvalCoordinatorPersistsPendingAndFinalDecisionWithStableId() = runBlocking {
        val records = mutableListOf<AgentApprovalAuditRecord>()
        val coordinator = AgentApprovalCoordinator { record -> records += record }
        coordinator.setHandler { AgentApprovalDecision.AUTHORIZE_TASK }

        val decision = coordinator.authorize("scope-audit", "mcp.sample", "写入远程数据")

        assertEquals(AgentApprovalDecision.AUTHORIZE_TASK, decision)
        assertEquals(listOf("PENDING", "AUTHORIZED_TASK"), records.map { it.status })
        assertEquals(records.first().id, records.last().id)
    }

    @Test
    fun approvalPendingCancellationPreservesWaitingForUserState() = runBlocking {
        val catalog = InMemoryAgentToolCatalog().apply {
            register(AgentToolSpec("approval.pending", "等待用户审批", AgentToolPermission.READ_ONLY)) {
                throw AgentApprovalPendingException("等待用户审批后继续")
            }
        }
        val states = InMemoryAgentTaskStateStore()
        val runtime = DefaultAgentRuntime(catalog, DefaultAgentPermissionRouter(), states)

        val pending = launch {
            runtime.execute(AgentToolRequest("task-approval-pending", "approval.pending"))
        }
        pending.join()

        assertTrue(pending.isCancelled)
        assertEquals(AgentTaskStatus.WAITING_FOR_USER, states.get("task-approval-pending")?.status)
        assertEquals("等待用户审批后继续", states.get("task-approval-pending")?.lastError)
    }

    @Test
    fun missingToolReturnsActionableErrorWithoutExecuting() = runBlocking {
        val states = InMemoryAgentTaskStateStore()
        val traces = InMemoryAgentTaskTraceStore()
        val runtime = DefaultAgentRuntime(
            InMemoryAgentToolCatalog(),
            DefaultAgentPermissionRouter(),
            states,
            traces
        )

        val result = runtime.execute(AgentToolRequest("task-3", "missing.tool"))

        assertFalse(result.success)
        assertTrue(result.error.contains("工具不存在"))
        assertEquals(AgentTaskStatus.FAILED, states.get("task-3")?.status)
        assertTrue(traces.recent("task-3").any { it.event == "tool_missing" })
    }

    @Test
    fun tracePersistenceCancellationPropagatesBeforeToolExecution() = runBlocking {
        val catalog = InMemoryAgentToolCatalog().apply {
            register(AgentToolSpec("screen.read", "读取当前页面", AgentToolPermission.READ_ONLY)) {
                AgentToolResult(success = true, output = "页面内容")
            }
        }
        val traces = object : AgentTaskTraceStore {
            override suspend fun append(trace: AgentTaskTrace) {
                throw kotlinx.coroutines.CancellationException("trace cancelled")
            }
            override suspend fun recent(taskId: String, limit: Int): List<AgentTaskTrace> = emptyList()
        }
        val runtime = DefaultAgentRuntime(catalog, DefaultAgentPermissionRouter(), InMemoryAgentTaskStateStore(), traces)

        val result = runCatching { runtime.execute(AgentToolRequest("task-trace-cancel", "screen.read")) }

        assertTrue(result.exceptionOrNull() is kotlinx.coroutines.CancellationException)
    }


    @Test
    fun taskSessionExposesOnlySelectedToolsAndRejectsUnloadedInvocation() = runBlocking {
        var unrelatedCalls = 0
        val catalog = InMemoryAgentToolCatalog().apply {
            register(AgentToolSpec("screen.read", "读取当前页面", AgentToolPermission.READ_ONLY)) {
                AgentToolResult(true, "页面")
            }
            register(AgentToolSpec("message.send", "发送消息", AgentToolPermission.EXTERNAL_WRITE)) {
                unrelatedCalls++
                AgentToolResult(true, "已发送")
            }
        }
        val session = catalog.openSession(setOf("screen.read"))
        assertEquals(listOf("screen.read"), session.list().map { it.id })
        assertEquals(null, session.find("message.send"))
        val denied = session.invoke("message.send", emptyMap())
        assertFalse(denied.success)
        assertEquals(0, unrelatedCalls)
        assertTrue(session.invoke("screen.read", emptyMap()).success)
        session.close()
        assertTrue(session.list().isEmpty())
        assertFalse(session.invoke("screen.read", emptyMap()).success)
    }

    @Test
    fun unknownRequestedToolsAreNotAddedToTaskSession() = runBlocking {
        val catalog = InMemoryAgentToolCatalog().apply {
            register(AgentToolSpec("screen.read", "读取当前页面", AgentToolPermission.READ_ONLY)) {
                AgentToolResult(true)
            }
        }
        val session = catalog.openSession(setOf("screen.read", "payments.transfer"))
        assertEquals(listOf("screen.read"), session.list().map { it.id })
        assertFalse(session.invoke("payments.transfer", emptyMap()).success)
    }

    @Test
    fun scopedCapabilityDoesNotGrantExternalWritePermission() = runBlocking {
        var writes = 0
        val catalog = InMemoryAgentToolCatalog().apply {
            register(AgentToolSpec("message.send", "发送消息", AgentToolPermission.EXTERNAL_WRITE)) {
                writes++
                AgentToolResult(true, "sent")
            }
        }
        val session = catalog.openSession(setOf("message.send"))
        val states = InMemoryAgentTaskStateStore()
        val runtime = DefaultAgentRuntime(
            session,
            DefaultAgentPermissionRouter(),
            states
        )

        val result = runtime.execute(AgentToolRequest("task-permission-boundary", "message.send"))

        assertFalse(result.success)
        assertTrue(result.requiresUser)
        assertEquals(0, writes)
        assertEquals(AgentTaskStatus.WAITING_FOR_USER, states.get("task-permission-boundary")?.status)
        session.close()
    }



    @Test
    fun activeSessionKeepsOriginalHandlerWhenCatalogEntryIsReplaced() = runBlocking {
        val catalog = InMemoryAgentToolCatalog()
        catalog.register(AgentToolSpec("screen.read", "读取页面", AgentToolPermission.READ_ONLY)) {
            AgentToolResult(true, "original")
        }
        val session = catalog.openSession(setOf("screen.read"))

        catalog.register(AgentToolSpec("screen.read", "读取页面的新版本", AgentToolPermission.READ_ONLY)) {
            AgentToolResult(true, "replacement")
        }

        assertEquals("original", session.invoke("screen.read", emptyMap()).output)
        assertEquals("replacement", catalog.invoke("screen.read", emptyMap()).output)
        assertEquals("读取页面", session.find("screen.read")?.description)
        session.close()
    }

    @Test
    fun taskRevocationInvalidatesEveryOpenToolLease() = runBlocking {
        val catalog = InMemoryAgentToolCatalog().apply {
            register(AgentToolSpec("screen.read", "读取当前页面", AgentToolPermission.READ_ONLY)) {
                AgentToolResult(true, "页面")
            }
        }
        val first = AgentToolLeaseRegistry.open("task-revoke", catalog, setOf("screen.read"))
        val second = AgentToolLeaseRegistry.open("task-revoke", catalog, setOf("screen.read"))

        assertEquals(2, AgentToolLeaseRegistry.activeLeaseCount("task-revoke"))
        assertTrue(first.access.invoke("screen.read", emptyMap()).success)
        assertEquals(2, AgentToolLeaseRegistry.revokeTask("task-revoke"))
        assertEquals(0, AgentToolLeaseRegistry.activeLeaseCount("task-revoke"))
        assertFalse(first.access.invoke("screen.read", emptyMap()).success)
        assertFalse(second.access.invoke("screen.read", emptyMap()).success)
    }

    @Test
    fun taskCredentialLeaseResolvesSecretOnDemandAndCanBeRevoked() {
        var reads = 0
        val lease = AgentCredentialLeaseRegistry.open("task-credential", ttlMillis = 5_000) {
            reads++
            "temporary-token"
        }

        assertEquals("temporary-token", lease.withSecret { it })
        assertEquals(1, reads)
        assertEquals(1, AgentCredentialLeaseRegistry.activeLeaseCount("task-credential"))
        assertEquals(1, AgentCredentialLeaseRegistry.revokeTask("task-credential"))
        assertEquals(null, lease.withSecret { it })
        assertEquals(0, AgentCredentialLeaseRegistry.activeLeaseCount("task-credential"))
        assertFalse(lease.isUsable(System.currentTimeMillis() + 10_000))
    }

}
