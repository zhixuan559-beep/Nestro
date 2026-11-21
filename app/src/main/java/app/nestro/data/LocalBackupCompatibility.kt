package app.nestro.data

import org.json.JSONArray
import org.json.JSONObject

object LocalBackupCompatibility {
    const val CURRENT_VERSION = 11

    fun isSupported(version: Int): Boolean = version in 1..CURRENT_VERSION

    internal fun requireUniqueIds(ids: List<String>, label: String) {
        require(ids.all { it.isNotBlank() } && ids.distinct().size == ids.size) { "$label ID 缺失或重复" }
    }

    internal fun requireUtf8SizeWithin(value: String, maxBytes: Int, message: String) {
        require(maxBytes >= 0 && value.toByteArray(Charsets.UTF_8).size <= maxBytes) { message }
    }

    fun sha256(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    fun canonicalJson(value: JSONObject): String = canonicalValue(value)

    fun verifyChecksum(root: JSONObject): Boolean {
        val checksum = root.optString("checksum_sha256")
        // Checksums were introduced in backup format v10. Only older formats
        // may omit one; current-format backups must never bypass integrity checks.
        if (checksum.isBlank()) return root.optInt("version", 0) in 1..9
        val payload = JSONObject(root.toString()).apply { remove("checksum_sha256") }
        val recomputed = runCatching { sha256(canonicalJson(payload)) }.getOrNull() ?: return false
        return checksum.equals(recomputed, ignoreCase = true)
    }

    private fun canonicalValue(value: Any?): String = when {
        value == null || value === JSONObject.NULL -> "null"
        value is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { key ->
            JSONObject.quote(key) + ":" + canonicalValue(value.opt(key))
        }
        value is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { index -> canonicalValue(value.opt(index)) }
        value is String -> JSONObject.quote(value)
        // Numbers must be canonicalised exactly the way org.json serialises them
        // (JSONObject.numberToString): an integral double such as a speech rate of
        // 1.0 is written to the file as "1" and re-parsed as an integer, so using
        // value.toString() here ("1.0") made every such backup fail its own
        // integrity check after a serialise/parse round trip.
        value is Number -> JSONObject.numberToString(value)
        value is Boolean -> value.toString()
        else -> JSONObject.quote(value.toString())
    }
}
