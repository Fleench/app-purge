package com.apppurge.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.purgeDataStore: DataStore<Preferences> by preferencesDataStore("purge_config")

class PurgeStore(private val context: Context) {
    private object Keys {
        val PackageName = stringPreferencesKey("package_name")
        val AppName = stringPreferencesKey("app_name")
        val PurgeAtMillis = longPreferencesKey("purge_at_millis")
        val AllowSnooze = booleanPreferencesKey("allow_snooze")
        val Snoozed = booleanPreferencesKey("snoozed")
    }

    val config: Flow<PurgeConfig?> = context.purgeDataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences ->
            val packageName = preferences[Keys.PackageName] ?: return@map null
            val appName = preferences[Keys.AppName] ?: packageName
            val purgeAtMillis = preferences[Keys.PurgeAtMillis] ?: return@map null
            PurgeConfig(
                packageName = packageName,
                appName = appName,
                purgeAtMillis = purgeAtMillis,
                allowSnooze = preferences[Keys.AllowSnooze] ?: false,
                snoozed = preferences[Keys.Snoozed] ?: false,
            )
        }

    suspend fun currentConfig(): PurgeConfig? = config.first()

    suspend fun save(config: PurgeConfig) {
        context.purgeDataStore.edit { preferences ->
            preferences[Keys.PackageName] = config.packageName
            preferences[Keys.AppName] = config.appName
            preferences[Keys.PurgeAtMillis] = config.purgeAtMillis
            preferences[Keys.AllowSnooze] = config.allowSnooze
            preferences[Keys.Snoozed] = config.snoozed
        }
    }

    suspend fun snoozeFor24Hours(config: PurgeConfig): PurgeConfig {
        val updated = config.copy(
            purgeAtMillis = System.currentTimeMillis() + 24L * 60L * 60L * 1000L,
            snoozed = true,
        )
        save(updated)
        return updated
    }

    suspend fun clear() {
        context.purgeDataStore.edit { it.clear() }
    }
}
