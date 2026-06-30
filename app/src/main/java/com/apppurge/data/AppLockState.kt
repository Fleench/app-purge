package com.apppurge.data

data class AppLockEntry(
    val packageName: String,
    val appName: String,
    val reason: String,
    val unlockedUntilMillis: Long,
    val cooldownUntilMillis: Long,
)

data class AppLockState(
    val geminiApiKey: String,
    val locks: List<AppLockEntry>,
    val lastDecision: String,
    val lastGrantedMinutes: Int,
    val emergencyCoins: Int,
    val lastCoinEarnedDay: Long,
) {
    val enabled: Boolean = locks.isNotEmpty()
    val reason: String = locks.firstOrNull()?.reason.orEmpty()
    val lockedPackageName: String = locks.firstOrNull()?.packageName.orEmpty()
    val lockedAppName: String = locks.firstOrNull()?.appName.orEmpty()

    fun lockedEntryForPackage(packageName: String, nowMillis: Long = System.currentTimeMillis()): AppLockEntry? {
        return locks.firstOrNull { it.packageName == packageName && it.unlockedUntilMillis <= nowMillis }
    }

    fun entryForPackage(packageName: String): AppLockEntry? {
        return locks.firstOrNull { it.packageName == packageName }
    }

    fun isLocked(nowMillis: Long = System.currentTimeMillis()): Boolean {
        return locks.any { it.unlockedUntilMillis <= nowMillis }
    }

    fun isCoolingDown(nowMillis: Long = System.currentTimeMillis()): Boolean {
        return locks.any { it.cooldownUntilMillis > nowMillis }
    }
}
