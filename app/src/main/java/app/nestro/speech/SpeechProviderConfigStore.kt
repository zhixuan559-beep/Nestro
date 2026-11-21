package app.nestro.speech

import android.content.Context
import app.nestro.data.LocalVault
import org.json.JSONObject

/**
 * Stores non-secret provider metadata in preferences and the API key in Android Keystore-backed
 * encrypted storage. The plaintext key exists only in the returned runtime config.
 */
class SpeechProviderConfigStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("nestro_voice_provider_v1", Context.MODE_PRIVATE)

    fun save(config: SpeechProviderConfig) {
        config.validateForRemoteProvider()
        val keyId = keyId(config.providerId)
        LocalVault.writeApiKey(appContext, keyId, config.apiKey)
        val metadata = JSONObject()
            .put("providerId", config.providerId)
            .put("baseUrl", config.baseUrl.trim().trimEnd('/'))
            .put("model", config.model.trim())
            .put("voice", config.voice?.trim().orEmpty())
        if (!preferences.edit().putString(METADATA_KEY, metadata.toString()).commit()) {
            LocalVault.deleteApiKey(appContext, keyId)
            error("语音服务配置保存失败")
        }
    }

    fun read(): SpeechProviderConfig? {
        val raw = preferences.getString(METADATA_KEY, null) ?: return null
        val metadata = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val providerId = metadata.optString("providerId")
        if (!providerId.matches(Regex("[a-zA-Z0-9._-]{1,80}"))) return null
        val apiKey = runCatching { LocalVault.readApiKey(appContext, keyId(providerId)) }.getOrDefault("")
        if (apiKey.isBlank()) return null
        return runCatching {
            SpeechProviderConfig(
                providerId = providerId,
                baseUrl = metadata.optString("baseUrl"),
                apiKey = apiKey,
                model = metadata.optString("model"),
                voice = metadata.optString("voice").ifBlank { null },
                inputModel = metadata.optString("inputModel").ifBlank { "whisper-1" },
                outputModel = metadata.optString("outputModel").ifBlank { "gpt-4o-mini-tts" }
            ).also { it.validateForRemoteProvider() }
        }.getOrNull()
    }

    fun delete() {
        readProviderId()?.let { LocalVault.deleteApiKey(appContext, keyId(it)) }
        check(preferences.edit().remove(METADATA_KEY).commit()) { "语音服务配置删除失败" }
    }

    private fun readProviderId(): String? {
        val raw = preferences.getString(METADATA_KEY, null) ?: return null
        return runCatching {
            JSONObject(raw).optString("providerId")
                .takeIf { it.matches(Regex("[a-zA-Z0-9._-]{1,80}")) }
        }.getOrNull()
    }

    private fun keyId(providerId: String) = "speech-provider-$providerId"

    private companion object {
        const val METADATA_KEY = "provider_metadata"
    }
}
