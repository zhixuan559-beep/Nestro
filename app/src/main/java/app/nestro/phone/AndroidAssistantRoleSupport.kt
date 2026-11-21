package app.nestro.phone

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build

/** Runtime-checked access to Android's optional default-assistant role. */
object AndroidAssistantRoleSupport {
    fun isAvailable(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val manager = context.getSystemService(RoleManager::class.java) ?: return false
        return manager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)
    }

    fun isHeld(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val manager = context.getSystemService(RoleManager::class.java) ?: return false
        return manager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
    }

    /** Returns the system-owned consent UI intent; null means unavailable or already held. */
    fun createRequestIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val manager = context.getSystemService(RoleManager::class.java) ?: return null
        if (!manager.isRoleAvailable(RoleManager.ROLE_ASSISTANT) ||
            manager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
        ) return null
        return manager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
    }
}
