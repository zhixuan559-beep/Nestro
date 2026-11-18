package app.nestro.agent

/**
 * Centralizes lightweight agent selection without eagerly constructing every agent.
 * Concrete agent creation stays with the caller/registry so this layer remains cheap.
 */
object UnifiedAgentDispatcher {
    enum class AgentKind { PHONE, KNOWLEDGE, EXPERT, GENERAL }

    data class Route(
        val kind: AgentKind,
        val confidence: Int,
        val reason: String
    )

    fun route(task: String): Route {
        val text = task.lowercase()
        val phone = listOf("手机", "打开", "点击", "安装", "设置", "截图", "app", "页面")
            .count { text.contains(it) }
        val knowledge = listOf("知识库", "文档", "pdf", "文件", "资料", "搜索")
            .count { text.contains(it) }
        val expert = listOf("分析", "研究", "方案", "比较", "规划", "评估")
            .count { text.contains(it) }
        val scores = listOf(
            AgentKind.PHONE to phone,
            AgentKind.KNOWLEDGE to knowledge,
            AgentKind.EXPERT to expert
        ).sortedByDescending { it.second }
        val winner = scores.firstOrNull()
        if (winner == null || winner.second == 0) {
            return Route(AgentKind.GENERAL, 0, "没有命中特定能力，使用通用 Agent。")
        }
        return Route(winner.first, winner.second, "按任务关键词选择按需 Agent。")
    }
}
