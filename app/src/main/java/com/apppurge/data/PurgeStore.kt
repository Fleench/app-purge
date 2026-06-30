package com.apppurge.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
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
        val GeminiApiKey = stringPreferencesKey("gemini_api_key")
        val AppLockEnabled = booleanPreferencesKey("app_lock_enabled")
        val AppLockReason = stringPreferencesKey("app_lock_reason")
        val AppLockUnlockedUntil = longPreferencesKey("app_lock_unlocked_until")
        val AppLockCooldownUntil = longPreferencesKey("app_lock_cooldown_until")
        val AppLockLastDecision = stringPreferencesKey("app_lock_last_decision")
        val AppLockLastGrantedMinutes = intPreferencesKey("app_lock_last_granted_minutes")
    }

    val preferences: Flow<Preferences> = context.purgeDataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }

    val lockState: Flow<AppLockState> = preferences
        .map { preferences ->
            AppLockState(
                geminiApiKey = preferences[Keys.GeminiApiKey].orEmpty(),
                enabled = preferences[Keys.AppLockEnabled] ?: false,
                reason = preferences[Keys.AppLockReason].orEmpty(),
                unlockedUntilMillis = preferences[Keys.AppLockUnlockedUntil] ?: 0L,
                cooldownUntilMillis = preferences[Keys.AppLockCooldownUntil] ?: 0L,
                lastDecision = preferences[Keys.AppLockLastDecision].orEmpty(),
                lastGrantedMinutes = preferences[Keys.AppLockLastGrantedMinutes] ?: 0,
            )
        }

    val config: Flow<PurgeConfig?> = preferences
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

    suspend fun currentLockState(): AppLockState = lockState.first()

    suspend fun saveGeminiApiKey(apiKey: String) {
        context.purgeDataStore.edit { preferences ->
            preferences[Keys.GeminiApiKey] = apiKey.trim()
        }
    }

    suspend fun enableAppLock(reason: String) {
        context.purgeDataStore.edit { preferences ->
            preferences[Keys.AppLockEnabled] = true
            preferences[Keys.AppLockReason] = reason.trim()
            preferences[Keys.AppLockUnlockedUntil] = 0L
            preferences[Keys.AppLockCooldownUntil] = 0L
            preferences[Keys.AppLockLastDecision] = "Lock enabled."
            preferences[Keys.AppLockLastGrantedMinutes] = 0
        }
    }

    suspend fun applyUnlockDecision(unlockedUntilMillis: Long, cooldownUntilMillis: Long, decision: String, grantedMinutes: Int) {
        context.purgeDataStore.edit { preferences ->
            preferences[Keys.AppLockUnlockedUntil] = unlockedUntilMillis
            preferences[Keys.AppLockCooldownUntil] = cooldownUntilMillis
            preferences[Keys.AppLockLastDecision] = decision
            preferences[Keys.AppLockLastGrantedMinutes] = grantedMinutes
        }
    }

    suspend fun denyLockRequest(cooldownUntilMillis: Long, decision: String) {
        context.purgeDataStore.edit { preferences ->
            preferences[Keys.AppLockCooldownUntil] = cooldownUntilMillis
            preferences[Keys.AppLockLastDecision] = decision
            preferences[Keys.AppLockLastGrantedMinutes] = 0
        }
    }

    suspend fun disableAppLock(decision: String) {
        context.purgeDataStore.edit { preferences ->
            preferences[Keys.AppLockEnabled] = false
            preferences[Keys.AppLockReason] = ""
            preferences[Keys.AppLockUnlockedUntil] = 0L
            preferences[Keys.AppLockCooldownUntil] = 0L
            preferences[Keys.AppLockLastDecision] = decision
            preferences[Keys.AppLockLastGrantedMinutes] = 0
        }
    }

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
        context.purgeDataStore.edit { preferences ->
            preferences.remove(Keys.PackageName)
            preferences.remove(Keys.AppName)
            preferences.remove(Keys.PurgeAtMillis)
            preferences.remove(Keys.AllowSnooze)
            preferences.remove(Keys.Snoozed)
        }
    }
}
