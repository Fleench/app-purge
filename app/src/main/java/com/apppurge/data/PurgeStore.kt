package com.apppurge.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
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
import org.json.JSONArray
import org.json.JSONObject
import com.apppurge.GeminiLockClient
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
        val AppLockPackageName = stringPreferencesKey("app_lock_package_name")
        val AppLockAppName = stringPreferencesKey("app_lock_app_name")
        val AppLockUnlockedUntil = longPreferencesKey("app_lock_unlocked_until")
        val AppLockCooldownUntil = longPreferencesKey("app_lock_cooldown_until")
        val AppLockEntries = stringPreferencesKey("app_lock_entries")
        val PurgeConfigs = stringPreferencesKey("purge_configs")
        val AppLockLastDecision = stringPreferencesKey("app_lock_last_decision")
        val AppLockLastGrantedMinutes = intPreferencesKey("app_lock_last_granted_minutes")
        val EmergencyCoins = intPreferencesKey("app_lock_emergency_coins")
        val LastCoinEarnedDay = longPreferencesKey("app_lock_last_coin_earned_day")
        val TemporaryUnlockPrompt = stringPreferencesKey("app_lock_temporary_unlock_prompt")
        val RemoveLockPrompt = stringPreferencesKey("app_lock_remove_lock_prompt")
    }

    val preferences: Flow<Preferences> = context.purgeDataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }

    val lockState: Flow<AppLockState> = preferences
        .map { preferences -> lockStateFromPreferences(preferences) }

    val configs: Flow<List<PurgeConfig>> = preferences
        .map { preferences -> purgeConfigsFromPreferences(preferences) }

    val config: Flow<PurgeConfig?> = configs
        .map { configs -> configs.minByOrNull { it.purgeAtMillis } }

    suspend fun currentConfigs(): List<PurgeConfig> = configs.first()

    suspend fun currentConfig(): PurgeConfig? = config.first()

    suspend fun currentLockState(): AppLockState = lockState.first()

    suspend fun saveGeminiApiKey(apiKey: String) {
        context.purgeDataStore.edit { preferences ->
            preferences[Keys.GeminiApiKey] = apiKey.trim()
        }
    }

    suspend fun saveLockPrompts(temporaryUnlockPrompt: String, removeLockPrompt: String) {
        context.purgeDataStore.edit { preferences ->
            preferences[Keys.TemporaryUnlockPrompt] = temporaryUnlockPrompt.trim()
            preferences[Keys.RemoveLockPrompt] = removeLockPrompt.trim()
        }
    }

    suspend fun updateAppLockReason(packageName: String, reason: String) {
        updateLock(packageName, "Lock reason updated.", 0) { entry ->
            entry.copy(reason = reason.trim())
        }
    }

    suspend fun enableAppLock(packageName: String, appName: String, reason: String = "") {
        context.purgeDataStore.edit { preferences ->
            val entries = lockStateFromPreferences(preferences).locks
                .filterNot { it.packageName == packageName } + AppLockEntry(
                packageName = packageName,
                appName = appName,
                reason = reason.trim(),
                unlockedUntilMillis = 0L,
                cooldownUntilMillis = 0L,
            )
            preferences[Keys.AppLockEntries] = encodeLockEntries(entries)
            preferences[Keys.AppLockEnabled] = entries.isNotEmpty()
            preferences[Keys.AppLockLastDecision] = "Lock enabled for $appName."
            preferences[Keys.AppLockLastGrantedMinutes] = 0
        }
    }

    suspend fun applyUnlockDecision(packageName: String, unlockedUntilMillis: Long, cooldownUntilMillis: Long, decision: String, grantedMinutes: Int) {
        updateLock(packageName, decision, grantedMinutes) { entry ->
            entry.copy(unlockedUntilMillis = unlockedUntilMillis, cooldownUntilMillis = cooldownUntilMillis)
        }
    }

    suspend fun denyLockRequest(packageName: String, cooldownUntilMillis: Long, decision: String) {
        updateLock(packageName, decision, 0) { entry ->
            entry.copy(cooldownUntilMillis = cooldownUntilMillis)
        }
    }

    suspend fun spendEmergencyCoin(packageName: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
        var spent = false
        context.purgeDataStore.edit { preferences ->
            val state = lockStateFromPreferences(preferences).withEarnedCoins(nowMillis)
            if (state.emergencyCoins <= 0) return@edit
            val entries = state.locks.map { entry ->
                if (entry.packageName == packageName) {
                    entry.copy(
                        unlockedUntilMillis = nowMillis + 3L * 60L * 60L * 1000L,
                        cooldownUntilMillis = nowMillis + 3L * 60L * 60L * 1000L,
                    )
                } else {
                    entry
                }
            }
            preferences[Keys.AppLockEntries] = encodeLockEntries(entries)
            preferences[Keys.EmergencyCoins] = state.emergencyCoins - 1
            preferences[Keys.LastCoinEarnedDay] = state.lastCoinEarnedDay
            preferences[Keys.AppLockLastDecision] = "Emergency unlock spent 1 coin for 3 hours."
            preferences[Keys.AppLockLastGrantedMinutes] = 180
            spent = true
        }
        return spent
    }

    suspend fun disableAppLock(packageName: String, decision: String) {
        context.purgeDataStore.edit { preferences ->
            val entries = lockStateFromPreferences(preferences).locks.filterNot { it.packageName == packageName }
            preferences[Keys.AppLockEntries] = encodeLockEntries(entries)
            preferences[Keys.AppLockEnabled] = entries.isNotEmpty()
            preferences[Keys.AppLockLastDecision] = decision
            preferences[Keys.AppLockLastGrantedMinutes] = 0
            if (entries.isEmpty()) {
                preferences[Keys.AppLockReason] = ""
                preferences[Keys.AppLockPackageName] = ""
                preferences[Keys.AppLockAppName] = ""
                preferences[Keys.AppLockUnlockedUntil] = 0L
                preferences[Keys.AppLockCooldownUntil] = 0L
            }
        }
    }

    suspend fun refreshEmergencyCoins(nowMillis: Long = System.currentTimeMillis()) {
        context.purgeDataStore.edit { preferences ->
            val state = lockStateFromPreferences(preferences).withEarnedCoins(nowMillis)
            preferences[Keys.EmergencyCoins] = state.emergencyCoins
            preferences[Keys.LastCoinEarnedDay] = state.lastCoinEarnedDay
        }
    }

    suspend fun save(config: PurgeConfig) {
        context.purgeDataStore.edit { preferences ->
            val configs = purgeConfigsFromPreferences(preferences)
                .filterNot { it.packageName == config.packageName } + config
            preferences[Keys.PurgeConfigs] = encodePurgeConfigs(configs)
            writeLegacyCurrentConfig(preferences, configs.minByOrNull { it.purgeAtMillis })
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

    suspend fun clear(packageName: String? = null) {
        context.purgeDataStore.edit { preferences ->
            if (packageName == null) {
                preferences.remove(Keys.PurgeConfigs)
                writeLegacyCurrentConfig(preferences, null)
            } else {
                val configs = purgeConfigsFromPreferences(preferences).filterNot { it.packageName == packageName }
                preferences[Keys.PurgeConfigs] = encodePurgeConfigs(configs)
                writeLegacyCurrentConfig(preferences, configs.minByOrNull { it.purgeAtMillis })
            }
        }
    }


    private fun purgeConfigsFromPreferences(preferences: Preferences): List<PurgeConfig> {
        val parsed = decodePurgeConfigs(preferences[Keys.PurgeConfigs].orEmpty())
        if (parsed.isNotEmpty()) return parsed.sortedBy { it.purgeAtMillis }
        val packageName = preferences[Keys.PackageName] ?: return emptyList()
        val purgeAtMillis = preferences[Keys.PurgeAtMillis] ?: return emptyList()
        return listOf(
            PurgeConfig(
                packageName = packageName,
                appName = preferences[Keys.AppName] ?: packageName,
                purgeAtMillis = purgeAtMillis,
                allowSnooze = preferences[Keys.AllowSnooze] ?: false,
                snoozed = preferences[Keys.Snoozed] ?: false,
            ),
        )
    }

    private fun decodePurgeConfigs(json: String): List<PurgeConfig> {
        return runCatching {
            val array = JSONArray(json)
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                PurgeConfig(
                    packageName = item.getString("packageName"),
                    appName = item.optString("appName", item.getString("packageName")),
                    purgeAtMillis = item.getLong("purgeAtMillis"),
                    allowSnooze = item.optBoolean("allowSnooze", false),
                    snoozed = item.optBoolean("snoozed", false),
                )
            }.filter { it.packageName.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    private fun encodePurgeConfigs(configs: List<PurgeConfig>): String {
        val array = JSONArray()
        configs.sortedBy { it.purgeAtMillis }.forEach { config ->
            array.put(
                JSONObject()
                    .put("packageName", config.packageName)
                    .put("appName", config.appName)
                    .put("purgeAtMillis", config.purgeAtMillis)
                    .put("allowSnooze", config.allowSnooze)
                    .put("snoozed", config.snoozed),
            )
        }
        return array.toString()
    }

    private fun writeLegacyCurrentConfig(preferences: MutablePreferences, config: PurgeConfig?) {
        if (config == null) {
            preferences.remove(Keys.PackageName)
            preferences.remove(Keys.AppName)
            preferences.remove(Keys.PurgeAtMillis)
            preferences.remove(Keys.AllowSnooze)
            preferences.remove(Keys.Snoozed)
            return
        }
        preferences[Keys.PackageName] = config.packageName
        preferences[Keys.AppName] = config.appName
        preferences[Keys.PurgeAtMillis] = config.purgeAtMillis
        preferences[Keys.AllowSnooze] = config.allowSnooze
        preferences[Keys.Snoozed] = config.snoozed
    }

    private suspend fun updateLock(packageName: String, decision: String, grantedMinutes: Int, transform: (AppLockEntry) -> AppLockEntry) {
        context.purgeDataStore.edit { preferences ->
            val entries = lockStateFromPreferences(preferences).locks.map { entry ->
                if (entry.packageName == packageName) transform(entry) else entry
            }
            preferences[Keys.AppLockEntries] = encodeLockEntries(entries)
            preferences[Keys.AppLockEnabled] = entries.isNotEmpty()
            preferences[Keys.AppLockLastDecision] = decision
            preferences[Keys.AppLockLastGrantedMinutes] = grantedMinutes
        }
    }

    private fun lockStateFromPreferences(preferences: Preferences): AppLockState {
        val today = currentEpochDay()
        val rawCoins = preferences[Keys.EmergencyCoins]
        val rawLastDay = preferences[Keys.LastCoinEarnedDay]
        val startingCoins = rawCoins ?: 10
        val startingDay = rawLastDay ?: today
        return AppLockState(
            geminiApiKey = preferences[Keys.GeminiApiKey].orEmpty(),
            locks = decodeLockEntries(preferences[Keys.AppLockEntries].orEmpty(), preferences),
            lastDecision = preferences[Keys.AppLockLastDecision].orEmpty(),
            lastGrantedMinutes = preferences[Keys.AppLockLastGrantedMinutes] ?: 0,
            temporaryUnlockPrompt = preferences[Keys.TemporaryUnlockPrompt].orEmpty().ifBlank { GeminiLockClient.DEFAULT_TEMPORARY_UNLOCK_PROMPT },
            removeLockPrompt = preferences[Keys.RemoveLockPrompt].orEmpty().ifBlank { GeminiLockClient.DEFAULT_REMOVE_LOCK_PROMPT },
            emergencyCoins = startingCoins,
            lastCoinEarnedDay = startingDay,
        ).withEarnedCoins()
    }

    private fun AppLockState.withEarnedCoins(nowMillis: Long = System.currentTimeMillis()): AppLockState {
        val today = currentEpochDay(nowMillis)
        val days = (today - lastCoinEarnedDay).coerceAtLeast(0L).toInt()
        if (days == 0) return this
        return copy(
            emergencyCoins = emergencyCoins + days,
            lastCoinEarnedDay = today,
        )
    }

    private fun decodeLockEntries(json: String, preferences: Preferences): List<AppLockEntry> {
        val parsed = runCatching {
            val array = JSONArray(json)
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                AppLockEntry(
                    packageName = item.getString("packageName"),
                    appName = item.optString("appName", item.getString("packageName")),
                    reason = item.optString("reason"),
                    unlockedUntilMillis = item.optLong("unlockedUntilMillis", 0L),
                    cooldownUntilMillis = item.optLong("cooldownUntilMillis", 0L),
                )
            }.filter { it.packageName.isNotBlank() }
        }.getOrDefault(emptyList())
        if (parsed.isNotEmpty()) return parsed

        val legacyEnabled = preferences[Keys.AppLockEnabled] ?: false
        val legacyPackage = preferences[Keys.AppLockPackageName].orEmpty()
        if (!legacyEnabled || legacyPackage.isBlank()) return emptyList()
        return listOf(
            AppLockEntry(
                packageName = legacyPackage,
                appName = preferences[Keys.AppLockAppName].orEmpty().ifBlank { legacyPackage },
                reason = preferences[Keys.AppLockReason].orEmpty(),
                unlockedUntilMillis = preferences[Keys.AppLockUnlockedUntil] ?: 0L,
                cooldownUntilMillis = preferences[Keys.AppLockCooldownUntil] ?: 0L,
            ),
        )
    }

    private fun encodeLockEntries(entries: List<AppLockEntry>): String {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put("packageName", entry.packageName)
                    .put("appName", entry.appName)
                    .put("reason", entry.reason)
                    .put("unlockedUntilMillis", entry.unlockedUntilMillis)
                    .put("cooldownUntilMillis", entry.cooldownUntilMillis),
            )
        }
        return array.toString()
    }

    private fun currentEpochDay(nowMillis: Long = System.currentTimeMillis()): Long {
        return nowMillis / (24L * 60L * 60L * 1000L)
    }
}
