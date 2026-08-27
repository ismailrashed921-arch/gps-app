package com.kilagbe.fakegps

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object LocalBackupManager {
    var lastError: String? = null

    private const val BACKUP_DIR = "FakeGPS"
    private const val BACKUP_FILE = "saved_locations_backup.json"

    fun writeBackup(context: Context, locations: List<SavedLocation>): Boolean {
        return try {
            val json = toJson(locations)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                writeViaMediaStore(context, json)
            } else {
                writeViaLegacyFile(json)
            }
            true
        } catch (e: Exception) {
            lastError = e.message ?: e.toString()
            false
        }
    }

    fun readBackup(context: Context): List<SavedLocation>? {
        return try {
            val json = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                readViaMediaStore(context)
            } else {
                readViaLegacyFile()
            }
            json?.let { fromJson(it) }
        } catch (_: Exception) { null }
    }

    fun readFromUri(context: Context, uri: Uri): List<SavedLocation>? {
        return try {
            val text = context.contentResolver.openInputStream(uri)
                ?.use { it.readBytes().decodeToString() } ?: return null
            fromJson(text)
        } catch (_: Exception) { null }
    }

    private fun toJson(locations: List<SavedLocation>): String {
        val arr = JSONArray()
        locations.forEach {
            val obj = JSONObject()
            obj.put("name", it.name)
            obj.put("lat", it.lat)
            obj.put("lng", it.lng)
            arr.put(obj)
        }
        return arr.toString()
    }

    private fun fromJson(raw: String): List<SavedLocation> {
        val arr = JSONArray(raw)
        val list = mutableListOf<SavedLocation>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            list.add(SavedLocation(obj.getString("name"), obj.getDouble("lat"), obj.getDouble("lng")))
        }
        return list
    }

    private fun relativePath(): String = Environment.DIRECTORY_DOWNLOADS + "/" + BACKUP_DIR + "/"

    private fun findExistingUri(context: Context): Uri? {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Downloads._ID)
        val selection = "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?"
        val selectionArgs = arrayOf(BACKUP_FILE, relativePath())
        resolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(0)
                return ContentUris.withAppendedId(collection, id)
            }
        }
        return null
    }

    /** Removes any stale/duplicate/orphaned entries with our filename (any relative path,
     *  including trashed/pending ones some OEMs leave behind) — this is what causes
     *  "Failed to build unique file" on repeated writes on some devices (e.g. MIUI). */
    private fun cleanupStaleEntries(context: Context) {
        try {
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val selection = "${MediaStore.Downloads.DISPLAY_NAME}=?"
            val selectionArgs = arrayOf(BACKUP_FILE)
            resolver.delete(collection, selection, selectionArgs)
        } catch (_: Exception) { }
    }

    private fun writeViaMediaStore(context: Context, json: String) {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI

        cleanupStaleEntries(context)

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, BACKUP_FILE)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath())
        }
        val uri = resolver.insert(collection, values)
            ?: throw Exception("MediaStore insert returned null URI")

        resolver.openOutputStream(uri, "wt")?.use { out -> out.write(json.toByteArray()) }
            ?: throw Exception("Could not open output stream for backup file")
    }

    private fun readViaMediaStore(context: Context): String? {
        val uri = findExistingUri(context) ?: return null
        return context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
    }

    private fun legacyFile(): File {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), BACKUP_DIR)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, BACKUP_FILE)
    }

    private fun writeViaLegacyFile(json: String) {
        legacyFile().writeText(json)
    }

    private fun readViaLegacyFile(): String? {
        val f = legacyFile()
        return if (f.exists()) f.readText() else null
    }
}
