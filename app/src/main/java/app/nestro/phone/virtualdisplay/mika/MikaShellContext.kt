/*
 * Portions adapted from scrcpy (https://github.com/Genymobile/scrcpy):
 * Copyright (C) 2018 Genymobile
 * Copyright (C) 2018-2024 Romain Vimont
 * Licensed under the Apache License, Version 2.0.
 * Modifications: adapted shell-context initialization for Nestro's Mika-compatible helper process.
 * Mika source: https://github.com/wangzan101/Mika
 */
package app.nestro.phone.virtualdisplay.mika

import android.annotation.SuppressLint
import android.app.Application
import android.app.Instrumentation
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.os.Build

/** System context presented as com.android.shell (uid 2000) inside the helper process. */
internal class MikaShellContext private constructor(base: Context) : ContextWrapper(base) {
    override fun getPackageName(): String = SHELL_PACKAGE
    override fun getOpPackageName(): String = SHELL_PACKAGE
    override fun getAttributionSource(): AttributionSource {
        // AttributionSource.Builder 需要 API 31；低版本走系统默认归属源。
        // API 30 以下 AttributionSource 不存在，系统框架不会调用此方法。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return AttributionSource.Builder(SHELL_UID).setPackageName(SHELL_PACKAGE).build()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return super.getAttributionSource()
        }
        error("AttributionSource 在 API 30 以下不可用")
    }
    override fun getApplicationContext(): Context = this

    companion object {
        const val SHELL_PACKAGE = "com.android.shell"
        const val SHELL_UID = 2000

        @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
        fun create(): Context {
            val at = Class.forName("android.app.ActivityThread")
            val thread = at.getMethod("currentActivityThread").invoke(null) ?: newActivityThread(at)
            val system = at.getDeclaredMethod("getSystemContext").invoke(thread) as Context
            val ctx = MikaShellContext(system)
            if (at.getDeclaredField("mInitialApplication").apply { isAccessible = true }.get(thread) == null) {
                runCatching {
                    val app = Instrumentation.newApplication(Application::class.java, ctx)
                    at.getDeclaredField("mInitialApplication").apply { isAccessible = true }.set(thread, app)
                }
            }
            return ctx
        }

        @SuppressLint("PrivateApi", "DiscouragedPrivateApi", "SoonBlockedPrivateApi")
        private fun newActivityThread(at: Class<*>): Any {
            val thread = at.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            at.getDeclaredField("sCurrentActivityThread").apply { isAccessible = true }.set(null, thread)
            at.getDeclaredField("mSystemThread").apply { isAccessible = true }.setBoolean(thread, true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) runCatching {
                val cc = Class.forName("android.app.ConfigurationController")
                val internal = Class.forName("android.app.ActivityThreadInternal")
                val controller = cc.getDeclaredConstructor(internal).apply { isAccessible = true }.newInstance(thread)
                at.getDeclaredField("mConfigurationController").apply { isAccessible = true }.set(thread, controller)
            }
            runCatching {
                val abd = Class.forName("android.app.ActivityThread\$AppBindData")
                val data = abd.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
                abd.getDeclaredField("appInfo").apply { isAccessible = true }
                    .set(data, ApplicationInfo().apply { packageName = SHELL_PACKAGE })
                at.getDeclaredField("mBoundApplication").apply { isAccessible = true }.set(thread, data)
            }
            return thread
        }
    }
}
