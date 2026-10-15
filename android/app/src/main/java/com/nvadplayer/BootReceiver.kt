package com.nvadplayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.AlarmManager
import android.app.PendingIntent
import android.os.Build
import android.os.SystemClock
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d("BootReceiver", "Boot event received: $action")

        // PACKAGE_ADDED/PACKAGE_REPLACED broadcasts are sent for every app.
        // Only react when this application's own package was installed/updated.
        if ((action == Intent.ACTION_PACKAGE_ADDED || action == "android.intent.action.PACKAGE_REPLACED") &&
            intent.data?.schemeSpecificPart != context.packageName) {
            return
        }
        
        if (action == Intent.ACTION_BOOT_COMPLETED || 
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON" ||
            action == "android.intent.action.REBOOT" ||
            action == "android.intent.action.USER_PRESENT" ||
            action == "android.intent.action.LOCKED_BOOT_COMPLETED" ||
            action == "android.intent.action.MY_PACKAGE_REPLACED" ||
            action == "android.intent.action.PACKAGE_REPLACED" ||
            action == Intent.ACTION_PACKAGE_ADDED) {
            
            // Start Watchdog Service and launch app with retries
            try {
                val serviceIntent = Intent(context, KioskWatchdogService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (e: Exception) {
                Log.e("BootReceiver", "Error starting KioskWatchdogService on boot: ${e.message}")
            }
            // First attempt immediately. The alarm below is deliberately also
            // scheduled: many Android-TV launchers are not ready to accept an
            // activity start during the first seconds after BOOT_COMPLETED.
            launchApp(context)
            scheduleDelayedBootLaunch(context)
        }
    }

    private fun launchApp(context: Context) {
        try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                context.startActivity(launchIntent)
                Log.i("BootReceiver", "Immediate boot launch requested")
            } else {
                Log.e("BootReceiver", "Launch intent is null")
            }
        } catch (e: Exception) {
            Log.e("BootReceiver", "Immediate boot launch failed", e)
        }
    }

    private fun scheduleDelayedBootLaunch(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val launchIntent = Intent(context, BootLaunchReceiver::class.java).apply {
                action = BootLaunchReceiver.ACTION_BOOT_LAUNCH
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            val pendingIntent = PendingIntent.getBroadcast(context, 4101, launchIntent, flags)
            val triggerAt = SystemClock.elapsedRealtime() + 7000L
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
            } else {
                alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
            }
        } catch (e: Exception) {
            Log.e("BootReceiver", "Could not schedule delayed boot launch", e)
        }
    }
}
