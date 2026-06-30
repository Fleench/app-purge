package com.apppurge

import android.graphics.drawable.Drawable

data class InstalledApp(
    val packageName: String,
    val label: String,
    val icon: Drawable,
    val firstInstallTimeMillis: Long,
)
