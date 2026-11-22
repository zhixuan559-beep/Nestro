package app.nestro.data

/** Local, zero-model-call gates for deciding which optional context sources are worth retrieving. */
internal object ChatContextRetrievalPolicy {
    private val conversationSignals = listOf("之前", "上次", "以前", "我们聊过", "刚才", "你记得", "回忆", "历史对话", "接着上次", "上回")
    private val userMemorySignals = listOf("我的偏好", "我的习惯", "你记得我", "关于我", "我之前告诉", "我之前说", "根据我的记忆", "个人记忆", "我的情况", "我的背景")

    fun shouldRetrieveConversation(prompt: String): Boolean = conversationSignals.any(prompt::contains)
    fun shouldRetrieveUserMemory(prompt: String): Boolean = userMemorySignals.any(prompt::contains)

    /** A saved team selection is not authorization; team execution requires explicit task mode. */
    fun shouldRunExpertTeam(taskModeEnabled: Boolean, selectedTeamId: String): Boolean =
        taskModeEnabled && selectedTeamId.isNotBlank()
}

/** Immutable task authorization captured before a chat request is launched. */
internal data class ChatExecutionAuthorization(
    val taskModeEnabled: Boolean,
    val selectedTeamId: String
) {
    fun shouldExecutePhoneAgent(): Boolean = taskModeEnabled
    fun shouldRunExpertTeam(): Boolean = taskModeEnabled && selectedTeamId.isNotBlank()
}


/** Ordinary chat never injects selected expert or skill prompt assets. */
internal fun shouldLoadSelectedExpertContext(taskModeEnabled: Boolean): Boolean = taskModeEnabled
