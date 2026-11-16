package app.nestro.data

object LocalBackupCompatibility {
    const val CURRENT_VERSION = 9

    fun isSupported(version: Int): Boolean = version in 1..CURRENT_VERSION
}
