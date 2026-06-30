package com.apppurge

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.apppurge.data.PurgeConfig
import com.apppurge.data.PurgeStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PurgeOverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var activeConfig: PurgeConfig? = null

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_PACKAGE_REMOVED) return
            if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
            val removedPackage = intent.data?.schemeSpecificPart ?: return
            if (removedPackage == activeConfig?.packageName) {
                scope.launch { clearAndStop() }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        Notifications.ensureChannel(this)
        startForeground(
            Notifications.OVERLAY_NOTIFICATION_ID,
            Notifications.activePurgeNotification(this, "selected app"),
        )
        registerPackageReceiver()
        scope.launch { loadAndShowOverlay() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scope.launch { loadAndShowOverlay() }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        removeOverlay()
        try {
            unregisterReceiver(packageReceiver)
        } catch (_: IllegalArgumentException) {
        }
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun loadAndShowOverlay() {
        val store = PurgeStore(applicationContext)
        val now = System.currentTimeMillis()
        val config = store.configs.first().firstOrNull { it.purgeAtMillis <= now } ?: run {
            PurgeScheduler.scheduleNext(applicationContext, store.currentConfigs())
            stopSelf()
            return
        }
        activeConfig = config

        if (!AppRepository(applicationContext).isInstalled(config.packageName)) {
            store.clear(config.packageName)
            PurgeScheduler.scheduleNext(applicationContext, store.currentConfigs())
            stopSelf()
            return
        }

        startForeground(
            Notifications.OVERLAY_NOTIFICATION_ID,
            Notifications.activePurgeNotification(this, config.appName),
        )
        if (!android.provider.Settings.canDrawOverlays(this)) {
            Notifications.showOverlayPermissionNotification(this, config.appName)
            PurgeScheduler.scheduleRetry(applicationContext)
            stopSelf()
            return
        }
        showOverlay(config)
    }

    private fun showOverlay(config: PurgeConfig) {
        if (overlayView != null) return

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(245, 17, 24, 39))
            setPadding(48, 48, 48, 48)
        }

        val title = TextView(this).apply {
            text = "Time to purge ${config.appName}"
            setTextColor(Color.WHITE)
            textSize = 28f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        val message = TextView(this).apply {
            text = "The purge date has arrived. Uninstall the selected app to dismiss this prompt."
            setTextColor(Color.rgb(229, 231, 235))
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 32)
        }

        val uninstallButton = Button(this).apply {
            text = "Uninstall Now"
            textSize = 18f
            setOnClickListener { uninstallActivePackage() }
        }

        root.addView(title, matchWrapParams())
        root.addView(message, matchWrapParams())
        root.addView(uninstallButton, matchWrapParams())

        if (config.allowSnooze && !config.snoozed) {
            val snoozeButton = Button(this).apply {
                text = "Snooze 24h"
                textSize = 16f
                setOnClickListener { snoozeActivePurge() }
            }
            root.addView(snoozeButton, matchWrapParams(topMargin = 18))
        }

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
        }

        overlayView = root
        windowManager.addView(root, params)
    }

    private fun matchWrapParams(topMargin: Int = 0): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            if (topMargin > 0) setMargins(0, topMargin, 0, 0)
        }
    }

    private fun uninstallActivePackage() {
        val config = activeConfig ?: return
        scope.launch {
            val repository = AppRepository(applicationContext)
            if (!repository.isInstalled(config.packageName)) {
                clearAndStop()
                return@launch
            }

            if (ShizukuUninstaller.isBinderAvailable() && !ShizukuUninstaller.hasPermission()) {
                if (ShizukuUninstaller.requestPermissionIfNeeded()) {
                    Toast.makeText(
                        this@PurgeOverlayService,
                        "Grant Shizuku permission, then tap Uninstall Now again.",
                        Toast.LENGTH_LONG,
                    ).show()
                    return@launch
                }
            }

            val shizukuSucceeded = withContext(Dispatchers.IO) {
                ShizukuUninstaller.uninstallWithShizuku(config.packageName)
            }
            if (shizukuSucceeded || !repository.isInstalled(config.packageName)) {
                clearAndStop()
            } else {
                removeOverlay()
                ShizukuUninstaller.launchFallbackUninstall(this@PurgeOverlayService, config.packageName)
                delay(30_000L)
                if (repository.isInstalled(config.packageName)) {
                    showOverlay(config)
                }
            }
        }
    }

    private fun snoozeActivePurge() {
        val config = activeConfig ?: return
        scope.launch {
            val store = PurgeStore(applicationContext)
            store.snoozeFor24Hours(config)
            PurgeScheduler.scheduleNext(applicationContext, store.currentConfigs())
            removeOverlay()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun clearAndStop() {
        val store = PurgeStore(applicationContext)
        activeConfig?.let { store.clear(it.packageName) }
        PurgeScheduler.scheduleNext(applicationContext, store.currentConfigs())
        removeOverlay()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun removeOverlay() {
        overlayView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (_: IllegalArgumentException) {
            }
        }
        overlayView = null
    }

    private fun registerPackageReceiver() {
        val filter = IntentFilter(Intent.ACTION_PACKAGE_REMOVED).apply {
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.registerReceiver(
                this,
                packageReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(packageReceiver, filter)
        }
    }
}
