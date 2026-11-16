package app.nestro.data

import org.json.JSONArray
import org.json.JSONObject

object LocalBackupCompatibility {
    const val CURRENT_VERSION = 11

    fun isSupported(version: Int): Boolean = version in 1..CURRENT_VERSION

    fun sha256(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    fun canonicalJson(value: JSONObject): String = canonicalValue(value)

    private fun canonicalValue(value: Any?): String = when {
        value == null || value === JSONObject.NULL -> "null"
        value is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { key ->
            JSONObject.quote(key) + ":" + canonicalValue(value.opt(key))
        }
        value is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { index -> canonicalValue(value.opt(index)) }
        value is String -> JSONObject.quote(value)
        value is Number || value is Boolean -> value.toString()
        else -> JSONObject.quote(value.toString())
    }
}
