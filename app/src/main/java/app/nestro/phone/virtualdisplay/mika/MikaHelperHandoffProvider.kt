/*
 * Nestro handoff endpoint for the Mika-compatible shell helper.
 * Accepts only uid 2000 and verifies Binder identity, installed APK path,
 * signing-certificate pin and a one-use nonce before retaining the Binder.
 */
package app.nestro.phone.virtualdisplay.mika

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.util.Log
import com.wangzan101.mika.shell.IMikaShell
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.security.SecureRandom

class MikaHelperHandoffProvider : ContentProvider() {
    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_ATTACH) return Bundle().apply { putBoolean(KEY_ACCEPTED, false) }
        val ctx = context?.applicationContext ?: return null
        val callerUid = Binder.getCallingUid()
        val callerPid = Binder.getCallingPid()
        if (callerUid != SHELL_UID) return reject("caller_not_shell")
        val packageName = extras?.getString(KEY_PACKAGE) ?: return reject("missing_package")
        val appUid = extras.getInt(KEY_APP_UID, -1)
        val expectedCert = extras.getString(KEY_CERT) ?: return reject("missing_cert")
        val helperApk = extras.getString(KEY_APK)
        val nonce = extras.getString(KEY_NONCE)
        val binder = extras.getBinder(KEY_BINDER) ?: return reject("missing_binder")
        if (packageName != ctx.packageName || appUid != Process.myUid()) return reject("app_identity_mismatch")
        if (helperApk != ctx.applicationInfo.sourceDir) return reject("stale_apk")
        if (certificatePin(ctx.packageManager, packageName) != expectedCert) return reject("certificate_mismatch")
        val shell = IMikaShell.Stub.asInterface(binder) ?: return reject("invalid_binder")
        val identity = runCatching { shell.identity() }.getOrNull() ?: return reject("identity_unavailable")
        if (identity.size < 2 || identity[0] != callerUid || identity[1] != callerPid) return reject("binder_process_mismatch")
        synchronized(lock) {
            val prefs = ctx.getSharedPreferences(PREFS, 0)
            val expected = prefs.getString(KEY_NONCE_STORE, null) ?: return reject("no_pending_nonce")
            if (!constantTimeEquals(expected, nonce ?: "")) return reject("bad_nonce")
            val next = randomNonce()
            prefs.edit().putString(KEY_NONCE_STORE, next).apply()
            val linked = runCatching {
                binder.linkToDeath({
                    synchronized(lock) {
                        if (_shell.value?.asBinder() === binder) _shell.value = null
                    }
                }, 0)
                true
            }.getOrDefault(false)
            if (!linked) return reject("dead_binder")
            _shell.value = shell
            return Bundle().apply {
                putBoolean(KEY_ACCEPTED, true)
                putString(KEY_NEXT, next)
                putBinder(KEY_TOKEN, appProcessToken)
            }
        }
    }

    private fun reject(reason: String) = Bundle().apply {
        Log.w(TAG, "helper handoff rejected: " + reason)
        putBoolean(KEY_ACCEPTED, false)
        putString(KEY_REASON, reason)
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
        signatures.map { bytes ->
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        }.sorted().joinToString(",")
    }.getOrNull()

    companion object {
        private const val TAG = "NestroMikaHandoff"
        private const val SHELL_UID = 2000
        private const val METHOD_ATTACH = "attach"
        private const val PREFS = "nestro_mika_helper"
        private const val KEY_NONCE_STORE = "nonce"
        private const val KEY_ACCEPTED = "accepted"
        private const val KEY_NEXT = "next"
        private const val KEY_TOKEN = "token"
        private const val KEY_REASON = "reason"
        private const val KEY_NONCE = "nonce"
        private const val KEY_BINDER = "binder"
        private const val KEY_APK = "apk"
        private const val KEY_PACKAGE = "package"
        private const val KEY_APP_UID = "appUid"
        private const val KEY_CERT = "cert"
        private val lock = Any()
        private val appProcessToken = Binder()
        private val _shell = MutableStateFlow<IMikaShell?>(null)
        val shell: StateFlow<IMikaShell?> = _shell.asStateFlow()

        fun issueLaunchNonce(context: android.content.Context): String {
            val nonce = randomNonce()
            context.applicationContext.getSharedPreferences(PREFS, 0).edit()
                .putString(KEY_NONCE_STORE, nonce).apply()
            return nonce
        }

        private fun randomNonce(): String = ByteArray(32).also(SecureRandom()::nextBytes)
            .joinToString("") { "%02x".format(it) }

        private fun constantTimeEquals(a: String, b: String): Boolean =
            MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
    }
}
