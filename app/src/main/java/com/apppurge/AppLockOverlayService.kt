package com.apppurge

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.apppurge.data.AppLockEntry
import com.apppurge.data.AppLockState
import com.apppurge.data.PurgeStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppLockOverlayService : Service() {
    companion object {
        const val EXTRA_LOCKED_PACKAGE = "com.apppurge.extra.OVERLAY_LOCKED_PACKAGE"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var activePackageName: String = ""

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        Notifications.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        activePackageName = intent?.getStringExtra(EXTRA_LOCKED_PACKAGE).orEmpty()
        startForeground(
            Notifications.APP_LOCK_NOTIFICATION_ID,
            Notifications.activeAppLockNotification(this, "Selected app"),
        )
        scope.launch { loadAndShowOverlay() }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        removeOverlay()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun loadAndShowOverlay() {
        val store = PurgeStore(applicationContext)
        val state = store.lockState.first()
        val now = System.currentTimeMillis()
        val entry = state.lockedEntryForPackage(activePackageName, now) ?: run {
            stopSelf()
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            Notifications.showOverlayPermissionNotification(this, entry.appName)
            openAppLockGate(entry.packageName)
            stopSelf()
            return
        }

        startForeground(
            Notifications.APP_LOCK_NOTIFICATION_ID,
            Notifications.activeAppLockNotification(this, entry.appName),
        )
        showOverlay(state, entry)
    }

    private fun showOverlay(state: AppLockState, entry: AppLockEntry) {
        removeOverlay()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(245, 17, 24, 39))
            setPadding(48, 48, 48, 48)
        }

        val icon = ImageView(this).apply {
            runCatching { packageManager.getApplicationIcon(entry.packageName) }.getOrNull()?.let { setImageDrawable(it) }
            adjustViewBounds = true
            maxWidth = 144
            maxHeight = 144
        }
        val title = TextView(this).apply {
            text = entry.appName.ifBlank { "App" }
            setTextColor(Color.WHITE)
            textSize = 28f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val status = TextView(this).apply {
            text = "is locked"
            setTextColor(Color.rgb(229, 231, 235))
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 32)
        }
        val decision = TextView(this).apply {
            text = state.lastDecision
            setTextColor(Color.rgb(209, 213, 219))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 18, 0, 0)
            visibility = if (state.lastDecision.isBlank()) View.GONE else View.VISIBLE
        }

        val requestButton = Button(this).apply {
            text = "Request unlock"
            textSize = 18f
        }
        val quitButton = Button(this).apply {
            text = "Quit app"
            textSize = 18f
            setOnClickListener { quitLockedApp() }
        }

        root.addView(icon, wrapContentParams())
        root.addView(title, matchWrapParams())
        root.addView(status, matchWrapParams())
        root.addView(requestButton, matchWrapParams())
        root.addView(quitButton, matchWrapParams(topMargin = 18))
        root.addView(decision, matchWrapParams())

        requestButton.setOnClickListener {
            root.removeAllViews()
            addRequestForm(root, state, entry)
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
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.CENTER }

        overlayView = root
        windowManager.addView(root, params)
    }

    private fun addRequestForm(root: LinearLayout, state: AppLockState, entry: AppLockEntry) {
        val prompt = TextView(this).apply {
            text = "Ask Gemini for access to ${entry.appName.ifBlank { "this app" }}"
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val apiKeyInput = EditText(this).apply {
            hint = "Gemini API key"
            setSingleLine(true)
            setText(state.geminiApiKey)
            visibility = if (state.geminiApiKey.isBlank()) View.VISIBLE else View.GONE
        }
        val messageInput = EditText(this).apply {
            hint = "Message to Gemini"
            minLines = 3
        }
        val tempButton = Button(this).apply {
            text = "Temporary unlock"
            setOnClickListener { submitRequest(entry, apiKeyInput.text.toString(), messageInput.text.toString(), LockAction.Unlock) }
        }
        val removeButton = Button(this).apply {
            text = "Remove lock"
            setOnClickListener { submitRequest(entry, apiKeyInput.text.toString(), messageInput.text.toString(), LockAction.Remove) }
        }
        val backButton = Button(this).apply {
            text = "Back"
            setOnClickListener { scope.launch { loadAndShowOverlay() } }
        }
        val quitButton = Button(this).apply {
            text = "Quit app"
            setOnClickListener { quitLockedApp() }
        }

        root.addView(prompt, matchWrapParams())
        root.addView(apiKeyInput, matchWrapParams(topMargin = 24))
        root.addView(messageInput, matchWrapParams(topMargin = 18))
        root.addView(tempButton, matchWrapParams(topMargin = 18))
        root.addView(removeButton, matchWrapParams(topMargin = 18))
        root.addView(backButton, matchWrapParams(topMargin = 18))
        root.addView(quitButton, matchWrapParams(topMargin = 18))
    }

    private fun submitRequest(entry: AppLockEntry, apiKey: String, message: String, action: LockAction) {
        if (message.isBlank()) {
            Toast.makeText(this, "Enter a message for Gemini.", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            val store = PurgeStore(applicationContext)
            if (apiKey.isNotBlank()) store.saveGeminiApiKey(apiKey)
            val state = store.currentLockState()
            val key = apiKey.ifBlank { state.geminiApiKey }
            if (key.isBlank()) {
                Toast.makeText(this@AppLockOverlayService, "Save a Gemini API key first.", Toast.LENGTH_LONG).show()
                return@launch
            }
            try {
                val decision = withContext(Dispatchers.IO) {
                    GeminiLockClient.evaluate(key, entry.appName, entry.reason, message, action)
                }
                val now = System.currentTimeMillis()
                if (decision.approved && action == LockAction.Remove) {
                    store.disableAppLock(entry.packageName, "Gemini approved removing the lock: ${decision.reason}")
                    showDecisionResult(
                        entry = entry,
                        approved = true,
                        reason = decision.reason,
                        unlockMinutes = null,
                        primaryActionLabel = "Continue",
                        onPrimaryAction = { quitLockedApp(removeOnly = true) },
                    )
                } else if (decision.approved && decision.grantedMinutes > 0) {
                    val unlockedUntilMillis = now + decision.grantedMinutes * 60_000L
                    store.applyUnlockDecision(
                        packageName = entry.packageName,
                        unlockedUntilMillis = unlockedUntilMillis,
                        cooldownUntilMillis = now + decision.cooldownMinutes * 60_000L,
                        decision = "Gemini approved ${decision.grantedMinutes} minutes: ${decision.reason}",
                        grantedMinutes = decision.grantedMinutes,
                    )
                    Notifications.showAppUnlockedNotification(this@AppLockOverlayService, entry.appName, unlockedUntilMillis)
                    showDecisionResult(
                        entry = entry,
                        approved = true,
                        reason = decision.reason,
                        unlockMinutes = decision.grantedMinutes,
                        primaryActionLabel = "Continue",
                        onPrimaryAction = { quitLockedApp(removeOnly = true) },
                    )
                } else {
                    store.denyLockRequest(entry.packageName, now + decision.cooldownMinutes * 60_000L, "Gemini denied request: ${decision.reason}")
                    showDecisionResult(
                        entry = entry,
                        approved = false,
                        reason = decision.reason,
                        unlockMinutes = null,
                        primaryActionLabel = "Back",
                        onPrimaryAction = { scope.launch { loadAndShowOverlay() } },
                    )
                }
            } catch (error: Exception) {
                val reason = error.message ?: "Unknown error"
                store.denyLockRequest(entry.packageName, System.currentTimeMillis() + 10L * 60_000L, "Gemini request failed: $reason")
                showDecisionResult(
                    entry = entry,
                    approved = false,
                    reason = "Gemini request failed: $reason",
                    unlockMinutes = null,
                    primaryActionLabel = "Back",
                    onPrimaryAction = { scope.launch { loadAndShowOverlay() } },
                )
            }
        }
    }

    private fun showDecisionResult(
        entry: AppLockEntry,
        approved: Boolean,
        reason: String,
        unlockMinutes: Int?,
        primaryActionLabel: String,
        onPrimaryAction: () -> Unit,
    ) {
        removeOverlay()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(245, 17, 24, 39))
            setPadding(48, 48, 48, 48)
        }
        val title = TextView(this).apply {
            text = if (approved) "Approved" else "Denied"
            setTextColor(Color.WHITE)
            textSize = 28f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val appName = TextView(this).apply {
            text = entry.appName.ifBlank { "App" }
            setTextColor(Color.rgb(229, 231, 235))
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 12)
        }
        val unlockTime = TextView(this).apply {
            text = unlockMinutes?.let { "Unlock time: $it minutes" }.orEmpty()
            setTextColor(Color.rgb(209, 213, 219))
            textSize = 18f
            gravity = Gravity.CENTER
            visibility = if (unlockMinutes == null) View.GONE else View.VISIBLE
        }
        val reasonView = TextView(this).apply {
            text = reason
            setTextColor(Color.rgb(209, 213, 219))
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 18, 0, 24)
        }
        val primaryButton = Button(this).apply {
            text = primaryActionLabel
            textSize = 18f
            setOnClickListener { onPrimaryAction() }
        }

        root.addView(title, matchWrapParams())
        root.addView(appName, matchWrapParams())
        root.addView(unlockTime, matchWrapParams())
        root.addView(reasonView, matchWrapParams())
        root.addView(primaryButton, matchWrapParams())

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
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.CENTER }

        overlayView = root
        windowManager.addView(root, params)
    }

    private fun quitLockedApp(removeOnly: Boolean = false) {
        removeOverlay()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        if (!removeOnly) {
            startActivity(Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        }
    }

    private fun openAppLockGate(packageName: String) {
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_SHOW_LOCK_GATE, true)
            putExtra(MainActivity.EXTRA_LOCKED_PACKAGE, packageName)
        })
    }

    private fun matchWrapParams(topMargin: Int = 0): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { if (topMargin > 0) setMargins(0, topMargin, 0, 0) }
    }

    private fun wrapContentParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
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
}
