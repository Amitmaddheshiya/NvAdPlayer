package com.nvadplayer;

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
        // The Activity owns orientation.  Going through it also works for a
        // CMS request, which is received on the HTTP server thread rather than
        // React Native's current-activity lifecycle.
        MainActivity.applyCmsOrientation(orientation);
    }
}
