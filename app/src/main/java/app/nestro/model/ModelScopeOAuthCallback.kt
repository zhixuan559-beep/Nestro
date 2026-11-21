package app.nestro.model

import android.content.Context
import android.net.Uri
import app.nestro.data.LocalVault
import app.nestro.data.ModelProfileCatalogCodec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal object ModelScopeOAuthCallback {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    data class Result(val username: String, val displayName: String)

    fun complete(context: Context, callback: Uri, backendBaseUrl: String): Result {
        require(callback.scheme == "nestro" && callback.host == "oauth" && callback.path == "/modelscope") {
            "OAuth 回调地址无效"
        }
        val handoff = callback.getQueryParameter("handoff").orEmpty()
        val appState = callback.getQueryParameter("app_state").orEmpty()
        require(handoff.matches(Regex("[A-Za-z0-9_-]{32,128}"))) { "授权凭据无效或已过期" }
        val prefs = context.getSharedPreferences("nestro_local", Context.MODE_PRIVATE)
        val expectedState = prefs.getString("modelscope_oauth_app_state", null)
        require(!expectedState.isNullOrBlank() && expectedState == appState) { "授权请求已失效，请重新连接魔搭" }

        val base = backendBaseUrl.trim().trimEnd('/')
        require(base.startsWith("https://")) { "OAuth 服务地址未配置 HTTPS" }
        val body = JSONObject().put("handoff", handoff).put("app_state", appState)
            .toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url("$base/oauth/modelscope/complete").post(body).build()
        val response = client.newCall(request).execute()
        val payload = response.use {
            val raw = it.body?.string().orEmpty()
            if (!it.isSuccessful) error("魔搭授权确认失败（HTTP ${it.code}），请重新连接")
            JSONObject(raw)
        }
        val token = payload.optString("access_token")
        require(token.isNotBlank()) { "魔搭未返回有效授权凭据" }
        val account = payload.optJSONObject("account") ?: JSONObject()
        val username = account.optString("username").ifBlank { account.optString("subject") }
        val displayName = account.optString("displayName").ifBlank { username.ifBlank { "魔搭账号" } }
        val providerId = "preset-provider-modelscope"
        val keyId = "modelscope-oauth-account"
        val registryStore = ModelRegistryStore(context.applicationContext)
        val legacyProfiles = ModelProfileCatalogCodec.decode(
            context.getSharedPreferences("nestro_local", Context.MODE_PRIVATE)
                .getString("model_profiles_v1", "[]") ?: "[]"
        )
        val registry = registryStore.load(legacyProfiles) { profileId ->
            LocalVault.readProfile(context.applicationContext, profileId).isNotBlank()
        }
        val provider = registry.providers.firstOrNull { it.id == providerId }
            ?: ModelProvider(
                id = providerId,
                name = "魔搭社区",
                baseUrl = "https://api-inference.modelscope.cn/v1",
                presetId = "modelscope",
                protocol = ModelApiProtocol.OPENAI_CHAT_COMPLETIONS
            )
        val next = registry.upsertProvider(provider.copy(enabled = true)).upsertApiKey(
            ModelApiKey(keyId, providerId, "魔搭 OAuth · $displayName", enabled = true)
        )
        LocalVault.writeApiKey(context.applicationContext, keyId, token)
        try {
            registryStore.save(next)
            check(prefs.edit().remove("modelscope_oauth_app_state").putString("modelscope_oauth_account", username).commit())
        } catch (failure: Throwable) {
            LocalVault.deleteApiKey(context.applicationContext, keyId)
            throw failure
        }
        return Result(username, displayName)
    }
}
