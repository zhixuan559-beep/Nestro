package app.nestro.data

object LocalBackupCompatibility {
    const val CURRENT_VERSION = 10

    fun isSupported(version: Int): Boolean = version in 1..CURRENT_VERSION

    fun sha256(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
