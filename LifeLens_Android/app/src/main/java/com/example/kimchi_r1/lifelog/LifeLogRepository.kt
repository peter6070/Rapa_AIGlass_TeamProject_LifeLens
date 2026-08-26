package com.example.kimchi_r1.lifelog

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class SpeechRecord(val id: Long, val clientRecordId: String, val text: String, val createdAtMillis: Long)
data class PhotoRecord(
    val id: Long,
    val clientPhotoId: String,
    val uri: String,
    val createdAtMillis: Long,
    val latitude: Double?,
    val longitude: Double?,
    val locationName: String? = null,
)

class LifeLogRepository(context: Context) : SQLiteOpenHelper(context, "kimchi_lifelog.db", null, 3) {
  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL("CREATE TABLE speech_records (id INTEGER PRIMARY KEY AUTOINCREMENT, client_record_id TEXT NOT NULL UNIQUE, text TEXT NOT NULL, created_at INTEGER NOT NULL)")
    db.execSQL("CREATE TABLE photo_records (id INTEGER PRIMARY KEY AUTOINCREMENT, client_photo_id TEXT NOT NULL UNIQUE, uri TEXT NOT NULL, created_at INTEGER NOT NULL, latitude REAL, longitude REAL)")
  }

  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    if (oldVersion < 2) {
      db.execSQL("ALTER TABLE speech_records ADD COLUMN client_record_id TEXT")
      db.execSQL("UPDATE speech_records SET client_record_id = 'legacy-' || id WHERE client_record_id IS NULL")
      db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_speech_records_client_id ON speech_records(client_record_id)")
    }
    if (oldVersion < 3) {
      db.execSQL("CREATE TABLE IF NOT EXISTS photo_records (id INTEGER PRIMARY KEY AUTOINCREMENT, client_photo_id TEXT NOT NULL UNIQUE, uri TEXT NOT NULL, created_at INTEGER NOT NULL, latitude REAL, longitude REAL)")
    }
  }

  fun insert(text: String, timestamp: Long = System.currentTimeMillis()): SpeechRecord = upsert(SpeechRecord(0, UUID.randomUUID().toString(), text, timestamp))

  fun upsert(record: SpeechRecord): SpeechRecord {
    val existing = readableDatabase.query("speech_records", arrayOf("id"), "client_record_id = ?", arrayOf(record.clientRecordId), null, null, null).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
    if (existing != null) return record.copy(id = existing)
    val values = ContentValues().apply { put("client_record_id", record.clientRecordId); put("text", record.text); put("created_at", record.createdAtMillis) }
    return record.copy(id = writableDatabase.insertOrThrow("speech_records", null, values))
  }

  fun recordsOn(date: LocalDate): List<SpeechRecord> {
    val zone = ZoneId.systemDefault()
    val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
    val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    return readableDatabase.query("speech_records", arrayOf("id", "client_record_id", "text", "created_at"), "created_at >= ? AND created_at < ?", arrayOf(start.toString(), end.toString()), null, null, "created_at ASC").use { cursor ->
      buildList { while (cursor.moveToNext()) add(SpeechRecord(cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3))) }
    }
  }

  fun insertPhoto(uri: String, timestamp: Long, latitude: Double?, longitude: Double?): PhotoRecord {
    val record = PhotoRecord(0, UUID.randomUUID().toString(), uri, timestamp, latitude, longitude)
    val values = ContentValues().apply { put("client_photo_id", record.clientPhotoId); put("uri", uri); put("created_at", timestamp); if (latitude != null) put("latitude", latitude); if (longitude != null) put("longitude", longitude) }
    return record.copy(id = writableDatabase.insertOrThrow("photo_records", null, values))
  }

  fun photosOn(date: LocalDate): List<PhotoRecord> {
    val zone = ZoneId.systemDefault()
    val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
    val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    return readableDatabase.query("photo_records", arrayOf("id", "client_photo_id", "uri", "created_at", "latitude", "longitude"), "created_at >= ? AND created_at < ?", arrayOf(start.toString(), end.toString()), null, null, "created_at ASC").use { cursor ->
      buildList { while (cursor.moveToNext()) add(PhotoRecord(cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3), if (cursor.isNull(4)) null else cursor.getDouble(4), if (cursor.isNull(5)) null else cursor.getDouble(5))) }
    }
  }
}
