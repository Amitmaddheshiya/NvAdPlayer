package com.nvadplayer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream

class KioskAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        try {
            Log.d(TAG, "KioskAlarmReceiver alarm triggered!")
            val isLocked = isKioskLockedFromConfig()
            KioskModule.isKioskLocked = isLocked

            if (!isLocked) {
                // Kiosk UNLOCKED (OFF): Do not auto-relaunch
                return
            }

            if (!MainActivity.isActivityResumed) {
                Log.w(TAG, "MainActivity is NOT resumed via AlarmReceiver! Auto-relaunching in 2s!")
                val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                    )
                }

                if (launchIntent != null) {
                    val pendingIntent = PendingIntent.getActivity(
                        context,
                        1002,
                        launchIntent,
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        } else {
                            PendingIntent.FLAG_UPDATE_CURRENT
                        }
                    )
                    try {
                        pendingIntent.send()
                    } catch (e: Exception) {
                        context.startActivity(launchIntent)
                    }
                }
            }

            // Re-schedule alarm for 2 seconds later
            scheduleNextAlarm(context)
        } catch (e: Exception) {
            Log.e(TAG, "Error in KioskAlarmReceiver", e)
        }
    }

    private fun isKioskLockedFromConfig(): Boolean {
        try {
            val nvsignDir = File(Environment.getExternalStorageDirectory(), "nvsign")
            val configFile = File(nvsignDir, "config.json")
            if (configFile.exists()) {
                val fis = FileInputStream(configFile)
                val size = fis.available()
                val buffer = ByteArray(size)
                fis.read(buffer)
                fis.close()
                val json = JSONObject(String(buffer, Charsets.UTF_8))
                if (json.has("kioskMode")) {
                    return json.optBoolean("kioskMode", true)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read config.json in AlarmReceiver: ${e.message}")
        }
        return KioskModule.isKioskLocked
    }

    companion object {
        private const val TAG = "KioskAlarmReceiver"
        private const val ALARM_INTERVAL_MS = 2000L

        fun scheduleNextAlarm(context: Context) {
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                    ?: return

                val alarmIntent = Intent(context, KioskAlarmReceiver::class.java)
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    1003,
                    alarmIntent,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    } else {
                        PendingIntent.FLAG_UPDATE_CURRENT
                    }
                )

                val triggerAt = SystemClock.elapsedRealtime() + ALARM_INTERVAL_MS

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
                } else {
                    alarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule next alarm: ${e.message}")
            }
        }
    }
}
