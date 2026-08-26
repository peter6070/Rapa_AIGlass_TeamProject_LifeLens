package com.example.kimchi_r1.server

import android.content.Context
import com.example.kimchi_r1.BuildConfig

object ServerSettings {
  const val SHARED_LIFELOG_ID = "lifelens-shared"
  private const val PREFS = "backend_server_settings"
  private const val URL_KEY = "server_url"

  fun url(context: Context): String {
    val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(URL_KEY, BuildConfig.DEFAULT_RELAY_SERVER_URL).orEmpty()
    return normalize(saved).takeIf(::isValid) ?: ""
  }

  fun displayUrl(context: Context): String = url(context)
      .removePrefix("http://")
      .removePrefix("https://")

  fun saveUrl(context: Context, value: String) {
    normalize(value).takeIf(::isValid)?.let { normalized ->
      context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(URL_KEY, normalized).apply()
    }
  }

  fun normalize(value: String): String {
    val raw = value.trim().trimEnd('/')
    if (raw.isBlank()) return raw
    return if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "http://$raw"
  }

  fun isValid(value: String): Boolean = runCatching {
    val uri = android.net.Uri.parse(normalize(value))
    uri.host != null && uri.port in 1..65535 && uri.scheme in setOf("http", "https")
  }.getOrDefault(false)
}
