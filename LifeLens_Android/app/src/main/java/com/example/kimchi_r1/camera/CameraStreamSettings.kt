package com.example.kimchi_r1.camera

import android.content.Context

/** Persists the user's preferred camera transport independently of decoder fallback state. */
class CameraStreamSettings(context: Context) {
  private val preferences =
      context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

  fun isRawCompatibilityMode(): Boolean = preferences.getBoolean(RAW_COMPATIBILITY_MODE, false)

  fun setRawCompatibilityMode(enabled: Boolean) {
    preferences.edit().putBoolean(RAW_COMPATIBILITY_MODE, enabled).apply()
  }

  companion object {
    private const val PREFERENCES_NAME = "camera_stream_settings"
    private const val RAW_COMPATIBILITY_MODE = "raw_compatibility_mode"
  }
}
