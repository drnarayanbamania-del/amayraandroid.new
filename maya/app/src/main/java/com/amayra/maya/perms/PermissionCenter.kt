package com.amayra.maya.perms

import android.Manifest
import android.app.AlarmManager
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.content.ContextCompat

/**
 * Central permission introspection (blueprint §PERMISSION ARCHITECTURE).
 *
 * Read-only truth for the debug panel. Actual grants still happen exclusively
 * through the normal Android flows (runtime dialogs / system Settings) — this
 * class never requests, bypasses or simulates anything.
 */
object PermissionCenter {

    /**
     * One permission row.
     * [granted] true = usable, false = missing, null = indeterminate (needs manual check).
     */
    data class Perm(
        val name: String,
        val granted: Boolean?,
        val why: String,
        val howToFix: String,
    )

    fun snapshot(ctx: Context): List<Perm> = listOf(
        mic(ctx),
        notifications(ctx),
        accessibility(ctx),
        notificationsListener(ctx),
        exactAlarms(ctx),
        systemAlertWindow(ctx),
    )

    fun mic(ctx: Context) = Perm(
        name = "Microphone (RECORD_AUDIO)",
        granted = isGranted(ctx, Manifest.permission.RECORD_AUDIO),
        why = "Wake word, speech recognition and Voice Guardian",
        howToFix = "Settings → Apps → Maya → Permissions → Microphone",
    )

    fun notifications(ctx: Context): Perm {
        val ok = if (Build.VERSION.SDK_INT >= 33)
            isGranted(ctx, Manifest.permission.POST_NOTIFICATIONS) else true
        return Perm(
            name = "Notifications (POST_NOTIFICATIONS)",
            granted = ok,
            why = "Foreground voice service and reply notifications",
            howToFix = "Settings → Apps → Maya → Permissions → Notifications",
        )
    }

    fun accessibility(ctx: Context): Perm {
        val live = com.amayra.maya.accessibility.MayaAccessibilityService.isLive
        return Perm(
            name = "Accessibility service",
            granted = live,
            why = "Screen understanding, UI control and macros",
            howToFix = "Settings → Accessibility → Maya → On",
        )
    }

    fun notificationsListener(ctx: Context): Perm {
        val flat = Settings.Secure.getString(
            ctx.contentResolver, "enabled_notification_listeners"
        ).orEmpty()
        val ok = flat.split(":").any { it.trim().startsWith(ctx.packageName) }
        return Perm(
            name = "Notification listener",
            granted = ok,
            why = "Reading notifications (WhatsApp auto-reply)",
            howToFix = "Settings → Notification access → Maya",
        )
    }

    fun exactAlarms(ctx: Context): Perm {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val ok = if (Build.VERSION.SDK_INT >= 31) am?.canScheduleExactAlarms() == true else true
        return Perm(
            name = "Exact alarms (SCHEDULE_EXACT_ALARM)",
            granted = ok,
            why = "Reminders and social alarms fire on time",
            howToFix = "Settings → Apps → Maya → Alarms & reminders → Allow",
        )
    }

    fun systemAlertWindow(ctx: Context): Perm {
        val ok = if (Build.VERSION.SDK_INT >= 29) {
            val appOps = ctx.getSystemService(AppOpsManager::class.java)
            appOps?.checkOpNoThrow(
                AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, Process.myUid(), ctx.packageName
            ) == AppOpsManager.MODE_ALLOWED
        } else Settings.canDrawOverlays(ctx)
        return Perm(
            name = "Draw over other apps",
            granted = ok,
            why = "Floating Maya overlay while she works",
            howToFix = "Settings → Apps → Maya → Display over other apps → Allow",
        )
    }

    private fun isGranted(ctx: Context, perm: String): Boolean =
        ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED
}
