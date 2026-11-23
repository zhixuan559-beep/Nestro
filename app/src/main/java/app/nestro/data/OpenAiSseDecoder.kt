package app.nestro.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Decodes OpenAI-compatible Server-Sent Events.
 *
 * SSE data fields are dispatched at the blank line boundary, not individually.
 * This supports providers that split a JSON event over multiple data lines.
 */
internal data class ProviderTokenUsage(val inputTokens: Int, val outputTokens: Int)

internal class OpenAiSseDecoder {
    private val dataLines = mutableListOf<String>()

    var providerUsage: ProviderTokenUsage? = null
        private set

    var isDone: Boolean = false
        private set

    fun acceptLine(line: String): String? {
        if (isDone) return null
        if (line.isEmpty()) return dispatchEvent()
        if (line.startsWith(":")) return null
        if (line.startsWith("data:")) {
            var value = line.substring(5)
            if (value.startsWith(" ")) value = value.substring(1)
            dataLines += value
        }
        return null
    }

    fun finish(): String? = if (isDone) null else dispatchEvent()

    private fun dispatchEvent(): String? {
        if (dataLines.isEmpty()) return null
        val payload = dataLines.joinToString("\n")
        dataLines.clear()
        if (payload.trim() == "[DONE]") {
            isDone = true
            return null
        }
        return runCatching {
            val event = JSONObject(payload)
            event.optJSONObject("usage")?.let { usage ->
                val input = usage.optInt("prompt_tokens", -1)
                val output = usage.optInt("completion_tokens", -1)
                if (input >= 0 && output >= 0) providerUsage = ProviderTokenUsage(input, output)
            }
            val choices = event.optJSONArray("choices") ?: JSONArray()
            val choice = choices.optJSONObject(0) ?: return@runCatching null
            val delta = choice.optJSONObject("delta")
            val content = delta?.opt("content")
                ?: choice.optJSONObject("message")?.opt("content")
            when (content) {
                is String -> content
                is JSONArray -> buildString {
                    for (index in 0 until content.length()) {
                        val part = content.optJSONObject(index) ?: continue
                        append(part.optString("text"))
                    }
                }
                else -> null
            }
        }.getOrNull()
    }
}
