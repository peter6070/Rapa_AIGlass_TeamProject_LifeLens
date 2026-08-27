package com.example.kimchi_r1.vision

import android.content.Context
import org.json.JSONObject

/** Persistent switches for independently enabling gesture command groups. */
class GestureControlSettings(context: Context) {
  private val preferences =
      context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

  fun isEnabled(key: String): Boolean =
      when (key) {
        PINCH -> preferences.getBoolean(PINCH, false)
        POWER -> preferences.getBoolean(POWER, true)
        PRESENTATION -> preferences.getBoolean(PRESENTATION, true)
        else -> false
      }

  fun setEnabled(key: String, enabled: Boolean): Boolean {
    if (key !in SUPPORTED_KEYS) return false
    preferences.edit().putBoolean(key, enabled).apply()
    return true
  }

  fun toJson(): String =
      JSONObject()
          .put(PINCH, isEnabled(PINCH))
          .put(POWER, isEnabled(POWER))
          .put(PRESENTATION, isEnabled(PRESENTATION))
          .toString()

  companion object {
    const val PINCH = "pinch"
    const val POWER = "power"
    const val PRESENTATION = "presentation"
    private const val PREFERENCES_NAME = "gesture_control_settings"
    private val SUPPORTED_KEYS = setOf(PINCH, POWER, PRESENTATION)
  }
}
