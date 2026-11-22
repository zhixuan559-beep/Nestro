/*
 * Nestro shell-UID helper entry, adapted from Mika's HelperProcess handoff model.
 * Mika: https://github.com/wangzan101/Mika (Apache-2.0).
 */
package app.nestro.phone.virtualdisplay.mika

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Log
import com.wangzan101.mika.shell.IMikaShell
import java.io.InputStream
import java.io.InputStreamReader
import java.io.BufferedReader
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

object MikaHelperMain {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 4) exitProcess(2)
        val packageName = args[0]
        val appUid = args[1].toIntOrNull() ?: exitProcess(2)
        val certificatePin = args[2]
        val helperApkPath = args[3]
        val stdin = System::class.java.getField("in").get(null) as InputStream
        val nonce = runCatching { BufferedReader(InputStreamReader(stdin)).readLine()?.trim() }.getOrNull()
        runCatching { stdin.close() }
        if (nonce.isNullOrBlank() || Process.myUid() != MikaShellContext.SHELL_UID) exitProcess(2)

        Looper.prepareMainLooper()
        val shellContext = MikaShellContext.create()
        val service = MikaVirtualDisplayService(appUid)
        val worker = HandlerThread("nestro-mika-helper").apply { start() }
        Handler(worker.looper).post {
            var nextNonce: String? = nonce
            while (true) {
                val attached = attach(shellContext, packageName, appUid, certificatePin, helperApkPath, service, nextNonce)
                if (attached == null) {
                    Thread.sleep(RETRY_MS)
                    continue
                }
                nextNonce = attached.second
                val died = CountDownLatch(1)
                try {
                    attached.first.linkToDeath({ died.countDown() }, 0)
                    died.await()
                } catch (_: Throwable) {
                    // App process death is the expected signal to release virtual displays.
                }
                runCatching { service.releaseAll() }
                Thread.sleep(RETRY_MS)
            }
        }
        Looper.loop()
    }

    private fun attach(
        context: android.content.Context,
        packageName: String,
        appUid: Int,
        certificatePin: String,
        helperApkPath: String,
        service: IMikaShell,
        nonce: String?,
    ): Pair<IBinder, String>? = try {
        val identity = service.identity()
        if (identity.size < 2 || identity[0] != Process.myUid() || identity[1] != Process.myPid()) return null
        val extras = Bundle().apply {
            putString(KEY_NONCE, nonce)
            putBinder(KEY_BINDER, service.asBinder())
            putInt(KEY_UID, identity[0])
            putInt(KEY_PID, identity[1])
            putString(KEY_APK, helperApkPath)
            putString(KEY_PACKAGE, packageName)
            putInt(KEY_APP_UID, appUid)
            putString(KEY_CERT, certificatePin)
        }
        val result = context.contentResolver.call(
            Uri.parse("content://$packageName.helper"), METHOD_ATTACH, null, extras
        ) ?: return null
        if (!result.getBoolean(KEY_ACCEPTED, false)) return null
        val token = result.getBinder(KEY_TOKEN) ?: return null
        val next = result.getString(KEY_NEXT)?.takeIf { it.isNotBlank() } ?: return null
        token to next
    } catch (t: Throwable) {
        Log.w(TAG, "helper handoff retry: " + t.javaClass.simpleName)
        null
    }

    private const val TAG = "NestroMikaHelper"
    private const val RETRY_MS = 1_000L
    private const val METHOD_ATTACH = "attach"
    private const val KEY_NONCE = "nonce"
    private const val KEY_BINDER = "binder"
    private const val KEY_UID = "uid"
    private const val KEY_PID = "pid"
    private const val KEY_APK = "apk"
    private const val KEY_PACKAGE = "package"
    private const val KEY_APP_UID = "appUid"
    private const val KEY_CERT = "cert"
    private const val KEY_ACCEPTED = "accepted"
    private const val KEY_NEXT = "next"
    private const val KEY_TOKEN = "token"
}