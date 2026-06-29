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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
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
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("App Purge") },
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
        if (selectedApp == null) {
            MainScreen(
                modifier = Modifier.padding(padding),
                config = config,
                apps = apps,
                onSelectApp = { selectedApp = it },
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

@Composable
private fun MainScreen(
    modifier: Modifier,
    config: PurgeConfig?,
    apps: List<InstalledApp>,
    onSelectApp: (InstalledApp) -> Unit,
    onCancelConfig: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onRequestShizuku: () -> Unit,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Spacer(Modifier.height(4.dp))
            PermissionPanel(
                onRequestNotificationPermission = onRequestNotificationPermission,
                onOpenOverlaySettings = onOpenOverlaySettings,
                onRequestShizuku = onRequestShizuku,
            )
        }
        if (config != null) {
            item {
                ActiveConfigCard(config = config, onCancelConfig = onCancelConfig)
            }
        }
        item {
            Text("Installed user apps", fontWeight = FontWeight.SemiBold)
        }
        items(apps, key = { it.packageName }) { app ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectApp(app) },
            ) {
                ListItem(
                    headlineContent = { Text(app.label) },
                    supportingContent = { Text(app.packageName) },
                    leadingContent = { Icon(Icons.Filled.Delete, contentDescription = null) },
                )
            }
        }
        item {
            Spacer(Modifier.height(24.dp))
        }
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
    val overlayGranted = Settings.canDrawOverlays(context)
    val shizukuReady = ShizukuUninstaller.hasPermission()

    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Readiness", fontWeight = FontWeight.SemiBold)
            PermissionRow(
                icon = Icons.Filled.Notifications,
                title = "Notifications",
                ready = notificationsGranted,
                action = "Allow",
                onClick = onRequestNotificationPermission,
            )
            PermissionRow(
                icon = Icons.Filled.Warning,
                title = "Overlay",
                ready = overlayGranted,
                action = "Open settings",
                onClick = onOpenOverlaySettings,
            )
            PermissionRow(
                icon = Icons.Filled.Security,
                title = "Shizuku",
                ready = shizukuReady,
                action = "Request",
                onClick = onRequestShizuku,
            )
        }
    }
}

@Composable
private fun PermissionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    ready: Boolean,
    action: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null)
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(if (ready) "Ready" else "Required")
        }
        OutlinedButton(onClick = onClick, enabled = !ready) {
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
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Scheduled purge", fontWeight = FontWeight.SemiBold)
            Text(config.appName)
            Text(purgeDate)
            Text(if (config.allowSnooze) "One 24h snooze allowed" else "No snooze")
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
        Text("Configure purge", fontWeight = FontWeight.SemiBold)
        Card(Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(app.label, fontWeight = FontWeight.SemiBold)
                Text(app.packageName)
            }
        }
        Button(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Date: ${purgeDateTime.format(DateTimeFormatter.ofPattern("MMM d, yyyy"))}")
        }
        Button(onClick = { showTimePicker = true }, modifier = Modifier.fillMaxWidth()) {
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
        Divider()
        Text("Purge at ${purgeDateTime.format(formatter)}")
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
