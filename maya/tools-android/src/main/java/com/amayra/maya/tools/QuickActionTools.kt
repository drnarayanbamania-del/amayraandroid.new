package com.amayra.maya.tools

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.serialization.json.JsonObject

/**
 * Quick-action device tools backing the Tools screen tiles:
 * volume up/down, vibrate, set alarm, countdown timer, calendar, camera, settings.
 */

class VolumeUpTool : Tool {
    override val name = "volume_up"
    override val description = "Raise the media volume one step."
    override fun parameters() = JsonObject(emptyMap())
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val am = ctx.appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return ToolResult.Failure("Audio service unavailable.")
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
        return ToolResult.Success("Volume raised to ${am.getStreamVolume(AudioManager.STREAM_MUSIC)}/${am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}.")
    }
}

class VolumeDownTool : Tool {
    override val name = "volume_down"
    override val description = "Lower the media volume one step."
    override fun parameters() = JsonObject(emptyMap())
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val am = ctx.appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return ToolResult.Failure("Audio service unavailable.")
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
        return ToolResult.Success("Volume lowered to ${am.getStreamVolume(AudioManager.STREAM_MUSIC)}/${am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}.")
    }
}

class VibrateTool : Tool {
    override val name = "vibrate"
    override val description = "Vibrate the phone briefly (haptic feedback)."
    override fun parameters() = JsonObject(emptyMap())
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            (ctx.appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return ToolResult.Failure("Vibrator unavailable.")
        if (!vibrator.hasVibrator()) return ToolResult.Failure("This device has no vibrator.")
        vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
        return ToolResult.Success("Vibrated.")
    }
}

class SetAlarmTool : Tool {
    override val name = "set_alarm"
    override val description = "Set an alarm at a given hour/minute (24h). Args: {\"hour\": 7, \"minute\": 0}."
    override fun parameters() = argSchema(
        mapOf("hour" to "Hour 0-23", "minute" to "Minute 0-59"),
        listOf("hour")
    )
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val hour = args.int("hour") ?: return ToolResult.Failure("Missing 'hour'.")
        val minute = args.int("minute") ?: 0
        if (hour !in 0..23 || minute !in 0..59) return ToolResult.Failure("Hour must be 0-23, minute 0-59.")
        val intent = android.content.Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
            putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
            putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            ctx.appContext.startActivity(intent)
            ToolResult.Success("Alarm set for %02d:%02d.".format(hour, minute))
        } catch (t: Throwable) {
            ToolResult.Failure("No alarm app available on this device.")
        }
    }
}

class StartTimerTool : Tool {
    override val name = "start_timer"
    override val description = "Start a countdown timer of N minutes. Args: {\"minutes\": 5}."
    override fun parameters() = argSchema(mapOf("minutes" to "Timer length in minutes"), listOf("minutes"))
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val minutes = args.int("minutes") ?: return ToolResult.Failure("Missing 'minutes'.")
        if (minutes !in 1..180) return ToolResult.Failure("Minutes must be 1-180.")
        val intent = android.content.Intent(android.provider.AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(android.provider.AlarmClock.EXTRA_LENGTH, minutes * 60)
            putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, "$minutes minute timer")
            putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            ctx.appContext.startActivity(intent)
            ToolResult.Success("$minutes minute timer started.")
        } catch (t: Throwable) {
            ToolResult.Failure("No timer app available on this device.")
        }
    }
}

class OpenCalendarTool : Tool {
    override val name = "open_calendar"
    override val description = "Open the calendar app."
    override fun parameters() = JsonObject(emptyMap())
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
            .setData(android.provider.CalendarContract.CONTENT_URI)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.appContext.startActivity(intent)
            ToolResult.Success("Calendar opened.")
        } catch (t: Throwable) {
            ToolResult.Failure("No calendar app available.")
        }
    }
}

class OpenCameraTool : Tool {
    override val name = "open_camera"
    override val description = "Open the camera app to capture a photo."
    override fun parameters() = JsonObject(emptyMap())
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val intent = android.content.Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.appContext.startActivity(intent)
            ToolResult.Success("Camera opened.")
        } catch (t: Throwable) {
            ToolResult.Failure("No camera app available.")
        }
    }
}

class OpenSettingsTool : Tool {
    override val name = "open_settings"
    override val description = "Open the Android system Settings app."
    override fun parameters() = JsonObject(emptyMap())
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val intent = android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.appContext.startActivity(intent)
        return ToolResult.Success("Settings opened.")
    }
}
