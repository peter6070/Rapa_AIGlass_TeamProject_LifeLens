package com.example.kimchi_r1.camera

import android.content.Context

/** Compatibility bridge for web bundles created before the stream was fixed to kimchi raw I420. */
class CameraStreamSettings(context: Context) {
  fun isRawCompatibilityMode(): Boolean = true

  fun setRawCompatibilityMode(enabled: Boolean) = Unit
}
