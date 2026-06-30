package com.apppurge

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.apppurge.data.PurgeStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AppLockAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastBlockedPackage: String? = null
    private var lastBlockedAtMillis: Long = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val foregroundPackage = event.packageName?.toString() ?: return
        if (foregroundPackage == packageName) return

        scope.launch {
            val lockState = PurgeStore(applicationContext).lockState.first()
            val now = System.currentTimeMillis()
            if (lockState.lockedEntryForPackage(foregroundPackage, now) == null) {
                if (foregroundPackage != lastBlockedPackage) lastBlockedPackage = null
                return@launch
            }
            if (lastBlockedPackage == foregroundPackage && now - lastBlockedAtMillis < 2_000L) return@launch

            lastBlockedPackage = foregroundPackage
            lastBlockedAtMillis = now
            val intent = Intent(applicationContext, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(MainActivity.EXTRA_SHOW_LOCK_GATE, true)
                putExtra(MainActivity.EXTRA_LOCKED_PACKAGE, foregroundPackage)
            }
            startActivity(intent)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
