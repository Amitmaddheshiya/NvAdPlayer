package com.nvadplayer

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod

class PermissionModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {

    override fun getName(): String = "PermissionModule"

    @ReactMethod
    fun checkStoragePermission(promise: Promise) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val hasPermission = Environment.isExternalStorageManager()
                Log.d("PermissionModule", "Storage permission check (Android 11+): $hasPermission")
                promise.resolve(hasPermission)
            } else {
                val readPermission = reactApplicationContext.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                val writePermission = reactApplicationContext.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                val hasPermission = readPermission == android.content.pm.PackageManager.PERMISSION_GRANTED &&
                                writePermission == android.content.pm.PackageManager.PERMISSION_GRANTED
                Log.d("PermissionModule", "Storage permission check (Android 10-): $hasPermission")
                promise.resolve(hasPermission)
            }
        } catch (e: Exception) {
            Log.e("PermissionModule", "Error checking storage permission: ${e.message}")
            promise.reject("ERROR", e.message)
        }
    }

    @ReactMethod
    fun requestStoragePermission(promise: Promise) {
        try {
            val context = getCurrentActivity() ?: reactApplicationContext
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (!Environment.isExternalStorageManager()) {
                    try {
                        // Try direct app-specific all files access
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        intent.data = Uri.parse("package:${context.packageName}")
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        context.startActivity(intent)
                        Log.d("PermissionModule", "Opening app-specific all files access settings")
                        promise.resolve(true)
                    } catch (e: Exception) {
                        try {
                            // Fallback to general all files access settings
                            val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            context.startActivity(intent)
                            Log.d("PermissionModule", "Opening general all files access settings")
                            promise.resolve(true)
                        } catch (e2: Exception) {
                            // Ultimate fallback to app details settings
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            intent.data = Uri.parse("package:${context.packageName}")
                            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            context.startActivity(intent)
                            Log.d("PermissionModule", "Opening app details settings as fallback")
                            promise.resolve(true)
                        }
                    }
                } else {
                    Log.d("PermissionModule", "Storage permission already granted")
                    promise.resolve(true)
                }
            } else {
                // For Android 10 and below, open app details settings
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = Uri.parse("package:${context.packageName}")
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                context.startActivity(intent)
                Log.d("PermissionModule", "Opening app details settings for Android 10-")
                promise.resolve(true)
            }
        } catch (e: Exception) {
            Log.e("PermissionModule", "Error requesting storage permission: ${e.message}")
            promise.reject("ERROR", e.message)
        }
    }

    @ReactMethod
    fun checkOverlayPermission(promise: Promise) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val hasPermission = Settings.canDrawOverlays(reactApplicationContext)
                Log.d("PermissionModule", "Overlay permission check: $hasPermission")
                promise.resolve(hasPermission)
            } else {
                promise.resolve(true)
            }
        } catch (e: Exception) {
            Log.e("PermissionModule", "Error checking overlay permission: ${e.message}")
            promise.reject("ERROR", e.message)
        }
    }

    @ReactMethod
    fun requestOverlayPermission(promise: Promise) {
        try {
            val context = getCurrentActivity() ?: reactApplicationContext
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                intent.data = Uri.parse("package:${context.packageName}")
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                context.startActivity(intent)
                Log.d("PermissionModule", "Opening overlay permission settings")
                promise.resolve(true)
            } else {
                promise.resolve(true)
            }
        } catch (e: Exception) {
            Log.e("PermissionModule", "Error requesting overlay permission: ${e.message}")
            promise.reject("ERROR", e.message)
        }
    }

    @ReactMethod
    fun requestAutoStartPermission(promise: Promise) {
        try {
            val context = getCurrentActivity() ?: reactApplicationContext
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.parse("package:${context.packageName}")
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            context.startActivity(intent)
            Log.d("PermissionModule", "Opening app details settings for auto-start")
            promise.resolve(true)
        } catch (e: Exception) {
            Log.e("PermissionModule", "Error requesting auto-start permission: ${e.message}")
            promise.reject("ERROR", e.message)
        }
    }
}
