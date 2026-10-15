package com.nvadplayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
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
            launchAppWithRetry(context, 0)
        }
    }
    
    private fun launchAppWithRetry(context: Context, attempt: Int) {
        if (attempt > 5) {
            Log.e("BootReceiver", "Failed to launch app after 5 attempts")
            return
        }
        
        try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                context.startActivity(launchIntent)
                Log.d("BootReceiver", "App launched successfully on attempt $attempt")
            } else {
                Log.e("BootReceiver", "Launch intent is null on attempt $attempt")
                // Retry with delay
                Handler(Looper.getMainLooper()).postDelayed({
                    launchAppWithRetry(context, attempt + 1)
                }, 3000)
            }
        } catch (e: Exception) {
            Log.e("BootReceiver", "Error launching app on attempt $attempt: ${e.message}")
            // Retry with delay
            Handler(Looper.getMainLooper()).postDelayed({
                launchAppWithRetry(context, attempt + 1)
            }, 3000)
        }
    }
}
