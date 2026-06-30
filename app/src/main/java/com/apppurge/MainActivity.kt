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
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ElevatedCard
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
import com.apppurge.data.PurgeConfig
import com.apppurge.data.PurgeStore
import com.apppurge.ui.theme.AppPurgeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
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
    val config by store.config.collectAsState(initial = null)
    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var selectedApp by remember { mutableStateOf<InstalledApp?>(null) }
    var currentPage by remember { mutableStateOf(AppPage.Home) }
    var appListMode by remember { mutableStateOf(AppListMode.All) }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { repository.installedUserApps() }
    }

    LaunchedEffect(config) {
        val current = config ?: return@LaunchedEffect
        if (!repository.isInstalled(current.packageName)) {
            store.clear()
            PurgeScheduler.cancel(context.applicationContext)
            return@LaunchedEffect
        }

        if (current.purgeAtMillis <= System.currentTimeMillis()) {
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
        } else {
            PurgeScheduler.schedule(context.applicationContext, current)
        }
    }

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
            )
        },
    ) { padding ->
        if (selectedApp == null && currentPage == AppPage.Home) {
            HomeScreen(
                modifier = Modifier.padding(padding),
                config = config,
                apps = apps,
                onGoToAllApps = {
                    appListMode = AppListMode.All
                    currentPage = AppPage.Apps
                },
                onGoToNewApps = {
                    appListMode = AppListMode.NewThisWeek
                    currentPage = AppPage.Apps
                },
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
                apps = appsForMode(apps, appListMode),
                title = appListMode.title,
                emptyMessage = appListMode.emptyMessage,
                onBack = { currentPage = AppPage.Home },
                onSelectApp = { selectedApp = it },
            )
        } else {
            ConfigureScreen(
                modifier = Modifier.padding(padding),
                app = selectedApp!!,
                onBack = { selectedApp = null },
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
                        PurgeScheduler.schedule(context.applicationContext, newConfig)
                        selectedApp = null
                    }
                },
            )
        }
    }
}

private enum class AppPage { Home, Apps }

private enum class AppListMode(
    val title: String,
    val emptyMessage: String,
) {
    All(
        title = "All apps",
        emptyMessage = "No apps found.",
    ),
    NewThisWeek(
        title = "New apps this week",
        emptyMessage = "No apps were installed in the last week.",
    ),
}

private fun appsInstalledThisWeek(apps: List<InstalledApp>): List<InstalledApp> {
    val cutoff = System.currentTimeMillis() - 7L * 24L * 60L * 60L * 1000L
    return apps
        .filter { it.firstInstallTimeMillis >= cutoff }
        .sortedByDescending { it.firstInstallTimeMillis }
}

private fun appsForMode(apps: List<InstalledApp>, mode: AppListMode): List<InstalledApp> {
    return when (mode) {
        AppListMode.All -> apps
        AppListMode.NewThisWeek -> appsInstalledThisWeek(apps).take(5)
    }
}

@Composable
private fun HomeScreen(
    modifier: Modifier,
    config: PurgeConfig?,
    apps: List<InstalledApp>,
    onGoToAllApps: () -> Unit,
    onGoToNewApps: () -> Unit,
    onCancelConfig: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onRequestShizuku: () -> Unit,
) {
    val newAppsCount = remember(apps) { appsInstalledThisWeek(apps).size }
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
            PermissionPanel(
                onRequestNotificationPermission = onRequestNotificationPermission,
                onOpenOverlaySettings = onOpenOverlaySettings,
                onRequestShizuku = onRequestShizuku,
            )
        }
        item {
            if (config != null) {
                ActiveConfigCard(config = config, onCancelConfig = onCancelConfig)
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
    onBack: () -> Unit,
    onSelectApp: (InstalledApp) -> Unit,
) {
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
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                FilledTonalButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Home")
                }
            }
        }
        if (apps.isEmpty()) {
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(
                            Icons.Filled.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(emptyMessage, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        items(apps, key = { it.packageName }) { app ->
            AppRow(app = app, onClick = { onSelectApp(app) })
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun AppRow(app: InstalledApp, onClick: () -> Unit) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        val iconBitmap = remember(app.packageName) { app.icon.toBitmap(width = 96, height = 96).asImageBitmap() }
        ListItem(
            headlineContent = { Text(app.label) },
            supportingContent = { Text(app.packageName) },
            leadingContent = { Image(iconBitmap, contentDescription = null, modifier = Modifier.size(44.dp)) },
            trailingContent = { AssistChip(onClick = onClick, label = { Text("Purge") }) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
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
    val overlayGranted = !shizukuAvailable || Settings.canDrawOverlays(context)
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
private fun ActiveConfigCard(config: PurgeConfig, onCancelConfig: () -> Unit) {
    val formatter = remember { DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a") }
    val purgeDate = remember(config.purgeAtMillis) {
        Instant.ofEpochMilli(config.purgeAtMillis)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
            .format(formatter)
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
                "Scheduled purge",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(config.appName, style = MaterialTheme.typography.titleSmall)
            Text(purgeDate, color = MaterialTheme.colorScheme.onTertiaryContainer)
            Text(
                if (config.allowSnooze) "One 24h snooze allowed" else "No snooze",
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            OutlinedButton(onClick = onCancelConfig) {
                Text("Cancel")
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
