package com.nvadplayer;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.NonNull;

import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.module.annotations.ReactModule;

@ReactModule(name = "KioskModule")
public class KioskModule extends ReactContextBaseJavaModule {
    private static final String TAG = "KioskModule";
    public static volatile boolean isKioskLocked = false; // Disabled until app is activated

    public KioskModule(ReactApplicationContext reactContext) {
        super(reactContext);
    }

    @NonNull
    @Override
    public String getName() {
        return "KioskModule";
    }

    @ReactMethod
    public void enableKioskMode() {
        try {
            Activity activity = getCurrentActivity();
            if (activity != null) {
                activity.runOnUiThread(() -> {
                    try {
                        activity.getWindow().addFlags(
                            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
                            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                            WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON
                        );

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            try {
                                activity.startLockTask();
                            } catch (Exception e) {
                                Log.w(TAG, "startLockTask failed: " + e.getMessage());
                            }
                            activity.setTaskDescription(new android.app.ActivityManager.TaskDescription("NvAdPlayer"));
                        }

                        View decorView = activity.getWindow().getDecorView();
                        if (decorView != null) {
                            decorView.setSystemUiVisibility(
                                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                                View.SYSTEM_UI_FLAG_FULLSCREEN |
                                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            );
                        }

                        isKioskLocked = true;
                        try {
                            Intent serviceIntent = new Intent(activity, KioskWatchdogService.class);
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                activity.startForegroundService(serviceIntent);
                            } else {
                                activity.startService(serviceIntent);
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error starting KioskWatchdogService", e);
                        }
                        Log.d(TAG, "Kiosk mode enabled");
                    } catch (Exception e) {
                        Log.e(TAG, "Error enabling kiosk mode", e);
                    }
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in enableKioskMode", e);
        }
    }

    @ReactMethod
    public void disableKioskMode() {
        try {
            Activity activity = getCurrentActivity();
            if (activity != null) {
                activity.runOnUiThread(() -> {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            try {
                                activity.stopLockTask();
                            } catch (Exception e) {
                                Log.w(TAG, "stopLockTask failed: " + e.getMessage());
                            }
                        }

                        isKioskLocked = false;
                        Log.d(TAG, "Kiosk mode disabled");
                    } catch (Exception e) {
                        Log.e(TAG, "Error disabling kiosk mode", e);
                    }
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in disableKioskMode", e);
        }
    }

    @ReactMethod
    public void setAsHomeLauncher() {
        try {
            Activity activity = getCurrentActivity();
            if (activity != null) {
                activity.runOnUiThread(() -> {
                    try {
                        // This requires the app to be set as default launcher via system settings
                        // The user needs to manually set it as home launcher
                        // We can show a dialog to guide them
                        Log.d(TAG, "To set as home launcher, user must manually select this app as default launcher in system settings");
                    } catch (Exception e) {
                        Log.e(TAG, "Error setting as home launcher", e);
                    }
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in setAsHomeLauncher", e);
        }
    }
}
