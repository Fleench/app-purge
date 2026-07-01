package com.apppurge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.material3.TimePicker
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.apppurge.data.AppLockState
import com.apppurge.data.PurgeConfig
import com.apppurge.data.PurgeStore
import com.apppurge.ui.theme.AppPurgeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_SHOW_LOCK_GATE = "com.apppurge.extra.SHOW_LOCK_GATE"
        const val EXTRA_LOCKED_PACKAGE = "com.apppurge.extra.LOCKED_PACKAGE"
        const val EXTRA_CONFIGURE_PACKAGE = "com.apppurge.extra.CONFIGURE_PACKAGE"
    }
    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { _, _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifications.ensureChannel(this)
        if (ShizukuUninstaller.isBinderAvailable()) {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        }
        setContent {
            AppPurgeTheme {
                AppPurgeApp()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_SHOW_LOCK_GATE, false) ||
            intent.getStringExtra(EXTRA_CONFIGURE_PACKAGE).isNullOrBlank().not()
        ) {
            recreate()
        }
    }

    override fun onDestroy() {
        if (ShizukuUninstaller.isBinderAvailable()) {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        }
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppPurgeApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { PurgeStore(context.applicationContext) }
    val repository = remember { AppRepository(context.applicationContext) }
    val configs by store.configs.collectAsState(initial = emptyList())
    val lockState by store.lockState.collectAsState(initial = null)
    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var selectedApp by remember { mutableStateOf<InstalledApp?>(null) }
    var configuringSelectedApp by remember { mutableStateOf(false) }
    val activity = context as? MainActivity
    var activeLockPackage by remember { mutableStateOf(activity?.intent?.getStringExtra(MainActivity.EXTRA_LOCKED_PACKAGE).orEmpty()) }
    var currentPage by remember { mutableStateOf(if (activity?.intent?.getBooleanExtra(MainActivity.EXTRA_SHOW_LOCK_GATE, false) == true) AppPage.LockGate else AppPage.Home) }
    var appListMode by remember { mutableStateOf(AppListMode.All) }
    var lockRequestResult by remember { mutableStateOf<AppLockRequestResult?>(null) }
    var appActionResult by remember { mutableStateOf<AppLockRequestResult?>(null) }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    LaunchedEffect(Unit) {
        val installedApps = withContext(Dispatchers.IO) { repository.installedUserApps() }
        apps = installedApps
        activity?.intent?.getStringExtra(MainActivity.EXTRA_CONFIGURE_PACKAGE)
            ?.takeIf { it.isNotBlank() }
            ?.let { packageName ->
                val app = installedApps.firstOrNull { it.packageName == packageName }
                    ?: withContext(Dispatchers.IO) { repository.installedApp(packageName) }
                if (app != null) {
                    selectedApp = app
                    configuringSelectedApp = true
                    currentPage = AppPage.Home
                }
            }
        store.refreshEmergencyCoins()
    }

    LaunchedEffect(configs) {
        val activeConfigs = configs.filter { repository.isInstalled(it.packageName) }
        configs.filterNot { repository.isInstalled(it.packageName) }.forEach { store.clear(it.packageName) }
        val current = activeConfigs.firstOrNull { it.purgeAtMillis <= System.currentTimeMillis() }
        if (current == null) {
            PurgeScheduler.scheduleNext(context.applicationContext, activeConfigs)
            return@LaunchedEffect
        }

        if (!ShizukuUninstaller.isBinderAvailable()) {
            ShizukuUninstaller.launchFallbackUninstall(context.applicationContext, current.packageName)
            PurgeScheduler.scheduleRetry(context.applicationContext)
        } else if (Settings.canDrawOverlays(context.applicationContext)) {
            ContextCompat.startForegroundService(
                context.applicationContext,
                Intent(context.applicationContext, PurgeOverlayService::class.java),
            )
        } else {
            Notifications.showOverlayPermissionNotification(context.applicationContext, current.appName)
            PurgeScheduler.scheduleRetry(context.applicationContext)
        }
    }

    val currentLockState = lockState
    val activeLockEntry = activeLockPackage.takeIf { it.isNotBlank() }?.let { currentLockState?.entryForPackage(it) }
        ?: currentLockState?.locks?.firstOrNull()
    val activeLockApp = activeLockEntry?.let { entry -> apps.firstOrNull { it.packageName == entry.packageName } }

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = {
                    Column {
                        Text("App Purge")
                        Text(
                            "Pick temporary apps before they linger",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = null,
                        modifier = Modifier.padding(start = 16.dp),
                    )
                },
                actions = {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = "Settings",
                        modifier = Modifier
                            .padding(end = 16.dp)
                            .clickable { currentPage = AppPage.Settings },
                    )
                },
            )
        },
    ) { padding ->
        if (selectedApp == null && currentPage == AppPage.Settings) {
            SettingsScreen(
                modifier = Modifier.padding(padding),
                lockState = currentLockState,
                apps = apps,
                onBack = { currentPage = AppPage.Home },
                onSaveApiKey = { apiKey -> scope.launch { store.saveGeminiApiKey(apiKey) } },
                onSaveLockPrompts = { temporaryPrompt, removePrompt ->
                    scope.launch { store.saveLockPrompts(temporaryPrompt, removePrompt) }
                },
                onRevokeEmergencyBypass = { entry, reason ->
                    scope.launch(Dispatchers.IO) {
                        val state = store.currentLockState()
                        val key = state.geminiApiKey
                        if (key.isBlank()) {
                            store.revokeEmergencyBypass(entry.packageName, false, "Emergency bypass revoked for ${entry.appName}. Save a Gemini API key to request a coin refund.")
                            return@launch
                        }
                        try {
                            val decision = GeminiLockClient.evaluate(key, entry.appName, entry.reason, reason, LockAction.Unlock, state.temporaryUnlockPrompt, state.removeLockPrompt)
                            store.revokeEmergencyBypass(
                                packageName = entry.packageName,
                                refundCoin = decision.approved,
                                decision = if (decision.approved) "Emergency bypass revoked and 1 coin refunded: ${decision.reason}" else "Emergency bypass revoked without refund: ${decision.reason}",
                            )
                        } catch (error: Exception) {
                            store.revokeEmergencyBypass(entry.packageName, false, "Emergency bypass revoked. Gemini refund review failed: ${error.message ?: "Unknown error"}")
                        }
                    }
                },
                onOpenAccessibilitySettings = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                },
            )
        } else if (selectedApp == null && currentPage == AppPage.Home) {
            HomeScreen(
                modifier = Modifier.padding(padding),
                configs = configs,
                apps = apps,
                onGoToAllApps = {
                    appListMode = AppListMode.All
                    currentPage = AppPage.Apps
                },
                onGoToNewApps = {
                    appListMode = AppListMode.NewThisWeek
                    currentPage = AppPage.Apps
                },
                onGoToLockedApps = {
                    appListMode = AppListMode.Locked
                    currentPage = AppPage.Apps
                },
                lockState = currentLockState,
                onCancelConfig = {
                    scope.launch {
                        store.clear()
                        PurgeScheduler.cancel(context.applicationContext)
                    }
                },
                onRequestNotificationPermission = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
                onOpenOverlaySettings = {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    )
                    context.startActivity(intent)
                },
                onRequestShizuku = {
                    ShizukuUninstaller.requestPermissionIfNeeded()
                },
            )
        } else if (selectedApp == null) {
            AppsScreen(
                modifier = Modifier.padding(padding),
                apps = appsForMode(apps, appListMode, currentLockState),
                title = appListMode.title,
                emptyMessage = appListMode.emptyMessage,
                mode = appListMode,
                onBack = { currentPage = AppPage.Home },
                onModeChange = { appListMode = it },
                lockState = currentLockState,
                onSelectApp = {
                    selectedApp = it
                    configuringSelectedApp = false
                },
                onToggleLock = { app, isLocked ->
                    scope.launch {
                        if (!isLocked) store.enableAppLock(app.packageName, app.label)
                    }
                },
                onEmergencyUnlock = { app ->
                    scope.launch {
                        val now = System.currentTimeMillis()
                        if (store.spendEmergencyCoin(app.packageName, now)) {
                            Notifications.showAppUnlockedNotification(
                                context.applicationContext,
                                app.packageName,
                                app.label,
                                now + 3L * 60L * 60L * 1000L,
                                180,
                            )
                        }
                    }
                },
            )
        } else if (!configuringSelectedApp) {
            val app = selectedApp!!
            val lockEntry = currentLockState?.entryForPackage(app.packageName)
            AppDetailsScreen(
                modifier = Modifier.padding(padding),
                app = app,
                configs = configs,
                lockEntry = lockEntry,
                geminiApiKey = currentLockState?.geminiApiKey.orEmpty(),
                emergencyCoins = currentLockState?.emergencyCoins ?: 0,
                lastDecision = currentLockState?.lastDecision.orEmpty(),
                onBack = {
                    selectedApp = null
                    configuringSelectedApp = false
                },
                onSaveApiKey = { apiKey -> scope.launch { store.saveGeminiApiKey(apiKey) } },
                onConfigurePurge = { configuringSelectedApp = true },
                onCancelPurge = {
                    scope.launch {
                        store.clear(app.packageName)
                        PurgeScheduler.scheduleNext(context.applicationContext, store.currentConfigs())
                    }
                },
                onToggleLock = { reason ->
                    scope.launch {
                        if (lockEntry == null) store.enableAppLock(app.packageName, app.label, reason)
                    }
                },
                onRequestRemoveLock = { reason ->
                    scope.launch(Dispatchers.IO) {
                        val state = store.currentLockState()
                        val entry = state.entryForPackage(app.packageName) ?: return@launch
                        val now = System.currentTimeMillis()
                        if (entry.cooldownUntilMillis > now) {
                            withContext(Dispatchers.Main) {
                                appActionResult = AppLockRequestResult(
                                    approved = false,
                                    appName = entry.appName,
                                    reason = "Gemini requests are cooling down until ${formatMillis(entry.cooldownUntilMillis, DateTimeFormatter.ofPattern("MMM d h:mm a"))}. Use emergency unlock if you need access now.",
                                    unlockMinutes = null,
                                    closeOnDismiss = false,
                                )
                            }
                            return@launch
                        }
                        val key = state.geminiApiKey
                        if (key.isBlank()) {
                            withContext(Dispatchers.Main) {
                                appActionResult = AppLockRequestResult(
                                    approved = false,
                                    appName = entry.appName,
                                    reason = "Save a Gemini API key before requesting lock removal.",
                                    unlockMinutes = null,
                                    closeOnDismiss = false,
                                )
                            }
                            return@launch
                        }
                        try {
                            val decision = GeminiLockClient.evaluate(key, entry.appName, entry.reason, reason, LockAction.Remove, state.temporaryUnlockPrompt, state.removeLockPrompt)
                            if (decision.approved) {
                                store.disableAppLock(entry.packageName, "Gemini approved removing the lock: ${decision.reason}")
                            } else {
                                store.denyLockRequest(entry.packageName, now + decision.cooldownMinutes * 60_000L, "Gemini denied remove-lock request: ${decision.reason}")
                            }
                            withContext(Dispatchers.Main) {
                                appActionResult = AppLockRequestResult(
                                    approved = decision.approved,
                                    appName = entry.appName,
                                    reason = decision.reason,
                                    unlockMinutes = null,
                                    closeOnDismiss = false,
                                )
                            }
                        } catch (error: Exception) {
                            val message = error.message ?: "Unknown error"
                            store.denyLockRequest(entry.packageName, System.currentTimeMillis() + 10L * 60_000L, "Gemini request failed: $message")
                            withContext(Dispatchers.Main) {
                                appActionResult = AppLockRequestResult(
                                    approved = false,
                                    appName = entry.appName,
                                    reason = "Gemini request failed: $message",
                                    unlockMinutes = null,
                                    closeOnDismiss = false,
                                )
                            }
                        }
                    }
                },
                onEmergencyUnlock = {
                    scope.launch {
                        val now = System.currentTimeMillis()
                        if (store.spendEmergencyCoin(app.packageName, now)) {
                            Notifications.showAppUnlockedNotification(
                                context.applicationContext,
                                app.packageName,
                                app.label,
                                now + 3L * 60L * 60L * 1000L,
                                180,
                            )
                        }
                    }
                },
            )
        } else {
            ConfigureScreen(
                modifier = Modifier.padding(padding),
                app = selectedApp!!,
                onBack = { configuringSelectedApp = false },
                onSave = { purgeAtMillis, allowSnooze ->
                    scope.launch {
                        val newConfig = PurgeConfig(
                            packageName = selectedApp!!.packageName,
                            appName = selectedApp!!.label,
                            purgeAtMillis = purgeAtMillis,
                            allowSnooze = allowSnooze,
                            snoozed = false,
                        )
                        store.save(newConfig)
                        PurgeScheduler.scheduleNext(context.applicationContext, store.currentConfigs())
                        configuringSelectedApp = false
                    }
                },
            )
        }
    }

    if (currentPage == AppPage.LockGate && currentLockState?.enabled == true && activeLockEntry != null) {
        LockPopup(
            lockState = currentLockState,
            lockEntry = activeLockEntry,
            appIcon = activeLockApp?.icon?.toBitmap(width = 96, height = 96)?.asImageBitmap(),
            requestResult = lockRequestResult,
            onSaveApiKey = { apiKey -> scope.launch { store.saveGeminiApiKey(apiKey) } },
            onDismiss = { currentPage = AppPage.Home },
            onResultDismiss = {
                if (lockRequestResult?.closeOnDismiss == true) {
                    lockRequestResult = null
                    currentPage = AppPage.Home
                } else {
                    lockRequestResult = null
                }
            },
            onEmergencyUnlock = {
                scope.launch {
                    val now = System.currentTimeMillis()
                    if (store.spendEmergencyCoin(activeLockEntry.packageName, now)) {
                        val grantedMinutes = 180
                        val unlockedUntilMillis = now + grantedMinutes * 60_000L
                        Notifications.showAppUnlockedNotification(
                            context.applicationContext,
                            activeLockEntry.packageName,
                            activeLockEntry.appName,
                            unlockedUntilMillis,
                            grantedMinutes,
                        )
                        lockRequestResult = AppLockRequestResult(
                            approved = true,
                            appName = activeLockEntry.appName,
                            reason = "Emergency unlock spent 1 coin.",
                            unlockMinutes = grantedMinutes,
                            closeOnDismiss = true,
                        )
                    } else {
                        lockRequestResult = AppLockRequestResult(
                            approved = false,
                            appName = activeLockEntry.appName,
                            reason = "No emergency unlocks are available.",
                            unlockMinutes = null,
                            closeOnDismiss = false,
                        )
                    }
                }
            },
            onRequest = { action, reason ->
                scope.launch(Dispatchers.IO) {
                    try {
                        val state = store.currentLockState()
                        val entry = state.entryForPackage(activeLockEntry.packageName) ?: return@launch
                        val now = System.currentTimeMillis()
                        if (entry.cooldownUntilMillis > now) {
                            withContext(Dispatchers.Main) {
                                lockRequestResult = AppLockRequestResult(
                                    approved = false,
                                    appName = entry.appName,
                                    reason = "Gemini requests are cooling down until ${formatMillis(entry.cooldownUntilMillis, DateTimeFormatter.ofPattern("MMM d h:mm a"))}. Use emergency unlock if you need access now.",
                                    unlockMinutes = null,
                                    closeOnDismiss = false,
                                )
                            }
                            return@launch
                        }
                        val decision = GeminiLockClient.evaluate(state.geminiApiKey, entry.appName, entry.reason, reason, action, state.temporaryUnlockPrompt, state.removeLockPrompt)
                        if (decision.approved && action == LockAction.Remove) {
                            store.disableAppLock(entry.packageName, "Gemini approved removing the lock: ${decision.reason}")
                            withContext(Dispatchers.Main) {
                                lockRequestResult = AppLockRequestResult(
                                    approved = true,
                                    appName = entry.appName,
                                    reason = decision.reason,
                                    unlockMinutes = null,
                                    closeOnDismiss = true,
                                )
                            }
                        } else if (decision.approved && decision.grantedMinutes > 0) {
                            val unlockedUntilMillis = now + decision.grantedMinutes * 60_000L
                            store.applyUnlockDecision(
                                packageName = entry.packageName,
                                unlockedUntilMillis = unlockedUntilMillis,
                                cooldownUntilMillis = now + decision.cooldownMinutes * 60_000L,
                                decision = "Gemini approved ${decision.grantedMinutes} minutes: ${decision.reason}",
                                grantedMinutes = decision.grantedMinutes,
                            )
                            Notifications.showAppUnlockedNotification(context.applicationContext, entry.packageName, entry.appName, unlockedUntilMillis, decision.grantedMinutes)
                            withContext(Dispatchers.Main) {
                                lockRequestResult = AppLockRequestResult(
                                    approved = true,
                                    appName = entry.appName,
                                    reason = decision.reason,
                                    unlockMinutes = decision.grantedMinutes,
                                    closeOnDismiss = true,
                                )
                            }
                        } else {
                            store.denyLockRequest(entry.packageName, now + decision.cooldownMinutes * 60_000L, "Gemini denied request: ${decision.reason}")
                            withContext(Dispatchers.Main) {
                                lockRequestResult = AppLockRequestResult(
                                    approved = false,
                                    appName = entry.appName,
                                    reason = decision.reason,
                                    unlockMinutes = null,
                                    closeOnDismiss = false,
                                )
                            }
                        }
                    } catch (error: Exception) {
                        val reason = error.message ?: "Unknown error"
                        store.denyLockRequest(activeLockEntry.packageName, System.currentTimeMillis() + 10L * 60_000L, "Gemini request failed: $reason")
                        withContext(Dispatchers.Main) {
                            lockRequestResult = AppLockRequestResult(
                                approved = false,
                                appName = activeLockEntry.appName,
                                reason = "Gemini request failed: $reason",
                                unlockMinutes = null,
                                closeOnDismiss = false,
                            )
                        }
                    }
                }
            },
        )
    }

    appActionResult?.let { result ->
        AlertDialog(
            onDismissRequest = { appActionResult = null },
            confirmButton = {
                Button(onClick = { appActionResult = null }) {
                    Text("Done")
                }
            },
            title = { Text(if (result.approved) "Approved" else "Denied") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(result.appName.ifBlank { "App" }, style = MaterialTheme.typography.titleMedium)
                    Text(result.reason, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
        )
    }
}

private enum class AppPage { Home, Apps, Settings, LockGate }

private data class AppLockRequestResult(
    val approved: Boolean,
    val appName: String,
    val reason: String,
    val unlockMinutes: Int?,
    val closeOnDismiss: Boolean,
)

private enum class AppListMode(
    val title: String,
    val emptyMessage: String,
    val filterLabel: String,
) {
    All(
        title = "All apps",
        emptyMessage = "No apps found.",
        filterLabel = "All",
    ),
    NewThisWeek(
        title = "New apps this week",
        emptyMessage = "No apps were installed in the last week.",
        filterLabel = "New",
    ),
    Locked(
        title = "Locked apps",
        emptyMessage = "No locked apps.",
        filterLabel = "Locked",
    ),
}

private fun appsInstalledThisWeek(apps: List<InstalledApp>): List<InstalledApp> {
    val cutoff = System.currentTimeMillis() - 7L * 24L * 60L * 60L * 1000L
    return apps
        .filter { it.firstInstallTimeMillis >= cutoff }
        .sortedByDescending { it.firstInstallTimeMillis }
}

private fun appsForMode(apps: List<InstalledApp>, mode: AppListMode, lockState: AppLockState?): List<InstalledApp> {
    return when (mode) {
        AppListMode.All -> apps
        AppListMode.NewThisWeek -> appsInstalledThisWeek(apps).take(5)
        AppListMode.Locked -> apps.filter { app -> lockState?.entryForPackage(app.packageName) != null }
    }
}

@Composable
private fun HomeScreen(
    modifier: Modifier,
    configs: List<PurgeConfig>,
    apps: List<InstalledApp>,
    onGoToAllApps: () -> Unit,
    onGoToNewApps: () -> Unit,
    onGoToLockedApps: () -> Unit,
    lockState: AppLockState?,
    onCancelConfig: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onRequestShizuku: () -> Unit,
) {
    val newAppsCount = remember(apps) { appsInstalledThisWeek(apps).size }
    val lockedAppsCount = lockState?.locks?.size ?: 0
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Spacer(Modifier.height(4.dp)) }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatCard(
                    title = "Installed apps",
                    value = apps.size.toString(),
                    icon = Icons.Filled.Apps,
                    modifier = Modifier.weight(1f),
                    onClick = onGoToAllApps,
                )
                StatCard(
                    title = "New this week",
                    value = newAppsCount.toString(),
                    icon = Icons.Filled.AutoAwesome,
                    modifier = Modifier.weight(1f),
                    onClick = onGoToNewApps,
                )
            }
        }
        item {
            StatCard(
                title = "Locked apps",
                value = lockedAppsCount.toString(),
                icon = Icons.Filled.Lock,
                modifier = Modifier.fillMaxWidth(),
                onClick = onGoToLockedApps,
            )
        }
        item {
            PermissionPanel(
                onRequestNotificationPermission = onRequestNotificationPermission,
                onOpenOverlaySettings = onOpenOverlaySettings,
                onRequestShizuku = onRequestShizuku,
            )
        }
        item {
            if (configs.isNotEmpty()) {
                ActiveConfigsCard(configs = configs, onCancelConfig = onCancelConfig)
            } else {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "No active purges",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "Pick an app and choose when it should be removed.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        item {
            Button(onClick = onGoToAllApps, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Delete, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Set up a purge")
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun StatCard(
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    ElevatedCard(
        modifier = modifier.clickable { onClick() },
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = MaterialTheme.shapes.large,
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.padding(10.dp))
            }
            Text(
                value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun AppsScreen(
    modifier: Modifier,
    apps: List<InstalledApp>,
    title: String,
    emptyMessage: String,
    mode: AppListMode,
    lockState: AppLockState?,
    onBack: () -> Unit,
    onModeChange: (AppListMode) -> Unit,
    onSelectApp: (InstalledApp) -> Unit,
    onToggleLock: (InstalledApp, Boolean) -> Unit,
    onEmergencyUnlock: (InstalledApp) -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    val filteredApps = remember(apps, searchQuery) {
        val query = searchQuery.trim()
        if (query.isBlank()) {
            apps
        } else {
            apps.filter { app ->
                app.label.contains(query, ignoreCase = true) ||
                    app.packageName.contains(query, ignoreCase = true)
            }
        }
    }
    val currentEmptyMessage = if (searchQuery.isBlank()) emptyMessage else "No apps match your search."

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Home")
                }
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                label = { Text("Search apps") },
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppListMode.entries.forEach { option ->
                    FilterChip(
                        selected = mode == option,
                        onClick = { onModeChange(option) },
                        label = { Text(option.filterLabel) },
                    )
                }
            }
        }
        if (filteredApps.isEmpty()) {
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(
                            Icons.Filled.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(currentEmptyMessage, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        items(filteredApps, key = { it.packageName }) { app ->
            val lockEntry = lockState?.entryForPackage(app.packageName)
            AppRow(
                app = app,
                lockEntry = lockEntry,
                emergencyCoins = lockState?.emergencyCoins ?: 0,
                onClick = { onSelectApp(app) },
                onToggleLock = { onToggleLock(app, lockEntry != null) },
                onEmergencyUnlock = { onEmergencyUnlock(app) },
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun AppRow(
    app: InstalledApp,
    lockEntry: com.apppurge.data.AppLockEntry?,
    emergencyCoins: Int,
    onClick: () -> Unit,
    onToggleLock: () -> Unit,
    onEmergencyUnlock: () -> Unit,
) {
    var showEmergencyConfirm by remember { mutableStateOf(false) }
    if (showEmergencyConfirm) {
        EmergencyUnlockConfirmDialog(
            appName = app.label,
            emergencyCoins = emergencyCoins,
            onConfirm = { showEmergencyConfirm = false; onEmergencyUnlock() },
            onDismiss = { showEmergencyConfirm = false },
        )
    }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        val iconBitmap = remember(app.packageName) { app.icon.toBitmap(width = 96, height = 96).asImageBitmap() }
        val isCoolingDown = lockEntry?.cooldownUntilMillis?.let { it > System.currentTimeMillis() } == true
        ListItem(
            headlineContent = { Text(app.label) },
            supportingContent = {
                Column {
                    Text(app.packageName)
                    if (lockEntry != null) Text("Locked", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }
            },
            leadingContent = { Image(iconBitmap, contentDescription = null, modifier = Modifier.size(44.dp)) },
            trailingContent = {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    AssistChip(onClick = onClick, label = { Text("Purge") })
                    if (lockEntry == null) {
                        AssistChip(onClick = onToggleLock, label = { Text("Lock") })
                    } else {
                        AssistChip(onClick = onClick, label = { Text(if (isCoolingDown) "Cooldown" else "Details") })
                    }
                    if (lockEntry != null) {
                        AssistChip(
                            onClick = { showEmergencyConfirm = true },
                            enabled = emergencyCoins > 0,
                            label = { Text("Emergency") },
                        )
                    }
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

@Composable
private fun AppDetailsScreen(
    modifier: Modifier,
    app: InstalledApp,
    configs: List<PurgeConfig>,
    lockEntry: com.apppurge.data.AppLockEntry?,
    geminiApiKey: String,
    emergencyCoins: Int,
    lastDecision: String,
    onBack: () -> Unit,
    onSaveApiKey: (String) -> Unit,
    onConfigurePurge: () -> Unit,
    onCancelPurge: () -> Unit,
    onToggleLock: (String) -> Unit,
    onRequestRemoveLock: (String) -> Unit,
    onEmergencyUnlock: () -> Unit,
) {
    val formatter = remember { DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a") }
    var showRemoveLockRequest by remember { mutableStateOf(false) }
    var showAddLockReason by remember { mutableStateOf(false) }
    var newLockReason by remember { mutableStateOf("") }
    var removeReason by remember { mutableStateOf("") }
    var apiKey by remember(geminiApiKey) { mutableStateOf(geminiApiKey) }
    var showEmergencyConfirm by remember { mutableStateOf(false) }
    if (showEmergencyConfirm) {
        EmergencyUnlockConfirmDialog(
            appName = app.label,
            emergencyCoins = emergencyCoins,
            onConfirm = { showEmergencyConfirm = false; onEmergencyUnlock() },
            onDismiss = { showEmergencyConfirm = false },
        )
    }
    val now = System.currentTimeMillis()
    val appIcon = remember(app.packageName) { app.icon.toBitmap(width = 128, height = 128).asImageBitmap() }
    val installedAt = remember(app.firstInstallTimeMillis) {
        Instant.ofEpochMilli(app.firstInstallTimeMillis).atZone(ZoneId.systemDefault()).format(formatter)
    }
    val purgeForThisApp = configs.firstOrNull { it.packageName == app.packageName }
    val purgeText = purgeForThisApp?.let {
        val date = Instant.ofEpochMilli(it.purgeAtMillis).atZone(ZoneId.systemDefault()).format(formatter)
        "Scheduled for $date"
    } ?: "No purge scheduled"
    val lockStatus = when {
        lockEntry == null -> "Not locked"
        lockEntry.unlockedUntilMillis > now -> "Temporarily unlocked until ${formatMillis(lockEntry.unlockedUntilMillis, formatter)}"
        else -> "Locked"
    }
    val cooldownText = lockEntry?.cooldownUntilMillis
        ?.takeIf { it > now }
        ?.let { "Cooldown until ${formatMillis(it, formatter)}" }
        ?: "No cooldown"

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("App info", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                FilledTonalButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Apps")
                }
            }
        }
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(appIcon, contentDescription = null, modifier = Modifier.size(56.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(app.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(app.packageName, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Purge", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    StatusLine("Status", purgeText)
                    purgeForThisApp?.let {
                        StatusLine("Snooze", if (it.allowSnooze) "One 24h snooze allowed" else "No snooze")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = onConfigurePurge, modifier = Modifier.weight(1f)) {
                            Text(if (purgeForThisApp == null) "Set purge" else "Change purge")
                        }
                        if (purgeForThisApp != null) {
                            OutlinedButton(onClick = onCancelPurge, modifier = Modifier.weight(1f)) {
                                Text("Cancel")
                            }
                        }
                    }
                }
            }
        }
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("App lock", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    StatusLine("Status", lockStatus)
                    StatusLine("Cooldown", cooldownText)
                    lockEntry?.reason?.takeIf { it.isNotBlank() }?.let {
                        StatusLine("Reason", it)
                    }
                    StatusLine("Emergency coins", emergencyCoins.toString())
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (lockEntry == null) {
                            Button(onClick = { showAddLockReason = true }, modifier = Modifier.weight(1f)) {
                                Text("Lock app")
                            }
                        } else {
                            Button(
                                onClick = { showRemoveLockRequest = true },
                                enabled = lockEntry.cooldownUntilMillis <= now,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(if (lockEntry.cooldownUntilMillis > now) "On cooldown" else "Request removal")
                            }
                        }
                        if (lockEntry != null) {
                            OutlinedButton(
                                onClick = { showEmergencyConfirm = true },
                                enabled = emergencyCoins > 0,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Emergency")
                            }
                        }
                    }
                    lastDecision.takeIf { it.isNotBlank() }?.let {
                        Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Install", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    StatusLine("Installed", installedAt)
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (showAddLockReason && lockEntry == null) {
        AlertDialog(
            onDismissRequest = { showAddLockReason = false },
            confirmButton = {
                Button(onClick = {
                    onToggleLock(newLockReason)
                    showAddLockReason = false
                    newLockReason = ""
                }) { Text("Save lock") }
            },
            dismissButton = { TextButton(onClick = { showAddLockReason = false }) { Text("Cancel") } },
            title = { Text("Add lock reason") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Tell Gemini why ${app.label} should stay locked. You can leave this blank, but a reason makes unlock decisions more useful.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = newLockReason,
                        onValueChange = { newLockReason = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Lock reason") },
                        minLines = 3,
                    )
                }
            },
        )
    }

    if (showRemoveLockRequest && lockEntry != null) {
        AlertDialog(
            onDismissRequest = { showRemoveLockRequest = false },
            confirmButton = {
                Button(
                    onClick = {
                        onRequestRemoveLock(removeReason)
                        showRemoveLockRequest = false
                        removeReason = ""
                    },
                    enabled = geminiApiKey.isNotBlank() && removeReason.isNotBlank(),
                ) {
                    Text("Ask Gemini")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveLockRequest = false }) {
                    Text("Cancel")
                }
            },
            title = { Text("Request lock removal") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(lockEntry.appName.ifBlank { "App" }, style = MaterialTheme.typography.titleMedium)
                    Text("Gemini must approve removing this lock.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (geminiApiKey.isBlank()) {
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Gemini API key") },
                            singleLine = true,
                        )
                        Button(onClick = { onSaveApiKey(apiKey) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Save API key")
                        }
                    }
                    OutlinedTextField(
                        value = removeReason,
                        onValueChange = { removeReason = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Why should this lock be removed?") },
                        minLines = 3,
                    )
                }
            },
        )
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            modifier = Modifier.weight(0.34f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
        Text(value, modifier = Modifier.weight(0.66f))
    }
}

private fun formatMillis(millis: Long, formatter: DateTimeFormatter): String {
    return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(formatter)
}

@Composable
private fun PermissionPanel(
    onRequestNotificationPermission: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onRequestShizuku: () -> Unit,
) {
    val context = LocalContext.current
    val notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val shizukuAvailable = ShizukuUninstaller.isBinderAvailable()
    val overlayGranted = Settings.canDrawOverlays(context)
    val shizukuReady = !shizukuAvailable || ShizukuUninstaller.hasPermission()

    val hasActions = !notificationsGranted || !overlayGranted || !shizukuReady
    if (!hasActions) return

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Readiness",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (!notificationsGranted) {
                PermissionRow(
                    icon = Icons.Filled.Notifications,
                    title = "Notifications",
                    action = "Allow",
                    onClick = onRequestNotificationPermission,
                )
            }
            if (!overlayGranted) {
                PermissionRow(
                    icon = Icons.Filled.Warning,
                    title = "Overlay",
                    action = "Open settings",
                    onClick = onOpenOverlaySettings,
                )
            }
            if (!shizukuReady) {
                PermissionRow(
                    icon = Icons.Filled.Security,
                    title = "Shizuku",
                    action = "Request",
                    onClick = onRequestShizuku,
                )
            }
        }
    }
}

@Composable
private fun PermissionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    action: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary,
            shape = MaterialTheme.shapes.large,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.padding(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text("Required", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(onClick = onClick) {
            Text(action)
        }
    }
}

@Composable
private fun ActiveConfigsCard(configs: List<PurgeConfig>, onCancelConfig: () -> Unit) {
    val formatter = remember { DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a") }
    val purgeSummaries = remember(configs) {
        configs.sortedBy { it.purgeAtMillis }.take(3).map { config ->
            val purgeDate = Instant.ofEpochMilli(config.purgeAtMillis)
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime()
                .format(formatter)
            config to purgeDate
        }
    }
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                if (configs.size == 1) "Scheduled purge" else "Scheduled purges",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            purgeSummaries.forEach { (config, purgeDate) ->
                Text(config.appName, style = MaterialTheme.typography.titleSmall)
                Text(purgeDate, color = MaterialTheme.colorScheme.onTertiaryContainer)
                Text(
                    if (config.allowSnooze) "One 24h snooze allowed" else "No snooze",
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            if (configs.size > purgeSummaries.size) {
                Text("+${configs.size - purgeSummaries.size} more", color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
            OutlinedButton(onClick = onCancelConfig) {
                Text(if (configs.size == 1) "Cancel" else "Cancel all")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigureScreen(
    modifier: Modifier,
    app: InstalledApp,
    onBack: () -> Unit,
    onSave: (Long, Boolean) -> Unit,
) {
    var purgeDateTime by remember {
        mutableStateOf(LocalDateTime.now().plusHours(1).withSecond(0).withNano(0))
    }
    var allowSnooze by remember { mutableStateOf(true) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    val formatter = remember { DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Configure purge",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(app.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(app.packageName, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        FilledTonalButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.CalendarToday, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Date: ${purgeDateTime.format(DateTimeFormatter.ofPattern("MMM d, yyyy"))}")
        }
        FilledTonalButton(onClick = { showTimePicker = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.AccessTime, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Time: ${purgeDateTime.format(DateTimeFormatter.ofPattern("h:mm a"))}")
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Allow one-time 24h snooze")
            Switch(checked = allowSnooze, onCheckedChange = { allowSnooze = it })
        }
        HorizontalDivider()
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Text(
                "Purge at ${purgeDateTime.format(formatter)}",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.titleSmall,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                Text("Back")
            }
            Button(
                onClick = {
                    val millis = purgeDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    onSave(millis, allowSnooze)
                },
                modifier = Modifier.weight(1f),
            ) {
                Text("Schedule")
            }
        }
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = purgeDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            val date = Instant.ofEpochMilli(millis)
                                .atZone(ZoneId.systemDefault())
                                .toLocalDate()
                            purgeDateTime = date.atTime(purgeDateTime.toLocalTime())
                        }
                        showDatePicker = false
                    },
                ) {
                    Text("Done")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancel")
                }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showTimePicker) {
        val timePickerState = rememberTimePickerState(
            initialHour = purgeDateTime.hour,
            initialMinute = purgeDateTime.minute,
            is24Hour = false,
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        purgeDateTime = purgeDateTime
                            .withHour(timePickerState.hour)
                            .withMinute(timePickerState.minute)
                        showTimePicker = false
                    },
                ) {
                    Text("Done")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) {
                    Text("Cancel")
                }
            },
            text = { TimePicker(state = timePickerState) },
        )
    }
}

@Composable
private fun SettingsScreen(
    modifier: Modifier,
    lockState: AppLockState?,
    apps: List<InstalledApp>,
    onBack: () -> Unit,
    onSaveApiKey: (String) -> Unit,
    onSaveLockPrompts: (String, String) -> Unit,
    onRevokeEmergencyBypass: (com.apppurge.data.AppLockEntry, String) -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
) {
    var apiKey by remember(lockState?.geminiApiKey) { mutableStateOf(lockState?.geminiApiKey.orEmpty()) }
    var temporaryPrompt by remember(lockState?.temporaryUnlockPrompt) { mutableStateOf(lockState?.temporaryUnlockPrompt.orEmpty()) }
    var removePrompt by remember(lockState?.removeLockPrompt) { mutableStateOf(lockState?.removeLockPrompt.orEmpty()) }
    var refundEntry by remember { mutableStateOf<com.apppurge.data.AppLockEntry?>(null) }
    var refundReason by remember { mutableStateOf("") }
    val now = System.currentTimeMillis()

    refundEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { refundEntry = null },
            confirmButton = {
                Button(
                    onClick = {
                        onRevokeEmergencyBypass(entry, refundReason)
                        refundEntry = null
                        refundReason = ""
                    },
                    enabled = refundReason.isNotBlank(),
                ) { Text("Revoke and ask Gemini") }
            },
            dismissButton = { TextButton(onClick = { refundEntry = null }) { Text("Cancel") } },
            title = { Text("Revoke emergency bypass") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("This immediately locks ${entry.appName} again. Gemini will use the temporary unlock prompt to decide whether your case deserves the emergency coin back.")
                    OutlinedTextField(
                        value = refundReason,
                        onValueChange = { refundReason = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Make your case to Gemini") },
                        minLines = 3,
                    )
                }
            },
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                FilledTonalButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Home")
                }
            }
        }
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Gemini API key", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("API key") },
                        singleLine = true,
                    )
                    Button(onClick = { onSaveApiKey(apiKey) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Save API key")
                    }
                }
            }
        }
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Gemini unlock prompts", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Customize Gemini's decision guidance. App Purge always appends required JSON response rules so custom prompts cannot break unlock parsing.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = temporaryPrompt,
                        onValueChange = { temporaryPrompt = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Temporary unlock prompt") },
                        minLines = 4,
                    )
                    OutlinedTextField(
                        value = removePrompt,
                        onValueChange = { removePrompt = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Permanent unlock prompt") },
                        minLines = 4,
                    )
                    Button(onClick = { onSaveLockPrompts(temporaryPrompt, removePrompt) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Save prompts")
                    }
                }
            }
        }
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("App lock", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Enable the App Purge accessibility service in Android settings so selected apps can be detected.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = onOpenAccessibilitySettings, modifier = Modifier.fillMaxWidth()) {
                        Text("Open accessibility settings")
                    }
                    Text(
                        if (lockState?.enabled == true) "Locked apps: ${lockState.locks.size}" else "Disabled",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    lockState?.let { state ->
                        Text("Emergency coins: ${state.emergencyCoins} (earn 1 per day)", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    lockState?.locks?.forEach { entry ->
                        val bypassActive = entry.unlockedUntilMillis > now
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("• ${entry.appName}", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            if (bypassActive) {
                                TextButton(onClick = { refundEntry = entry }) {
                                    Text("Revoke bypass")
                                }
                            }
                        }
                    }
                    Text("Lock and unlock apps from the Apps page. App Purge stays available so you can request temporary Gemini unlocks when a locked app opens.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    lockState?.lastDecision?.takeIf { it.isNotBlank() }?.let {
                        Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmergencyUnlockConfirmDialog(
    appName: String,
    emergencyCoins: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var secondsRemaining by remember { mutableStateOf(10) }
    LaunchedEffect(Unit) {
        while (secondsRemaining > 0) {
            delay(1_000L)
            secondsRemaining -= 1
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = secondsRemaining == 0 && emergencyCoins > 0,
            ) { Text(if (secondsRemaining == 0) "Yes, spend 1 coin" else "Yes loads in ${secondsRemaining}s") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Are you sure?") },
        text = { Text("Emergency unlock for ${appName.ifBlank { "this app" }} spends 1 coin and bypasses the lock for 3 hours.") },
    )
}

@Composable
private fun LockPopup(
    lockState: AppLockState,
    lockEntry: com.apppurge.data.AppLockEntry,
    appIcon: ImageBitmap?,
    requestResult: AppLockRequestResult?,
    onSaveApiKey: (String) -> Unit,
    onDismiss: () -> Unit,
    onResultDismiss: () -> Unit,
    onEmergencyUnlock: () -> Unit,
    onRequest: (LockAction, String) -> Unit,
) {
    var apiKey by remember(lockState.geminiApiKey) { mutableStateOf(lockState.geminiApiKey) }
    var reason by remember { mutableStateOf("") }
    var showRequest by remember { mutableStateOf(false) }
    var showEmergencyConfirm by remember { mutableStateOf(false) }
    var action by remember { mutableStateOf(LockAction.Unlock) }
    val formatter = remember { DateTimeFormatter.ofPattern("MMM d h:mm a") }
    val cooldown = lockEntry.cooldownUntilMillis.takeIf { it > System.currentTimeMillis() }?.let {
        Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(formatter)
    }

    if (showEmergencyConfirm) {
        EmergencyUnlockConfirmDialog(
            appName = lockEntry.appName,
            emergencyCoins = lockState.emergencyCoins,
            onConfirm = { showEmergencyConfirm = false; onEmergencyUnlock() },
            onDismiss = { showEmergencyConfirm = false },
        )
    }

    if (requestResult != null) {
        AlertDialog(
            onDismissRequest = onResultDismiss,
            confirmButton = {
                Button(onClick = onResultDismiss) {
                    Text(if (requestResult.closeOnDismiss) "Continue" else "Back")
                }
            },
            title = { Text(if (requestResult.approved) "Approved" else "Denied") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(requestResult.appName.ifBlank { "App" }, style = MaterialTheme.typography.titleMedium)
                    requestResult.unlockMinutes?.let { minutes ->
                        Text("Unlock time: $minutes minutes", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(requestResult.reason, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            if (cooldown != null) {
                Button(
                    onClick = { showEmergencyConfirm = true },
                    enabled = lockState.emergencyCoins > 0,
                ) { Text("Emergency unlock") }
            } else if (showRequest) {
                Button(
                    onClick = { onRequest(action, reason) },
                    enabled = lockState.geminiApiKey.isNotBlank() && reason.isNotBlank(),
                ) { Text("Ask Gemini") }
            } else {
                Button(onClick = { showRequest = true }) { Text("Request unlock") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Back to App Purge") }
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (appIcon != null) {
                    Image(appIcon, contentDescription = null, modifier = Modifier.size(48.dp))
                } else {
                    Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
                Column {
                    Text(lockEntry.appName.ifBlank { "App" })
                    Text("is locked", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (cooldown != null) {
                    Text("Gemini requests are cooling down until $cooldown.", color = MaterialTheme.colorScheme.error)
                    Text("Only emergency unlock can open this app right now. Emergency coins: ${lockState.emergencyCoins}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (!showRequest) {
                    Text("Use Request unlock to ask Gemini for temporary access, or use emergency unlock from the app details screen.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    if (lockState.geminiApiKey.isBlank()) {
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Gemini API key") },
                            singleLine = true,
                        )
                        Button(onClick = { onSaveApiKey(apiKey) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Save API key")
                        }
                    }
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Message to Gemini") },
                        minLines = 3,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AssistChip(onClick = { action = LockAction.Unlock }, label = { Text("Temporary unlock") })
                        AssistChip(onClick = { action = LockAction.Remove }, label = { Text("Remove lock") })
                    }
                    lockState.lastDecision.takeIf { it.isNotBlank() }?.let {
                        Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
    )
}
