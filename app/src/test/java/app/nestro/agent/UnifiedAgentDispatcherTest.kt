package app.nestro.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class UnifiedAgentDispatcherTest {
    @Test fun routesPhoneTasksWithoutLoadingOtherAgents() {
        assertEquals(
            UnifiedAgentDispatcher.AgentKind.PHONE,
            UnifiedAgentDispatcher.route("打开手机设置").kind
        )
    }

    @Test fun routesKnowledgeTasks() {
        assertEquals(
            UnifiedAgentDispatcher.AgentKind.KNOWLEDGE,
            UnifiedAgentDispatcher.route("搜索知识库里的 PDF 文档").kind
        )
    }

    @Test fun fallsBackToGeneral() {
        assertEquals(
            UnifiedAgentDispatcher.AgentKind.GENERAL,
            UnifiedAgentDispatcher.route("你好").kind
        )
    }
}
