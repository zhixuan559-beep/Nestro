/*
 * Launch command and certificate pinning for Nestro's Mika-compatible helper.
 * The nonce is returned separately so callers write it to the ADB exec stream,
 * never into process argv or logs.
 */
package app.nestro.phone.virtualdisplay.mika

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import java.security.MessageDigest

data class MikaHelperLaunchRequest(val command: String, val stdinLine: ByteArray)

object MikaHelperCommandFactory {
    fun create(context: Context): MikaHelperLaunchRequest {
        val app = context.applicationContext
        val packageName = app.packageName
        val apkPath = app.applicationInfo.sourceDir
        val uid = Process.myUid()
        val certPin = certificatePin(app.packageManager, packageName)
            ?: error("Cannot read the installed app signing certificate")
        val nonce = MikaHelperHandoffProvider.issueLaunchNonce(app)
        val helperClass = "app.nestro.phone.virtualdisplay.mika.MikaHelperMain"
        val processName = packageName + ":helper"
        val command = "read n; (echo \"\$n\" | CLASSPATH=" + quote(apkPath) +
            " setsid app_process /system/bin --nice-name=" + quote(processName) + " " +
            helperClass + " " + quote(packageName) + " " + uid + " " + quote(certPin) +
            " >/dev/null 2>&1 &); read _"
        return MikaHelperLaunchRequest(command, (nonce + "\n").toByteArray(Charsets.UTF_8))
    }

    private fun certificatePin(pm: PackageManager, packageName: String): String? = runCatching {
        val info = if (android.os.Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
        }
        val signatures = if (android.os.Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners?.map { it.toByteArray() }
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.map { it.toByteArray() }
        } ?: return null
        signatures.map { MessageDigest.getInstance("SHA-256").digest(it).joinToString("") { b -> "%02x".format(b) } }
            .sorted().joinToString(",")
    }.getOrNull()

    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
}
