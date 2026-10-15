package com.nvadplayer

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream

class KioskWatchdogService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var isWatchdogRunning = false

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            try {
                checkAndRelaunchIfNeeded()
            } catch (e: Exception) {
                Log.e(TAG, "Error in watchdog execution", e)
            } finally {
                if (isWatchdogRunning) {
                    handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "KioskWatchdogService onCreate")
        startForegroundServiceNotification()
        startWatchdogLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "KioskWatchdogService onStartCommand")
        startForegroundServiceNotification()
        if (!isWatchdogRunning) {
            startWatchdogLoop()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "KioskWatchdogService onDestroy")
        isWatchdogRunning = false
        handler.removeCallbacks(watchdogRunnable)
    }

    private fun startWatchdogLoop() {
        isWatchdogRunning = true
        handler.removeCallbacks(watchdogRunnable)
        handler.post(watchdogRunnable)
    }

    private fun checkAndRelaunchIfNeeded() {
        // Read local kioskMode setting
        val isLocked = isKioskLockedFromConfig()
        KioskModule.isKioskLocked = isLocked

        if (!isLocked) {
            // Kiosk is UNLOCKED (OFF): Disable auto-relaunch feature completely
            return
        }

        // Kiosk is LOCKED (ON): Check if MainActivity is currently resumed on screen
        if (!MainActivity.isActivityResumed) {
            Log.w(TAG, "MainActivity is NOT resumed while Kiosk Lock is ACTIVE. Auto-relaunching in 2s!")
            relaunchMainActivity()
        }
    }

    private fun isKioskLockedFromConfig(): Boolean {
        try {
            val nvsignDir = File(Environment.getExternalStorageDirectory(), "nvsign")
            val configFile = File(nvsignDir, "config.json")
            if (configFile.exists()) {
                val fis = FileInputStream(configFile)
                val data = byteInputStreamToString(fis)
                fis.close()
                val json = JSONObject(data)
                if (json.has("kioskMode")) {
                    return json.optBoolean("kioskMode", true)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read kioskMode from config.json: ${e.message}")
        }
        return KioskModule.isKioskLocked
    }

    private fun byteInputStreamToString(stream: FileInputStream): String {
        val size = stream.available()
        val buffer = ByteArray(size)
        stream.read(buffer)
        return String(buffer, Charsets.UTF_8)
    }

    private fun isAppInForeground(): Boolean {
        try {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return false

            // Strategy 1: Check running app processes
            val appProcesses = activityManager.runningAppProcesses
            if (appProcesses != null) {
                for (processInfo in appProcesses) {
                    if (processInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
                        processInfo.processName == packageName
                    ) {
                        return true
                    }
                }
            }

            // Strategy 2: Check running tasks (for older Android TV devices)
            @Suppress("DEPRECATION")
            val tasks = activityManager.getRunningTasks(1)
            if (!tasks.isNullOrEmpty()) {
                val topActivity = tasks[0].topActivity
                if (topActivity != null && topActivity.packageName == packageName) {
                    return true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking app foreground status: ${e.message}")
        }
        return false
    }

    private fun relaunchMainActivity() {
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            }
            if (launchIntent != null) {
                val pendingIntent = android.app.PendingIntent.getActivity(
                    this,
                    1004,
                    launchIntent,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                    } else {
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT
                    }
                )
                try {
                    pendingIntent.send()
                } catch (e: Exception) {
                    startActivity(launchIntent)
                }
            }
            KioskAlarmReceiver.scheduleNextAlarm(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to relaunch MainActivity: ${e.message}")
        }
    }

    private fun startForegroundServiceNotification() {
        try {
            val channelId = "kiosk_watchdog_channel"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    "Kiosk Watchdog Service",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Keeps TV Ads Player running continuously in Kiosk Mode"
                }
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.createNotificationChannel(channel)
            }

            val notification: Notification = NotificationCompat.Builder(this, channelId)
                .setContentTitle("TV Kiosk Watchdog Active")
                .setContentText("Monitoring TV Player status...")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build()

            startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground notification", e)
        }
    }

    companion object {
        private const val TAG = "KioskWatchdogService"
        private const val WATCHDOG_INTERVAL_MS = 2000L
        private const val NOTIFICATION_ID = 1001
    }
}
