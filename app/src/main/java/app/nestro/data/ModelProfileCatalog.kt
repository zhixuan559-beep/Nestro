package app.nestro.data

import org.json.JSONArray
import org.json.JSONObject

data class ModelProfileMetadata(
    val id: String,
    val name: String,
    val baseUrl: String,
    val model: String
)

object ModelProfileCatalogCodec {
    const val MAX_PROFILES = 20

    fun validate(profile: ModelProfileMetadata): String? {
        if (profile.id.isBlank() || profile.id.length > 80) return "模型配置标识无效"
        if (profile.name.isBlank() || profile.name.length > 40) return "配置名称需为 1 到 40 个字符"
        if (!profile.baseUrl.startsWith("https://", ignoreCase = true) || profile.baseUrl.length > 2048) return "API 地址必须使用 HTTPS，且不超过 2048 个字符"
        if (profile.model.isBlank() || profile.model.length > 200) return "模型名称需为 1 到 200 个字符"
        return null
    }

    fun validateCatalog(profiles: List<ModelProfileMetadata>): String? {
        if (profiles.size > MAX_PROFILES) return "最多保存 $MAX_PROFILES 组模型配置"
        if (profiles.map { it.id }.toSet().size != profiles.size) return "模型配置标识重复"
        if (profiles.map { it.name.trim().lowercase() }.toSet().size != profiles.size) return "模型配置名称不能重复"
        return profiles.firstNotNullOfOrNull(::validate)
    }

    fun encode(profiles: List<ModelProfileMetadata>): String = JSONArray().apply {
        profiles.take(MAX_PROFILES).forEach { profile ->
            put(JSONObject()
                .put("id", profile.id)
                .put("name", profile.name)
                .put("base_url", profile.baseUrl)
                .put("model", profile.model))
        }
    }.toString()

    fun decode(raw: String): List<ModelProfileMetadata> = runCatching {
        val array = JSONArray(raw)
        require(array.length() <= MAX_PROFILES)
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            ModelProfileMetadata(
                id = item.getString("id"),
                name = item.getString("name"),
                baseUrl = item.getString("base_url"),
                model = item.getString("model")
            )
        }.also { profiles ->
            require(validateCatalog(profiles) == null)
        }
    }.getOrDefault(emptyList())
}

data class ModelUsageStats(
    val requests: Long = 0,
    val estimatedInputTokens: Long = 0,
    val estimatedOutputTokens: Long = 0,
    val providerInputTokens: Long = 0,
    val providerOutputTokens: Long = 0,
    val providerUsageRequests: Long = 0,
    val estimatedUsageRequests: Long = 0,
    val lastUsedAt: Long = 0
)

object ModelUsageStatsCodec {
    fun decode(raw: String): Map<String, ModelUsageStats> = runCatching {
        val json = JSONObject(raw)
        val result = linkedMapOf<String, ModelUsageStats>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            val item = json.getJSONObject(id)
            if (id.isNotBlank() && id.length <= 80) {
                result[id] = ModelUsageStats(
                    requests = item.optLong("requests", 0).coerceAtLeast(0),
                    estimatedInputTokens = item.optLong("input_tokens", 0).coerceAtLeast(0),
                    estimatedOutputTokens = item.optLong("output_tokens", 0).coerceAtLeast(0),
                    providerInputTokens = item.optLong("provider_input_tokens", 0).coerceAtLeast(0),
                    providerOutputTokens = item.optLong("provider_output_tokens", 0).coerceAtLeast(0),
                    providerUsageRequests = item.optLong("provider_usage_requests", 0).coerceAtLeast(0),
                    estimatedUsageRequests = item.optLong("estimated_usage_requests", item.optLong("requests", 0)).coerceAtLeast(0),
                    lastUsedAt = item.optLong("last_used_at", 0).coerceAtLeast(0)
                )
            }
        }
        result
    }.getOrDefault(emptyMap())

    fun record(
        current: Map<String, ModelUsageStats>,
        profileId: String,
        inputChars: Int,
        outputChars: Int,
        now: Long = System.currentTimeMillis()
    ): Map<String, ModelUsageStats> {
        require(profileId.isNotBlank() && profileId.length <= 80)
        val old = current[profileId] ?: ModelUsageStats()
        return current + (profileId to old.copy(
            requests = old.requests + 1,
            estimatedInputTokens = old.estimatedInputTokens + ((inputChars.coerceAtLeast(0) + 3) / 4),
            estimatedOutputTokens = old.estimatedOutputTokens + ((outputChars.coerceAtLeast(0) + 3) / 4),
            estimatedUsageRequests = old.estimatedUsageRequests + 1,
            lastUsedAt = now
        ))
    }

    fun recordEstimatedTokens(
        current: Map<String, ModelUsageStats>,
        profileId: String,
        inputTokens: Int,
        outputTokens: Int,
        now: Long = System.currentTimeMillis()
    ): Map<String, ModelUsageStats> {
        require(profileId.isNotBlank() && profileId.length <= 80)
        val old = current[profileId] ?: ModelUsageStats()
        return current + (profileId to old.copy(
            requests = old.requests + 1,
            estimatedInputTokens = old.estimatedInputTokens + inputTokens.coerceAtLeast(0),
            estimatedOutputTokens = old.estimatedOutputTokens + outputTokens.coerceAtLeast(0),
            estimatedUsageRequests = old.estimatedUsageRequests + 1,
            lastUsedAt = now
        ))
    }

    fun recordProviderUsage(current: Map<String, ModelUsageStats>, profileId: String, inputTokens: Int, outputTokens: Int, now: Long = System.currentTimeMillis()): Map<String, ModelUsageStats> {
        require(profileId.isNotBlank() && profileId.length <= 80)
        val old = current[profileId] ?: ModelUsageStats()
        return current + (profileId to old.copy(
            requests = old.requests + 1,
            providerInputTokens = old.providerInputTokens + inputTokens.coerceAtLeast(0),
            providerOutputTokens = old.providerOutputTokens + outputTokens.coerceAtLeast(0),
            providerUsageRequests = old.providerUsageRequests + 1,
            lastUsedAt = now
        ))
    }

    fun encode(stats: Map<String, ModelUsageStats>): String = JSONObject().apply {
        stats.filterKeys { it.isNotBlank() && it.length <= 80 }.forEach { (id, value) ->
            put(id, JSONObject()
                .put("requests", value.requests.coerceAtLeast(0))
                .put("input_tokens", value.estimatedInputTokens.coerceAtLeast(0))
                .put("output_tokens", value.estimatedOutputTokens.coerceAtLeast(0))
                .put("provider_input_tokens", value.providerInputTokens.coerceAtLeast(0))
                .put("provider_output_tokens", value.providerOutputTokens.coerceAtLeast(0))
                .put("provider_usage_requests", value.providerUsageRequests.coerceAtLeast(0))
                .put("estimated_usage_requests", value.estimatedUsageRequests.coerceAtLeast(0))
                .put("last_used_at", value.lastUsedAt.coerceAtLeast(0)))
        }
    }.toString()
}
