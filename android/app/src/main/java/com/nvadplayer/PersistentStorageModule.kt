package com.nvadplayer

import android.content.SharedPreferences
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod

class PersistentStorageModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {

    override fun getName(): String = "PersistentStorageModule"

    private val prefs: SharedPreferences
        get() = reactApplicationContext.getSharedPreferences("nvadplayer_persistent", android.content.Context.MODE_PRIVATE)

    @ReactMethod
    fun setItem(key: String, value: String, promise: Promise) {
        try {
            android.util.Log.d("PersistentStorage", "Setting item: $key = $value")
            prefs.edit().putString(key, value).apply()
            android.util.Log.d("PersistentStorage", "Item saved successfully")
            promise.resolve(true)
        } catch (e: Exception) {
            android.util.Log.e("PersistentStorage", "Error setting item: ${e.message}", e)
            promise.reject("ERROR", e.message)
        }
    }

    @ReactMethod
    fun getItem(key: String, promise: Promise) {
        try {
            val value = prefs.getString(key, null)
            android.util.Log.d("PersistentStorage", "Getting item: $key = $value")
            promise.resolve(value)
        } catch (e: Exception) {
            android.util.Log.e("PersistentStorage", "Error getting item: ${e.message}", e)
            promise.reject("ERROR", e.message)
        }
    }

    @ReactMethod
    fun removeItem(key: String, promise: Promise) {
        try {
            prefs.edit().remove(key).apply()
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("ERROR", e.message)
        }
    }

    @ReactMethod
    fun clearAll(promise: Promise) {
        try {
            prefs.edit().clear().apply()
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("ERROR", e.message)
        }
    }
}
