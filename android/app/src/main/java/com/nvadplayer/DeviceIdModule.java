package com.nvadplayer;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.provider.Settings;
import android.content.Context;

import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;

public class DeviceIdModule extends ReactContextBaseJavaModule {
    private final ReactApplicationContext reactContext;

    DeviceIdModule(ReactApplicationContext context) {
        super(context);
        this.reactContext = context;
    }

    @Override
    public String getName() {
        return "DeviceIdModule";
    }

    @ReactMethod(isBlockingSynchronousMethod = true)
    public String getDeviceId() {
        Context context = reactContext.getApplicationContext();
        return Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.ANDROID_ID
        );
    }

    @ReactMethod
    public void applyOrientation(String orientation) {
        final Activity activity = getCurrentActivity();
        if (activity == null) return;

        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    int requestedOrientation;
                    if ("vertical".equals(orientation)) {
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT;
                    } else if ("reverse-vertical".equals(orientation)) {
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
                    } else if ("reverse-horizontal".equals(orientation)) {
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
                    } else {
                        // Match the TV's actual display orientation. This avoids
                        // letterboxing on portrait panels or ADB-rotated displays.
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR;
                    }
                    activity.setRequestedOrientation(requestedOrientation);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }
}
