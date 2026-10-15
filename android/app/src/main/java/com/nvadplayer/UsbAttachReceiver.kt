package com.nvadplayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log

class UsbAttachReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d("UsbAttachReceiver", "USB event received: $action")
        
        if (action != null) {
            when (action) {
                "android.hardware.usb.action.USB_DEVICE_ATTACHED",
                Intent.ACTION_MEDIA_MOUNTED,
                "android.intent.action.MEDIA_MOUNTED" -> {
                    Log.d("UsbAttachReceiver", "USB attached or media mounted, launching app with retry")
                    launchAppWithRetry(context, 0)
                }
                Intent.ACTION_MEDIA_EJECT,
                Intent.ACTION_MEDIA_REMOVED,
                "android.intent.action.MEDIA_EJECT",
                "android.intent.action.MEDIA_REMOVED" -> {
                    Log.d("UsbAttachReceiver", "USB ejected or media removed, launching app to switch to internal storage")
                    launchAppWithRetry(context, 0)
                }
            }
        }
    }
    
    private fun launchAppWithRetry(context: Context, attempt: Int) {
        if (attempt > 3) {
            Log.e("UsbAttachReceiver", "Failed to launch app after 3 attempts")
            return
        }
        
        try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                context.startActivity(launchIntent)
                Log.d("UsbAttachReceiver", "App launched successfully on attempt $attempt")
            } else {
                Log.e("UsbAttachReceiver", "Launch intent is null on attempt $attempt")
                Handler(Looper.getMainLooper()).postDelayed({
                    launchAppWithRetry(context, attempt + 1)
                }, 2000)
            }
        } catch (e: Exception) {
            Log.e("UsbAttachReceiver", "Error launching app on attempt $attempt: ${e.message}")
            Handler(Looper.getMainLooper()).postDelayed({
                launchAppWithRetry(context, attempt + 1)
            }, 2000)
        }
    }
}
