package app.nestro.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import app.nestro.model.ModelApiProtocol

internal data class ModelConfig(
    val baseUrl: String,
    val model: String,
    val apiKey: String,
    val reasoningEffort: String? = null,
    val protocol: ModelApiProtocol = ModelApiProtocol.OPENAI_CHAT_COMPLETIONS,
    val contextWindowTokens: Int? = null
)

internal object LocalVault {
    private const val ALIAS = "nestro_api_key_v1"
    private const val MODEL_KEY_PREFIX = "model_api_key_v1_"
    private const val TASK_PAYLOAD_ALIAS = "nestro_task_payload_v1"
    fun write(context: Context, config: ModelConfig) {
        val prefs = context.getSharedPreferences("nestro_local", Context.MODE_PRIVATE)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getKey())
        val encrypted = cipher.doFinal(config.apiKey.toByteArray(Charsets.UTF_8))
        val packed = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        prefs.edit().putString("base_url", config.baseUrl).putString("model", config.model)
            .putString("api_key_enc", packed).putString("active_model_protocol", config.protocol.name).apply()
    }
    fun read(context: Context): ModelConfig {
        val prefs = context.getSharedPreferences("nestro_local", Context.MODE_PRIVATE)
        val packed = prefs.getString("api_key_enc", null)
        val key = runCatching {
            if (packed.isNullOrBlank()) "" else {
                val parts = packed.split(":")
                require(parts.size == 2) { "旧版 API Key 密文格式无效" }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, getKey(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
                String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
            }
        }.getOrDefault("")
        return ModelConfig(
            prefs.getString("base_url", "") ?: "",
            prefs.getString("model", "") ?: "",
            key,
            protocol = runCatching {
                ModelApiProtocol.valueOf(prefs.getString("active_model_protocol", "") ?: "")
            }.getOrDefault(ModelApiProtocol.OPENAI_CHAT_COMPLETIONS)
        )
    }
    fun encryptTaskPayload(payload: String): String {
        require(payload.length <= 8_000) { "可恢复任务输入超过安全存储上限" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getKey(TASK_PAYLOAD_ALIAS))
        val encrypted = cipher.doFinal(payload.toByteArray(Charsets.UTF_8))
        return "task1:" + Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    fun decryptTaskPayload(packed: String): String {
        require(packed.length in 1..48_000) { "可恢复任务数据长度无效" }
        val parts = packed.split(":")
        require(parts.size == 3 && parts[0] == "task1") { "可恢复任务数据格式无效" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            getKey(TASK_PAYLOAD_ALIAS),
            GCMParameterSpec(128, Base64.decode(parts[1], Base64.NO_WRAP))
        )
        return String(cipher.doFinal(Base64.decode(parts[2], Base64.NO_WRAP)), Charsets.UTF_8)
    }

    fun writeApiKey(context: Context, keyId: String, apiKey: String) {
        require(keyId.matches(Regex("[A-Za-z0-9_.-]{1,120}"))) { "API Key 标识无效" }
        val preferences = context.getSharedPreferences("nestro_local", Context.MODE_PRIVATE)
        val preferenceKey = MODEL_KEY_PREFIX + keyId
        if (apiKey.isBlank()) {
            check(preferences.edit().remove(preferenceKey).commit()) { "API Key 删除失败" }
            return
        }
        check(preferences.edit().putString(preferenceKey, encryptSecret(apiKey)).commit()) {
            "API Key 加密保存失败"
        }
    }

    fun readApiKey(context: Context, keyId: String): String {
        require(keyId.matches(Regex("[A-Za-z0-9_.-]{1,120}"))) { "API Key 标识无效" }
        val packed = context.getSharedPreferences("nestro_local", Context.MODE_PRIVATE)
            .getString(MODEL_KEY_PREFIX + keyId, null) ?: return ""
        return decryptSecret(packed)
    }

    fun deleteApiKey(context: Context, keyId: String) {
        require(keyId.matches(Regex("[A-Za-z0-9_.-]{1,120}"))) { "API Key 标识无效" }
        check(
            context.getSharedPreferences("nestro_local", Context.MODE_PRIVATE)
                .edit().remove(MODEL_KEY_PREFIX + keyId).commit()
        ) { "API Key 删除失败" }
    }

    fun writeProfile(context: Context, id: String, apiKey: String) {
        require(id.isNotBlank() && id.length <= 80)
        val legacyPreferenceKey = "model_api_key_enc_$id"
        if (apiKey.isBlank()) {
            deleteApiKey(context, "legacy-key-$id")
            context.getSharedPreferences("nestro_local", Context.MODE_PRIVATE)
                .edit().remove(legacyPreferenceKey).apply()
            return
        }
        writeApiKey(context, "legacy-key-$id", apiKey)
        context.getSharedPreferences("nestro_local", Context.MODE_PRIVATE)
            .edit().remove(legacyPreferenceKey).apply()
    }

    fun readProfile(context: Context, id: String): String {
        require(id.isNotBlank() && id.length <= 80)
        val keyId = "legacy-key-$id"
        val migrated = readApiKey(context, keyId)
        if (migrated.isNotBlank()) return migrated
        val preferences = context.getSharedPreferences("nestro_local", Context.MODE_PRIVATE)
        val legacyPreferenceKey = "model_api_key_enc_$id"
        val packed = preferences.getString(legacyPreferenceKey, null) ?: return ""
        val legacyValue = decryptSecret(packed)
        if (legacyValue.isNotBlank()) {
            writeApiKey(context, keyId, legacyValue)
            preferences.edit().remove(legacyPreferenceKey).apply()
        }
        return legacyValue
    }

    fun deleteProfile(context: Context, id: String) {
        require(id.isNotBlank() && id.length <= 80)
        deleteApiKey(context, "legacy-key-$id")
        context.getSharedPreferences("nestro_local", Context.MODE_PRIVATE)
            .edit().remove("model_api_key_enc_$id").apply()
    }

    private fun encryptSecret(secret: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getKey())
        val encrypted = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    private fun decryptSecret(packed: String): String = runCatching {
        val parts = packed.split(":")
        require(parts.size == 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getKey(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrDefault("")

    fun clearAllApiKeys(context: Context) {
        val preferences = context.getSharedPreferences("nestro_local", Context.MODE_PRIVATE)
        val editor = preferences.edit().remove("api_key_enc")
        preferences.all.keys
            .filter { it.startsWith(MODEL_KEY_PREFIX) || it.startsWith("model_api_key_enc_") }
            .forEach(editor::remove)
        check(editor.commit()) { "API Key 清理失败" }
    }

    fun clearActive(context: Context) {
        context.getSharedPreferences("nestro_local", Context.MODE_PRIVATE)
            .edit()
            .remove("active_model_profile_id")
            .remove("base_url")
            .remove("model")
            .remove("active_model_protocol")
            .remove("api_key_enc")
            .apply()
    }

    private fun getKey(alias: String = ALIAS): SecretKey {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(
            alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }
}

