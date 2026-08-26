package com.example.kimchi_r1.ui

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.kimchi_r1.server.ServerSettings
import java.net.HttpURLConnection
import java.net.URL

/** Native full-screen viewer avoids Android WebView Blob/dialog rendering failures. */
class PhotoViewerActivity : Activity() {
  companion object {
    const val EXTRA_PHOTO_URL = "photo_url"
    const val EXTRA_LOCAL_URI = "local_uri"
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val image = ImageView(this).apply {
      setBackgroundColor(Color.rgb(18, 16, 20))
      scaleType = ImageView.ScaleType.FIT_CENTER
      contentDescription = "라이프로그 사진"
    }
    val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(18, 16, 20)) }
    root.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    val close = TextView(this).apply {
      text = "×"
      textSize = 30f
      typeface = Typeface.DEFAULT_BOLD
      gravity = Gravity.CENTER
      setTextColor(Color.WHITE)
      background = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.rgb(244, 135, 123))
        setStroke(1.dp, Color.argb(120, 255, 255, 255))
      }
      elevation = 10.dp.toFloat()
      setOnClickListener { finish() }
      contentDescription = "닫기"
    }
    root.addView(close, FrameLayout.LayoutParams(48.dp, 48.dp, Gravity.TOP or Gravity.END))
    // This activity is edge-to-edge on some devices. Keep the close control below the status bar.
    ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
      val bars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
      (close.layoutParams as FrameLayout.LayoutParams).apply {
        topMargin = bars.top + 12.dp
        rightMargin = bars.right + 18.dp
        close.layoutParams = this
      }
      insets
    }
    val error = TextView(this).apply { setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER }
    root.addView(error, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    setContentView(root)
    ViewCompat.requestApplyInsets(root)
    Thread {
      val bitmap = loadBitmap()
      runOnUiThread {
        if (bitmap != null) image.setImageBitmap(bitmap) else error.text = "사진을 불러오지 못했습니다"
      }
    }.start()
  }

  private fun loadBitmap() = runCatching {
    val remote = intent.getStringExtra(EXTRA_PHOTO_URL).orEmpty()
    if (remote.isNotBlank()) {
      val absolute = if (remote.startsWith("http://") || remote.startsWith("https://")) remote else "${ServerSettings.url(this).trimEnd('/')}/${remote.trimStart('/')}"
      val connection = URL(absolute).openConnection() as HttpURLConnection
      try { connection.inputStream.use { BitmapFactory.decodeStream(it) } } finally { connection.disconnect() }
    } else {
      val uri = intent.getStringExtra(EXTRA_LOCAL_URI).orEmpty()
      if (uri.isBlank()) null else contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { BitmapFactory.decodeStream(it) }
    }
  }.getOrNull()

  private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}
