package com.example.kimchi_r1.lifelog

import android.content.Context
import android.util.Log
import com.example.kimchi_r1.server.ServerSettings
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import android.graphics.Bitmap
import android.util.Base64
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Sends each finished STT utterance to the common LifeLens timeline. */
object LifeLogSyncer {
  fun syncRecord(context: Context, record: SpeechRecord) {
    val server = ServerSettings.url(context).trimEnd('/')
    if (server.isBlank()) return
    runCatching {
      val instant = Instant.ofEpochMilli(record.createdAtMillis)
      val payload = JSONObject().apply {
        put("device_id", ServerSettings.SHARED_LIFELOG_ID)
        put("date", instant.atZone(ZoneId.systemDefault()).toLocalDate().toString())
        put("records", JSONArray().put(JSONObject().apply {
          put("client_record_id", record.clientRecordId)
          put("text", record.text)
          put("spoken_at", instant.toString())
        }))
      }
      val connection = (URL("$server/v1/lifelogs/sync").openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 8_000
        readTimeout = 8_000
        doOutput = true
        setRequestProperty("Content-Type", "application/json")
      }
      connection.outputStream.bufferedWriter().use { it.write(payload.toString()) }
      connection.inputStream.use { it.readBytes() }
      connection.disconnect()
    }.onFailure { Log.w("LifeLogSync", "STT sync failed", it) }
  }

  /** Saves the capture locally for immediate LifeLog display, then uploads it to the server. */
  fun syncPhoto(
      context: Context,
      photo: PhotoRecord,
      bitmap: Bitmap,
      onLocalSaved: () -> Unit = {},
      resolveLocationName: () -> String? = { photo.locationName },
  ): Boolean {
    val image = compressedPhoto(bitmap)
    if (persistLocalPhoto(context, photo, image)) onLocalSaved()
    // Reverse geocoding and network upload may take seconds. They intentionally happen only after
    // the local row exists and the WebView has been notified about the new capture.
    val syncedPhoto =
        if (!photo.locationName.isNullOrBlank()) photo
        else photo.copy(locationName = resolveLocationName())
    val server = ServerSettings.url(context).trimEnd('/')
    if (server.isBlank()) {
      queuePhoto(context, syncedPhoto, image)
      return false
    }
    retryPendingPhotos(context)
    if (uploadPhoto(server, syncedPhoto, image)) return true
    queuePhoto(context, syncedPhoto, image)
    return false
  }

  /** Keeps a durable app-private JPEG and registers it in the local LifeLog database. */
  private fun persistLocalPhoto(context: Context, photo: PhotoRecord, image: ByteArray): Boolean =
      runCatching {
      val directory = File(context.filesDir, "lifelog-photos").apply { mkdirs() }
      val imageFile = File(directory, "${photo.clientPhotoId}.jpg")
      imageFile.writeBytes(image)
      val uri =
          FileProvider.getUriForFile(
              context,
              "${context.packageName}.fileprovider",
              imageFile,
          )
      LifeLogRepository(context).use { repository ->
        repository.upsertPhoto(photo.copy(uri = uri.toString()))
      }
      true
    }.onFailure { Log.e("LifeLogSync", "Could not save photo to local LifeLog", it) }
        .getOrDefault(false)

  /** Retries failed uploads from app-private cache. Successful files are deleted immediately. */
  fun retryPendingPhotos(context: Context): Int {
    val server = ServerSettings.url(context).trimEnd('/')
    if (server.isBlank()) return 0
    var uploaded = 0
    pendingDirectory(context).listFiles { file -> file.extension == "json" }?.forEach { metadataFile ->
      runCatching {
        val metadata = JSONObject(metadataFile.readText())
        val clientPhotoId = metadata.getString("client_photo_id")
        val imageFile = File(metadataFile.parentFile, "$clientPhotoId.jpg")
        if (!imageFile.isFile) {
          metadataFile.delete()
          return@runCatching
        }
        val photo = PhotoRecord(
            id = 0,
            clientPhotoId = clientPhotoId,
            uri = "",
            createdAtMillis = metadata.getLong("taken_at_millis"),
            latitude = if (metadata.has("latitude")) metadata.getDouble("latitude") else null,
            longitude = if (metadata.has("longitude")) metadata.getDouble("longitude") else null,
            locationName = metadata.optString("location_name").trim().ifBlank { null },
        )
        if (uploadPhoto(server, photo, imageFile.readBytes())) {
          imageFile.delete()
          metadataFile.delete()
          uploaded++
        }
      }.onFailure { Log.w("LifeLogSync", "Pending photo retry failed", it) }
    }
    return uploaded
  }

  /** Imports captures queued by older app versions into the local LifeLog before it is displayed. */
  fun restorePendingPhotos(context: Context): Int {
    var restored = 0
    pendingDirectory(context).listFiles { file -> file.extension == "json" }?.forEach { metadataFile ->
      runCatching {
        val metadata = JSONObject(metadataFile.readText())
        val clientPhotoId = metadata.getString("client_photo_id")
        val imageFile = File(metadataFile.parentFile, "$clientPhotoId.jpg")
        if (!imageFile.isFile) return@runCatching
        val photo =
            PhotoRecord(
                id = 0,
                clientPhotoId = clientPhotoId,
                uri = "",
                createdAtMillis = metadata.getLong("taken_at_millis"),
                latitude = if (metadata.has("latitude")) metadata.getDouble("latitude") else null,
                longitude = if (metadata.has("longitude")) metadata.getDouble("longitude") else null,
                locationName = metadata.optString("location_name").trim().ifBlank { null },
            )
        persistLocalPhoto(context, photo, imageFile.readBytes())
        restored++
      }.onFailure { Log.w("LifeLogSync", "Pending photo restore failed", it) }
    }
    return restored
  }

  private fun uploadPhoto(server: String, photo: PhotoRecord, image: ByteArray): Boolean {
    repeat(2) { attempt ->
      val sent = runCatching {
        val payload = JSONObject().apply {
          put("device_id", ServerSettings.SHARED_LIFELOG_ID)
          put("client_photo_id", photo.clientPhotoId)
          put("taken_at", Instant.ofEpochMilli(photo.createdAtMillis).toString())
          if (photo.latitude != null) put("latitude", photo.latitude)
          if (photo.longitude != null) put("longitude", photo.longitude)
          if (!photo.locationName.isNullOrBlank()) put("location_name", photo.locationName)
          put("image_base64", Base64.encodeToString(image, Base64.NO_WRAP))
        }
        val connection = (URL("$server/v1/lifelogs/photos/sync").openConnection() as HttpURLConnection).apply {
          requestMethod = "POST"; connectTimeout = 12_000; readTimeout = 20_000; doOutput = true
          setRequestProperty("Content-Type", "application/json")
        }
        try {
          connection.outputStream.bufferedWriter().use { it.write(payload.toString()) }
          connection.inputStream.use { it.readBytes() }
          true
        } finally {
          connection.disconnect()
        }
      }.getOrElse { error ->
        Log.w("LifeLogSync", "Photo upload failed (attempt ${attempt + 1})", error)
        false
      }
      if (sent) return true
      if (attempt == 0) Thread.sleep(600)
    }
    return false
  }

  private fun queuePhoto(context: Context, photo: PhotoRecord, image: ByteArray) {
    runCatching {
      val directory = pendingDirectory(context)
      File(directory, "${photo.clientPhotoId}.jpg").writeBytes(image)
      File(directory, "${photo.clientPhotoId}.json").writeText(JSONObject().apply {
        put("client_photo_id", photo.clientPhotoId)
        put("taken_at_millis", photo.createdAtMillis)
        if (photo.latitude != null) put("latitude", photo.latitude)
        if (photo.longitude != null) put("longitude", photo.longitude)
        if (!photo.locationName.isNullOrBlank()) put("location_name", photo.locationName)
      }.toString())
    }.onFailure { Log.e("LifeLogSync", "Could not queue failed photo upload", it) }
  }

  private fun pendingDirectory(context: Context): File =
      File(context.cacheDir, "lifelog-photo-upload-queue").apply { mkdirs() }

  private fun compressedPhoto(bitmap: Bitmap): ByteArray {
    val longestSide = maxOf(bitmap.width, bitmap.height)
    val scaled = if (longestSide > 1600) {
      val scale = 1600f / longestSide
      Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
    } else bitmap
    val output = java.io.ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, 88, output)
    if (scaled !== bitmap) scaled.recycle()
    return output.toByteArray()
  }
}
