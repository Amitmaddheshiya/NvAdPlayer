package com.nvadplayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Executes after Android has settled its boot UI. */
class BootLaunchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            val launchIntent = context.packageManager
                .getLaunchIntentForPackage(context.packageName)
                ?.apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                }
            if (launchIntent != null) {
                context.startActivity(launchIntent)
                Log.i(TAG, "Delayed boot launch requested")
            }
        } catch (error: Exception) {
            Log.e(TAG, "Delayed boot launch failed", error)
        }
    }

    companion object {
        const val ACTION_BOOT_LAUNCH = "com.nvadplayer.action.BOOT_LAUNCH"
        private const val TAG = "BootLaunchReceiver"
    }
}
