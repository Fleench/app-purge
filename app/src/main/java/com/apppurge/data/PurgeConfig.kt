package com.apppurge.data

data class PurgeConfig(
    val packageName: String,
    val appName: String,
    val purgeAtMillis: Long,
    val allowSnooze: Boolean,
    val snoozed: Boolean,
)
