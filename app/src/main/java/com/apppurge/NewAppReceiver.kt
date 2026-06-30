package com.apppurge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class NewAppReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_PACKAGE_ADDED) return
        if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return

        val packageName = intent.data?.schemeSpecificPart.orEmpty()
        if (packageName.isBlank() || packageName == context.packageName) return

        val repository = AppRepository(context.applicationContext)
        val app = repository.installedApp(packageName) ?: return
        Notifications.showNewAppPurgePrompt(context.applicationContext, app.packageName, app.label)
    }
}
