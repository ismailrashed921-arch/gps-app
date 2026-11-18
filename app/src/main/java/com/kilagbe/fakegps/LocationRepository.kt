package com.kilagbe.fakegps

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
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
    val CYCLE_LOCATION_NAMES = stringPreferencesKey("cycle_location_names")
    val REAL_LAT = doublePreferencesKey("real_lat")
    val REAL_LNG = doublePreferencesKey("real_lng")
    val BUBBLE_ENABLED = booleanPreferencesKey("bubble_enabled")
}

class LocationRepository(private val context: Context) {

    val savedLocationsFlow: Flow<List<SavedLocation>> =
        context.dataStore.data.map { prefs ->
            parseLocations(prefs[PrefKeys.SAVED_LOCATIONS] ?: "[]")
        }

    val activeStateFlow: Flow<Triple<Boolean, Double, Double>> =
        context.dataStore.data.map { prefs ->
            Triple(
                prefs[PrefKeys.ACTIVE] ?: false,
                prefs[PrefKeys.ACTIVE_LAT] ?: 23.8103,
                prefs[PrefKeys.ACTIVE_LNG] ?: 90.4125
            )
        }

    val activeNameFlow: Flow<String> =
        context.dataStore.data.map { prefs -> prefs[PrefKeys.ACTIVE_NAME] ?: "" }

    val autoStartFlow: Flow<Boolean> =
        context.dataStore.data.map { prefs -> prefs[PrefKeys.AUTO_START] ?: true }

    val jitterFlow: Flow<Boolean> =
        context.dataStore.data.map { prefs -> prefs[PrefKeys.JITTER] ?: false }

    val autoCycleFlow: Flow<Pair<Boolean, Int>> =
        context.dataStore.data.map { prefs ->
            Pair(
                prefs[PrefKeys.AUTO_CYCLE] ?: false,
                prefs[PrefKeys.AUTO_CYCLE_MINUTES] ?: 10
            )
        }

    val cycleLocationNamesFlow: Flow<List<String>> =
        context.dataStore.data.map { prefs ->
            try {
                val arr = JSONArray(prefs[PrefKeys.CYCLE_LOCATION_NAMES] ?: "[]")
                (0 until arr.length()).map { arr.getString(it) }
            } catch (_: Exception) {
                emptyList()
            }
        }

    val bubbleEnabledFlow: Flow<Boolean> =
        context.dataStore.data.map { prefs -> prefs[PrefKeys.BUBBLE_ENABLED] ?: false }

    suspend fun getSavedLocations(): List<SavedLocation> = savedLocationsFlow.first()

    suspend fun getCycleLocations(): List<SavedLocation> {
        val all = getSavedLocations()
        val selectedNames = cycleLocationNamesFlow.first()
        if (selectedNames.isEmpty()) return all
        val byName = all.associateBy { it.name }
        return selectedNames.mapNotNull { byName[it] }
    }

    suspend fun setCycleLocationNames(names: List<String>) {
        val arr = JSONArray()
        names.forEach { arr.put(it) }
        context.dataStore.edit { prefs -> prefs[PrefKeys.CYCLE_LOCATION_NAMES] = arr.toString() }
    }

    suspend fun addLocation(loc: SavedLocation) {
        val current = getSavedLocations().toMutableList()
        var name = loc.name
        var n = 2
        while (current.any { it.name == name }) {
            name = "${loc.name} ($n)"
            n++
        }
        current.add(loc.copy(name = name))
        persist(current)
        FakeGpsWidgetProvider.updateWidgets(context)
    }

    suspend fun removeLocation(name: String) {
        val current = getSavedLocations().filterNot { it.name == name }
        persist(current)
        FakeGpsWidgetProvider.updateWidgets(context)
        val names = cycleLocationNamesFlow.first()
        if (name in names) setCycleLocationNames(names.filterNot { it == name })
    }

    suspend fun restoreFromBackupIfEmpty(): Boolean {
        if (getSavedLocations().isNotEmpty()) return false
        val backup = withContext(Dispatchers.IO) { LocalBackupManager.readBackup(context) }
        if (backup.isNullOrEmpty()) return false
        persist(backup)
        return true
    }

    suspend fun backupNow(): Boolean {
        val list = getSavedLocations()
        return withContext(Dispatchers.IO) { LocalBackupManager.writeBackup(context, list) }
    }

    suspend fun restoreFromBackupMerge(): Int {
        val backup = withContext(Dispatchers.IO) { LocalBackupManager.readBackup(context) } ?: return -1
        return mergeIn(backup)
    }

    suspend fun restoreFromUri(uri: Uri): Int {
        val items = withContext(Dispatchers.IO) { LocalBackupManager.readFromUri(context, uri) } ?: return -1
        return mergeIn(items)
    }

    private suspend fun mergeIn(items: List<SavedLocation>): Int {
        if (items.isEmpty()) return 0
        val current = getSavedLocations()
        val currentNames = current.map { it.name }.toSet()
        val toAdd = items.filterNot { it.name in currentNames }
        if (toAdd.isEmpty()) return 0
        persist(current + toAdd)
        return toAdd.size
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
        withContext(Dispatchers.IO) { LocalBackupManager.writeBackup(context, list) }
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

    suspend fun setAutoCycleEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[PrefKeys.AUTO_CYCLE] = enabled }
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
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<SavedLocation>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(SavedLocation(obj.getString("name"), obj.getDouble("lat"), obj.getDouble("lng")))
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }
}
