package com.kilagbe.fakegps

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

data class SavedLocation(val name: String, val lat: Double, val lng: Double)

val Context.dataStore by preferencesDataStore(name = "fake_gps_prefs")

object PrefKeys {
    val SAVED_LOCATIONS = stringPreferencesKey("saved_locations")
    val ACTIVE = booleanPreferencesKey("active")
    val ACTIVE_LAT = doublePreferencesKey("active_lat")
    val ACTIVE_LNG = doublePreferencesKey("active_lng")
    val ACTIVE_NAME = stringPreferencesKey("active_name")
    val AUTO_START = booleanPreferencesKey("auto_start")
    val JITTER = booleanPreferencesKey("jitter")
    val AUTO_CYCLE = booleanPreferencesKey("auto_cycle")
    val AUTO_CYCLE_MINUTES = intPreferencesKey("auto_cycle_minutes")
    val REAL_LAT = doublePreferencesKey("real_lat")
    val REAL_LNG = doublePreferencesKey("real_lng")
    val BUBBLE_ENABLED = booleanPreferencesKey("bubble_enabled")
}

class LocationRepository(private val context: Context) {

    val savedLocationsFlow: Flow<List<SavedLocation>> =
        context.dataStore.data.map { prefs ->
            val raw = prefs[PrefKeys.SAVED_LOCATIONS] ?: "[]"
            parseLocations(raw)
        }

    val activeStateFlow: Flow<Triple<Boolean, Double, Double>> =
        context.dataStore.data.map { prefs ->
            Triple(
                prefs[PrefKeys.ACTIVE] ?: false,
                prefs[PrefKeys.ACTIVE_LAT] ?: 23.8103,
                prefs[PrefKeys.ACTIVE_LNG] ?: 90.4125
            )
        }

    val autoCycleFlow: Flow<Pair<Boolean, Int>> =
        context.dataStore.data.map { prefs ->
            Pair(
                prefs[PrefKeys.AUTO_CYCLE] ?: false,
                prefs[PrefKeys.AUTO_CYCLE_MINUTES] ?: 10
            )
        }

    val bubbleEnabledFlow: Flow<Boolean> =
        context.dataStore.data.map { prefs -> prefs[PrefKeys.BUBBLE_ENABLED] ?: false }

    suspend fun getSavedLocations(): List<SavedLocation> = savedLocationsFlow.first()

    suspend fun addLocation(loc: SavedLocation) {
        val current = getSavedLocations().toMutableList()
        current.add(loc)
        persist(current)
        FakeGpsWidgetProvider.updateWidgets(context)
    }

    suspend fun removeLocation(name: String) {
        val current = getSavedLocations().filterNot { it.name == name }
        persist(current)
        FakeGpsWidgetProvider.updateWidgets(context)
    }

    /** Restores saved locations from the Downloads backup file, but only if the
     *  current list is empty (i.e. fresh install / reinstall). Returns true if it restored anything. */
    suspend fun restoreFromBackupIfEmpty(): Boolean {
        if (getSavedLocations().isNotEmpty()) return false
        val backup = LocalBackupManager.readBackup(context)
        if (backup.isNullOrEmpty()) return false
        persist(backup)
        return true
    }

    private suspend fun persist(list: List<SavedLocation>) {
        val arr = JSONArray()
        list.forEach {
            val obj = JSONObject()
            obj.put("name", it.name)
            obj.put("lat", it.lat)
            obj.put("lng", it.lng)
            arr.put(obj)
        }
        context.dataStore.edit { prefs ->
            prefs[PrefKeys.SAVED_LOCATIONS] = arr.toString()
        }
        LocalBackupManager.writeBackup(context, list)
    }

    suspend fun setActive(active: Boolean, lat: Double, lng: Double, name: String?) {
        context.dataStore.edit { prefs ->
            prefs[PrefKeys.ACTIVE] = active
            prefs[PrefKeys.ACTIVE_LAT] = lat
            prefs[PrefKeys.ACTIVE_LNG] = lng
            prefs[PrefKeys.ACTIVE_NAME] = name ?: ""
        }
        FakeGpsWidgetProvider.updateWidgets(context)
    }

    suspend fun setAutoStart(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[PrefKeys.AUTO_START] = enabled }
    }

    suspend fun setJitter(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[PrefKeys.JITTER] = enabled }
    }

    suspend fun setAutoCycle(enabled: Boolean, minutes: Int) {
        context.dataStore.edit { prefs ->
            prefs[PrefKeys.AUTO_CYCLE] = enabled
            prefs[PrefKeys.AUTO_CYCLE_MINUTES] = minutes
        }
    }

    suspend fun setRealLocation(lat: Double, lng: Double) {
        context.dataStore.edit { prefs ->
            prefs[PrefKeys.REAL_LAT] = lat
            prefs[PrefKeys.REAL_LNG] = lng
        }
    }

    suspend fun getRealLocation(): Pair<Double, Double>? {
        val prefs = context.dataStore.data.first()
        val lat = prefs[PrefKeys.REAL_LAT]
        val lng = prefs[PrefKeys.REAL_LNG]
        return if (lat != null && lng != null) Pair(lat, lng) else null
    }

    suspend fun setBubbleEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[PrefKeys.BUBBLE_ENABLED] = enabled }
    }

    private fun parseLocations(raw: String): List<SavedLocation> {
        val arr = JSONArray(raw)
        val list = mutableListOf<SavedLocation>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            list.add(SavedLocation(obj.getString("name"), obj.getDouble("lat"), obj.getDouble("lng")))
        }
        return list
    }
}
