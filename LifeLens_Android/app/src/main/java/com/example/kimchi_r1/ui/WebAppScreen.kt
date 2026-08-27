package com.example.kimchi_r1.ui

import android.annotation.SuppressLint
import android.provider.Settings
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.kimchi_r1.BuildConfig
import com.example.kimchi_r1.server.ServerSettings
import com.example.kimchi_r1.lifelog.LifeLogRepository
import com.example.kimchi_r1.lifelog.LifeLogSyncer
import com.example.kimchi_r1.lifelog.SpeechRecord
import com.example.kimchi_r1.speech.GestureSpeechFeedback
import com.example.kimchi_r1.vision.GestureControlSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Keeps Android back gestures inside the single-page web app. */
object WebAppBackNavigation {
  @Volatile private var currentWebView: WebView? = null
  fun attach(webView: WebView) { currentWebView = webView }
  fun navigateBack() {
    val webView = currentWebView ?: return
    webView.post {
      webView.evaluateJavascript("window.handleNativeBack && window.handleNativeBack()", null)
    }
  }
  fun refreshWebApp() {
    val webView = currentWebView ?: return
    webView.post {
      webView.clearCache(true)
      webView.clearHistory()
      webView.reload()
    }
  }
  fun dispatchIotGesture(action: String) {
    val webView = currentWebView ?: return
    webView.post {
      webView.evaluateJavascript("window.handleNativeIotGesture && window.handleNativeIotGesture('${action}')", null)
    }
  }
}

private class NativeBridge(
    private val context: android.content.Context,
    private val onToggleMicrophone: () -> Unit,
    private val onToggleSession: () -> Unit,
    private val onStartStream: () -> Unit,
    private val onOpenLiveVision: () -> Unit,
    private val visionState: () -> String,
    private val previewFrame: () -> android.graphics.Bitmap?,
) {
  private val lifeLogRepository = LifeLogRepository(context.applicationContext)
  private val gestureSpeechFeedback = GestureSpeechFeedback(context.applicationContext)
  private val gestureControlSettings = GestureControlSettings(context.applicationContext)
  @JavascriptInterface fun getServerUrl(): String = ServerSettings.url(context)
  @JavascriptInterface fun getSharedLifeLogId(): String = ServerSettings.SHARED_LIFELOG_ID
  @JavascriptInterface fun saveServerUrl(value: String) = ServerSettings.saveUrl(context, value)
  @JavascriptInterface fun refreshWebApp() = WebAppBackNavigation.refreshWebApp()
  @JavascriptInterface fun speakGestureFeedback(text: String) = gestureSpeechFeedback.speak(text)
  @JavascriptInterface fun cancelGestureFeedback() = gestureSpeechFeedback.cancel()
  @JavascriptInterface fun getGestureSettings(): String = gestureControlSettings.toJson()
  @JavascriptInterface fun setGestureEnabled(key: String, enabled: Boolean): Boolean =
      gestureControlSettings.setEnabled(key, enabled)
  @JavascriptInterface fun openPhotoViewer(photoUrl: String, localUri: String): Boolean = runCatching {
    android.os.Handler(android.os.Looper.getMainLooper()).post {
      context.startActivity(
          android.content.Intent(context, PhotoViewerActivity::class.java).apply {
            putExtra(PhotoViewerActivity.EXTRA_PHOTO_URL, photoUrl)
            putExtra(PhotoViewerActivity.EXTRA_LOCAL_URI, localUri)
          },
      )
    }
    true
  }.getOrDefault(false)
  @JavascriptInterface fun shareText(title: String, text: String): Boolean = runCatching {
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
      type = "text/plain"
      putExtra(android.content.Intent.EXTRA_TITLE, title)
      putExtra(android.content.Intent.EXTRA_TEXT, text)
      addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(android.content.Intent.createChooser(intent, "리포트 공유").apply {
      addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    })
    true
  }.getOrDefault(false)
  @JavascriptInterface fun toggleMicrophone() {
    android.os.Handler(android.os.Looper.getMainLooper()).post(onToggleMicrophone)
  }
  @JavascriptInterface fun toggleSession() {
    android.os.Handler(android.os.Looper.getMainLooper()).post(onToggleSession)
  }
  @JavascriptInterface fun startStream() {
    android.os.Handler(android.os.Looper.getMainLooper()).post(onStartStream)
  }
  @JavascriptInterface fun openLiveVision() {
    android.os.Handler(android.os.Looper.getMainLooper()).post(onOpenLiveVision)
  }
  @JavascriptInterface fun getVisionState(): String = visionState()
  @JavascriptInterface fun getPreviewFrame(): String = runCatching {
    previewFrame()?.let { bitmap ->
      val output = java.io.ByteArrayOutputStream()
      bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 65, output)
      android.util.Base64.encodeToString(output.toByteArray(), android.util.Base64.NO_WRAP)
    }.orEmpty()
  }.getOrDefault("")
  @JavascriptInterface fun getLifeLogRecords(day: String): String {
    return runCatching {
      val records = lifeLogRepository.recordsOn(java.time.LocalDate.parse(day))
      org.json.JSONArray().apply {
        records.forEach { record ->
          put(org.json.JSONObject().apply {
            put("client_record_id", record.clientRecordId)
            put("text", record.text)
            put("spoken_at", java.time.Instant.ofEpochMilli(record.createdAtMillis).toString())
          })
        }
      }.toString()
    }.getOrDefault("[]")
  }
  @JavascriptInterface fun getLifeLogPhotos(day: String): String = runCatching {
    LifeLogSyncer.restorePendingPhotos(context)
    val photos = lifeLogRepository.photosOn(java.time.LocalDate.parse(day))
    org.json.JSONArray().apply {
      photos.forEach { photo ->
        put(org.json.JSONObject().apply {
          put("client_photo_id", photo.clientPhotoId)
          put("uri", photo.uri)
          put("taken_at", java.time.Instant.ofEpochMilli(photo.createdAtMillis).toString())
          if (photo.latitude != null) put("latitude", photo.latitude)
          if (photo.longitude != null) put("longitude", photo.longitude)
          if (!photo.locationName.isNullOrBlank()) put("location_name", photo.locationName)
          put("thumbnail", imageBase64(photo.uri, 360, 72))
        })
      }
    }.toString()
  }.getOrDefault("[]")
  @JavascriptInterface fun getLifeLogPhoto(uri: String): String = imageBase64(uri, 1024, 78)

  private fun imageBase64(uriValue: String, maxSide: Int, quality: Int): String = runCatching {
    val uri = android.net.Uri.parse(uriValue)
    val resolver = context.contentResolver
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) sample *= 2
    val bitmap = resolver.openInputStream(uri)?.use {
      android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: return@runCatching ""
    val output = java.io.ByteArrayOutputStream()
    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, output)
    bitmap.recycle()
    android.util.Base64.encodeToString(output.toByteArray(), android.util.Base64.NO_WRAP)
  }.getOrDefault("")
  @JavascriptInterface fun addTestLifeLogRecord(text: String): Boolean = runCatching {
    lifeLogRepository.insert(text.trim()).also { LifeLogSyncer.syncRecord(context, it) }
    true
  }.getOrDefault(false)
  fun close() = gestureSpeechFeedback.close()
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebAppScreen(
    page: String,
    gestureName: String,
    gestureConfidence: Int,
    isGestureActive: Boolean,
    isSessionEnabled: Boolean,
    isMicrophoneOn: Boolean,
    latestTranscript: String,
    transcripts: List<SpeechRecord>,
    photoRevision: Long = 0,
    previewFrame: android.graphics.Bitmap? = null,
    onToggleMicrophone: () -> Unit = {},
    onToggleSession: () -> Unit = {},
    onStartStream: () -> Unit = {},
    onOpenLiveVision: () -> Unit = {},
) {
  val context = LocalContext.current
  val currentVisionState = rememberUpdatedState(
      org.json.JSONObject().apply {
        put("gestureName", gestureName)
        put("gestureConfidence", gestureConfidence)
        put("isGestureActive", isGestureActive)
        put("isSessionEnabled", isSessionEnabled)
        put("isMicrophoneOn", isMicrophoneOn)
        put("latestTranscript", latestTranscript)
        put("photoRevision", photoRevision)
        put("transcripts", org.json.JSONArray().apply {
          transcripts.takeLast(50).forEach { record ->
            put(org.json.JSONObject().apply {
              put("id", record.clientRecordId)
              put("text", record.text)
              put("spokenAt", record.createdAtMillis)
            })
          }
        })
      }.toString(),
  )
  val currentPreviewFrame = rememberUpdatedState(previewFrame)
  val currentToggleMicrophone = rememberUpdatedState(onToggleMicrophone)
  val currentToggleSession = rememberUpdatedState(onToggleSession)
  val currentStartStream = rememberUpdatedState(onStartStream)
  val currentOpenLiveVision = rememberUpdatedState(onOpenLiveVision)
  val bridge = remember(context) {
    NativeBridge(
        context,
        { currentToggleMicrophone.value.invoke() },
        { currentToggleSession.value.invoke() },
        { currentStartStream.value.invoke() },
        { currentOpenLiveVision.value.invoke() },
        { currentVisionState.value },
        { currentPreviewFrame.value },
    )
  }
  DisposableEffect(bridge) { onDispose { bridge.close() } }
  var retryKey by remember { mutableIntStateOf(0) }
  var serverInput by remember { mutableStateOf(ServerSettings.displayUrl(context)) }
  var serverUrl by remember { mutableStateOf(ServerSettings.url(context)) }
  var isReachable by remember { mutableStateOf<Boolean?>(null) }

  LaunchedEffect(retryKey) {
    isReachable = null
    isReachable = checkBackendServer(serverUrl)
    if (isReachable == true) {
      withContext(Dispatchers.IO) { LifeLogSyncer.retryPendingPhotos(context.applicationContext) }
    }
  }

  if (isReachable != true) {
    ServerAddressScreen(
        address = serverInput,
        checking = isReachable == null,
        onAddressChange = { serverInput = it },
        onConnect = {
          val normalized = ServerSettings.normalize(serverInput)
          if (ServerSettings.isValid(normalized)) {
            serverUrl = normalized
            ServerSettings.saveUrl(context, normalized)
            retryKey++
          }
        },
    )
    return
  }

  val url = remember(page, serverUrl, retryKey) {
    val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    "${serverUrl.trimEnd('/')}/app/?page=$page&key=${java.net.URLEncoder.encode(BuildConfig.DEFAULT_RELAY_API_KEY, "UTF-8")}&device_id=${java.net.URLEncoder.encode(deviceId, "UTF-8")}&reload=$retryKey"
  }
  AndroidView(
      modifier = Modifier.fillMaxSize().statusBarsPadding(),
      factory = {
        WebView(it).apply {
          WebAppBackNavigation.attach(this)
          settings.javaScriptEnabled = true
          settings.domStorageEnabled = true
          // LOAD_NO_CACHE made every launch rebuild the full web UI (including
          // its inline illustrations) from the network. Let WebView reuse a
          // valid response, while the reload token still gives native retries a
          // deterministic fresh URL.
          settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
          settings.offscreenPreRaster = true
          setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
          webViewClient = object : WebViewClient() {
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
              if (request?.isForMainFrame == true) isReachable = false
            }
          }
          webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
              Log.e("KimchiWeb", "${message.message()} @${message.lineNumber()}")
              return true
            }
          }
          addJavascriptInterface(bridge, "NativeBridge")
          tag = url
          loadUrl(url)
        }
      },
      // The single-page web app changes its query string with history.replaceState.
      // Reload only when the native app actually requests a different URL.
      update = { webView ->
        if (webView.tag != url) {
          webView.tag = url
          webView.loadUrl(url)
        }
      },
  )
}

@Composable
private fun ServerAddressScreen(
    address: String,
    checking: Boolean,
    onAddressChange: (String) -> Unit,
    onConnect: () -> Unit,
) {
  Column(
      modifier = Modifier.fillMaxSize().padding(28.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
  ) {
    Text("Backend Server", style = MaterialTheme.typography.headlineLarge)
    Text("웹앱을 열기 위해 백엔드 서버 주소와 포트가 필요합니다.")
    OutlinedTextField(
        value = address,
        onValueChange = onAddressChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("IP address : port") },
        placeholder = { Text("192.168.2.187:8080") },
        isError = address.isNotBlank() && !ServerSettings.isValid(address),
        singleLine = true,
    )
    Button(onClick = onConnect, modifier = Modifier.fillMaxWidth(), enabled = ServerSettings.isValid(address) && !checking) {
      Text(if (checking) "Connecting..." else "Connect")
    }
  }
}

private suspend fun checkBackendServer(address: String): Boolean = withContext(Dispatchers.IO) {
  if (address.isBlank()) return@withContext false
  runCatching {
    val connection = (URL(address.trim().trimEnd('/') + "/health").openConnection() as HttpURLConnection).apply {
      connectTimeout = 4_000
      readTimeout = 4_000
      requestMethod = "GET"
    }
    val successful = connection.responseCode in 200..299
    connection.disconnect()
    successful
  }.getOrDefault(false)
}
