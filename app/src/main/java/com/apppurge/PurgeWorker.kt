package com.apppurge

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.apppurge.data.PurgeStore
import kotlinx.coroutines.flow.first

class PurgeWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val store = PurgeStore(applicationContext)
        val configs = store.configs.first()
        val now = System.currentTimeMillis()
        val repository = AppRepository(applicationContext)
        configs.filterNot { repository.isInstalled(it.packageName) }.forEach { store.clear(it.packageName) }
        val activeConfigs = store.currentConfigs()
        val config = activeConfigs.firstOrNull { it.purgeAtMillis <= now }
        if (config == null) {
            PurgeScheduler.scheduleNext(applicationContext, activeConfigs)
            return Result.success()
        }

        if (!ShizukuUninstaller.isBinderAvailable()) {
            ShizukuUninstaller.launchFallbackUninstall(applicationContext, config.packageName)
            PurgeScheduler.scheduleRetry(applicationContext)
            return Result.success()
        }

        if (!Settings.canDrawOverlays(applicationContext)) {
            Notifications.showOverlayPermissionNotification(applicationContext, config.appName)
            PurgeScheduler.scheduleRetry(applicationContext)
            return Result.success()
        }

        val intent = Intent(applicationContext, PurgeOverlayService::class.java)
        ContextCompat.startForegroundService(applicationContext, intent)
        PurgeScheduler.scheduleRetry(applicationContext)
        return Result.success()
    }
}
