package app.nestro.model

import app.nestro.data.ChatMessage
import app.nestro.data.ModelConfig
import app.nestro.data.OpenAiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/** Application-level model connectivity check shared by model-management UI. */
class ModelConnectionService {
    suspend fun test(candidate: ModelConfig): String {
        require(candidate.baseUrl.startsWith("https://") && candidate.model.isNotBlank() && candidate.apiKey.isNotBlank()) {
            "请填写 HTTPS 地址、模型名称和 API Key"
        }
        val response = withContext(Dispatchers.IO) {
            OpenAiClient.complete(
                candidate,
                listOf(ChatMessage(UUID.randomUUID().toString(), "user", "Reply with OK.")),
                ""
            )
        }
        return if (response.isBlank()) "连接成功，但模型返回了空内容。" else "连接成功：模型已返回内容。"
    }
}
