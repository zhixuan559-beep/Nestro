package app.nestro.phone

import java.net.URI

/** Reject local-network and credential-bearing URLs before opening third-party content. */
object PhoneAgentUrlPolicy {
    fun isSafePublicWebUrl(rawUrl: String): Boolean {
        val uri = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return false
        val scheme = uri.scheme.lowercase()
        if (scheme != "https" && scheme != "http") return false
        val portAllowed = uri.port == -1 || (scheme == "https" && uri.port == 443) || (scheme == "http" && uri.port == 80)
        if (uri.userInfo != null || uri.host.isNullOrBlank() || !portAllowed) return false
        val sensitiveQueryKey = Regex("(?i)(^|&)(access_token|refresh_token|api_key|apikey|token|secret|password|session|auth|credential|signature)=([^&]+)")
        val decodedQuery = runCatching { java.net.URLDecoder.decode(uri.rawQuery.orEmpty(), "UTF-8") }.getOrDefault(uri.rawQuery.orEmpty())
        if (sensitiveQueryKey.containsMatchIn(decodedQuery)) return false
        val host = uri.host.lowercase().trimEnd('.').removePrefix("[").removeSuffix("]")
        if (host == "localhost" || host.endsWith(".localhost") ||
            host.endsWith(".local") || host.endsWith(".internal") || host.endsWith(".lan")
        ) return false
        // Do not let browsers reinterpret shortened, integer or hexadecimal IP literals.
        if (host.matches(Regex("(?i)(0x[0-9a-f]+|[0-9.]+)")) || host.contains(":")) return false
        return true
    }
}
