package com.apppurge.data

data class AppLockState(
    val geminiApiKey: String,
    val enabled: Boolean,
    val reason: String,
    val unlockedUntilMillis: Long,
    val cooldownUntilMillis: Long,
    val lastDecision: String,
    val lastGrantedMinutes: Int,
) {
    fun isLocked(nowMillis: Long = System.currentTimeMillis()): Boolean {
        return enabled && unlockedUntilMillis <= nowMillis
    }

    fun isCoolingDown(nowMillis: Long = System.currentTimeMillis()): Boolean {
        return cooldownUntilMillis > nowMillis
    }
}
