package app.nestro.mcp

import java.security.MessageDigest

/*
 * Per-server connection reuse is adapted from NoHarry/android-agent-runtime:
 * agent-app-server/.../mcp/McpHttpClients.kt (MIT, Copyright (c) 2026 NoHarry).
 * The cache is adapted to Nestro's encrypted credential repository and protocol client.
 * See LICENSE-NOHARRY-ANDROID-AGENT-RUNTIME-MIT.txt.
 */
internal class McpClientPool<T : AutoCloseable>(
    private val readCredential: (String) -> String,
    private val createClient: (endpoint: String, credential: String) -> T
) : AutoCloseable {
    private data class Entry<T>(
        val endpoint: String,
        val credentialFingerprint: String,
        val client: T
    )

    private val lock = Any()
    private val entries = mutableMapOf<String, Entry<T>>()

    fun get(serverId: String, endpoint: String): T {
        require(serverId.isNotBlank()) { "MCP 服务 ID 不能为空" }
        require(endpoint.isNotBlank()) { "MCP 服务地址不能为空" }

        val credential = readCredential(serverId)
        val fingerprint = sha256(credential)
        var stale: T? = null
        val selected = synchronized(lock) {
            val existing = entries[serverId]
            if (existing != null &&
                existing.endpoint == endpoint &&
                existing.credentialFingerprint == fingerprint
            ) {
                existing.client
            } else {
                val replacement = createClient(endpoint, credential)
                stale = existing?.client
                entries[serverId] = Entry(endpoint, fingerprint, replacement)
                replacement
            }
        }
        stale?.let(::closeQuietly)
        return selected
    }

    fun invalidate(serverId: String) {
        val stale = synchronized(lock) { entries.remove(serverId)?.client }
        stale?.let(::closeQuietly)
    }

    override fun close() {
        val stale = synchronized(lock) {
            entries.values.map { it.client }.also { entries.clear() }
        }
        stale.forEach(::closeQuietly)
    }

    private fun closeQuietly(client: T) {
        runCatching { client.close() }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
