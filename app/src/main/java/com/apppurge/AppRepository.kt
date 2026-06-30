package com.apppurge

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build

class AppRepository(private val context: Context) {
    private val packageManager = context.packageManager

    fun installedUserApps(): List<InstalledApp> {
        val applications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledApplications(0)
        }

        return applications
            .asSequence()
            .filter { it.packageName != context.packageName }
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
            .map { appInfo ->
                val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.getPackageInfo(appInfo.packageName, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getPackageInfo(appInfo.packageName, 0)
                }
                InstalledApp(
                    packageName = appInfo.packageName,
                    label = appInfo.loadLabel(packageManager).toString(),
                    icon = appInfo.loadIcon(packageManager),
                    firstInstallTimeMillis = packageInfo.firstInstallTime,
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    fun isInstalled(packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }
}
