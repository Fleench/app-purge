package com.apppurge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.apppurge.data.PurgeStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PurgeAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PURGE_DUE) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val store = PurgeStore(context.applicationContext)
                val repository = AppRepository(context.applicationContext)
                store.currentConfigs().filterNot { repository.isInstalled(it.packageName) }.forEach { store.clear(it.packageName) }
                val configs = store.currentConfigs()
                val config = configs.firstOrNull { it.purgeAtMillis <= System.currentTimeMillis() }
                if (config == null) {
                    PurgeScheduler.scheduleNext(context.applicationContext, configs)
                    return@launch
                }

                if (!Settings.canDrawOverlays(context.applicationContext)) {
                    Notifications.showOverlayPermissionNotification(context.applicationContext, config.appName)
                    PurgeScheduler.scheduleRetry(context.applicationContext)
                    return@launch
                }

                ContextCompat.startForegroundService(
                    context.applicationContext,
                    Intent(context.applicationContext, PurgeOverlayService::class.java),
                )
                PurgeScheduler.scheduleRetry(context.applicationContext)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_PURGE_DUE = "com.apppurge.action.PURGE_DUE"
    }
}
