package app.nestro.phone

import java.net.URI

/** Reject local-network and credential-bearing URLs before opening third-party content. */
object PhoneAgentUrlPolicy {
    fun isSafePublicWebUrl(rawUrl: String): Boolean {
        val uri = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return false
        if (!(uri.scheme.equals("https", true) || uri.scheme.equals("http", true))) return false
        if (uri.userInfo != null || uri.host.isNullOrBlank() || uri.port !in setOf(-1, 80, 443)) return false
        val host = uri.host.lowercase().trimEnd('.').removePrefix("[").removeSuffix("]")
        if (host == "localhost" || host.endsWith(".localhost") ||
            host.endsWith(".local") || host.endsWith(".internal") || host.endsWith(".lan")
        ) return false
        val octets = host.split(".")
        if (octets.size == 4 && octets.all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }) {
            val a = octets[0].toInt()
            val b = octets[1].toInt()
            if (a == 10 || a == 127 || a == 0 || a == 169 && b == 254 ||
                a == 172 && b in 16..31 || a == 192 && b == 168 ||
                a >= 224
            ) return false
        }
        if (host == "::1" || host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe80")) return false
        return true
    }
}
