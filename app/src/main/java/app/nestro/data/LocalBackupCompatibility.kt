package app.nestro.data

import org.json.JSONArray
import org.json.JSONObject

object LocalBackupCompatibility {
    const val CURRENT_VERSION = 10

    fun isSupported(version: Int): Boolean = version in 1..CURRENT_VERSION

    fun sha256(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    fun canonicalJson(value: JSONObject): String {
        val sorted = JSONObject()
        val keys = value.keys().asSequence().toList().sorted()
        keys.forEach { key -> sorted.put(key, canonicalValue(value.opt(key))) }
        return sorted.toString()
    }

    private fun canonicalValue(value: Any?): Any? = when (value) {
        is JSONObject -> JSONObject(canonicalJson(value))
        is JSONArray -> JSONArray().apply {
            for (index in 0 until value.length()) put(canonicalValue(value.opt(index)))
        }
        else -> value
    }
}
