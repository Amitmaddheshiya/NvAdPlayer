package com.nvadplayer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import com.facebook.react.ReactActivity
import com.facebook.react.ReactActivityDelegate
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint.fabricEnabled
import com.facebook.react.defaults.DefaultReactActivityDelegate

class MainActivity : ReactActivity() {

  private var wakeLock: PowerManager.WakeLock? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    
    // 1. AGGRESSIVE Fullscreen - Force window to cover entire display
    window.addFlags(
      WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
      WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
      WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
      WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
      WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON or
      WindowManager.LayoutParams.FLAG_FULLSCREEN or
      WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
      WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
    )

    // 2. Force window to be fullscreen and ignore system decorations
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    // 3. Disable fitsSystemWindows to prevent padding from system bars
    val decorView = window.decorView
    decorView.fitsSystemWindows = false

    // 4. Acquire persistent PowerManager WakeLock to prevent TV screensaver / sleep
    try {
      val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
      if (powerManager != null) {
        @Suppress("DEPRECATION")
        wakeLock = powerManager.newWakeLock(
          PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE or PowerManager.ACQUIRE_CAUSES_WAKEUP,
          "NvAdPlayer:TvAlwaysOnWakeLock"
        )
        wakeLock?.acquire(10*60*1000L) // 10 minutes timeout, will be renewed
      }
    } catch (e: Exception) {
      e.printStackTrace()
    }

    // 5. Set aggressive sticky immersive full screen
    hideSystemUI()

    // 6. Start 2-Second Kiosk Watchdog Foreground Service
    startWatchdogService()
  }

  override fun onResume() {
    super.onResume()
    isActivityResumed = true
    
    // Re-apply aggressive fullscreen flags on resume
    window.addFlags(
      WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
      WindowManager.LayoutParams.FLAG_FULLSCREEN or
      WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
      WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
    )
    
    if (wakeLock?.isHeld == false) {
      try { wakeLock?.acquire(10*60*1000L) } catch (_: Exception) {}
    }
    
    // Force fitsSystemWindows to false
    window.decorView.fitsSystemWindows = false
    
    hideSystemUI()
    startWatchdogService()
  }

  override fun onPause() {
    super.onPause()
    isActivityResumed = false
    if (KioskModule.isKioskLocked) {
      relaunchSelfImmediately()
    }
  }

  private fun startWatchdogService() {
    try {
      val watchdogIntent = Intent(this, KioskWatchdogService::class.java)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        startForegroundService(watchdogIntent)
      } else {
        startService(watchdogIntent)
      }
    } catch (e: Exception) {
      e.printStackTrace()
    }
  }

  override fun onStop() {
    super.onStop()
    isActivityResumed = false
    if (KioskModule.isKioskLocked) {
      relaunchSelfImmediately()
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    isActivityResumed = false
    try {
      if (wakeLock?.isHeld == true) {
        wakeLock?.release()
      }
    } catch (_: Exception) {}
    if (KioskModule.isKioskLocked) {
      relaunchSelfImmediately()
    }
  }

  private fun relaunchSelfImmediately() {
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
          1005,
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
    } catch (e: Exception) {
      e.printStackTrace()
    }
  }

  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    if (hasFocus) {
      // Re-apply all fullscreen flags when window gains focus
      window.addFlags(
        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
        WindowManager.LayoutParams.FLAG_FULLSCREEN or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
      )
      window.decorView.fitsSystemWindows = false
      hideSystemUI()
    }
  }

  private fun hideSystemUI() {
    try {
      val decorView = window.decorView
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        // Android 11+: legacy systemUiVisibility flags are no longer reliable.
        // Hide both bars through the current insets API and allow only a
        // transient swipe reveal, after which focus/resume hides them again.
        window.setDecorFitsSystemWindows(false)
        window.insetsController?.apply {
          hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
          systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
      } else {
        @Suppress("DEPRECATION")
        run {
          decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LOW_PROFILE
          )
        }
      }
    } catch (e: Exception) {
      e.printStackTrace()
    }
  }

  override fun onUserLeaveHint() {
    super.onUserLeaveHint()
    if (KioskModule.isKioskLocked) {
      try {
        val intent = Intent(this, MainActivity::class.java).apply {
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
      } catch (e: Exception) {
        e.printStackTrace()
      }
    }
  }

  // Prevent user from pressing Back / Home / Menu / Settings / TV / AppSwitch keys when Kiosk Mode is locked
  override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
    if (KioskModule.isKioskLocked) {
      when (keyCode) {
        KeyEvent.KEYCODE_BACK -> {
          event?.startTracking()
          return true
        }
        KeyEvent.KEYCODE_HOME,
        KeyEvent.KEYCODE_MENU,
        KeyEvent.KEYCODE_APP_SWITCH,
        KeyEvent.KEYCODE_SETTINGS,
        KeyEvent.KEYCODE_SEARCH,
        KeyEvent.KEYCODE_TV,
        KeyEvent.KEYCODE_GUIDE -> {
          return true // Block key press completely
        }
      }
    }
    return super.onKeyDown(keyCode, event)
  }

  override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
    if (keyCode == KeyEvent.KEYCODE_BACK && KioskModule.isKioskLocked) {
      try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
          try {
            stopLockTask()
          } catch (e: Exception) {
            e.printStackTrace()
          }
        }
        KioskModule.isKioskLocked = false

        val nvsignDir = java.io.File(Environment.getExternalStorageDirectory(), "nvsign")
        val configFile = java.io.File(nvsignDir, "config.json")
        if (configFile.exists()) {
          val fis = java.io.FileInputStream(configFile)
          val size = fis.available()
          val buffer = ByteArray(size)
          fis.read(buffer)
          fis.close()
          
          val jsonStr = String(buffer, Charsets.UTF_8)
          val json = org.json.JSONObject(jsonStr)
          json.put("kioskMode", false)
          
          val fos = java.io.FileOutputStream(configFile)
          fos.write(json.toString(2).toByteArray(Charsets.UTF_8))
          fos.close()
        }
        
        android.widget.Toast.makeText(this, "Kiosk Mode Unlocked", android.widget.Toast.LENGTH_LONG).show()
      } catch (e: Exception) {
        e.printStackTrace()
      }
      return true
    }
    return super.onKeyLongPress(keyCode, event)
  }

  @Suppress("DEPRECATION")
  override fun onBackPressed() {
    if (KioskModule.isKioskLocked) {
      // Block back button press when kiosk mode is enabled
      return
    }
    super.onBackPressed()
  }

  /**
   * Returns the name of the main component registered from JavaScript. This is used to schedule
   * rendering of the component.
   */
  override fun getMainComponentName(): String = "NvAdPlayer"

  /**
   * Returns the instance of the [ReactActivityDelegate]. We use [DefaultReactActivityDelegate]
   * which allows you to enable New Architecture with a single boolean flags [fabricEnabled]
   */
  override fun createReactActivityDelegate(): ReactActivityDelegate =
      DefaultReactActivityDelegate(this, mainComponentName, fabricEnabled)

  companion object {
    @JvmStatic
    @Volatile
    var isActivityResumed: Boolean = false
  }
}

