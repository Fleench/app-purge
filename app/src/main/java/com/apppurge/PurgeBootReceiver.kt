package com.apppurge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.apppurge.data.PurgeStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PurgeBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                PurgeStore(context.applicationContext).currentConfig()?.let { config ->
                    PurgeScheduler.schedule(context.applicationContext, config)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
