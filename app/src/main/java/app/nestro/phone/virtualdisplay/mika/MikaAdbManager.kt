/*
 * Nestro wireless-debugging transport for the Mika-compatible shell helper.
 * ADB pairing, TLS transport and NSD discovery are adapted from Mika (Apache-2.0).
 */
package app.nestro.phone.virtualdisplay.mika

import android.content.Context
import android.os.Build
import android.util.Log
import app.nestro.phone.virtualdisplay.mika.adb.AdbConnection
import app.nestro.phone.virtualdisplay.mika.adb.AdbPairing
import app.nestro.phone.virtualdisplay.mika.adb.AdbServiceTypes
import app.nestro.phone.virtualdisplay.mika.adb.AndroidAdb
import app.nestro.phone.virtualdisplay.mika.adb.NsdAdbServiceFinder
import app.nestro.phone.virtualdisplay.mika.adb.AdbPeerInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

class MikaAdbManager(context: Context) {
    private val app = context.applicationContext
    private val finder = NsdAdbServiceFinder(app)

    val shell = MikaHelperHandoffProvider.shell

    suspend fun findPairingPorts(timeoutMs: Long = 3_000L): List<Int> =
        finder.findPorts(AdbServiceTypes.PAIRING, timeoutMs)

    suspend fun findConnectionPorts(timeoutMs: Long = 3_000L): List<Int> =
        finder.findPorts(AdbServiceTypes.CONNECT, timeoutMs)

    suspend fun pair(pairingPort: Int, pairingCode: String): AdbPeerInfo = withContext(Dispatchers.IO) {
        require(pairingPort in 1..65535)
        require(pairingCode.matches(Regex("[0-9]{6}")))
        val identity = AndroidAdb.pairingIdentity(app)
        AdbPairing(AndroidAdb.tls(identity)).pair("127.0.0.1", pairingPort, pairingCode)
    }

    /** Launch the shell-UID helper through the already paired local adbd service. */
    suspend fun launchHelper(): Boolean = withContext(Dispatchers.IO) {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { "Isolated shell helper requires Android 12 or later" }
        val discovered = finder.findPorts(AdbServiceTypes.CONNECT, 3_000L)
        val candidates = (discovered + (systemTlsPort()?.let(::listOf) ?: emptyList())).distinct()
        if (candidates.isEmpty()) throw IOException("No local wireless-debugging connect port was found")
        val request = MikaHelperCommandFactory.create(app)
        var lastFailure: Throwable? = null
        for (port in candidates) {
            val before = shell.value
            try {
                val tls = AndroidAdb.tls(AndroidAdb.identity(app))
                AdbConnection.open("127.0.0.1", port, tls).use { connection ->
                    val stream = connection.exec(request.command)
                    try {
                        stream.write(request.stdinLine)
                        val attached = withTimeoutOrNull(HANDOFF_TIMEOUT_MS) {
                            shell.first { current -> current != null && current !== before }
                        }
                        if (attached != null) return@withContext true
                    } finally {
                        runCatching { stream.close() }
                    }
                }
            } catch (t: Throwable) {
                lastFailure = t
                Log.w(TAG, "helper launch failed on local ADB port " + port, t)
            }
        }
        throw IOException("Unable to start or attach the isolated phone helper", lastFailure)
    }

    fun adapterOrNull(): MikaAidlShellAdapter? = shell.value?.let(::MikaAidlShellAdapter)

    private fun systemTlsPort(): Int? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            .invoke(null, "service.adb.tls.port") as? String
    }.getOrNull()?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 }

    private companion object {
        const val TAG = "NestroMikaAdb"
        const val HANDOFF_TIMEOUT_MS = 15_000L
    }
}
