package app.nestro.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.io.IOException

class AgentTaskCoordinatorTest {
    @Test
    fun parallelSafeToolsMustBeReadOnlyAndNotRequireConfirmation() {
        val safe = AgentToolSpec(
            id = "knowledge.search",
            description = "只读知识检索",
            permission = AgentToolPermission.READ_ONLY,
            parallelSafe = true
        )
        assertTrue(safe.parallelSafe)
        assertTrue(runCatching {
            AgentToolSpec(
                id = "unsafe.write",
                description = "外部写入",
                permission = AgentToolPermission.EXTERNAL_WRITE,
                parallelSafe = true
            )
        }.isFailure)
        assertTrue(runCatching {
            AgentToolSpec(
                id = "unsafe.confirmed",
                description = "需要确认的读取",
                permission = AgentToolPermission.READ_ONLY,
                requiresConfirmation = true,
                parallelSafe = true
            )
        }.isFailure)
    }

    @Test
    fun idempotencyKeyReturnsExistingTaskAndPreservesProjectMetadata() = runBlocking {
        val states = InMemoryAgentTaskStateStore()
        val registry = AgentTaskHandlerRegistry()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        registry.register("idempotent") {
            calls.incrementAndGet()
            started.complete(Unit)
            release.await()
        }
        val coordinator = AgentTaskCoordinator(states, registry, this)

        val first = coordinator.enqueue(
            "first-task",
            "idempotent",
            metadata = AgentTaskMetadata(projectId = "project-a", kind = "publish", title = "发布任务"),
            idempotencyKey = "project-a:publish:source-42"
        )
        started.await()
        val duplicate = coordinator.enqueue(
            "second-task",
            "idempotent",
            idempotencyKey = "project-a:publish:source-42"
        )
        release.complete(Unit)

        withTimeout(2_000) {
            while (states.get("first-task")?.status != AgentTaskStatus.COMPLETED) delay(10)
        }
        assertEquals("first-task", first.taskId)
        assertEquals("first-task", duplicate.taskId)
        assertEquals("project-a", states.get("first-task")?.projectId)
        assertEquals("publish", states.get("first-task")?.kind)
        assertEquals(1, calls.get())
        coordinator.close()
    }

    @Test
    fun externallyScheduledTasksAreRegisteredBeforeWorkerDispatch() = runBlocking {
        val states = InMemoryAgentTaskStateStore()
        val registry = AgentTaskHandlerRegistry()
        val calls = AtomicInteger()
        registry.register("phone_agent") { calls.incrementAndGet() }
        val coordinator = AgentTaskCoordinator(states, registry, this)

        val registered = coordinator.registerExternallyScheduled(
            AgentTaskState(
                taskId = "phone-external",
                owner = "phone_agent",
                status = AgentTaskStatus.CREATED,
                projectId = "project-a",
                kind = "phone_agent"
            )
        )
        assertEquals(AgentTaskStatus.CREATED, registered.status)
        delay(40)
        assertEquals(0, calls.get())

        val prepared = coordinator.prepareExternallyScheduled(
            "phone-external",
            "phone_agent",
            metadata = AgentTaskMetadata(projectId = "project-a", kind = "phone_agent")
        )
        assertEquals(AgentTaskStatus.QUEUED, prepared?.status)
        delay(40)
        assertEquals(0, calls.get())

        coordinator.dispatchExternallyScheduled("phone-external", "phone_agent")
        withTimeout(2_000) {
            while (states.get("phone-external")?.status != AgentTaskStatus.COMPLETED) delay(10)
        }
        assertEquals(1, calls.get())
        coordinator.close()
    }

    @Test
    fun executionContextOwnsPhoneAgentPauseAndUserHandoffTransitions() = runBlocking {
        val states = InMemoryAgentTaskStateStore()
        val registry = AgentTaskHandlerRegistry()
        registry.register("phone_agent") { context ->
            context.checkpoint("persisted-phone-plan", 3)
            context.waitForUser("需要用户在目标应用中完成验证")
        }
        val coordinator = AgentTaskCoordinator(states, registry, this)

        coordinator.enqueue("phone-context-lifecycle", "phone_agent")
        withTimeout(2_000) {
            while (states.get("phone-context-lifecycle")?.status != AgentTaskStatus.WAITING_FOR_USER) delay(10)
        }

        val state = states.get("phone-context-lifecycle")
        assertEquals("persisted-phone-plan", state?.checkpoint)
        assertEquals(3, state?.currentStep)
        assertEquals("需要用户在目标应用中完成验证", state?.lastError)
        coordinator.close()
    }

    @Test
    fun retryableFailureUsesBackoffAndRetriesWithinConfiguredLimit() = runBlocking {
        val states = InMemoryAgentTaskStateStore()
        val registry = AgentTaskHandlerRegistry()
        val calls = AtomicInteger()
        registry.register("retryable") {
            if (calls.incrementAndGet() == 1) {
                throw RetryableAgentTaskException("temporary network failure", IOException())
            }
        }
        val traces = InMemoryAgentTaskTraceStore()
        val coordinator = AgentTaskCoordinator(states, registry, this, traces)

        coordinator.enqueue("retry-task", "retryable", maxRetries = 1)
        withTimeout(4_000) {
            while (states.get("retry-task")?.status != AgentTaskStatus.COMPLETED) delay(10)
        }

        assertEquals(2, calls.get())
        assertEquals(1, states.get("retry-task")?.attempts)
        assertTrue(traces.recent("retry-task").any { it.event == "retry_scheduled" })
        coordinator.close()
    }

    @Test
    fun inlineExecutionWaitsForRetryAndKeepsHandlerRegistered() = runBlocking {
        val states = InMemoryAgentTaskStateStore()
        val registry = AgentTaskHandlerRegistry()
        val calls = AtomicInteger()
        val coordinator = AgentTaskCoordinator(states, registry, this)

        val result = coordinator.runInline("inline-retry", "workflow") {
            if (calls.incrementAndGet() == 1) {
                throw RetryableAgentTaskException("temporary service failure")
            }
            "completed after retry"
        }

        assertEquals("completed after retry", result)
        assertEquals(2, calls.get())
        assertEquals(1, states.get("inline-retry")?.attempts)
        assertEquals(AgentTaskStatus.COMPLETED, states.get("inline-retry")?.status)
        coordinator.close()
    }

    @Test
    fun nonRetryableFailureDoesNotEnterRetryQueue() = runBlocking {
        val states = InMemoryAgentTaskStateStore()
        val registry = AgentTaskHandlerRegistry()
        val calls = AtomicInteger()
        registry.register("nonretryable") {
            calls.incrementAndGet()
            throw IllegalArgumentException("invalid task")
        }
        val coordinator = AgentTaskCoordinator(states, registry, this)

        coordinator.enqueue("no-retry-task", "nonretryable", maxRetries = 3)
        withTimeout(2_000) {
            while (states.get("no-retry-task")?.status != AgentTaskStatus.FAILED) delay(10)
        }

        assertEquals(1, calls.get())
        assertEquals(0, states.get("no-retry-task")?.attempts)
        coordinator.close()
    }

    @Test
    fun checkpointTracePropagatesCoroutineCancellation() = runBlocking {
        val states = InMemoryAgentTaskStateStore()
        states.put(AgentTaskState("trace-cancel", "test"))
        val traces = object : AgentTaskTraceStore {
            override suspend fun append(trace: AgentTaskTrace) {
                throw CancellationException("trace write cancelled")
            }
            override suspend fun recent(taskId: String, limit: Int): List<AgentTaskTrace> = emptyList()
        }
        val context = AgentTaskExecutionContext("trace-cancel", states, traces)

        val result = runCatching { context.checkpoint("persisted-checkpoint") }

        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals("persisted-checkpoint", states.get("trace-cancel")?.checkpoint)
    }

    @Test
    fun duplicateTaskIdDoesNotStartASecondExecution() = runBlocking {
        val states = InMemoryAgentTaskStateStore()
        val registry = AgentTaskHandlerRegistry()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val traces = InMemoryAgentTaskTraceStore()
        registry.register("test") {
            calls.incrementAndGet()
            started.complete(Unit)
            release.await()
        }
        val coordinator = AgentTaskCoordinator(states, registry, this, traces)

        coordinator.enqueue("same-task", "test")
        started.await()
        coordinator.enqueue("same-task", "test")
        release.complete(Unit)

        withTimeout(2_000) {
            while (states.get("same-task")?.status != AgentTaskStatus.COMPLETED) delay(10)
        }
        assertEquals(1, calls.get())
        assertTrue(traces.recent("same-task").any { it.event == "completed" })
        coordinator.close()
    }

    @Test
    fun coordinatorPreservesFailureReportedByFeatureHandler() = runBlocking {
        val states = InMemoryAgentTaskStateStore()
        val registry = AgentTaskHandlerRegistry()
        registry.register("feature") { context ->
            val current = states.get(context.taskId)!!
            states.put(current.copy(
                status = AgentTaskStatus.FAILED,
                lastError = "feature execution failed"
            ))
        }
        val coordinator = AgentTaskCoordinator(states, registry, this)

        coordinator.enqueue("feature-failure", "feature")
        withTimeout(2_000) {
            while (states.get("feature-failure")?.status != AgentTaskStatus.FAILED) delay(10)
        }

        assertEquals("feature execution failed", states.get("feature-failure")?.lastError)
        coordinator.close()
    }

    @Test
    fun taskIdCannotBeReusedByAnotherHandler() = runBlocking {
        val states = InMemoryAgentTaskStateStore()
        val registry = AgentTaskHandlerRegistry()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        registry.register("first") {
            started.complete(Unit)
            release.await()
        }
        val coordinator = AgentTaskCoordinator(states, registry, this)

        coordinator.enqueue("owned-task", "first")
        started.await()
        val rejected = runCatching { coordinator.enqueue("owned-task", "second") }

        assertTrue(rejected.isFailure)
        assertEquals("first", states.get("owned-task")?.owner)
        release.complete(Unit)
        withTimeout(2_000) {
            while (states.get("owned-task")?.status != AgentTaskStatus.COMPLETED) delay(10)
        }
        coordinator.close()
    }

    @Test
    fun pausedTaskCanResumeFromPersistedCheckpoint() = runBlocking {
        val states = InMemoryAgentTaskStateStore()
        val registry = AgentTaskHandlerRegistry()
        val started = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        registry.register("resumable") { context ->
            val attempt = calls.incrementAndGet()
            context.checkpoint("step-$attempt", attempt)
            if (attempt == 1) {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        val coordinator = AgentTaskCoordinator(states, registry, this)

        coordinator.enqueue("resume-task", "resumable")
        started.await()
        coordinator.pause("resume-task")
        withTimeout(2_000) {
            while (states.get("resume-task")?.status != AgentTaskStatus.PAUSED) delay(10)
        }
        val paused = states.get("resume-task")
        assertEquals("step-1", paused?.checkpoint)

        coordinator.resume("resume-task")
        withTimeout(2_000) {
            while (states.get("resume-task")?.status != AgentTaskStatus.COMPLETED) delay(10)
        }
        assertEquals(2, calls.get())
        assertEquals("step-2", states.get("resume-task")?.checkpoint)
        coordinator.close()
    }

    @Test
    fun inlineFeatureExecutionUsesSharedQueueStateAndTrace() = runBlocking {
        val states = InMemoryAgentTaskStateStore()
        val registry = AgentTaskHandlerRegistry()
        val traces = InMemoryAgentTaskTraceStore()
        val coordinator = AgentTaskCoordinator(states, registry, this, traces)

        val result = coordinator.runInline("inline-task", "workflow") { context ->
            context.checkpoint("workflow-step-1", 1)
            "workflow-output"
        }

        assertEquals("workflow-output", result)
        withTimeout(2_000) {
            while (states.get("inline-task")?.status != AgentTaskStatus.COMPLETED) delay(10)
        }
        assertEquals("workflow-step-1", states.get("inline-task")?.checkpoint)
        assertTrue(traces.recent("inline-task").any { it.event == "completed" })
        coordinator.close()
    }

    @Test
    fun recoveryPreservesUserHandoffAndPausesQueuedTasksWithoutHandlers() = runBlocking {
        val states = InMemoryAgentTaskStateStore()
        states.put(AgentTaskState("handoff", "workflow", AgentTaskStatus.WAITING_FOR_USER, 2, "step-2", "需要用户确认"))
        states.put(AgentTaskState("orphan", "temporary-handler", AgentTaskStatus.QUEUED, 0, "queued", ""))
        states.put(AgentTaskState("failed", "workflow", AgentTaskStatus.FAILED, 1, "step-1", "模型请求失败"))
        val coordinator = AgentTaskCoordinator(states, AgentTaskHandlerRegistry(), this)

        val recovered = coordinator.recoverAfterProcessRestart()

        assertEquals(AgentTaskStatus.WAITING_FOR_USER, recovered.first { it.taskId == "handoff" }.status)
        assertEquals(AgentTaskStatus.PAUSED, states.get("orphan")?.status)
        assertTrue(states.get("orphan")?.lastError?.contains("处理器尚未恢复") == true)
        assertEquals(AgentTaskStatus.FAILED, recovered.first { it.taskId == "failed" }.status)
        coordinator.close()
    }

    @Test
    fun pausingInlineExecutionDoesNotTurnItIntoCancellation() = runBlocking {
        val states = InMemoryAgentTaskStateStore()
        val registry = AgentTaskHandlerRegistry()
        val coordinator = AgentTaskCoordinator(states, registry, this)
        val started = CompletableDeferred<Unit>()

        val caller = launch {
            runCatching {
                coordinator.runInline("inline-pause", "workflow") {
                    started.complete(Unit)
                    awaitCancellation()
                }
            }
        }
        started.await()
        coordinator.pause("inline-pause")
        caller.join()

        assertEquals(AgentTaskStatus.PAUSED, states.get("inline-pause")?.status)
        coordinator.close()
    }

    @Test
    fun interruptedRunningTaskIsMarkedPausedForManualRecovery() = runBlocking {
        val states = InMemoryAgentTaskStateStore()
        states.put(AgentTaskState("interrupted", "test", AgentTaskStatus.RUNNING, 3, "saved", ""))
        val coordinator = AgentTaskCoordinator(states, AgentTaskHandlerRegistry(), this)

        val recovered = coordinator.recoverAfterProcessRestart()

        assertNotNull(recovered.firstOrNull())
        assertEquals(AgentTaskStatus.PAUSED, states.get("interrupted")?.status)
        assertEquals("saved", states.get("interrupted")?.checkpoint)
        coordinator.close()
    }

    @Test
    fun recoveryLeavesWorkManagerOwnedPhoneTasksQueuedForTheirDurableScheduler() = runBlocking {
        val states = InMemoryAgentTaskStateStore()
        val registry = AgentTaskHandlerRegistry()
        val calls = AtomicInteger()
        registry.register("phone_agent") { calls.incrementAndGet() }
        states.put(AgentTaskState("workmanager-owned", "phone_agent", AgentTaskStatus.QUEUED))
        val coordinator = AgentTaskCoordinator(states, registry, this)

        val recovered = coordinator.recoverAfterProcessRestart(externallyScheduledOwners = setOf("phone_agent"))

        assertEquals(AgentTaskStatus.QUEUED, recovered.single().status)
        assertEquals(AgentTaskStatus.QUEUED, states.get("workmanager-owned")?.status)
        delay(50)
        assertEquals(0, calls.get())
        coordinator.close()
    }


    @Test
    fun externallyScheduledRunningTaskIsReattachedByWorkerInsteadOfPausedDuringRecovery() = runBlocking {
        val states = InMemoryAgentTaskStateStore()
        val registry = AgentTaskHandlerRegistry()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        registry.register("phone_agent") {
            started.complete(Unit)
            release.await()
        }
        states.put(AgentTaskState("workmanager-running", "phone_agent", AgentTaskStatus.RUNNING, checkpoint = "saved"))
        val coordinator = AgentTaskCoordinator(states, registry, this)

        val recovered = coordinator.recoverAfterProcessRestart(externallyScheduledOwners = setOf("phone_agent"))
        assertEquals(AgentTaskStatus.RUNNING, recovered.single().status)
        assertEquals(AgentTaskStatus.RUNNING, states.get("workmanager-running")?.status)

        coordinator.requeueRunningTaskIfIdle("workmanager-running")
        started.await()
        assertEquals(AgentTaskStatus.RUNNING, states.get("workmanager-running")?.status)
        release.complete(Unit)
        withTimeout(2_000) {
            while (states.get("workmanager-running")?.status != AgentTaskStatus.COMPLETED) delay(10)
        }
        coordinator.close()
    }

    @Test
    fun reattachingAlreadyActiveRunningTaskDoesNotDuplicateItsHandler() = runBlocking {
        val states = InMemoryAgentTaskStateStore()
        val registry = AgentTaskHandlerRegistry()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        registry.register("phone_agent") {
            calls.incrementAndGet()
            started.complete(Unit)
            release.await()
        }
        val coordinator = AgentTaskCoordinator(states, registry, this)
        coordinator.enqueue("already-active", "phone_agent")
        started.await()

        coordinator.requeueRunningTaskIfIdle("already-active")
        release.complete(Unit)
        withTimeout(2_000) {
            while (states.get("already-active")?.status != AgentTaskStatus.COMPLETED) delay(10)
        }
        assertEquals(1, calls.get())
        coordinator.close()
    }

    @Test
    fun inlineTaskCanReregisterStableHandlerAndResumePersistedCheckpoint() = runBlocking {
        val states = InMemoryAgentTaskStateStore()
        val registry = AgentTaskHandlerRegistry()
        val coordinator = AgentTaskCoordinator(states, registry, this)
        val started = CompletableDeferred<Unit>()
        val caller = launch {
            runCatching {
                coordinator.runInline("inline-resume-stable", "workflow") { context ->
                    context.checkpoint("completed-step-1", 1)
                    started.complete(Unit)
                    awaitCancellation()
                }
            }
        }
        started.await()
        coordinator.pause("inline-resume-stable")
        caller.join()
        assertEquals(AgentTaskStatus.PAUSED, states.get("inline-resume-stable")?.status)

        val result = coordinator.runInline("inline-resume-stable", "workflow") { context ->
            assertEquals("completed-step-1", states.get(context.taskId)?.checkpoint)
            context.checkpoint("completed-step-2", 2)
            "resumed"
        }
        assertEquals("resumed", result)
        assertEquals(AgentTaskStatus.COMPLETED, states.get("inline-resume-stable")?.status)
        assertEquals("completed-step-2", states.get("inline-resume-stable")?.checkpoint)
        coordinator.close()
    }


    @Test
    fun approvalWaitAndResumeArePersistedBySharedTaskCoordinator() = runBlocking {
        val states = InMemoryAgentTaskStateStore()
        val coordinator = AgentTaskCoordinator(states, AgentTaskHandlerRegistry(), this)
        try {
            states.putIfAbsent(
                AgentTaskState(
                    taskId = "chat-approval-state",
                    owner = "chat",
                    status = AgentTaskStatus.RUNNING
                )
            )
            coordinator.transitionTask(
                "chat-approval-state",
                AgentTaskStatus.WAITING_FOR_USER,
                "等待外部写入审批"
            )
            assertEquals(AgentTaskStatus.WAITING_FOR_USER, states.get("chat-approval-state")?.status)

            coordinator.transitionTask(
                "chat-approval-state",
                AgentTaskStatus.RUNNING,
                "用户已批准"
            )
            assertEquals(AgentTaskStatus.RUNNING, states.get("chat-approval-state")?.status)
        } finally {
            coordinator.close()
        }
    }


    @Test
    fun preRegisteredWorkManagerTaskUsesTheSameInlineOwnerAndCompletes() = runBlocking {
        val states = InMemoryAgentTaskStateStore()
        val coordinator = AgentTaskCoordinator(states, AgentTaskHandlerRegistry(), this)
        try {
            val registered = coordinator.registerInlineTask(
                taskId = "media-ai:00000000-0000-0000-0000-000000000001",
                owner = "media_ai",
                checkpoint = "media_ai:project:task",
                metadata = AgentTaskMetadata(
                    projectId = "project",
                    kind = "media_ai",
                    title = "媒体 AI"
                )
            )
            assertEquals(AgentTaskStatus.CREATED, registered.status)
            val result = coordinator.runInline(
                taskId = registered.taskId,
                owner = "media_ai",
                initialCheckpoint = "media_ai:project:task",
                metadata = AgentTaskMetadata(projectId = "project", kind = "media_ai")
            ) { context ->
                assertEquals("media_ai:project:task", states.get(context.taskId)?.checkpoint)
                "done"
            }
            assertEquals("done", result)
            assertEquals(AgentTaskStatus.COMPLETED, states.get(registered.taskId)?.status)
        } finally {
            coordinator.close()
        }
    }


    @Test
    fun staleRunningInlineTaskCanReattachAfterWorkerRestart() = runBlocking {
        val states = InMemoryAgentTaskStateStore()
        val coordinator = AgentTaskCoordinator(states, AgentTaskHandlerRegistry(), this)
        try {
            val taskId = "media-render:00000000-0000-0000-0000-000000000002"
            coordinator.registerInlineTask(taskId, "media_render", "render-checkpoint")
            coordinator.transitionTask(taskId, AgentTaskStatus.RUNNING, "旧进程正在执行")
            assertEquals(AgentTaskStatus.RUNNING, states.get(taskId)?.status)

            val result = coordinator.runInline(taskId, "media_render", "render-checkpoint") {
                "reattached"
            }
            assertEquals("reattached", result)
            assertEquals(AgentTaskStatus.COMPLETED, states.get(taskId)?.status)
        } finally {
            coordinator.close()
        }
    }


    @Test
    fun independentTaskKindsRunConcurrentlyWithinTheGlobalLimit() = runBlocking {
        val states = InMemoryAgentTaskStateStore()
        val registry = AgentTaskHandlerRegistry()
        val release = CompletableDeferred<Unit>()
        val started = AtomicInteger()
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()
        val kinds = listOf("workflow", "calendar_automation", "media_ai")
        kinds.forEach { kind ->
            registry.register(kind) {
                val current = active.incrementAndGet()
                maximumActive.updateAndGet { previous -> maxOf(previous, current) }
                started.incrementAndGet()
                try {
                    release.await()
                } finally {
                    active.decrementAndGet()
                }
            }
        }
        val coordinator = AgentTaskCoordinator(states, registry, this)
        try {
            kinds.forEach { kind ->
                coordinator.enqueue(
                    taskId = "parallel-$kind",
                    owner = kind,
                    metadata = AgentTaskMetadata(kind = kind)
                )
            }
            withTimeout(2_000) {
                while (started.get() < 3) delay(10)
            }
            assertEquals(3, maximumActive.get())
            release.complete(Unit)
            withTimeout(2_000) {
                while (kinds.any { states.get("parallel-$it")?.status != AgentTaskStatus.COMPLETED }) delay(10)
            }
        } finally {
            release.complete(Unit)
            coordinator.close()
        }
    }

    @Test
    fun phoneAgentLaneRemainsSingleExecutionWhileOtherKindsCanRun() = runBlocking {
        val states = InMemoryAgentTaskStateStore()
        val registry = AgentTaskHandlerRegistry()
        val release = CompletableDeferred<Unit>()
        val started = AtomicInteger()
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()
        registry.register("phone_agent") {
            val current = active.incrementAndGet()
            maximumActive.updateAndGet { previous -> maxOf(previous, current) }
            started.incrementAndGet()
            try {
                release.await()
            } finally {
                active.decrementAndGet()
            }
        }
        val coordinator = AgentTaskCoordinator(states, registry, this)
        try {
            repeat(2) { index ->
                coordinator.enqueue(
                    taskId = "phone-lane-$index",
                    owner = "phone_agent",
                    metadata = AgentTaskMetadata(kind = "phone_agent")
                )
            }
            withTimeout(2_000) {
                while (started.get() < 1) delay(10)
            }
            delay(80)
            assertEquals(1, started.get())
            assertEquals(1, maximumActive.get())
            release.complete(Unit)
            withTimeout(2_000) {
                while ((0..1).any { states.get("phone-lane-$it")?.status != AgentTaskStatus.COMPLETED }) delay(10)
            }
            assertEquals(2, started.get())
            assertEquals(1, maximumActive.get())
        } finally {
            release.complete(Unit)
            coordinator.close()
        }
    }

}
