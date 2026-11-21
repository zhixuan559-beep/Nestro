package app.nestro.speech

import android.content.Context
import app.nestro.data.LocalVault
import org.json.JSONObject
import java.util.UUID

/**
 * Stores non-secret provider metadata in preferences and the API key in Android Keystore-backed
 * encrypted storage. Each save rotates the encrypted-key record so a failed metadata write
 * cannot destroy the previously saved working configuration.
 */
class SpeechProviderConfigStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("nestro_voice_provider_v1", Context.MODE_PRIVATE)

    fun save(config: SpeechProviderConfig) {
        config.validateForRemoteProvider()
        val previousKeyId = readKeyId()
        val nextKeyId = "speech-provider-${UUID.randomUUID()}"
        LocalVault.writeApiKey(appContext, nextKeyId, config.apiKey)
        val metadata = JSONObject()
            .put("providerId", config.providerId)
            .put("keyId", nextKeyId)
            .put("baseUrl", config.baseUrl.trim().trimEnd('/'))
            .put("model", config.model.trim())
            .put("inputModel", config.inputModel.trim())
            .put("outputModel", config.outputModel.trim())
            .put("voice", config.voice?.trim().orEmpty())
        if (!preferences.edit().putString(METADATA_KEY, metadata.toString()).commit()) {
            LocalVault.deleteApiKey(appContext, nextKeyId)
            error("语音服务配置保存失败")
        }
        previousKeyId?.takeIf { it != nextKeyId }?.let { LocalVault.deleteApiKey(appContext, it) }
    }

    fun read(): SpeechProviderConfig? {
        val raw = preferences.getString(METADATA_KEY, null) ?: return null
        val metadata = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val providerId = metadata.optString("providerId")
        if (!providerId.matches(Regex("[a-zA-Z0-9._-]{1,80}"))) return null
        val keyId = metadata.optString("keyId").takeIf {
            it.matches(Regex("[A-Za-z0-9_.-]{1,120}"))
        } ?: "speech-provider-$providerId"
        val apiKey = runCatching { LocalVault.readApiKey(appContext, keyId) }.getOrDefault("")
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
        readKeyId()?.let { LocalVault.deleteApiKey(appContext, it) }
        check(preferences.edit().remove(METADATA_KEY).commit()) { "语音服务配置删除失败" }
    }

    private fun readKeyId(): String? {
        val raw = preferences.getString(METADATA_KEY, null) ?: return null
        return runCatching {
            val metadata = JSONObject(raw)
            metadata.optString("keyId").takeIf {
                it.matches(Regex("[A-Za-z0-9_.-]{1,120}"))
            } ?: metadata.optString("providerId")
                .takeIf { it.matches(Regex("[a-zA-Z0-9._-]{1,80}")) }
                ?.let { "speech-provider-$it" }
        }.getOrNull()
    }

    private companion object {
        const val METADATA_KEY = "provider_metadata"
    }
}
